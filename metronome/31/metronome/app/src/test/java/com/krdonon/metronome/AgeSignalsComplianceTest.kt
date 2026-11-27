package com.krdonon.metronome

import com.google.android.play.agesignals.AgeSignalsException
import com.google.android.play.agesignals.AgeSignalsRequest
import com.google.android.play.agesignals.ApplicableAgeFeaturesResult
import com.google.android.play.agesignals.AgeSignalsResult
import com.google.android.play.agesignals.model.AgeRangeSource
import com.google.android.play.agesignals.model.AgeSignalsErrorCode
import com.google.android.play.agesignals.model.AgeSignalsStatus
import com.google.android.play.agesignals.model.ApplicableAgeFeature
import com.google.android.play.agesignals.model.SignificantChangeStatus
import com.google.android.play.agesignals.testing.FakeAgeSignalsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class AgeSignalsComplianceTest {

    private val directExecutor = Executor { runnable -> runnable.run() }

    @Test
    fun classifyAgeRangeHandlesAdultMinorAndBoundaryCrossingBands() {
        assertEquals(
            AgeSignalsCompliance.AgeCategory.ADULT,
            AgeSignalsCompliance.classifyAgeRange(ageLower = 18, ageUpper = null),
        )
        assertEquals(
            AgeSignalsCompliance.AgeCategory.ADULT,
            AgeSignalsCompliance.classifyAgeRange(ageLower = 21, ageUpper = null),
        )
        assertEquals(
            AgeSignalsCompliance.AgeCategory.MINOR,
            AgeSignalsCompliance.classifyAgeRange(ageLower = 0, ageUpper = 12),
        )
        assertEquals(
            AgeSignalsCompliance.AgeCategory.MINOR,
            AgeSignalsCompliance.classifyAgeRange(ageLower = 13, ageUpper = 15),
        )
        assertEquals(
            AgeSignalsCompliance.AgeCategory.MINOR,
            AgeSignalsCompliance.classifyAgeRange(ageLower = 16, ageUpper = 17),
        )
        // Band crossing 18 or missing bounds must fail closed to UNKNOWN
        assertEquals(
            AgeSignalsCompliance.AgeCategory.UNKNOWN,
            AgeSignalsCompliance.classifyAgeRange(ageLower = 16, ageUpper = 20),
        )
        assertEquals(
            AgeSignalsCompliance.AgeCategory.UNKNOWN,
            AgeSignalsCompliance.classifyAgeRange(ageLower = null, ageUpper = null),
        )
        assertEquals(
            AgeSignalsCompliance.AgeCategory.UNKNOWN,
            AgeSignalsCompliance.classifyAgeRange(ageLower = 13, ageUpper = null),
        )
    }

    @Test
    fun transientRetryableErrorCodesAndBackoffMatchPolicy() {
        assertTrue(AgeSignalsCompliance.isTransientRetryableError(AgeSignalsErrorCode.NETWORK_ERROR))
        assertTrue(AgeSignalsCompliance.isTransientRetryableError(AgeSignalsErrorCode.CANNOT_BIND_TO_SERVICE))
        assertTrue(AgeSignalsCompliance.isTransientRetryableError(AgeSignalsErrorCode.CLIENT_TRANSIENT_ERROR))
        assertTrue(AgeSignalsCompliance.isTransientRetryableError(AgeSignalsErrorCode.INTERNAL_ERROR))

        assertFalse(AgeSignalsCompliance.isTransientRetryableError(AgeSignalsErrorCode.APP_NOT_OWNED))
        assertFalse(AgeSignalsCompliance.isTransientRetryableError(AgeSignalsErrorCode.SDK_VERSION_OUTDATED))
        assertFalse(AgeSignalsCompliance.isTransientRetryableError(AgeSignalsErrorCode.DEVICE_NOT_SUPPORTED))
        assertFalse(AgeSignalsCompliance.isTransientRetryableError(null))

        assertEquals(500L, AgeSignalsCompliance.retryDelayMs(0))
        assertEquals(1000L, AgeSignalsCompliance.retryDelayMs(1))
    }

    @Test
    fun checkMandatorySharingApplicableReads006ApplicableAgeFeatures() {
        val manager = FakeAgeSignalsManager()
        manager.setNextApplicableAgeFeaturesResult(
            ApplicableAgeFeaturesResult.builder()
                .setFeatures(setOf(ApplicableAgeFeature.MANDATORY_SHARING))
                .build(),
        )
        val compliance = AgeSignalsCompliance(
            ageSignalsManager = manager,
            callbackExecutor = directExecutor,
            retryScheduler = { _, action -> action() },
        )

        var result: Boolean? = null
        compliance.checkMandatorySharingApplicable { result = it }
        assertEquals(true, result)

        manager.setNextApplicableAgeFeaturesResult(
            ApplicableAgeFeaturesResult.builder()
                .setFeatures(emptySet())
                .build(),
        )
        compliance.checkMandatorySharingApplicable { result = it }
        assertEquals(false, result)

        manager.setNextApplicableAgeFeaturesException(
            AgeSignalsException(AgeSignalsErrorCode.DEVICE_NOT_SUPPORTED),
        )
        compliance.checkMandatorySharingApplicable { result = it }
        assertNull(result)
    }

    @Test
    fun fakeManagerCheckAgeSignalsMapsSupervisedMinorAndAdult() {
        val manager = FakeAgeSignalsManager()
        manager.setNextAgeSignalsResult(
            AgeSignalsResult.builder()
                .setAgeRangeSource(AgeRangeSource.TIER_B)
                .setAgeLower(13)
                .setAgeUpper(15)
                .setSignificantChangeStatus(SignificantChangeStatus.PENDING)
                .build(),
        )

        var capturedState: AgeSignalsCompliance.AgeSignalsState? = null
        manager.checkAgeSignals(AgeSignalsRequest.builder().build())
            .addOnSuccessListener(directExecutor) { res ->
                capturedState = AgeSignalsCompliance.AgeSignalsState(
                    ageCategory = AgeSignalsCompliance.classifyAgeRange(res.ageLower(), res.ageUpper()),
                    accessStatus = AgeSignalsStatus.SHARED,
                    ageRangeSource = res.ageRangeSource(),
                    significantChangeStatus = res.significantChangeStatus(),
                )
            }

        assertEquals(AgeSignalsCompliance.AgeCategory.MINOR, capturedState?.ageCategory)
        assertEquals(AgeRangeSource.TIER_B, capturedState?.ageRangeSource)
        assertTrue(capturedState?.isSignificantChangePending == true)
        assertFalse(capturedState?.isSignificantChangeDeclined == true)
    }
}
