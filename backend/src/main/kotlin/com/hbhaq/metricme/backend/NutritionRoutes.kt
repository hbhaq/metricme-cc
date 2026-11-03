package com.hbhaq.metricme.backend

import com.hbhaq.metricme.contracts.*
import com.hbhaq.metricme.domain.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Semaphore
import kotlinx.io.readByteArray
import java.time.*
import kotlin.math.roundToInt

internal fun nutritionResult(request:NutritionRequest,a:NutritionAssessment):NutritionReply {
    WellnessRules.request(request); WellnessRules.assessment(a)
    if(a.excluded==true) return NutritionReply("UNAVAILABLE","A general calorie target is not appropriate for the circumstances you described. Use a clinician-agreed target instead.",a.continuation)
    a.question?.let { return NutritionReply("CLARIFY",it,a.continuation) }
    val missing=when { a.sex==null -> "For the calorie equation, should I use male or female physiology, or would you prefer no estimate?"; a.activity==null -> "What does a usual day of work and exercise look like?"; a.goal==null -> "Is your goal to maintain, lose or gain weight?"; a.excluded==null -> "Is there pregnancy, breastfeeding, an eating-disorder history or a clinician's nutrition plan that makes a general estimate inappropriate? You do not need to share details."; else -> null }
    if(missing!=null) return NutritionReply("CLARIFY",missing,a.continuation)
    val p=request.profile
    val weight=requireNotNull(p.weightKg); val height=requireNotNull(p.heightCm)
    if(a.goal=="LOSE" && weight/((height/100)*(height/100))<18.5) return NutritionReply("UNAVAILABLE","I cannot recommend a weight-loss calorie target at this height and weight. Please discuss an appropriate target with a clinician.",a.continuation)
    val today=LocalDate.now(ZoneOffset.UTC)
    val profile=Profile(birthDate=today.minusYears(requireNotNull(p.age).toLong()),equationInput=if(a.sex=="MALE") EquationInput.PLUS_5 else EquationInput.MINUS_161,activity=ActivityLevel.valueOf(requireNotNull(a.activity)),provenance=Provenance(createdAt=Instant.now()))
    val estimate=HealthRules.estimate(profile,weight,height,today)
    val maintenance=requireNotNull(estimate.tdee)
    val adjustment=when(a.goal) { "LOSE" -> -250; "GAIN" -> 250; else -> 0 }
    val target=((maintenance+adjustment)/50).roundToInt()*50
    val floor=if(a.sex=="MALE") 1500 else 1200
    if(target<floor || target<requireNotNull(estimate.bmr) || target>10000) return NutritionReply("UNAVAILABLE","These inputs need an individual nutrition assessment before choosing a daily calorie target.",a.continuation)
    val goal=when(a.goal) { "LOSE" -> "a modest weight-loss starting target"; "GAIN" -> "a modest weight-gain starting target"; else -> "a maintenance starting target" }
    return NutritionReply("READY","AI-guided estimate: "+goal+" using Mifflin–St Jeor and your confirmed usual activity. It is a starting estimate, not a hard limit; review it against your actual intake and weight trend.",a.continuation,target,(maintenance/50).roundToInt()*50)
}

fun Route.nutritionRoutes(gateway:AiGateway?,slots:Semaphore) {
    post("/v1/nutrition/target") {
        val ai=gateway?:throw ApiFailure("not_configured",503)
        if(!slots.tryAcquire()) throw ApiFailure("busy",429,true)
        try {
            val bytes=withTimeout(20000) { call.receiveChannel().readRemaining(25001).readByteArray() }
            if(bytes.size>25000) throw ApiFailure("upload_too_large",413)
            val request=try { WireJson.decodeFromString<NutritionRequest>(String(bytes,Charsets.UTF_8)).also(WellnessRules::request) } catch(_:Exception) { throw ApiFailure("invalid_profile",400) }
            call.respond(nutritionResult(request,ai.nutrition(request)))
        } finally { slots.release() }
    }
}
