package com.hbhaq.metricme.contracts

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val WireJson = Json { ignoreUnknownKeys = false; encodeDefaults = true; explicitNulls = true }
const val CONSENT_VERSION = "media-ai-v1"
const val MAX_MEDIA = 10 * 1024 * 1024
@Serializable data class EstimateRequest(val mediaBase64: String, val mimeType: String, val context: String = "", val consentVersion: String, val consented: Boolean)
@Serializable data class FoodItem(val name: String, val quantity: Double, val unit: String, val calories: Double?, val protein: Double?, val carbs: Double?, val fat: Double?)
@Serializable data class FoodEstimate(val items: List<FoodItem>, val assumptions: List<String>, val confidence: Double?, val needsClarification: Boolean, val question: String?, val portionConsumed: Double)
@Serializable data class ExerciseEstimate(val name: String, val sets: Int?, val reps: Int?, val loadKg: Double?)
@Serializable data class ActivityEstimate(val transcript: String, val type: String?, val start: String?, val durationMinutes: Double?, val steps: Int?, val intensity: String?, val exercises: List<ExerciseEstimate>, val assumptions: List<String>, val needsClarification: Boolean, val question: String?)
@Serializable data class AiResult(val kind: String, val food: FoodEstimate? = null, val activity: ActivityEstimate? = null, val model: String, val sample: Boolean = false)
@Serializable data class JobResponse(val id: String, val status: String, val result: AiResult? = null, val code: String? = null, val retryable: Boolean = false, val retryAfterSeconds: Int? = null)
@Serializable data class ApiError(val code: String, val retryable: Boolean = false, val requestId: String = "")
@Serializable data class ServiceInfo(val status: String, val aiConfigured: Boolean, val authConfigured: Boolean)

object EstimateRules {
    fun validate(result: AiResult) {
        require(result.kind in listOf("FOOD", "ACTIVITY"))
        fun text(value: String?, max: Int) { require(value == null || value.length <= max) }
        fun number(value: Double?, max: Double) { require(value == null || value.isFinite() && value in 0.0..max) }
        require(result.model.isNotBlank() && result.model.length <= 100)
        if (result.kind == "FOOD") {
            require(result.activity == null); val f = requireNotNull(result.food)
            require(f.items.size <= 30 && (f.items.isNotEmpty() || f.needsClarification)); number(f.confidence, 1.0)
            require(f.portionConsumed.isFinite() && f.portionConsumed in 0.0..1.0)
            require(f.assumptions.size <= 20); f.assumptions.forEach { text(it, 500) }; text(f.question, 1000)
            require(!f.needsClarification || !f.question.isNullOrBlank())
            f.items.forEach { i -> require(i.name.isNotBlank() && i.name.length <= 120 && i.unit.isNotBlank() && i.unit.length <= 30); require(i.quantity.isFinite() && i.quantity > 0 && i.quantity <= 100000); number(i.calories, 20000.0); listOf(i.protein, i.carbs, i.fat).forEach { number(it, 5000.0) } }
        } else {
            require(result.food == null); val a = requireNotNull(result.activity)
            text(a.transcript, 20000); text(a.type, 120); text(a.start, 100); text(a.intensity, 120); text(a.question, 1000)
            number(a.durationMinutes, 1440.0); require(a.durationMinutes == null || a.durationMinutes > 0)
            require(a.steps == null || a.steps in 0..200000); require(a.exercises.size <= 50 && a.assumptions.size <= 20)
            a.assumptions.forEach { text(it, 500) }; require(!a.needsClarification || !a.question.isNullOrBlank())
            a.exercises.forEach { require(it.name.isNotBlank() && it.name.length <= 120 && (it.sets == null || it.sets in 1..100) && (it.reps == null || it.reps in 1..10000)); number(it.loadKg, 1000.0) }
        }
    }
    fun warnings(food: FoodEstimate): List<String> = food.items.mapNotNull { i ->
        if (i.calories != null && i.protein != null && i.carbs != null && i.fat != null) {
            val macro = 4 * (i.protein + i.carbs) + 9 * i.fat
            if (kotlin.math.abs(macro - i.calories) > maxOf(50.0, i.calories * .25)) "${i.name}: calories and macros differ; review the estimate." else null
        } else null
    }
}
object DemoEstimates {
    fun food() = AiResult("FOOD", food = FoodEstimate(listOf(FoodItem("Sample rice bowl", 1.0, "bowl", 420.0, 14.0, 62.0, 13.0)), listOf("Sample only: portion and ingredients are invented for this demonstration."), .6, false, null, 1.0), model = "local-sample", sample = true)
    fun activity() = AiResult("ACTIVITY", activity = ActivityEstimate("Sample: I walked for twenty minutes.", "Walk", null, 20.0, null, null, emptyList(), listOf("Sample only. No steps were stated, so steps remain unknown."), false, null), model = "local-sample", sample = true)
}
object RetryDelays {
    const val MAX_SECONDS = 7 * 86400
    fun seconds(header:String?,nowMillis:Long,defaultSeconds:Int=60):Int? {
        val delay=header?.trim()?.toLongOrNull()?:header?.let { runCatching { ((java.time.ZonedDateTime.parse(it,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()-nowMillis+999)/1000).coerceAtLeast(0) }.getOrNull() }?:defaultSeconds.toLong()
        return if(delay>MAX_SECONDS) null else delay.coerceAtLeast(1).toInt()
    }
}
