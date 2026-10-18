package com.hbhaq.metricme.ai

import android.content.Context
import android.util.Base64
import androidx.room.withTransaction
import androidx.work.*
import com.hbhaq.metricme.contracts.*
import com.hbhaq.metricme.data.MetricMeDatabase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.Response
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.*
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

interface MetricMeApi {
    @POST("v1/food-estimates") suspend fun food(@Header("Authorization") bearer:String,@Header("Idempotency-Key") key:String,@Body request:EstimateRequest):Response<JobResponse>
    @POST("v1/activity-transcriptions") suspend fun activity(@Header("Authorization") bearer:String,@Header("Idempotency-Key") key:String,@Body request:EstimateRequest):Response<JobResponse>
    @DELETE("v1/jobs/{id}") suspend fun purge(@Header("Authorization") bearer:String,@Path("id") id:String):Response<Unit>
}
fun apiAt(url:String):MetricMeApi {
    val uri=java.net.URI(url); require(uri.scheme=="https" && uri.userInfo==null && uri.query==null && uri.fragment==null)
    val client=OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(10)).readTimeout(Duration.ofSeconds(180)).callTimeout(Duration.ofSeconds(200)).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    return Retrofit.Builder().baseUrl(url).client(client).addConverterFactory(WireJson.asConverterFactory("application/json".toMediaType())).build().create(MetricMeApi::class.java)
}
@Singleton class AiQueue @Inject constructor(@param:ApplicationContext private val context:Context,private val drafts:AiDraftRepository,private val files:MediaFiles,private val sessions:SessionStore,private val db:MetricMeDatabase) {
    private val work get()=WorkManager.getInstance(context)
    fun enqueue(draftId:String,jobId:String) {
        val request=OneTimeWorkRequestBuilder<AiUploadWorker>().setInputData(workDataOf("draftId" to draftId,"jobId" to jobId)).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).addTag("ai:$draftId").build()
        work.enqueueUniqueWork("ai:$jobId",ExistingWorkPolicy.KEEP,request)
    }
    fun maintain() { work.enqueueUniqueWork("ai-maintenance-now",ExistingWorkPolicy.APPEND_OR_REPLACE,OneTimeWorkRequestBuilder<AiMaintenanceWorker>().build()) }
    fun start() { work.enqueueUniquePeriodicWork("ai-maintenance-periodic",ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<AiMaintenanceWorker>(6,TimeUnit.HOURS).build()); maintain() }
    suspend fun cancel(id:String,delete:Boolean) {
        val path=drafts.cancel(id,delete); work.cancelAllWorkByTag("ai:$id"); if(delete) files.delete(path); maintain()
    }
    suspend fun maintenance() {
        val now=System.currentTimeMillis()
        for(row in drafts.dao.all()) {
            if(row.localMediaPath!=null && (row.stamp.createdAt<now-7*86400000L || row.stamp.processing=="CONFIRMED" && row.stamp.updatedAt<now-86400000L)) {
                if(row.stamp.processing!="CONFIRMED") cancel(row.id,true) else db.withTransaction { drafts.dao.draft(row.copy(localMediaPath=null)) }
                files.delete(row.localMediaPath)
            }
        }
        val owned=drafts.dao.all().mapNotNull { it.localMediaPath }.toSet()
        files.files().filter { it.path !in owned && it.lastModified()<now-3600000 }.forEach { files.delete(it.path) }
        for(job in drafts.dao.recoverable()) {
            val row=drafts.dao.draft(job.draftId)?:continue; val value=WireJson.decodeFromString<DraftContent>(row.content)
            if(job.status=="PURGE_PENDING") {
                if(value.binding==null || value.backend==null || sessions.binding()!=value.binding) continue
                try {
                    val response=apiAt(value.backend).purge("Bearer ${sessions.accessToken(value.binding)}",job.operationKey)
                    if(response.isSuccessful || response.code()==404) db.withTransaction {
                        val current=drafts.dao.job(row.id)?:return@withTransaction
                        if(current.id!=job.id || current.status!="PURGE_PENDING") return@withTransaction
                        drafts.dao.job(current.copy(status="CANCELLED",updatedAt=now))
                        if(row.stamp.processing=="DELETED") drafts.dao.remove(row.id) else drafts.dao.clearTombstone(row.id)
                    }
                } catch(e:CancellationException) { throw e } catch(_:Exception) { /* Authenticated purge remains pending. */ }
            } else if(job.attempt<5 && row.localMediaPath!=null && sessions.binding()==value.binding) enqueue(row.id,job.id)
        }
    }
}
@EntryPoint @InstallIn(SingletonComponent::class) interface AiEntryPoint { fun drafts():AiDraftRepository; fun sessions():SessionStore; fun queue():AiQueue; fun files():MediaFiles }
class AiUploadWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result {
        val graph=EntryPointAccessors.fromApplication(applicationContext,AiEntryPoint::class.java)
        val repo=graph.drafts(); val id=inputData.getString("draftId")?:return Result.failure(); val jobId=inputData.getString("jobId")?:return Result.failure()
        val row=repo.dao.draft(id)?:return Result.success(); val job=repo.dao.job(id)?:return Result.success()
        if(job.id!=jobId || repo.dao.tombstone(id)!=null || job.status !in listOf("QUEUED","RUNNING","RETRYABLE","AUTH_REQUIRED")) return Result.success()
        if(job.attempt>=5 || runAttemptCount>=5) { repo.status(id,jobId,"FAILED","Retry limit reached. Review before submitting again."); return Result.failure() }
        val value=WireJson.decodeFromString<DraftContent>(row.content)
        val binding=value.binding?:return Result.failure(); val backend=value.backend?:return Result.failure()
        try {
            val token=try { graph.sessions().accessToken(binding) } catch(e:CancellationException) { throw e } catch(_:Exception) { repo.status(id,jobId,"AUTH_REQUIRED","Sign in with the account that queued this draft."); return Result.success() }
            val file=graph.files().owned(row.localMediaPath?:error("Media expired."))
            require(file.length() in 1..MAX_MEDIA.toLong())
            repo.status(id,jobId,"RUNNING",attempt=job.attempt+1)
            val request=EstimateRequest(Base64.encodeToString(withContext(Dispatchers.IO) { file.readBytes() },Base64.NO_WRAP),value.mime,value.context,job.consentVersion,true)
            val api=apiAt(backend); val response=if(row.kind=="FOOD") api.food("Bearer $token",job.operationKey,request) else api.activity("Bearer $token",job.operationKey,request)
            val result=response.body()
            val safeCode=if(!response.isSuccessful) runCatching { WireJson.decodeFromString<ApiError>(response.errorBody()?.string()?.take(2048).orEmpty()).code }.getOrNull() else null
            when {
                response.code()==401 || response.code()==403 -> { repo.status(id,jobId,"AUTH_REQUIRED","Sign in again to continue."); return Result.success() }
                response.code()==503 && safeCode=="not_configured" -> { repo.status(id,jobId,"FAILED","The service is not configured for real AI yet. Your draft stays on this device."); return Result.failure() }
                response.code() in listOf(408,429) || response.code() in 500..599 -> { repo.status(id,jobId,"RETRYABLE","Temporary server issue. Retrying with the same operation key."); return Result.retry() }
                !response.isSuccessful || result==null -> { repo.status(id,jobId,"FAILED","Request rejected. Check the media, connection configuration and consent."); return Result.failure() }
                result.status=="READY" && result.result!=null -> { if(!repo.apply(id,jobId,requireNotNull(result.result))) graph.queue().maintain(); return Result.success() }
                result.status in listOf("RUNNING","RETRYABLE") -> { repo.status(id,jobId,"RETRYABLE","Processing or temporarily unavailable. Retrying safely."); return Result.retry() }
                else -> { repo.status(id,jobId,"FAILED",when(result.code) { "refused" -> "The model declined this request. Use manual logging."; "upstream_uncertain" -> "The upstream outcome is uncertain. Automatic retries stopped. Review before making a new request."; else -> "No usable estimate was returned. Try clearer media or manual entry." }); return Result.failure() }
            }
        } catch(e:CancellationException) { throw e }
        catch(_:java.io.IOException) { repo.status(id,jobId,"RETRYABLE","Offline or interrupted. Your draft is safe; retrying with the same key."); return Result.retry() }
        catch(_:Exception) { repo.status(id,jobId,"FAILED","Could not process this draft. Check media and sign-in, or log manually."); return Result.failure() }
    }
}
class AiMaintenanceWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result=try { EntryPointAccessors.fromApplication(applicationContext,AiEntryPoint::class.java).queue().maintenance(); Result.success() } catch(e:CancellationException) { throw e } catch(_:Exception) { if(runAttemptCount<3) Result.retry() else Result.failure() }
}
