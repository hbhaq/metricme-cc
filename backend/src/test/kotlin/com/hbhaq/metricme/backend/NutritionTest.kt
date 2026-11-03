package com.hbhaq.metricme.backend

import com.hbhaq.metricme.contracts.*
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Test
import org.junit.Assert.*
import io.ktor.server.testing.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*

class NutritionTest {
    private val input=NutritionRequest(WellnessProfile(30,"Man",175.0,70.0),"Recommend a starting target",consented=true)
    private val complete=NutritionAssessment("MALE","MODERATE","MAINTAIN",false,continuation="All interview answers confirmed")
    @Test fun confirmedInputsProduceReproducibleMaintenanceAndGoalTargets() {
        assertEquals(2550,nutritionResult(input,complete).calories)
        assertEquals(2300,nutritionResult(input,complete.copy(goal="LOSE")).calories)
        assertEquals(2800,nutritionResult(input,complete.copy(goal="GAIN")).calories)
        assertEquals("READY",nutritionResult(input,complete).status)
    }
    @Test fun genderIdentityDoesNotChoosePhysiologyAndMissingFactsKeepAsking() {
        assertEquals("CLARIFY",nutritionResult(input,complete.copy(sex=null)).status)
        assertEquals("CLARIFY",nutritionResult(input,complete.copy(activity=null)).status)
        assertEquals("CLARIFY",nutritionResult(input,complete.copy(goal=null)).status)
        assertEquals("CLARIFY",nutritionResult(input,complete.copy(excluded=null)).status)
        assertEquals(nutritionResult(input,complete).calories,nutritionResult(input.copy(profile=input.profile.copy(gender="Different identity")),complete).calories)
    }
    @Test fun specialCircumstancesAndUnderweightLossDoNotProduceTargets() {
        assertNull(nutritionResult(input,complete.copy(excluded=true)).calories)
        assertEquals("UNAVAILABLE",nutritionResult(input.copy(profile=input.profile.copy(weightKg=50.0)),complete.copy(goal="LOSE")).status)
    }
    @Test fun explicitQuestionTakesPriorityOverIncompleteRecommendation() {
        val result=nutritionResult(input,complete.copy(question="How active are your work days?"))
        assertEquals("CLARIFY",result.status); assertEquals("How active are your work days?",result.message); assertNull(result.calories)
    }
    @Test fun nutritionRouteRequiresAuthenticationConsentAndValidProfile()=testApplication {
        var calls=0
        application { metricMe(ServerConfig(null,null,null,null,null),null,object:AiGateway {
            override suspend fun estimate(kind:String,request:EstimateRequest,media:ByteArray):AiResult=error("unused")
            override suspend fun nutrition(request:NutritionRequest):NutritionAssessment { calls++; return if(request.continuation.isEmpty()) complete.copy(question="What is your goal?",continuation="Male physiology; moderate activity; goal pending") else complete }
        },verify={ if(it=="alice") "owner" else null }) }
        suspend fun send(r:NutritionRequest,auth:Boolean=true)=client.post("/v1/nutrition/target") { if(auth) bearerAuth("alice"); contentType(ContentType.Application.Json); setBody(WireJson.encodeToString(r)) }
        assertEquals(HttpStatusCode.Unauthorized,send(input,false).status)
        assertEquals(HttpStatusCode.BadRequest,send(input.copy(consented=false)).status)
        val first=WireJson.decodeFromString<NutritionReply>(send(input).bodyAsText())
        assertEquals("CLARIFY",first.status)
        val next=WireJson.decodeFromString<NutritionReply>(send(input.copy(text="Maintain",continuation=first.continuation)).bodyAsText())
        assertEquals("READY",next.status); assertEquals(2,calls)
    }
    @Test fun oldClientsDoNotReceiveNewGuidanceFields() {
        val json=WireJson.encodeToJsonElement(JournalReply("id","CLARIFY",message="Question?",continuation="Known facts"))
        assertFalse(compatibleJournalJson(json,false).toString().contains("continuation"))
        assertFalse(legacyJournalJson(json).toString().contains("continuation"))
    }
}
