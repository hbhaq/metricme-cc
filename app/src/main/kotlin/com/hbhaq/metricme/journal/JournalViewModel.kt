package com.hbhaq.metricme.journal

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import android.util.Base64
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hbhaq.metricme.ai.*
import com.hbhaq.metricme.contracts.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.*
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject

interface JournalApi {
    @POST("v1/journal/messages") suspend fun send(@Header("Authorization") token:String,@Header("Idempotency-Key") id:String,@Body input:JournalInput):Response<JournalReply>
    @GET("v1/journal/entries") suspend fun entries(@Header("Authorization") token:String,@Query("before") before:String?=null,@Query("category") category:String?=null):Response<JournalPage>
    @DELETE("v1/journal/messages/{id}") suspend fun undo(@Header("Authorization") token:String,@Path("id") id:String):Response<Unit>
    @DELETE("v1/journal/entries/{id}") suspend fun delete(@Header("Authorization") token:String,@Path("id") id:String):Response<Unit>
    @PUT("v1/journal/entries/{id}") suspend fun edit(@Header("Authorization") token:String,@Path("id") id:String,@Body edit:JournalEdit):Response<JournalRecord>
    @GET("v1/journal/insights") suspend fun insights(@Header("Authorization") token:String,@Query("zone") zone:String,@Query("from") from:String?=null,@Query("to") to:String?=null):Response<JournalInsights>
    @DELETE("v1/account/data") suspend fun clear(@Header("Authorization") token:String):Response<Unit>
}
private fun journalApi(backend:String):JournalApi=Retrofit.Builder().baseUrl(backend)
    .client(OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(180,TimeUnit.SECONDS).callTimeout(200,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build())
    .addConverterFactory(WireJson.asConverterFactory("application/json".toMediaType())).build().create(JournalApi::class.java)

@Serializable data class PendingJournal(val id:String,val input:JournalInput)
data class JournalState(
    val entries:List<JournalRecord> = emptyList(),val before:String?=null,val busy:Boolean=false,
    val error:String?=null,val reply:JournalReply?=null,val insights:JournalInsights?=null,
    val pending:Boolean=false,val signedIn:Boolean=false,val attachment:String?=null,val attachmentMime:String="",
    val filter:String?=null,val consentAccepted:Boolean=false,val period:String="month",val insightDate:String=java.time.LocalDate.now().toString(),
    val questions:List<JournalReply> = emptyList(),val questionPending:Boolean=false
)

@HiltViewModel class JournalViewModel @Inject constructor(
    @param:ApplicationContext private val context:Context,val sessions:SessionStore,
    val media:MediaFiles,val recorder:LocalAudioRecorder,private val saved:SavedStateHandle
):ViewModel() {
    val state=MutableStateFlow(JournalState())
    val text=saved.getStateFlow("journalText","")
    val questionText=saved.getStateFlow("insightQuestion","")
    fun typeQuestion(value:String) { if(value.length<=10000) saved["insightQuestion"]=value }
    private var active:String?=null
    private var request:Job?=null
    private var clarification=""
    private val directory get()=File(context.filesDir,"journal-cache").apply { mkdirs() }
    private fun path(binding:String,name:String)=AtomicFile(File(directory,"$binding-$name.json"))
    private fun read(binding:String,name:String):String?=runCatching { path(binding,name).openRead().bufferedReader().use { it.readText() } }.getOrNull()
    private fun write(binding:String,name:String,content:String) {
        val target=path(binding,name); val output=target.startWrite()
        try { output.write(content.toByteArray(Charsets.UTF_8)); target.finishWrite(output) } catch(e:Exception) { target.failWrite(output); throw e }
    }
    private fun identity():String?=sessions.binding()?.let { binding ->
        java.security.MessageDigest.getInstance("SHA-256").digest("$binding|${sessions.settings().backend}".toByteArray()).joinToString("") { "%02x".format(it) }
    }
    init { viewModelScope.launch { sessions.changes.collect {
        val binding=identity()
        if(active!=binding) {
            request?.cancel(); recorder.discard(); state.value.attachment?.let(media::delete)
            active=binding; saved["journalText"]=""; saved["insightQuestion"]=""; clarification=""; state.value=JournalState(signedIn=binding!=null)
            if(binding!=null) {
                val cache=withContext(Dispatchers.IO) { trimMealCache(context); read(binding,"entries")?.let { runCatching { WireJson.decodeFromString<JournalPage>(it) }.getOrNull() } }
                if(active==binding) { state.value=state.value.copy(entries=cache?.entries.orEmpty(),before=cache?.nextBefore,pending=withContext(Dispatchers.IO) { read(binding,"pending")!=null },questionPending=withContext(Dispatchers.IO) { read(binding,"question")!=null },consentAccepted=withContext(Dispatchers.IO) { read(binding,"consent")==JOURNAL_CONSENT }); refresh() }
            }
        }
    } } }
    fun type(value:String) { if(value.length<=10000) saved["journalText"]=value }
    fun dismissError() { state.value=state.value.copy(error=null) }
    private fun launch(block:suspend (String,JournalApi,String)->Unit) {
        if(state.value.busy) return
        val who=active?:run { state.value=state.value.copy(error="Sign in from Setup to use your cloud journal."); return }
        val account=sessions.binding()?:return; val backend=sessions.settings().backend
        state.value=state.value.copy(busy=true,error=null)
        request=viewModelScope.launch {
            try { val token=sessions.accessToken(account); ensureActive(); check(active==who); block(who,journalApi(backend),"Bearer $token") }
            catch(e:CancellationException) { throw e }
            catch(e:JournalException) { if(active==who) state.value=state.value.copy(error=e.message) }
            catch(_:Exception) { if(active==who) state.value=state.value.copy(error="Could not reach your journal. Your pending message is kept on this device. Check your connection and sign-in, then retry.") }
            finally { if(active==who) state.value=state.value.copy(busy=false) }
        }
    }
    private fun <T> Response<T>.value():T {
        if(!isSuccessful) throw JournalException(when(code()) { 401,403 -> "Sign in again from Setup."; 404 -> "The service needs the journal update. Please try again shortly."; 409 -> "This entry changed. Refresh the journal before editing again."; 413 -> "Use a smaller photo or a shorter voice note."; 429 -> "Please wait a minute before trying again."; else -> "The journal service could not finish this request. Your message is kept for retry." })
        return body()?:throw JournalException("The journal returned an empty response.")
    }
    private suspend fun load(who:String,api:JournalApi,token:String,more:Boolean=false) {
        val page=api.entries(token,if(more) state.value.before else null,state.value.filter).value()
        if(active!=who) return
        val entries=if(more) (state.value.entries+page.entries).distinctBy { it.id } else page.entries
        state.value=state.value.copy(entries=entries.map { it.copy(fact=MealRetention.redact(it.fact)) },before=page.nextBefore)
        if(state.value.filter==null) withContext(Dispatchers.IO) { write(who,"entries",WireJson.encodeToString(JournalPage(entries.take(200),if(entries.size>200) null else page.nextBefore))) }
    }
    fun refresh()=launch { who,api,token -> load(who,api,token) }
    fun more()=launch { who,api,token -> load(who,api,token,true) }
    fun filter(category:String?) { if(state.value.busy) return; state.value=state.value.copy(filter=category); refresh() }
    fun attach(uri:Uri) {
        if(state.value.busy || state.value.pending) return
        val who=active
        request=viewModelScope.launch {
            try { val file=media.importPhoto(uri); if(active!=who) { media.delete(file.path); return@launch }; require(file.length()<=2_900_000) { "Choose a smaller photo." }; state.value.attachment?.let(media::delete); state.value=state.value.copy(attachment=file.path,attachmentMime="image/jpeg",error=null) }
            catch(_:Exception) { state.value=state.value.copy(error="Could not attach that photo. Choose a smaller image.") }
        }
    }
    fun cameraUri():Uri { val file=media.newFile("jpg"); saved["journalCamera"]=file.path; return media.uri(file) }
    fun cameraResult(success:Boolean) { val path:String=saved["journalCamera"]?:return; saved["journalCamera"]=null; if(success) { attach(media.uri(media.owned(path))) }; /* Camera raw file is private and cleaned up by media maintenance. */ }
    fun startRecording() { if(state.value.pending || state.value.busy) return; try { recorder.start(80) } catch(_:Exception) { state.value=state.value.copy(error="Microphone unavailable. You can type instead.") } }
    fun keepRecording() { recorder.state.value.path?.let { path -> state.value.attachment?.let(media::delete); state.value=state.value.copy(attachment=path,attachmentMime="audio/wav"); recorder.consume() } }
    fun removeAttachment() { state.value.attachment?.let(media::delete); state.value=state.value.copy(attachment=null,attachmentMime="") }
    fun acceptConsentAndSend() {
        val who=active?:return
        viewModelScope.launch { withContext(Dispatchers.IO) { write(who,"consent",JOURNAL_CONSENT) }; if(active==who) { state.value=state.value.copy(consentAccepted=true); send() } }
    }
    fun acceptConsentAndAsk() { val who=active?:return; viewModelScope.launch { withContext(Dispatchers.IO) { write(who,"consent",JOURNAL_CONSENT) }; if(active==who) { state.value=state.value.copy(consentAccepted=true); ask() } } }
    fun revokeConsent() { val who=active?:return; viewModelScope.launch { withContext(Dispatchers.IO) { path(who,"consent").delete() }; if(active==who) state.value=state.value.copy(consentAccepted=false) } }
    fun clearCloudData()=launch { who,api,token ->
        if(!api.clear(token).isSuccessful) throw JournalException("Could not delete cloud data yet. Please retry.")
        withContext(Dispatchers.IO) { path(who,"entries").delete(); path(who,"pending").delete(); path(who,"question").delete() }
        state.value=state.value.copy(entries=emptyList(),before=null,reply=null,insights=null,pending=false,questions=emptyList(),questionPending=false)
    }
    fun send()=launch { who,api,token ->
        val pending=withContext(Dispatchers.IO) { read(who,"pending")?.let { WireJson.decodeFromString<PendingJournal>(it) } }?:run {
            val attachment=state.value.attachment
            if(text.value.isBlank() && attachment==null) throw JournalException("Say something, type a note or attach a photo first.")
            val bytes=attachment?.let { withContext(Dispatchers.IO) { media.owned(it).readBytes() } }
            val input=JournalInput(text.value,bytes?.let { Base64.encodeToString(it,Base64.NO_WRAP) }.orEmpty(),state.value.attachmentMime,Instant.now().toString(),ZoneId.systemDefault().id,consented=true,clarificationContext=clarification)
            JournalRules.input(input)
            PendingJournal(UUID.randomUUID().toString(),input).also { withContext(Dispatchers.IO) { write(who,"pending",WireJson.encodeToString(it)) }; state.value=state.value.copy(pending=true) }
        }
        val reply=api.send(token,pending.id,pending.input).value()
        currentCoroutineContext().ensureActive(); if(active!=who) return@launch
        if(reply.status=="PROCESSING") throw JournalException("Still processing. Wait a moment, then tap Retry. The same message ID prevents duplicate entries.")
        withContext(Dispatchers.IO) { path(who,"pending").delete() }
        clarification=if(reply.status=="CLARIFY") (pending.input.clarificationContext+"\nUser: ${reply.transcript}\nQuestion: ${reply.clarification}").take(12000) else ""
        state.value=state.value.copy(reply=reply,pending=false,insights=reply.insights)
        if(reply.status in listOf("SAVED","CLARIFY","ANSWER","DELETED","EDITED")) { saved["journalText"]=""; removeAttachment() }
        load(who,api,token)
    }
    fun discardPending()=launch { who,api,token ->
        val pending=withContext(Dispatchers.IO) { read(who,"pending")?.let { WireJson.decodeFromString<PendingJournal>(it) } }
        if(pending!=null && !api.undo(token,pending.id).isSuccessful) throw JournalException("Could not cancel yet. Retry when connected.")
        withContext(Dispatchers.IO) { path(who,"pending").delete() }; state.value=state.value.copy(pending=false); load(who,api,token)
    }
    fun undo()=launch { who,api,token ->
        val id=state.value.reply?.takeIf { it.status=="SAVED" }?.id?:return@launch
        if(!api.undo(token,id).isSuccessful) throw JournalException("Could not undo yet. Please retry.")
        withContext(Dispatchers.IO) { path(who,"entries").delete() }
        state.value=state.value.copy(reply=null); load(who,api,token)
    }
    fun delete(entry:JournalRecord)=launch { who,api,token ->
        if(!api.delete(token,entry.id).isSuccessful) throw JournalException("Could not delete yet. Please retry.")
        withContext(Dispatchers.IO) { path(who,"entries").delete() }
        state.value=state.value.copy(reply=null,insights=null)
        load(who,api,token)
    }
    fun edit(entry:JournalRecord,fact:JournalFact,done:()->Unit)=launch { who,api,token -> JournalRules.fact(fact); api.edit(token,entry.id,JournalEdit(entry.revision,fact)).value(); withContext(Dispatchers.IO) { path(who,"entries").delete() }; state.value=state.value.copy(reply=null,insights=null); load(who,api,token); done() }
    fun insights()=launch { who,api,token ->
        val range=InsightPeriods.range(state.value.period,java.time.LocalDate.parse(state.value.insightDate))
        val data=api.insights(token,ZoneId.systemDefault().id,range.first.toString(),range.second.toString()).value()
        if(active==who) state.value=state.value.copy(insights=data)
    }
    fun choosePeriod(period:String) { if(state.value.busy || period !in listOf("day","week","month")) return; state.value=state.value.copy(period=period,insights=null); insights() }
    fun chooseInsightDate(date:java.time.LocalDate) { if(state.value.busy || date>java.time.LocalDate.now()) return; state.value=state.value.copy(insightDate=date.toString(),insights=null); insights() }
    fun shiftPeriod(direction:Long) {
        val date=java.time.LocalDate.parse(state.value.insightDate)
        chooseInsightDate(when(state.value.period) { "day" -> date.plusDays(direction); "week" -> date.plusWeeks(direction); else -> date.plusMonths(direction) }.coerceAtMost(java.time.LocalDate.now()))
    }
    fun ask()=launch { who,api,token ->
        val pending=withContext(Dispatchers.IO) { read(who,"question")?.let { WireJson.decodeFromString<PendingJournal>(it) } }?:run {
            if(questionText.value.isBlank()) throw JournalException("Type a question about your saved records.")
            val input=JournalInput(text=questionText.value,time=Instant.now().toString(),zone=ZoneId.systemDefault().id,consented=true,mode="INSIGHTS",clarificationContext=state.value.questions.lastOrNull()?.transcript.orEmpty().take(10000))
            JournalRules.input(input)
            PendingJournal(UUID.randomUUID().toString(),input).also { withContext(Dispatchers.IO) { write(who,"question",WireJson.encodeToString(it)) }; state.value=state.value.copy(questionPending=true) }
        }
        val result=api.send(token,pending.id,pending.input).value()
        if(result.status=="PROCESSING") throw JournalException("Still checking your records. Tap Retry in a moment.")
        withContext(Dispatchers.IO) { path(who,"question").delete() }
        if(active==who) { state.value=state.value.copy(questions=(state.value.questions+result.copy(transcript=pending.input.text)).takeLast(10),questionPending=false); saved["insightQuestion"]="" }
    }
    override fun onCleared() { recorder.stop(); super.onCleared() }
}
private class JournalException(message:String):Exception(message)

object InsightPeriods {
    fun range(period:String,anchor:java.time.LocalDate,today:java.time.LocalDate=java.time.LocalDate.now()):Pair<java.time.LocalDate,java.time.LocalDate> {
        require(anchor<=today)
        val from=when(period) { "day" -> anchor; "week" -> anchor.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)); "month" -> anchor.withDayOfMonth(1); else -> error("Unknown period") }
        val end=when(period) { "day" -> from; "week" -> from.plusDays(6); else -> from.plusMonths(1).minusDays(1) }
        return from to end.coerceAtMost(today)
    }
}
