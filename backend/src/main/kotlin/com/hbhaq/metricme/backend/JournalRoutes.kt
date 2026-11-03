package com.hbhaq.metricme.backend

import com.hbhaq.metricme.contracts.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*
import java.time.*
import java.util.Base64
import java.util.UUID

fun Route.journalRoutes(store:JournalStore?,gateway:AiGateway?,slots:Semaphore) {
    suspend fun journalFor(owner:String):JournalStore { val db=store?:throw ApiFailure("not_configured",503); withContext(Dispatchers.IO) { db.compact(owner) }; return db }
    fun String.uuid():String=try { require(UUID.fromString(this).toString()==this); this } catch(_:Exception) { throw ApiFailure("invalid_id",400) }
    post("/v1/journal/messages") {
        val owner=call.principal<SubjectPrincipal>()!!.hash
        val db=journalFor(owner); val ai=gateway?:throw ApiFailure("not_configured",503)
        val id=(call.request.headers["Idempotency-Key"]?:throw ApiFailure("idempotency_required",400)).uuid()
        if(!slots.tryAcquire()) throw ApiFailure("busy",429,true)
        try {
            val bytes=withTimeout(20000) { call.receiveChannel().readRemaining(4_100_001).readByteArray() }
            if(bytes.size>4_100_000) throw ApiFailure("upload_too_large",413)
            val request=try { WireJson.decodeFromString<JournalInput>(String(bytes,Charsets.UTF_8)).also(JournalRules::input) } catch(_:Exception) { throw ApiFailure("invalid_journal_input",400) }
            val media=if(request.mimeType.isEmpty()) byteArrayOf() else try { Base64.getDecoder().decode(request.mediaBase64) } catch(_:Exception) { throw ApiFailure("invalid_media",400) }
            if(request.mimeType.startsWith("image/")) MediaValidation.image(media,request.mimeType)
            if(request.mimeType=="audio/wav") MediaValidation.audio(media,request.mimeType)
            val (owned,previous)=withContext(Dispatchers.IO) { db.begin(owner,id,journalRequestHash(bytes,request)) }
            if(!owned) { call.journalRespond(previous,if(previous.status=="PROCESSING") HttpStatusCode.Accepted else HttpStatusCode.OK); return@post }
            val reply=try {
                var extraction=ai.journal(request,media); JournalRules.extraction(extraction)
                extraction=extraction.copy(entries=extraction.entries.map { it.copy(zone=request.zone) })
                if(request.mode=="INSIGHTS" && extraction.action!="QUESTION" && extraction.clarification==null) extraction=extraction.copy(action="LOG",entries=emptyList(),clarification="What would you like to know about your saved records? To log a new event, use Log.")
                if(request.mimeType.startsWith("image/") && request.text.isBlank() && extraction.entries.any { it.category=="MEDICATION" }) {
                    extraction=extraction.copy(entries=emptyList(),clarification="What medicine and dose did you actually take, and when? A photo alone does not establish that.")
                }
                if(request.mimeType.startsWith("image/") && request.text.isBlank() && extraction.clarification==null && extraction.entries.any { it.category=="FOOD" }) {
                    val question="How much of each food or drink in the photo did you actually have? Please confirm portion sizes and any preparation, oil or sauce details you know."
                    extraction=extraction.copy(entries=emptyList(),clarification=question,continuation=JournalQuality.continuation(request.clarificationContext,extraction,question))
                }
                JournalQuality.question(extraction)?.let { question ->
                    extraction=extraction.copy(entries=emptyList(),clarification=question,continuation=JournalQuality.continuation(request.clarificationContext,extraction,question))
                }
                if(extraction.clarification!=null) JournalReply(id,"CLARIFY",extraction.transcript,extraction.clarification.orEmpty(),clarification=extraction.clarification,continuation=JournalQuality.continuation(request.clarificationContext,extraction,extraction.clarification.orEmpty()))
                else if(extraction.action=="QUESTION") {
                    val zone=ZoneId.of(request.zone)
                    val from=LocalDate.parse(extraction.fromDate); val to=LocalDate.parse(extraction.toDate)
                    val baseStats=withContext(Dispatchers.IO) { db.insights(owner,from,to,zone) }
                    val profile=request.profile
                    val applicable=profile?.dailyCalorieTarget!=null && profile.targetEffectiveFrom?.let { LocalDate.parse(it)<=to }==true
                    val stats=if(applicable) baseStats.copy(dailyCalorieTarget=profile?.dailyCalorieTarget,targetEffectiveFrom=profile?.targetEffectiveFrom) else baseStats
                    val rows=withContext(Dispatchers.IO) { db.search(owner,from,to,zone,extraction.categoryFilter,extraction.searchTerm,extraction.questionMode=="LATEST") }
                    val latest=extraction.questionMode=="LATEST"
                    val message=if(latest) rows.firstOrNull()?.let { "Your most recent matching record is ${it.fact.title}, on ${Instant.parse(it.fact.occurredAt).atZone(zone).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy 'at' HH:mm"))}." }
                        ?:"No matching detailed record was found from $from to $to. Meal names and exact times are kept for three months; older nutrition summaries cannot answer item lookups."
                    else if(extraction.searchTerm!=null) "Found ${rows.size} matching saved records (up to 50), from $from to $to. Meal details are available for three months."
                    else "Period summary across all categories. "+insightText(stats)
                    val explanation=ai.explain(request,stats,rows)
                    val question=explanation?.clarification
                    if(question!=null) JournalReply(id,"CLARIFY",extraction.transcript,question,clarification=question,continuation=explanation.continuation.ifBlank { extraction.continuation })
                    else JournalReply(id,"ANSWER",extraction.transcript,explanation?.message?.takeIf(String::isNotBlank)?:message,insights=if(latest || extraction.searchTerm!=null) null else stats,references=rows,continuation=explanation?.continuation?.takeIf(String::isNotBlank)?:extraction.continuation)
                } else JournalReply(id,"SAVED",extraction.transcript,"Saved ${extraction.entries.size} ${if(extraction.entries.size==1) "entry" else "entries"} to your journal.",extraction.entries.map { JournalRecord("",id,0,it) })
            } catch(e:CancellationException) {
                withContext(NonCancellable+Dispatchers.IO) { db.finish(owner,id,JournalReply(id,"UNCERTAIN",message="Processing was interrupted. Check your journal before sending a new message.")) }; throw e
            } catch(_:Exception) { JournalReply(id,"FAILED",message="Could not understand this message. Nothing was journalled. Try again with a clearer description.") }
            call.journalRespond(withContext(Dispatchers.IO) { db.finish(owner,id,reply) })
        } finally { slots.release() }
    }
    get("/v1/journal/entries") { val owner=call.principal<SubjectPrincipal>()!!.hash; val db=journalFor(owner); call.journalRespond(withContext(Dispatchers.IO) { db.page(owner,call.request.queryParameters["before"],call.request.queryParameters["category"]) }) }
    put("/v1/journal/entries/{id}") {
        val owner=call.principal<SubjectPrincipal>()!!.hash; val db=journalFor(owner)
        val edit=try { call.receive<JournalEdit>().also { JournalRules.fact(it.fact) } } catch(_:Exception) { throw ApiFailure("invalid_entry",400) }
        call.journalRespond(withContext(Dispatchers.IO) { db.edit(owner,call.parameters["id"].orEmpty().uuid(),edit) })
    }
    delete("/v1/journal/entries/{id}") { val owner=call.principal<SubjectPrincipal>()!!.hash; val db=journalFor(owner); withContext(Dispatchers.IO) { db.remove(owner,call.parameters["id"].orEmpty().uuid()) }; call.respond(HttpStatusCode.NoContent) }
    delete("/v1/journal/messages/{id}") { val owner=call.principal<SubjectPrincipal>()!!.hash; val db=journalFor(owner); withContext(Dispatchers.IO) { db.undo(owner,call.parameters["id"].orEmpty().uuid()) }; call.respond(HttpStatusCode.NoContent) }
    get("/v1/journal/insights") {
        val owner=call.principal<SubjectPrincipal>()!!.hash; val db=journalFor(owner)
        val dates=try { val zone=ZoneId.of(call.request.queryParameters["zone"]?:"UTC"); val to=call.request.queryParameters["to"]?.let(LocalDate::parse)?:LocalDate.now(zone); Triple(call.request.queryParameters["from"]?.let(LocalDate::parse)?:to.minusDays(29),to,zone) } catch(_:Exception) { throw ApiFailure("invalid_date_range",400) }
        call.journalRespond(withContext(Dispatchers.IO) { db.insights(owner,dates.first,dates.second,dates.third) })
    }
}

