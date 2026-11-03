package com.hbhaq.metricme.journal

import android.content.Context
import android.util.AtomicFile
import androidx.lifecycle.*
import com.hbhaq.metricme.ai.SessionStore
import com.hbhaq.metricme.contracts.*
import com.hbhaq.metricme.data.ProfileRepository
import com.hbhaq.metricme.domain.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.*
import java.io.File
import java.time.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Serializable internal data class AcceptedNutrition(val profile:WellnessProfile,val calories:Int,val date:String)
@Singleton class NutritionRepository @Inject constructor(@param:ApplicationContext private val context:Context,private val profiles:ProfileRepository,val sessions:SessionStore,private val clock:Clock) {
    val revision=MutableStateFlow(0)
    fun accountKey():String?=sessions.binding()?.let { it+"|"+sessions.settings().backend }
    private fun file(kind:String,key:String?=accountKey()):AtomicFile? {
        val binding=key?:return null
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(binding.toByteArray()).joinToString("") { "%02x".format(it) }
        val dir=File(context.filesDir,"nutrition").apply { mkdirs() }
        return AtomicFile(File(dir,hash+"-"+kind+".json"))
    }
    private fun read(kind:String,key:String?=accountKey())=runCatching { file(kind,key)?.openRead()?.bufferedReader()?.use { it.readText() } }.getOrNull()
    private fun write(kind:String,text:String,key:String?=accountKey()) { check(key!=null && key==accountKey()); val f=file(kind,key)?:error("Sign in first"); val out=f.startWrite(); try { out.write(text.toByteArray()); f.finishWrite(out) } catch(e:Exception) { f.failWrite(out); throw e }; revision.value++ }
    fun allowed(key:String?=accountKey())=read("consent",key)=="profile-ai-v1"
    fun allow(key:String)=write("consent","profile-ai-v1",key)
    fun revoke() { file("consent")?.delete(); revision.value++ }
    private fun enrich(base:WellnessProfile):WellnessProfile {
        val accepted=read("target")?.let { runCatching { WireJson.decodeFromString<AcceptedNutrition>(it) }.getOrNull() }
        return if(accepted?.profile==base) base.copy(dailyCalorieTarget=accepted.calories,targetEffectiveFrom=accepted.date) else base
    }
    val profile=combine(profiles.profile,profiles.measurements,sessions.changes,revision) { p,measurements,_,_ ->
        val now=clock.instant()
        withContext(Dispatchers.IO) { enrich(WellnessProfile(p?.birthDate?.let { Period.between(it,LocalDate.now(clock)).years },p?.genderIdentity.orEmpty(),HealthRules.effectiveMeasurement(measurements,MeasurementKind.HEIGHT,now)?.value,HealthRules.effectiveMeasurement(measurements,MeasurementKind.WEIGHT,now)?.value)) }
    }
    suspend fun sharedProfile():WellnessProfile? { val key=accountKey()?:return null; if(!withContext(Dispatchers.IO) { allowed(key) }) return null; val p=profile.first(); check(key==accountKey()); return p }
    suspend fun accept(base:WellnessProfile,reply:NutritionReply,key:String) {
        require(reply.status=="READY" && reply.calories!=null)
        val current=profile.first().copy(dailyCalorieTarget=null,targetEffectiveFrom=null)
        require(current==base) { "Your profile changed. Request an updated estimate." }
        withContext(Dispatchers.IO) { write("target",WireJson.encodeToString(AcceptedNutrition(base,requireNotNull(reply.calories),LocalDate.now(clock).toString())),key) }
    }
    suspend fun interview(key:String):String=withContext(Dispatchers.IO) { read("interview",key).orEmpty() }
    suspend fun keepInterview(text:String,key:String)=withContext(Dispatchers.IO) { check(key==accountKey()); if(text.isBlank()) file("interview",key)?.delete() else write("interview",text,key) }
}

