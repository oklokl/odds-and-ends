package com.krdondon.week

import org.junit.Assert.assertEquals
import org.junit.Test

class AgeSignalsComplianceTest {

    @Test
    fun `minor range returns MINOR`() {
        assertEquals(
            AgeSignalsCompliance.ProtectionState.MINOR,
            AgeSignalsCompliance.classifyAgeRange(ageLower = 13, ageUpper = 15),
        )
        assertEquals(
            AgeSignalsCompliance.ProtectionState.MINOR,
            AgeSignalsCompliance.classifyAgeRange(ageLower = null, ageUpper = 17),
        )
    }

    @Test
    fun `adult range returns ADULT`() {
        assertEquals(
            AgeSignalsCompliance.ProtectionState.ADULT,
            AgeSignalsCompliance.classifyAgeRange(ageLower = 18, ageUpper = null),
        )
        assertEquals(
            AgeSignalsCompliance.ProtectionState.ADULT,
            AgeSignalsCompliance.classifyAgeRange(ageLower = 21, ageUpper = 30),
        )
    }

    @Test
    fun `ambiguous or null bounds return UNKNOWN`() {
        assertEquals(
            AgeSignalsCompliance.ProtectionState.UNKNOWN,
            AgeSignalsCompliance.classifyAgeRange(ageLower = 17, ageUpper = null),
        )
        assertEquals(
            AgeSignalsCompliance.ProtectionState.UNKNOWN,
            AgeSignalsCompliance.classifyAgeRange(ageLower = null, ageUpper = null),
        )
    }
}
