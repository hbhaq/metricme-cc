package com.hbhaq.metricme

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.Base64
import androidx.lifecycle.*
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.hbhaq.metricme.ai.*
import com.hbhaq.metricme.contracts.*
import com.hbhaq.metricme.data.*
import com.hbhaq.metricme.domain.*
import com.hbhaq.metricme.journal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import retrofit2.Response
import java.io.File
import java.time.*
import java.util.UUID

class GuidedJournalTest {
    private class IsolatedContext(base:Context):ContextWrapper(base) {
        val prefix="guided-test-"+UUID.randomUUID()+"-"
        private val dir=File(base.filesDir,prefix).apply { mkdirs() }
        override fun getFilesDir():File=dir
        override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=super.getSharedPreferences(prefix+name,mode)
        fun cleanup() { dir.deleteRecursively(); super.deleteSharedPreferences(prefix+"ai-connection") }
    }
    private suspend fun signIn(sessions:SessionStore,sub:String) {
        val payload=Base64.encodeToString(JSONObject().put("sub",sub).toString().toByteArray(),Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val flow=DeviceSignIn(post={ address,_ -> if(address.endsWith("/device/code")) JSONObject().put("device_code","test").put("user_code","TEST-CODE").put("verification_uri_complete",ConnectionSettings.MetricMe.issuer+"activate").put("expires_in",60).put("interval",5) else JSONObject().put("access_token","local-test-token").put("id_token","e30."+payload+".test").put("token_type","Bearer").put("expires_in",3600) },pause={},now={ 0L })
        sessions.store(flow.authorize(ConnectionSettings.MetricMe) {})
    }
    private class FakeApi:JournalApi {
        val inputs=mutableListOf<JournalInput>(); var loads=0; var records=emptyList<JournalRecord>()
        override suspend fun send(token:String,id:String,input:JournalInput):Response<JournalReply> {
            inputs+=input
            val result=if(input.mode=="INSIGHTS") when(input.text) {
                "How am I doing?" -> JournalReply(id,"CLARIFY",input.text,"Which goal?",clarification="Which goal?",continuation="Original question how doing; goal pending")
                "My sleep" -> JournalReply(id,"CLARIFY",input.text,"Which period?",clarification="Which period?",continuation="Sleep goal; period pending")
                else -> JournalReply(id,"ANSWER",input.text,"No sleep logs in that period.",continuation="Sleep last week")
            } else when(input.text) {
                "Burger" -> JournalReply(id,"CLARIFY",input.text,"What portion?",clarification="What portion?",continuation="Burger; portion unknown")
                "One large" -> JournalReply(id,"CLARIFY",input.text,"How prepared?",clarification="How prepared?",continuation="Burger; one large; preparation pending")
                else -> {
                    records=listOf(JournalRecord(UUID.randomUUID().toString(),id,1,JournalFact("FOOD","Grilled burger","Reported",Instant.now().minusSeconds(5).toString(),quantity=1.0,unit="large burger",calories=650.0,estimated=true)))
                    JournalReply(id,"SAVED",input.text,"Saved",records)
                }
            }
            return Response.success(result)
        }
        override suspend fun entries(token:String,before:String?,category:String?):Response<JournalPage> { loads++; return Response.success(JournalPage(records)) }
        override suspend fun undo(token:String,id:String):Response<Unit> =Response.success(Unit)
        override suspend fun delete(token:String,id:String):Response<Unit> =Response.success(Unit)
        override suspend fun edit(token:String,id:String,edit:JournalEdit):Response<JournalRecord> =Response.success(records.single())
        override suspend fun insights(token:String,zone:String,from:String?,to:String?):Response<JournalInsights> =error("unused")
        override suspend fun clear(token:String):Response<Unit> =Response.success(Unit)
    }
    private suspend fun model(context:Context,sessions:SessionStore,nutrition:NutritionRepository,api:FakeApi):Pair<JournalViewModel,ViewModelStore> =withContext(Dispatchers.Main) {
        val files=MediaFiles(context); val store=ViewModelStore()
        val owner=object:ViewModelStoreOwner { override val viewModelStore=store }
        val factory=object:ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T=JournalViewModel(context,sessions,files,LocalAudioRecorder(files),SavedStateHandle(),nutrition,object:JournalApiFactory() { override fun create(backend:String):JournalApi=api }) as T
        }
        ViewModelProvider(owner,factory)[JournalViewModel::class.java] to store
    }
    private suspend fun waitFor(model:JournalViewModel,condition:(JournalState)->Boolean) { withTimeout(10000) { model.state.first { !it.busy && condition(it) } } }
    @Test fun foodAndInsightClarificationsSurviveViewModelRecreation()=runBlocking<Unit> {
        val context=IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val db=Room.inMemoryDatabaseBuilder(context,MetricMeDatabase::class.java).build()
        val sessions=SessionStore(context); val nutrition=NutritionRepository(context,ProfileRepository(db,Clock.systemUTC()),sessions,Clock.systemUTC()); val api=FakeApi()
        val stores=mutableListOf<ViewModelStore>()
        try {
            signIn(sessions,"guided-user")
            var pair=model(context,sessions,nutrition,api); stores+=pair.second; var vm=pair.first
            waitFor(vm) { it.signedIn && api.loads>0 }
            withContext(Dispatchers.Main) { vm.type("Burger"); vm.acceptConsentAndSend() }
            waitFor(vm) { it.reply?.transcript=="Burger" }
            assertTrue(api.records.isEmpty())
            withContext(Dispatchers.Main) { vm.type("One large"); vm.send() }
            waitFor(vm) { it.reply?.transcript=="One large" }
            assertEquals("Burger; portion unknown",api.inputs.last().clarificationContext)
            withContext(Dispatchers.Main) { pair.second.clear() }
            pair=model(context,sessions,nutrition,api); stores+=pair.second; vm=pair.first
            waitFor(vm) { it.reply?.message=="How prepared?" }
            withContext(Dispatchers.Main) { vm.type("Grilled beef with cheese"); vm.send() }
            waitFor(vm) { it.reply?.status=="SAVED" }
            assertEquals("Burger; one large; preparation pending",api.inputs.last().clarificationContext)
            assertEquals(1,api.records.size)
            withContext(Dispatchers.Main) { vm.typeQuestion("How am I doing?"); vm.ask() }
            waitFor(vm) { it.questions.lastOrNull()?.message=="Which goal?" }
            withContext(Dispatchers.Main) { vm.typeQuestion("My sleep"); vm.ask() }
            waitFor(vm) { it.questions.lastOrNull()?.message=="Which period?" }
            withContext(Dispatchers.Main) { pair.second.clear() }
            pair=model(context,sessions,nutrition,api); stores+=pair.second; vm=pair.first
            waitFor(vm) { it.questions.lastOrNull()?.message=="Which period?" }
            withContext(Dispatchers.Main) { vm.typeQuestion("Last week"); vm.ask() }
            waitFor(vm) { it.questions.lastOrNull()?.status=="ANSWER" }
            assertTrue(api.inputs.last().clarificationContext.contains("Sleep goal; period pending"))
            assertEquals(1,api.records.size)
        } finally { withContext(Dispatchers.Main) { stores.forEach { it.clear() } }; sessions.signOut(); db.close(); context.cleanup() }
    }
    @Test fun calorieTargetNeedsConsentIsAccountBoundAndInvalidatesAfterProfileChange()=runBlocking<Unit> {
        val context=IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val db=Room.inMemoryDatabaseBuilder(context,MetricMeDatabase::class.java).build(); val clock=Clock.systemUTC()
        val profiles=ProfileRepository(db,clock); val sessions=SessionStore(context); val repo=NutritionRepository(context,profiles,sessions,clock)
        try {
            profiles.saveProfile(Profile(name="Test",birthDate=LocalDate.now(clock).minusYears(30),genderIdentity="Nonbinary",provenance=Provenance(createdAt=clock.instant())),UUID.randomUUID().toString())
            profiles.saveMeasurement(null,MeasurementKind.HEIGHT,170.0,LocalDate.now(clock),Acquisition.MEASURED,true,UUID.randomUUID().toString())
            profiles.saveMeasurement(null,MeasurementKind.WEIGHT,70.0,LocalDate.now(clock),Acquisition.MEASURED,true,UUID.randomUUID().toString())
            signIn(sessions,"account-a"); val key=requireNotNull(repo.accountKey())
            assertNull(repo.sharedProfile()); repo.allow(key)
            val base=repo.profile.first(); repo.accept(base,NutritionReply("READY","Estimate",calories=2200),key)
            assertEquals(2200,repo.sharedProfile()!!.dailyCalorieTarget)
            repo.keepInterview("Confirmed activity; goal pending",key)
            signIn(sessions,"account-b")
            assertFalse(repo.allowed()); assertNull(repo.profile.first().dailyCalorieTarget); assertEquals("",repo.interview(requireNotNull(repo.accountKey())))
            signIn(sessions,"account-a"); assertEquals(2200,repo.profile.first().dailyCalorieTarget)
            profiles.saveMeasurement(null,MeasurementKind.WEIGHT,71.0,LocalDate.now(clock),Acquisition.MEASURED,true,UUID.randomUUID().toString())
            assertNull(repo.profile.first().dailyCalorieTarget)
        } finally { sessions.signOut(); db.close(); context.cleanup() }
    }
}