private interface NutritionApi { @POST("v1/nutrition/target") suspend fun ask(@Header("Authorization") token:String,@Body request:NutritionRequest):retrofit2.Response<NutritionReply> }
data class NutritionState(val busy:Boolean=false,val reply:NutritionReply?=null,val error:String?=null,val consent:Boolean=false)
@HiltViewModel class NutritionViewModel @Inject constructor(val repository:NutritionRepository,private val saved:SavedStateHandle):ViewModel() {
    val state=MutableStateFlow(NutritionState())
    val profile=repository.profile.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),WellnessProfile())
    val text=saved.getStateFlow("nutritionText","")
    private var request:Job?=null
    private var proposed:WellnessProfile?=null
    private var active:String?=null
    init { viewModelScope.launch { combine(repository.sessions.changes,repository.revision) { a,b -> a to b }.collect {
        val key=repository.accountKey()
        if(active!=key) { active=key; request?.cancel(); state.value=NutritionState(); proposed=null; saved["nutritionText"]="" }
        val allowed=withContext(Dispatchers.IO) { repository.allowed(key) }
        if(active==key) { if(state.value.consent && !allowed) { request?.cancel(); proposed=null }; state.value=state.value.copy(consent=allowed) }
    } } }
    fun type(value:String) { if(value.length<=4000) saved["nutritionText"]=value }
    fun dismissError() { state.value=state.value.copy(error=null) }
    fun ask(allow:Boolean=false) {
        if(state.value.busy) return
        val account=repository.sessions.binding()?:run { state.value=state.value.copy(error="Sign in from Setup first."); return }
        val backend=repository.sessions.settings().backend
        val key=repository.accountKey()?:return
        state.value=state.value.copy(busy=true,error=null)
        request=viewModelScope.launch {
            try {
                if(allow) withContext(Dispatchers.IO) { repository.allow(key) }
                check(withContext(Dispatchers.IO) { repository.allowed(key) })
                val base=repository.profile.first().copy(dailyCalorieTarget=null,targetEffectiveFrom=null)
                val input=NutritionRequest(base,text.value.ifBlank { "Help me choose a daily calorie target." },repository.interview(key),true)
                WellnessRules.request(input)
                val token=repository.sessions.accessToken(account)
                val api=Retrofit.Builder().baseUrl(backend).client(OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(100,TimeUnit.SECONDS).callTimeout(110,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()).addConverterFactory(WireJson.asConverterFactory("application/json".toMediaType())).build().create(NutritionApi::class.java)
                val response=api.ask("Bearer "+token,input)
                check(response.isSuccessful) { if(response.code()==401) "Sign in again from Setup." else "Could not get a recommendation. Please retry." }
                val reply=requireNotNull(response.body())
                ensureActive(); check(repository.sessions.binding()==account && repository.sessions.settings().backend==backend)
                proposed=base
                repository.keepInterview(reply.continuation,key)
                state.value=state.value.copy(reply=reply,consent=true)
                saved["nutritionText"]=""
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { if(active==key) state.value=state.value.copy(error=if(e is IllegalArgumentException) "Add a valid age, height and weight in You first." else e.message?.take(180)?:"Could not reach AI. Please retry.") }
            finally { if(active==key) state.value=state.value.copy(busy=false) }
        }
    }
    fun accept(done:()->Unit={}) {
        val base=proposed?:return; val reply=state.value.reply?:return
        if(state.value.busy) return
        val account=repository.sessions.binding()?:return
        val key=repository.accountKey()?:return
        state.value=state.value.copy(busy=true,error=null)
        request=viewModelScope.launch {
            try { check(repository.sessions.binding()==account); repository.accept(base,reply,key); repository.keepInterview("",key); if(active==key) { state.value=state.value.copy(reply=null); done() } }
            catch(e:CancellationException) { throw e }
            catch(_:Exception) { if(active==key) state.value=state.value.copy(error="Profile changed or target could not be saved. Request an updated estimate.") }
            finally { if(active==key) state.value=state.value.copy(busy=false) }
        }
    }
    fun restart() { if(state.value.busy) return; val key=repository.accountKey()?:return; request=viewModelScope.launch { repository.keepInterview("",key); if(active==key) { state.value=state.value.copy(reply=null); proposed=null; saved["nutritionText"]="" } } }
}
