package com.hbhaq.metricme.contracts

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class JournalRulesTest {
    private fun fact()=JournalFact("MEDICATION","Reported medicine","Taken as reported",Instant.now().minusSeconds(60).toString(),dose="5 mg")
    @Test fun medicationDoseCannotBeAttachedToAnotherCategory() { JournalRules.fact(fact()); assertThrows(IllegalArgumentException::class.java) { JournalRules.fact(fact().copy(category="FOOD")) } }
    @Test fun unknownIsSeparateFromZeroAndInvalidNumbersFail() {
        JournalRules.fact(fact().copy(category="ACTIVITY",dose=null,steps=0)); JournalRules.fact(fact().copy(category="ACTIVITY",dose=null,steps=null))
        assertThrows(IllegalArgumentException::class.java) { JournalRules.fact(fact().copy(quantity=Double.NaN)) }
    }
    @Test fun clarificationCannotAccidentallyJournalPartialFacts() { assertThrows(IllegalArgumentException::class.java) { JournalRules.extraction(JournalExtraction("LOG","input",listOf(fact()),"Which dose?",null,null)) } }
    @Test fun consentAndTransportBoundsAreEnforced() {
        val input=JournalInput(text="I slept seven hours",time=Instant.now().toString(),zone="Asia/Karachi",consented=true)
        JournalRules.input(input)
        assertThrows(IllegalArgumentException::class.java) { JournalRules.input(input.copy(consented=false)) }
        assertThrows(IllegalArgumentException::class.java) { JournalRules.input(input.copy(mediaBase64="A".repeat(4_000_001),mimeType="audio/wav")) }
    }
}
