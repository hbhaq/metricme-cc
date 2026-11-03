package com.hbhaq.metricme.backend

import com.hbhaq.metricme.contracts.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.*
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class JournalTest {
    @Test fun latestItemSearchFindsMatchBeyondRecentPageAndEscapesWildcards() {
        database().use { db ->
            val store=PostgresJournal(db.pool); val owner=UUID.randomUUID().toString(); val now=Instant.now()
            val burger=fact("FOOD",at=now.minusSeconds(45*86400L)).copy(title="Cheeseburger",calories=500.0)
            val facts=listOf(burger)+(1..65).map { fact(at=now.minusSeconds(it.toLong())) }
            facts.chunked(30).forEach { batch -> val id=UUID.randomUUID().toString(); store.begin(owner,id,id); store.finish(owner,id,JournalReply(id,"SAVED",entries=batch.map { JournalRecord("",id,0,it) })) }
            val today=LocalDate.now(ZoneOffset.UTC)
            val match=store.search(owner,today.minusMonths(3),today,ZoneOffset.UTC,"FOOD","burger",true)
            assertEquals("Cheeseburger",match.single().fact.title)
            assertTrue(store.search(owner,today.minusMonths(3),today,ZoneOffset.UTC,null,"%",false).isEmpty())
            assertTrue(store.search("other",today.minusMonths(3),today,ZoneOffset.UTC,"FOOD","burger",true).isEmpty())
        }
    }
    @Test fun mealRetentionRemovesNamesTimesAndReceiptCopiesButKeepsNutritionAndDate() {
        database().use { db ->
            val store=PostgresJournal(db.pool); val owner=UUID.randomUUID().toString(); val id=UUID.randomUUID().toString(); val record=UUID.randomUUID().toString()
            val at=Instant.now().atZone(ZoneOffset.UTC).minusMonths(4).toInstant()
            val meal=fact("FOOD",at=at).copy(title="Birthday cake",note="Chocolate cake after dinner",quantity=2.0,unit="slices",calories=600.0,protein=5.0,zone="Asia/Karachi")
            store.begin(owner,id,"hash")
            db.pool.connection.use { c ->
                c.prepareStatement("INSERT INTO journal_entries VALUES (?,?,?,?,?,?,?)").use { s -> s.setString(1,owner); s.setString(2,record); s.setString(3,id); s.setInt(4,0); s.setString(5,"FOOD"); s.setLong(6,at.toEpochMilli()); s.setString(7,WireJson.encodeToString(meal)); s.executeUpdate() }
                c.prepareStatement("UPDATE journal_messages SET status='SAVED',result=? WHERE owner_hash=? AND id=?").use { s -> s.setString(1,WireJson.encodeToString(JournalReply(id,"SAVED",transcript="I had birthday cake",entries=listOf(JournalRecord(record,id,0,meal))))); s.setString(2,owner); s.setString(3,id); s.executeUpdate() }
            }
            store.compact(owner); val clean=store.page(owner,null,"FOOD").entries.single().fact
            assertFalse(clean.detailsRetained); assertEquals("Nutrition summary",clean.title); assertFalse(WireJson.encodeToString(clean).contains("cake")); assertNull(clean.quantity); assertNull(clean.unit)
            assertEquals(600.0,clean.calories!!,0.001); assertEquals(5.0,clean.protein!!,0.001)
            assertEquals(at.atZone(ZoneId.of("Asia/Karachi")).toLocalDate(),Instant.parse(clean.occurredAt).atZone(ZoneId.of("Asia/Karachi")).toLocalDate())
            assertTrue(store.begin(owner,id,"hash").second.entries.isEmpty())
            val day=at.atZone(ZoneId.of("Asia/Karachi")).toLocalDate()
            assertEquals(600.0,store.insights(owner,day,day,ZoneId.of("Asia/Karachi")).foodCalories!!,0.001)
            assertTrue(store.search(owner,day,day,ZoneId.of("Asia/Karachi"),"FOOD","cake",true).isEmpty())
        }
    }
    private fun database():PostgresJobs { val url=System.getenv("TEST_DATABASE_URL"); assumeTrue("CI supplies PostgreSQL",!url.isNullOrBlank()); return PostgresJobs(url!!) }
    private fun fact(category:String="NOTE",duration:Double?=null,at:Instant=Instant.now().minusSeconds(60))=JournalFact(category,"Reported $category","User reported",at.toString(),durationMinutes=duration)
    @Test fun durableAtomicBatchHasOwnerIsolationIdempotencyAndSafeUndo() {
        val db=database(); val owner=sha256(UUID.randomUUID().toString().toByteArray()); val id=UUID.randomUUID().toString()
        try {
            val store=PostgresJournal(db.pool); val executor=Executors.newFixedThreadPool(4)
            try { assertEquals(1,executor.invokeAll((1..8).map { Callable { store.begin(owner,id,"hash").first } }).count { it.get() }) } finally { executor.shutdown() }
            val result=store.finish(owner,id,JournalReply(id,"SAVED",entries=listOf(JournalRecord("",id,0,fact("FOOD")),JournalRecord("",id,0,fact("SLEEP",420.0)),JournalRecord("",id,0,fact("MEDICATION").copy(title="Reported medicine",dose="User stated 5 mg")))))
            assertEquals(3,result.entries.size)
            assertFalse(store.begin(owner,id,"hash").first)
            assertEquals(3,store.page(owner,null,null).entries.size)
            assertTrue(store.page("another-owner",null,null).entries.isEmpty())
            assertThrows(ApiFailure::class.java) { store.begin(owner,id,"different") }
            // Re-open the storage adapter: entries are database records, not process memory.
            assertEquals(3,PostgresJournal(db.pool).page(owner,null,null).entries.size)
            store.undo(owner,id); assertTrue(store.page(owner,null,null).entries.isEmpty())
            assertTrue(store.finish(owner,id,result).entries.isEmpty())
            assertEquals("DELETED",store.begin(owner,id,"hash").second.status)
        } finally { db.close() }
    }
    @Test fun editsUseRevisionsAndDeletedValuesAreNotReplayed() {
        database().use { db ->
            val store=PostgresJournal(db.pool); val owner=UUID.randomUUID().toString(); val id=UUID.randomUUID().toString()
            store.begin(owner,id,"hash")
            val row=store.finish(owner,id,JournalReply(id,"SAVED",entries=listOf(JournalRecord("",id,0,fact())))).entries.single()
            val question=UUID.randomUUID().toString(); store.begin(owner,question,"question"); store.finish(owner,question,JournalReply(question,"ANSWER",references=listOf(row)))
            val updated=store.edit(owner,row.id,JournalEdit(0,row.fact.copy(title="Corrected")))
            assertEquals(1,updated.revision); assertEquals("Corrected",store.page(owner,null,null).entries.single().fact.title)
            assertTrue(store.begin(owner,question,"question").second.references.isEmpty())
            assertThrows(ApiFailure::class.java) { store.edit(owner,row.id,JournalEdit(0,row.fact)) }
            assertThrows(ApiFailure::class.java) { store.edit("other",row.id,JournalEdit(1,row.fact)) }
            store.remove(owner,row.id)
            assertTrue(store.page(owner,null,null).entries.isEmpty())
            assertTrue(store.begin(owner,id,"hash").second.entries.isEmpty())
        }
    }
    @Test fun insightsExcludeMissingDaysAndAggregateDailySleepRatherThanEachNap() {
        database().use { db ->
            val store=PostgresJournal(db.pool); val owner=UUID.randomUUID().toString(); val id=UUID.randomUUID().toString()
            val yesterday=LocalDate.now(ZoneOffset.UTC).minusDays(1); val at=yesterday.atTime(12,0).toInstant(ZoneOffset.UTC)
            val facts=listOf(fact("SLEEP",360.0,at),fact("SLEEP",60.0,at.plusSeconds(3600)),fact("FOOD",at=at).copy(calories=0.0),fact("FOOD",at=at),fact("ACTIVITY",at=at).copy(steps=0))
            store.begin(owner,id,"hash"); store.finish(owner,id,JournalReply(id,"SAVED",entries=facts.map { JournalRecord("",id,0,it) }))
            val stats=store.insights(owner,yesterday.minusDays(29),yesterday,ZoneOffset.UTC)
            assertEquals(30,stats.daysInPeriod); assertEquals(1,stats.daysLogged); assertEquals(5,stats.totalEntries)
            assertEquals(420.0,stats.averageSleepMinutes!!,0.001); assertEquals(2,stats.sleepWithDuration)
            assertEquals(0.0,stats.foodCalories!!,0.001); assertEquals(1,stats.foodWithCalories); assertEquals(0L,stats.reportedSteps!!)
            assertNull(store.insights("other",yesterday,yesterday,ZoneOffset.UTC).averageSleepMinutes)
            store.purge(owner); assertEquals(0,store.insights(owner,yesterday,yesterday,ZoneOffset.UTC).totalEntries)
        }
    }
    @Test fun routesSaveMixedStatementsOnceAndNeverSaveClarificationOrQuestions()=testApplication {
        val db=database(); val store=PostgresJournal(db.pool); val owner=UUID.randomUUID().toString(); var calls=0
        application { metricMe(ServerConfig(null,null,null,null,null),null,object:AiGateway {
            override suspend fun estimate(kind:String,request:EstimateRequest,media:ByteArray):AiResult=error("unused")
            override suspend fun journal(request:JournalInput,media:ByteArray):JournalExtraction {
                calls++
                return if(request.text=="unclear") JournalExtraction("LOG",request.text,emptyList(),"Which medication did you take?",null,null)
                else if(request.text=="history") JournalExtraction("QUESTION",request.text,emptyList(),null,LocalDate.now().minusDays(29).toString(),LocalDate.now().toString())
                else JournalExtraction("LOG",request.text,listOf(fact("FOOD"),fact("MEDICATION"),fact("SLEEP",420.0)),null,null,null)
            }
        },verify={ if(it=="alice") owner else if(it=="bob") "other-owner" else null },journal=store) }
        try {
            assertEquals(HttpStatusCode.Unauthorized,client.get("/v1/journal/entries").status)
            suspend fun send(id:String,text:String,consent:Boolean=true)=client.post("/v1/journal/messages") { bearerAuth("alice"); header("Idempotency-Key",id); contentType(ContentType.Application.Json); setBody(WireJson.encodeToString(JournalInput(text=text,time=Instant.now().minusSeconds(10).toString(),zone="UTC",consented=consent))) }
            val id=UUID.randomUUID().toString()
            val input=JournalInput(text="Breakfast, medicine, seven hours sleep",time=Instant.now().minusSeconds(10).toString(),zone="UTC",consented=true)
            repeat(2) { val r=client.post("/v1/journal/messages") { bearerAuth("alice"); header("Idempotency-Key",id); contentType(ContentType.Application.Json); setBody(WireJson.encodeToString(input)) }; assertEquals("SAVED",WireJson.decodeFromString<JournalReply>(r.bodyAsText()).status) }
            assertEquals(1,calls); assertEquals(3,store.page(owner,null,null).entries.size)
            assertEquals("CLARIFY",WireJson.decodeFromString<JournalReply>(send(UUID.randomUUID().toString(),"unclear").bodyAsText()).status)
            assertEquals("ANSWER",WireJson.decodeFromString<JournalReply>(send(UUID.randomUUID().toString(),"history").bodyAsText()).status)
            assertEquals(3,store.page(owner,null,null).entries.size)
            assertEquals(HttpStatusCode.BadRequest,send(UUID.randomUUID().toString(),"no consent",false).status)
            assertTrue(WireJson.decodeFromString<JournalPage>(client.get("/v1/journal/entries") { bearerAuth("bob") }.bodyAsText()).entries.isEmpty())
        } finally { db.close() }
    }
}
