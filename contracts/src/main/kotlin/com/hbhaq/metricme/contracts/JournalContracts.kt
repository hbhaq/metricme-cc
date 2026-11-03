package com.hbhaq.metricme.contracts

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

const val JOURNAL_CONSENT = "cloud-journal-v1"
val JOURNAL_CATEGORIES = listOf("FOOD", "MEDICATION", "SLEEP", "ACTIVITY", "WATER", "MOOD", "MEASUREMENT", "NOTE")

@Serializable data class JournalInput(
    val text:String="", val mediaBase64:String="", val mimeType:String="",
    val time:String, val zone:String, val consentVersion:String=JOURNAL_CONSENT,
    val consented:Boolean=false, val clarificationContext:String=""
)
@Serializable data class JournalFact(
    val category:String, val title:String, val note:String, val occurredAt:String,
    val quantity:Double?=null, val unit:String?=null,
    val calories:Double?=null, val protein:Double?=null, val carbs:Double?=null, val fat:Double?=null,
    val durationMinutes:Double?=null, val steps:Int?=null, val dose:String?=null,
    val estimated:Boolean=false
)
@Serializable data class JournalExtraction(
    val action:String, val transcript:String, val entries:List<JournalFact>,
    val clarification:String?, val fromDate:String?, val toDate:String?,val categoryFilter:String?=null
)
@Serializable data class JournalRecord(val id:String,val messageId:String,val revision:Int,val fact:JournalFact)
@Serializable data class JournalReply(
    val id:String, val status:String, val transcript:String="", val message:String="",
    val entries:List<JournalRecord> = emptyList(), val clarification:String?=null,
    val insights:JournalInsights?=null,val references:List<JournalRecord> = emptyList()
)
@Serializable data class JournalPage(val entries:List<JournalRecord>,val nextBefore:String?=null)
@Serializable data class JournalEdit(val revision:Int,val fact:JournalFact)
@Serializable data class CategorySummary(val category:String,val entries:Int,val daysLogged:Int)
@Serializable data class JournalInsights(
    val fromDate:String,val toDate:String,val daysInPeriod:Int,val daysLogged:Int,val totalEntries:Int,
    val categories:List<CategorySummary>,val foodCalories:Double?,val foodWithCalories:Int,
    val averageSleepMinutes:Double?,val sleepWithDuration:Int,val reportedSteps:Long?,val activityWithSteps:Int,
    val note:String="Based only on recorded entries. Missing days are unknown. Associations do not establish causes."
)

object JournalRules {
    fun input(value:JournalInput) {
        require(value.consented && value.consentVersion==JOURNAL_CONSENT) { "Cloud journal consent required." }
        require(value.text.length<=10000 && value.clarificationContext.length<=12000)
        require(value.text.isNotBlank() || value.mediaBase64.isNotBlank())
        require(value.mimeType in listOf("", "image/jpeg", "image/png", "audio/wav"))
        require(value.mediaBase64.isEmpty()==value.mimeType.isEmpty())
        require(value.mediaBase64.length<=4_000_000) { "Use a smaller image or a voice note under 80 seconds." }
        val at=Instant.parse(value.time); require(at<=Instant.now().plusSeconds(300))
        ZoneId.of(value.zone)
    }
    fun fact(value:JournalFact,now:Instant=Instant.now()) {
        require(value.category in JOURNAL_CATEGORIES && value.title.isNotBlank() && value.title.length<=160 && value.note.length<=2000)
        require(Instant.parse(value.occurredAt)<=now.plusSeconds(300)) { "Journal events must not be in the future." }
        require(value.unit==null || value.unit.length<=50); require(value.dose==null || value.dose.length<=200)
        fun bounded(v:Double?,max:Double) { require(v==null || v.isFinite() && v in 0.0..max) }
        bounded(value.quantity,100000.0); bounded(value.calories,20000.0)
        listOf(value.protein,value.carbs,value.fat).forEach { bounded(it,5000.0) }
        bounded(value.durationMinutes,1440.0); require(value.steps==null || value.steps in 0..200000)
        require(value.category=="MEDICATION" || value.dose==null)
        require(value.category=="FOOD" || listOf(value.calories,value.protein,value.carbs,value.fat).all { it==null })
        require(value.category=="ACTIVITY" || value.steps==null)
    }
    fun extraction(value:JournalExtraction) {
        require(value.action in listOf("LOG","QUESTION")); require(value.transcript.length<=20000)
        require(value.categoryFilter==null || value.categoryFilter in JOURNAL_CATEGORIES)
        require(value.entries.size<=30); value.entries.forEach { fact(it) }
        require(value.clarification==null || value.clarification.isNotBlank() && value.clarification.length<=1000)
        if(value.clarification!=null) require(value.entries.isEmpty())
        if(value.action=="LOG") require(value.entries.isNotEmpty() || value.clarification!=null)
        if(value.action=="QUESTION") { require(value.entries.isEmpty()); LocalDate.parse(requireNotNull(value.fromDate)); LocalDate.parse(requireNotNull(value.toDate)) }
    }
}
