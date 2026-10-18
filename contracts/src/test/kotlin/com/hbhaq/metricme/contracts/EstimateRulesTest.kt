package com.hbhaq.metricme.contracts

import org.junit.Test
import org.junit.Assert.*

class EstimateRulesTest {
    @Test fun sampleContractsRoundTripWithoutCredentials() { listOf(DemoEstimates.food(),DemoEstimates.activity()).forEach { EstimateRules.validate(it); assertEquals(it,WireJson.decodeFromString<AiResult>(WireJson.encodeToString(it))) } }
    @Test fun missingStepsAndNutritionStayUnknown() { val activity=DemoEstimates.activity().activity!!; assertNull(activity.steps); val result=DemoEstimates.food().let { it.copy(food=it.food!!.copy(items=it.food.items.map { row -> row.copy(calories=null,protein=null,carbs=null,fat=null) })) }; EstimateRules.validate(result); assertNull(result.food!!.items[0].calories) }
    @Test(expected=IllegalArgumentException::class) fun negativeNutritionRejected() { val r=DemoEstimates.food(); EstimateRules.validate(r.copy(food=r.food!!.copy(items=r.food.items.map { it.copy(calories=-1.0) }))) }
    @Test(expected=IllegalArgumentException::class) fun infiniteQuantityRejected() { val r=DemoEstimates.food(); EstimateRules.validate(r.copy(food=r.food!!.copy(items=r.food.items.map { it.copy(quantity=Double.POSITIVE_INFINITY) }))) }
    @Test(expected=IllegalArgumentException::class) fun emptyFoodCannotPretendToBeAnEstimate() { val r=DemoEstimates.food(); EstimateRules.validate(r.copy(food=r.food!!.copy(items=emptyList()))) }
    @Test fun clarificationCanHaveNoItemsAndMacroMismatchIsWarning() { val r=DemoEstimates.food(); EstimateRules.validate(r.copy(food=r.food!!.copy(items=emptyList(),needsClarification=true,question="What portion did you eat?"))); assertTrue(EstimateRules.warnings(r.food.copy(items=r.food.items.map { it.copy(calories=1000.0) })).isNotEmpty()) }
    @Test(expected=Exception::class) fun malformedOrUnknownOutputRejected() { WireJson.decodeFromString<FoodEstimate>("{\"items\":[],\"override_instructions\":true}") }
    @Test(expected=IllegalArgumentException::class) fun excessiveActivityStepsRejected() { val r=DemoEstimates.activity(); EstimateRules.validate(r.copy(activity=r.activity!!.copy(steps=200001))) }
}
