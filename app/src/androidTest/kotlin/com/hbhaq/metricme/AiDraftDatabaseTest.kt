package com.hbhaq.metricme

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.hbhaq.metricme.ai.*
import com.hbhaq.metricme.contracts.*
import com.hbhaq.metricme.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.time.*
import java.util.UUID

class AiDraftDatabaseTest {
    private lateinit var db:MetricMeDatabase
    private lateinit var repo:AiDraftRepository
    private val clock=Clock.fixed(Instant.parse("2026-10-18T12:00:00Z"),ZoneId.of("UTC"))
    @Before fun setup() { db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,MetricMeDatabase::class.java).build(); repo=AiDraftRepository(db,LocalMutations(db,clock),clock) }
    @After fun close() { db.close() }
    private suspend fun ready(kind:String="FOOD",result:AiResult=DemoEstimates.food().copy(sample=false)):String {
        val id=repo.create(kind,"/private/test-media",if(kind=="FOOD") "image/jpeg" else "audio/wav")
        val job=repo.queue(id,"account","https://api.example.test/"); assertTrue(repo.apply(id,job,result)); return id
    }
    @Test fun draftsNeverCountAndConfirmedBatchIsAtomicAndIdempotent()=runBlocking {
        val food=DemoEstimates.food().let { it.copy(sample=false,food=it.food!!.copy(items=it.food.items+FoodItem("Second food",1.0,"serving",null,null,null,null))) }
        val id=ready(result=food); assertTrue(db.loggingDao().foods().first().isEmpty())
        val row=repo.dao.draft(id)!!; repeat(2) { repo.confirm(id,row.revision) }
        val entries=db.loggingDao().foods().first(); assertEquals(2,entries.size); assertTrue(entries.all { it.entry.stamp.source=="AI_ESTIMATED" && it.entry.stamp.confirmation=="CONFIRMED" }); assertEquals(1,entries.count { it.components.single().calories==null })
    }
    @Test fun deletedDraftRejectsLateResultsAndKeepsTombstone()=runBlocking {
        val id=repo.create("FOOD","/private/test-media","image/jpeg"); val job=repo.queue(id,"a","https://api.example.test/")
        repo.cancel(id,true); assertFalse(repo.apply(id,job,DemoEstimates.food().copy(sample=false))); assertNotNull(repo.dao.tombstone(id)); assertEquals("DELETED",repo.dao.draft(id)!!.stamp.processing); assertNull(repo.dao.draft(id)!!.localMediaPath)
    }
    @Test fun cancellationPreservesLocalDraftAndStaleJobCannotOverwriteNewEdits()=runBlocking {
        val id=repo.create("FOOD","/private/test-media","image/jpeg"); val oldJob=repo.queue(id,"a","https://api.example.test/"); repo.cancel(id,false)
        val row=repo.dao.draft(id)!!; val content=WireJson.decodeFromString<DraftContent>(row.content); repo.edit(id,row.revision,content.copy(context="My preserved edit"))
        assertFalse(repo.apply(id,oldJob,DemoEstimates.food().copy(sample=false)))
        repo.dao.clearTombstone(id); val newJob=repo.queue(id,"a","https://api.example.test/"); assertNotEquals(oldJob,newJob)
        assertFalse(repo.apply(id,oldJob,DemoEstimates.food().copy(sample=false))); assertTrue(repo.apply(id,newJob,DemoEstimates.food().copy(sample=false)))
        assertEquals("My preserved edit",WireJson.decodeFromString<DraftContent>(repo.dao.draft(id)!!.content).context)
    }
    @Test fun queuedEditsRejectedAndDraftSurvivesRepositoryRecreation()=runBlocking {
        val id=repo.create("FOOD","/private/test-media","image/jpeg"); repo.queue(id,"a","https://api.example.test/"); val row=repo.dao.draft(id)!!
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.edit(id,row.revision,WireJson.decodeFromString(row.content)) } }
        val restored=AiDraftRepository(db,LocalMutations(db,clock),clock); assertEquals(id,restored.dao.draft(id)!!.id); assertEquals("QUEUED",restored.dao.recoverable().single().status)
    }
    @Test fun activityUnknownStepsRemainNullAfterConfirmation()=runBlocking {
        val id=ready("ACTIVITY",DemoEstimates.activity().copy(sample=false)); repo.confirm(id,repo.dao.draft(id)!!.revision)
        assertNull(db.loggingDao().activities().first().single().entry.reportedSteps)
    }
    @Test fun sampleAndUnresolvedClarificationCannotBeConfirmed()=runBlocking {
        val sample=repo.create("FOOD",null,"",sample=true)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.confirm(sample,0) } }
        val result=DemoEstimates.food().let { it.copy(sample=false,food=it.food!!.copy(needsClarification=true,question="Which portion?")) }
        val id=ready(result=result); assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.confirm(id,repo.dao.draft(id)!!.revision) } }; assertTrue(db.loggingDao().foods().first().isEmpty())
    }
}
