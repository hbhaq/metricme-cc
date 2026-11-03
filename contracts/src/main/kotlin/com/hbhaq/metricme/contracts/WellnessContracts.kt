package com.hbhaq.metricme.contracts

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate

@Serializable data class WellnessProfile(val age:Int?=null,val gender:String="",val heightCm:Double?=null,val weightKg:Double?=null,val dailyCalorieTarget:Int?=null,val targetEffectiveFrom:String?=null)
@Serializable data class NutritionRequest(val profile:WellnessProfile,val text:String,val continuation:String="",val consented:Boolean=false)
@Serializable data class NutritionAssessment(val sex:String?=null,val activity:String?=null,val goal:String?=null,val excluded:Boolean?=null,val question:String?=null,val continuation:String="",val explanation:String="")
@Serializable data class NutritionReply(val status:String,val message:String,val continuation:String="",val calories:Int?=null,val maintenanceCalories:Int?=null)
@Serializable data class InsightInterpretation(val message:String,val clarification:String?=null,val continuation:String="")

object WellnessRules {
    fun profile(p:WellnessProfile) {
        require(p.age==null || p.age in 18..120)
        require(p.gender.length<=80)
        require(p.heightCm==null || p.heightCm.isFinite() && p.heightCm in 80.0..250.0)
        require(p.weightKg==null || p.weightKg.isFinite() && p.weightKg in 20.0..500.0)
        require(p.dailyCalorieTarget==null || p.dailyCalorieTarget in 1200..10000)
        p.targetEffectiveFrom?.let { require(LocalDate.parse(it)<=LocalDate.now(java.time.ZoneOffset.UTC).plusDays(1)) }
        require((p.dailyCalorieTarget==null)==(p.targetEffectiveFrom==null))
    }
    fun request(r:NutritionRequest) {
        profile(r.profile); require(r.consented)
        require(r.text.isNotBlank() && r.text.length<=4000 && r.continuation.length<=8000)
        require(r.profile.age!=null && r.profile.heightCm!=null && r.profile.weightKg!=null)
    }
    fun assessment(a:NutritionAssessment) {
        require(a.sex in listOf(null,"MALE","FEMALE"))
        require(a.activity in listOf(null,"SEDENTARY","LIGHT","MODERATE","HIGH","VERY_HIGH"))
        require(a.goal in listOf(null,"MAINTAIN","LOSE","GAIN"))
        require(a.question==null || a.question.isNotBlank() && a.question.length<=1000)
        require(a.continuation.length<=8000 && a.explanation.length<=1500)
    }
}

object JournalQuality {
    fun question(e:JournalExtraction):String? {
        if(e.action!="LOG" || e.clarification!=null) return null
        for(f in e.entries) when(f.category) {
            "FOOD" -> if(f.calories==null || f.quantity==null || f.quantity<=0 || f.unit.isNullOrBlank()) return "For "+f.title+", how much did you actually eat or drink? Please give a weight, volume, count or portion size, and the brand or preparation details you know. I need enough detail to estimate calories."
            "ACTIVITY" -> if(f.durationMinutes==null && f.steps==null) return "For "+f.title+", how long did you do it, or how many steps did you record? What was the intensity, if relevant?"
            "SLEEP" -> if(f.durationMinutes==null) return "For this sleep entry, how long were you actually asleep, and when did you wake up?"
            "MEDICATION" -> if(f.dose.isNullOrBlank()) return "For "+f.title+", what dose and unit did you actually take, and when?"
            "WATER","MEASUREMENT" -> if(f.quantity==null || f.unit.isNullOrBlank()) return "For "+f.title+", what was the amount and unit?"
        }
        return null
    }
    fun continuation(previous:String,e:JournalExtraction,question:String):String = e.continuation.takeIf(String::isNotBlank) ?: WireJson.encodeToString(e.copy(clarification=null,continuation="")).let { facts -> ("Previous task: "+previous.takeLast(2500)+"\nKnown facts: "+facts.take(4300)+"\nPending question: "+question).take(8000) }
}
