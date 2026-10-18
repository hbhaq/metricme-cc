package com.hbhaq.metricme.ai

import androidx.room.*
import com.hbhaq.metricme.contracts.*
import com.hbhaq.metricme.data.*
import com.hbhaq.metricme.domain.*
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import java.time.*
import java.util.UUID
import javax.inject.Inject

@Serializable data class DraftContent(val context:String="",val mime:String="",val time:String,val zone:String,val offset:String="",val result:AiResult?=null,val error:String?=null,val binding:String?=null,val backend:String?=null,val meal:String="Other",val originalTranscript:String?=null)
data class AiDraft(val row:DraftEntity,val value:DraftContent)
@Dao interface AiDao {
    @Query("SELECT * FROM Draft WHERE profileId='local' ORDER BY createdAt DESC") fun observe():kotlinx.coroutines.flow.Flow<List<DraftEntity>>
    @Query("SELECT * FROM Draft WHERE id=:id AND profileId='local'") suspend fun draft(id:String):DraftEntity?
    @Upsert suspend fun draft(row:DraftEntity)
    @Query("SELECT * FROM ProcessingJob WHERE draftId=:id ORDER BY createdAt DESC LIMIT 1") suspend fun job(id:String):ProcessingJobEntity?
    @Query("SELECT * FROM ProcessingJob WHERE status IN ('QUEUED','RUNNING','RETRYABLE','AUTH_REQUIRED','PURGE_PENDING')") suspend fun recoverable():List<ProcessingJobEntity>
    @Query("SELECT * FROM Draft") suspend fun all():List<DraftEntity>
    @Upsert suspend fun job(row:ProcessingJobEntity)
    @Query("SELECT * FROM DeletionTombstone WHERE id=:id") suspend fun tombstone(id:String):TombstoneEntity?
    @Upsert suspend fun tombstone(row:TombstoneEntity)
    @Query("DELETE FROM DeletionTombstone WHERE id=:id") suspend fun clearTombstone(id:String)
    @Query("DELETE FROM Draft WHERE id=:id") suspend fun remove(id:String)
}
class AiDraftRepository @Inject constructor(private val db:MetricMeDatabase,private val mutations:LocalMutations,private val clock:Clock) {
    val dao get()=db.aiDao()
    val drafts=dao.observe().map { rows -> rows.map { AiDraft(it,WireJson.decodeFromString<DraftContent>(it.content)) } }
    suspend fun create(kind:String,path:String?,mime:String,sample:Boolean=false):String=mutations.execute(UUID.randomUUID().toString(),"new-ai-draft:${UUID.randomUUID()}") {
        require(kind in listOf("FOOD","ACTIVITY")); val id=UUID.randomUUID().toString(); val now=clock.instant(); val zone=clock.zone
        val value=DraftContent(mime=mime,time=now.atZone(zone).toLocalDateTime().withSecond(0).withNano(0).toString(),zone=zone.id,result=if(sample) if(kind=="FOOD") DemoEstimates.food() else DemoEstimates.activity() else null)
        dao.draft(DraftEntity(id,"local",kind,0,path,WireJson.encodeToString(value),UUID.randomUUID().toString(),Stamp(source=if(sample) "MANUAL" else "AI_ESTIMATED",confirmation="DRAFT",processing=if(sample) "READY" else "LOCAL",createdAt=now.toEpochMilli(),updatedAt=now.toEpochMilli())))
        id
    }
    suspend fun edit(id:String,expected:Int,value:DraftContent)=db.withTransaction {
        val old=dao.draft(id)?:return@withTransaction
        require(old.revision==expected) { "The draft changed. Reopen it to keep the latest edits." }
        require(old.stamp.processing !in listOf("QUEUED","RUNNING","RETRYABLE","AUTH_REQUIRED")) { "Cancel the pending upload before editing." }
        require(old.stamp.processing!="CONFIRMED"); require(value.context.length<=2000)
        value.result?.let(EstimateRules::validate)
        dao.draft(old.copy(revision=old.revision+1,content=WireJson.encodeToString(value),stamp=old.stamp.copy(updatedAt=clock.millis())))
    }
    suspend fun queue(id:String,binding:String,backend:String)=db.withTransaction {
        val old=dao.draft(id)?:error("Draft no longer exists."); val value=WireJson.decodeFromString<DraftContent>(old.content)
        require(value.result?.sample!=true && old.localMediaPath!=null && old.stamp.processing !in listOf("CONFIRMED","DELETED"))
        require(dao.tombstone(id)==null) { "Wait for cancellation cleanup before uploading again." }
        val pending=dao.job(id)
        if(pending?.status in listOf("QUEUED","RUNNING","RETRYABLE","AUTH_REQUIRED")) return@withTransaction pending!!.id
        val key=UUID.randomUUID().toString(); val rev=old.revision+1; val now=clock.millis()
        dao.draft(old.copy(revision=rev,operationKey=key,content=WireJson.encodeToString(value.copy(error=null,binding=binding,backend=backend)),stamp=old.stamp.copy(processing="QUEUED",updatedAt=now)))
        dao.job(ProcessingJobEntity(key,id,key,"QUEUED",0,null,CONSENT_VERSION,rev,now,now)); key
    }
    suspend fun status(id:String,jobId:String,status:String,code:String?=null,attempt:Int?=null)=db.withTransaction {
        val row=dao.draft(id)?:return@withTransaction; val job=dao.job(id)?:return@withTransaction
        if(job.id!=jobId || dao.tombstone(id)!=null || row.revision!=job.revision || row.stamp.processing=="CONFIRMED" || job.status=="CANCELLED") return@withTransaction
        val value=WireJson.decodeFromString<DraftContent>(row.content)
        dao.draft(row.copy(content=WireJson.encodeToString(value.copy(error=code)),stamp=row.stamp.copy(processing=status,updatedAt=clock.millis())))
        dao.job(job.copy(status=status,attempt=attempt?:job.attempt,updatedAt=clock.millis()))
    }
    suspend fun apply(id:String,jobId:String,result:AiResult):Boolean=db.withTransaction {
        EstimateRules.validate(result); require(!result.sample)
        val row=dao.draft(id)?:return@withTransaction false; val job=dao.job(id)?:return@withTransaction false
        if(dao.tombstone(id)!=null || row.revision!=job.revision || job.id!=jobId || job.status=="CANCELLED" || row.stamp.processing=="CONFIRMED") return@withTransaction false
        val value=WireJson.decodeFromString<DraftContent>(row.content)
        dao.draft(row.copy(content=WireJson.encodeToString(value.copy(result=result,error=null,originalTranscript=result.activity?.transcript)),stamp=row.stamp.copy(processing="READY",confidence=result.food?.confidence,updatedAt=clock.millis())))
        dao.job(job.copy(status="READY",updatedAt=clock.millis())); true
    }
    suspend fun cancel(id:String,delete:Boolean):String?=db.withTransaction {
        val row=dao.draft(id)?:return@withTransaction null
        val job=dao.job(id); job?.let { dao.job(it.copy(status="PURGE_PENDING",updatedAt=clock.millis())) }
        dao.tombstone(TombstoneEntity(id,clock.millis(),job!=null))
        if(delete && job==null) { dao.remove(id); return@withTransaction row.localMediaPath }
        if(delete) { /* Keep a media-free tombstoned shell until remote purge is acknowledged. */
            dao.draft(row.copy(localMediaPath=null,content=WireJson.encodeToString(WireJson.decodeFromString<DraftContent>(row.content).copy(context="",result=null,error=null,originalTranscript=null)),stamp=row.stamp.copy(processing="DELETED")))
        } else {
            val value=WireJson.decodeFromString<DraftContent>(row.content)
            dao.draft(row.copy(revision=row.revision+1,content=WireJson.encodeToString(value.copy(error="Upload cancelled. Local draft retained.")),stamp=row.stamp.copy(processing="CANCELLED")))
        }
        row.localMediaPath
    }
    suspend fun confirm(id:String,expected:Int):String=mutations.execute("${UUID.nameUUIDFromBytes("confirm:$id".toByteArray())}","confirm-ai:$id") {
        val row=dao.draft(id)?:error("Draft was deleted."); require(row.revision==expected)
        val value=WireJson.decodeFromString<DraftContent>(row.content); val result=requireNotNull(value.result) { "Wait for an editable result first." }
        require(!result.sample) { "Sample data stays separate from your personal journal." }; EstimateRules.validate(result)
        val t=EventTimes.resolve(value.time,value.zone,value.offset); require(t.instant<=clock.instant()) { "Choose a past event time." }
        val stamp=Stamp(source="AI_ESTIMATED",confirmation="CONFIRMED",processing="COMPLETE",confidence=result.food?.confidence,createdAt=clock.millis(),updatedAt=clock.millis())
        if(row.kind=="FOOD") {
            val food=requireNotNull(result.food); require(!food.needsClarification && food.items.isNotEmpty()) { "Resolve the clarification or use manual entry before confirming." }
            require(value.meal in listOf("Breakfast","Lunch","Dinner","Snack","Other"))
            // Confirm the reviewed batch atomically; each recognized food stays independently editable.
            food.items.forEachIndexed { index,item ->
                LogRules.validate(FoodLog(null,t,item.name,value.meal,item.quantity,item.unit,food.portionConsumed,item.calories,item.protein,item.carbs,item.fat),clock.instant())
                val itemId=UUID.nameUUIDFromBytes("$id:item:$index".toByteArray()).toString()
                val key=UUID.nameUUIDFromBytes("${row.operationKey}:$index".toByteArray()).toString()
                db.loggingDao().food(FoodEntity(itemId,"local",t.instant.toEpochMilli(),t.zoneId,t.offsetSeconds,t.date.toString(),value.meal,food.portionConsumed,key,stamp))
                db.loggingDao().components(listOf(FoodComponentEntity(UUID.randomUUID().toString(),itemId,item.name,item.quantity,item.unit,item.calories,item.protein,item.carbs,item.fat)))
            }
        } else {
            val a=requireNotNull(result.activity); require(!a.needsClarification && !a.type.isNullOrBlank()) { "Resolve the activity name or clarification first." }
            val activityType=requireNotNull(a.type)
            val notes="Confirmed AI draft. ${a.assumptions.joinToString(" ")}".take(2000)
            LogRules.validate(ActivityLog(null,t,activityType,a.durationMinutes,a.steps,a.intensity,notes,a.exercises.map { Exercise(it.name,it.sets,it.reps,it.loadKg) }),clock.instant())
            db.loggingDao().activity(ActivityEntity(id,"local",t.instant.toEpochMilli(),t.zoneId,t.offsetSeconds,t.date.toString(),activityType,a.durationMinutes?.let { (it*60).toLong() },a.steps,a.intensity,notes,row.operationKey,stamp))
            db.loggingDao().exercises(a.exercises.map { ExerciseEntity(UUID.randomUUID().toString(),id,it.name,it.sets,it.reps,it.loadKg) })
        }
        dao.draft(row.copy(stamp=row.stamp.copy(confirmation="CONFIRMED",processing="CONFIRMED",updatedAt=clock.millis()))); id
    }
}
