package com.hbhaq.metricme.backend

import com.hbhaq.metricme.contracts.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.io.readByteArray
import java.time.*
import java.util.Base64
import java.util.UUID

fun Route.journalRoutes(store:JournalStore?,gateway:AiGateway?,slots:Semaphore) {
    fun String.uuid():String=try { require(UUID.fromString(this).toString()==this); this } catch(_:Exception) { throw ApiFailure("invalid_id",400) }
    post("/v1/journal/messages") {
        val owner=call.principal<SubjectPrincipal>()!!.hash
        val db=store?:throw ApiFailure("not_configured",503); val ai=gateway?:throw ApiFailure("not_configured",503)
        val id=(call.request.headers["Idempotency-Key"]?:throw ApiFailure("idempotency_required",400)).uuid()
        if(!slots.tryAcquire()) throw ApiFailure("busy",429,true)
        try {
            val bytes=withTimeout(20000) { call.receiveChannel().readRemaining(4_100_001).readByteArray() }
            if(bytes.size>4_100_000) throw ApiFailure("upload_too_large",413)
            val request=try { WireJson.decodeFromString<JournalInput>(String(bytes,Charsets.UTF_8)).also(JournalRules::input) } catch(_:Exception) { throw ApiFailure("invalid_journal_input",400) }
            val media=if(request.mimeType.isEmpty()) byteArrayOf() else try { Base64.getDecoder().decode(request.mediaBase64) } catch(_:Exception) { throw ApiFailure("invalid_media",400) }
            if(request.mimeType.startsWith("image/")) MediaValidation.image(media,request.mimeType)
            if(request.mimeType=="audio/wav") MediaValidation.audio(media,request.mimeType)
            val (owned,previous)=withContext(Dispatchers.IO) { db.begin(owner,id,sha256(bytes)) }
            if(!owned) { call.respond(if(previous.status=="PROCESSING") HttpStatusCode.Accepted else HttpStatusCode.OK,previous); return@post }
            val reply=try {
                var extraction=ai.journal(request,media); JournalRules.extraction(extraction)
                if(request.mimeType.startsWith("image/") && request.text.isBlank() && extraction.entries.any { it.category=="MEDICATION" }) {
                    extraction=extraction.copy(entries=emptyList(),clarification="What medicine and dose did you actually take, and when? A photo alone does not establish that.")
                }
                if(extraction.clarification!=null) JournalReply(id,"CLARIFY",extraction.transcript,extraction.clarification.orEmpty(),clarification=extraction.clarification)
                else if(extraction.action=="QUESTION") {
                    val zone=ZoneId.of(request.zone)
                    val from=LocalDate.parse(extraction.fromDate); val to=LocalDate.parse(extraction.toDate)
                    val stats=withContext(Dispatchers.IO) { db.insights(owner,from,to,zone) }
                    val start=from.atStartOfDay(zone).toInstant(); val end=to.plusDays(1).atStartOfDay(zone).toInstant()
                    val rows=withContext(Dispatchers.IO) { db.page(owner,"${end.toEpochMilli()}|00000000-0000-0000-0000-000000000000",extraction.categoryFilter).entries.filter { Instant.parse(it.fact.occurredAt)>=start && Instant.parse(it.fact.occurredAt)<end } }
                    JournalReply(id,"ANSWER",extraction.transcript,"Period summary across all categories. "+insightText(stats),insights=stats,references=rows)
                } else JournalReply(id,"SAVED",extraction.transcript,"Saved ${extraction.entries.size} ${if(extraction.entries.size==1) "entry" else "entries"} to your journal.",extraction.entries.map { JournalRecord("",id,0,it) })
            } catch(e:CancellationException) {
                withContext(NonCancellable+Dispatchers.IO) { db.finish(owner,id,JournalReply(id,"UNCERTAIN",message="Processing was interrupted. Check your journal before sending a new message.")) }; throw e
            } catch(_:Exception) { JournalReply(id,"FAILED",message="Could not understand this message. Nothing was journalled. Try again with a clearer description.") }
            call.respond(withContext(Dispatchers.IO) { db.finish(owner,id,reply) })
        } finally { slots.release() }
    }
    get("/v1/journal/entries") { val owner=call.principal<SubjectPrincipal>()!!.hash; val db=store?:throw ApiFailure("not_configured",503); call.respond(withContext(Dispatchers.IO) { db.page(owner,call.request.queryParameters["before"],call.request.queryParameters["category"]) }) }
    put("/v1/journal/entries/{id}") {
        val owner=call.principal<SubjectPrincipal>()!!.hash; val db=store?:throw ApiFailure("not_configured",503)
        val edit=try { call.receive<JournalEdit>().also { JournalRules.fact(it.fact) } } catch(_:Exception) { throw ApiFailure("invalid_entry",400) }
        call.respond(withContext(Dispatchers.IO) { db.edit(owner,call.parameters["id"].orEmpty().uuid(),edit) })
    }
    delete("/v1/journal/entries/{id}") { val owner=call.principal<SubjectPrincipal>()!!.hash; val db=store?:throw ApiFailure("not_configured",503); withContext(Dispatchers.IO) { db.remove(owner,call.parameters["id"].orEmpty().uuid()) }; call.respond(HttpStatusCode.NoContent) }
    delete("/v1/journal/messages/{id}") { val owner=call.principal<SubjectPrincipal>()!!.hash; val db=store?:throw ApiFailure("not_configured",503); withContext(Dispatchers.IO) { db.undo(owner,call.parameters["id"].orEmpty().uuid()) }; call.respond(HttpStatusCode.NoContent) }
    get("/v1/journal/insights") {
        val owner=call.principal<SubjectPrincipal>()!!.hash; val db=store?:throw ApiFailure("not_configured",503)
        val dates=try { val zone=ZoneId.of(call.request.queryParameters["zone"]?:"UTC"); val to=call.request.queryParameters["to"]?.let(LocalDate::parse)?:LocalDate.now(zone); Triple(call.request.queryParameters["from"]?.let(LocalDate::parse)?:to.minusDays(29),to,zone) } catch(_:Exception) { throw ApiFailure("invalid_date_range",400) }
        call.respond(withContext(Dispatchers.IO) { db.insights(owner,dates.first,dates.second,dates.third) })
    }
}

fun insightText(s:JournalInsights):String=buildString {
    append("${s.fromDate} to ${s.toDate}: ${s.totalEntries} recorded entries across ${s.daysLogged} of ${s.daysInPeriod} days. ")
    s.categories.forEach { append("${it.category.lowercase().replaceFirstChar(Char::uppercaseChar)}: ${it.entries} entries on ${it.daysLogged} days. ") }
    s.averageSleepMinutes?.let { append("Average reported sleep on days with duration: ${"%.1f".format(java.util.Locale.ROOT,it/60)} hours. ") }
    s.foodCalories?.let { append("Logged food subtotal: ${it.toLong()} kcal across ${s.foodWithCalories} entries with known/estimated calories. ") }
    s.reportedSteps?.let { append("Reported activity steps: $it. ") }
    append(s.note)
}
