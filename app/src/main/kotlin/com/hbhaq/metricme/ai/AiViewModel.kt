package com.hbhaq.metricme.ai

import android.net.Uri
import androidx.lifecycle.*
import com.hbhaq.metricme.contracts.*
import com.hbhaq.metricme.domain.EventTimes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import javax.inject.Inject

@HiltViewModel class AiViewModel @Inject constructor(private val repository:AiDraftRepository,private val queue:AiQueue,val sessions:SessionStore,val media:MediaFiles,val recorder:LocalAudioRecorder,private val saved:SavedStateHandle):ViewModel() {
    val drafts=repository.drafts.catch { error.value="Local drafts could not be opened. Restart to reconnect; nothing was deleted." }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val selected=saved.getStateFlow("aiSelected","")
    val fields=saved.getStateFlow("aiFields",hashMapOf<String,String>())
    val dirty=saved.getStateFlow("aiDirty",false)
    val busy=MutableStateFlow(false)
    val error=MutableStateFlow<String?>(null)
    private var editing:DraftContent?=null
    fun field(key:String,fallback:String="")=fields.value[key]?:fallback
    fun change(key:String,value:String) { if(value.length>20000) return; saved["aiFields"]=HashMap(fields.value).apply { put(key,value) }; saved["aiDirty"]=true; error.value=null }
    fun select(row:AiDraft) { saved["aiSelected"]=row.row.id; saved["aiFields"]=hashMapOf<String,String>(); saved["aiDirty"]=false; editing=row.value; error.value=null }
    fun sync(row:AiDraft) { if(!dirty.value) editing=row.value }
    fun close() { if(!busy.value) { saved["aiSelected"]=""; saved["aiFields"]=hashMapOf<String,String>(); saved["aiDirty"]=false; editing=null; error.value=null } }
    fun refresh() { queue.maintain() }
    private fun launch(block:suspend ()->Unit) {
        if(busy.value) return; busy.value=true; error.value=null
        viewModelScope.launch { try { block() } catch(e:CancellationException) { throw e } catch(e:IllegalArgumentException) { error.value=e.message?.take(300)?:"Check the entered values." } catch(_:Exception) { error.value="This action could not finish. Your saved draft is safe. Check sign-in, storage and connection." } finally { busy.value=false } }
    }
    fun cameraUri():Uri { val file=media.newFile("jpg"); saved["aiCamera"]=file.path; return media.uri(file) }
    fun cameraResult(success:Boolean) { val path:String=saved["aiCamera"]?:return; saved["aiCamera"]=null; if(success) importPhoto(media.uri(media.owned(path)),path) else media.delete(path) }
    fun importPhoto(uri:Uri,temporary:String?=null)=launch {
        try { val file=media.importPhoto(uri); val id=repository.create("FOOD",file.path,"image/jpeg"); repository.dao.draft(id)?.let { select(AiDraft(it,WireJson.decodeFromString(it.content))) } } finally { media.delete(temporary) }
    }
    fun startAudio() { try { recorder.start() } catch(_:Exception) { error.value="Microphone unavailable. Check permission or use manual logging." } }
    fun keepAudio()=launch {
        val path=recorder.state.value.path?:throw IllegalArgumentException("Record a short activity note first.")
        val id=repository.create("ACTIVITY",path,"audio/wav"); recorder.consume(); repository.dao.draft(id)?.let { select(AiDraft(it,WireJson.decodeFromString(it.content))) }
    }
    private fun build(row:AiDraft):DraftContent {
        val value=row.value
        fun text(key:String,old:String?)=field(key,old.orEmpty()).trim().ifBlank { null }
        fun number(key:String,old:Double?):Double? { val raw=text(key,old?.toString())?:return null; return raw.toDoubleOrNull()?.takeIf(Double::isFinite)?:throw IllegalArgumentException("Use finite decimal numbers, or leave optional fields blank.") }
        fun integer(key:String,old:Int?):Int? { val raw=text(key,old?.toString())?:return null; return raw.toIntOrNull()?:throw IllegalArgumentException("Steps, sets and reps need whole numbers.") }
        val result=value.result?.let { r ->
            if(r.kind=="FOOD") r.copy(food=r.food!!.let { f -> f.copy(items=f.items.mapIndexed { i,item -> item.copy(name=text("f$i.name",item.name).orEmpty(),quantity=number("f$i.quantity",item.quantity)?:0.0,unit=text("f$i.unit",item.unit).orEmpty(),calories=number("f$i.calories",item.calories),protein=number("f$i.protein",item.protein),carbs=number("f$i.carbs",item.carbs),fat=number("f$i.fat",item.fat)) },portionConsumed=(number("portion",f.portionConsumed*100)?:100.0)/100,needsClarification=field("clarification",f.needsClarification.toString())=="true") })
            else r.copy(activity=r.activity!!.let { a -> a.copy(type=text("type",a.type),durationMinutes=number("minutes",a.durationMinutes),steps=integer("steps",a.steps),intensity=text("intensity",a.intensity),needsClarification=field("clarification",a.needsClarification.toString())=="true",exercises=field("exercises",a.exercises.joinToString("\n") { "${it.name} | ${it.sets?:""} | ${it.reps?:""} | ${it.loadKg?:""}" }).lineSequence().filter(String::isNotBlank).map { line ->
                val parts=line.split('|').map(String::trim); require(parts.size in 1..4) { "Use name | sets | reps | load in kg for exercises." }
                fun part(i:Int)=parts.getOrNull(i)?.takeIf(String::isNotBlank)
                ExerciseEstimate(parts[0],part(1)?.let { it.toIntOrNull()?:throw IllegalArgumentException("Sets need a whole number.") },part(2)?.let { it.toIntOrNull()?:throw IllegalArgumentException("Reps need a whole number.") },part(3)?.let { it.toDoubleOrNull()?:throw IllegalArgumentException("Load needs a number in kg.") })
            }.toList()) })
        }
        val updated=value.copy(context=field("context",value.context),time=field("time",value.time),zone=field("zone",value.zone),offset=field("offset",value.offset),meal=field("meal",value.meal),result=result)
        require(updated.context.length<=2000) { "Keep context within 2,000 characters." }; EventTimes.resolve(updated.time,updated.zone,updated.offset); result?.let(EstimateRules::validate)
        return updated
    }
    private suspend fun persist(id:String):AiDraft {
        val raw=repository.dao.draft(id)?:error("Draft removed"); val row=AiDraft(raw,WireJson.decodeFromString(raw.content))
        if(dirty.value) repository.edit(id,row.row.revision,build(row))
        saved["aiFields"]=hashMapOf<String,String>(); saved["aiDirty"]=false
        return repository.dao.draft(id)!!.let { AiDraft(it,WireJson.decodeFromString(it.content)) }
    }
    fun save(id:String)=launch { persist(id) }
    fun upload(id:String)=launch {
        val row=persist(id); require(row.value.result?.sample!=true)
        val binding=sessions.binding()?:throw IllegalArgumentException("Sign in before consenting to an upload.")
        val settings=sessions.settings(); require(settings.complete) { "Configure the secure service connection first." }
        val job=repository.queue(id,binding,settings.backend); queue.enqueue(id,job)
    }
    fun confirm(id:String)=launch { val row=persist(id); repository.confirm(id,row.row.revision); closeAfterAction() }
    private fun closeAfterAction() { saved["aiSelected"]=""; saved["aiFields"]=hashMapOf<String,String>(); saved["aiDirty"]=false; editing=null }
    fun cancel(id:String,delete:Boolean)=launch { queue.cancel(id,delete); if(delete) closeAfterAction() }
    fun configure(settings:ConnectionSettings)=launch { require(drafts.value.none { it.row.stamp.processing in listOf("QUEUED","RUNNING","RETRYABLE","AUTH_REQUIRED") }) { "Cancel pending uploads before changing the connection." }; sessions.configure(settings); queue.maintain() }
    fun signIn() { try { sessions.signIn() } catch(_:Exception) { error.value="Configure your backend and secure sign-in connection first." } }
}