// A new optional null profile must not change an older Android pending request's receipt hash.
internal fun journalRequestHash(bytes:ByteArray,input:JournalInput):String {
    if(input.profile!=null) return sha256(bytes)
    val raw=WireJson.parseToJsonElement(String(bytes,Charsets.UTF_8)).jsonObject
    return if("profile" in raw) sha256(JsonObject(raw.filterKeys { it!="profile" }).toString().toByteArray(Charsets.UTF_8)) else sha256(bytes)
}

// 0.4.0 uses strict JSON parsing. New metadata is opt-in so that existing installs keep working.
internal suspend inline fun <reified T:Any> ApplicationCall.journalRespond(value:T,status:HttpStatusCode=HttpStatusCode.OK) {
    val feature=request.headers["X-MetricMe-Features"]
    if(feature=="guided-journal-v1") respond(status,value)
    else respond(status,compatibleJournalJson(WireJson.encodeToJsonElement(value),feature!="meal-retention-v1"))
}
@PublishedApi internal fun legacyJournalJson(value:JsonElement):JsonElement=compatibleJournalJson(value,true)
@PublishedApi internal fun compatibleJournalJson(value:JsonElement,legacy:Boolean):JsonElement=when(value) {
    is JsonObject -> JsonObject(value.filterKeys { it !in (if(legacy) setOf("zone","detailsRetained","continuation","dailyCalorieTarget","targetEffectiveFrom") else setOf("continuation","dailyCalorieTarget","targetEffectiveFrom")) }.mapValues { compatibleJournalJson(it.value,legacy) })
    is JsonArray -> JsonArray(value.map { compatibleJournalJson(it,legacy) })
    else -> value
}

fun insightText(s:JournalInsights):String=buildString {
    append("${s.fromDate} to ${s.toDate}: ${s.totalEntries} recorded entries across ${s.daysLogged} of ${s.daysInPeriod} days. ")
    s.categories.forEach { append("${it.category.lowercase().replaceFirstChar(Char::uppercaseChar)}: ${it.entries} entries on ${it.daysLogged} days. ") }
    s.averageSleepMinutes?.let { append("Average reported sleep on days with duration: ${"%.1f".format(java.util.Locale.ROOT,it/60)} hours. ") }
    s.foodCalories?.let { append("Logged food subtotal: ${it.toLong()} kcal across ${s.foodWithCalories} entries with known/estimated calories. ") }
    s.reportedSteps?.let { append("Reported activity steps: $it. ") }
    append(s.note)
}
