package com.krdonon.ratio

import com.google.android.play.agesignals.AgeSignalsAccessResult
import com.google.android.play.agesignals.AgeSignalsException
import com.google.android.play.agesignals.AgeSignalsRequest
import com.google.android.play.agesignals.AgeSignalsResult
import com.google.android.play.agesignals.model.AgeRangeSource
import com.google.android.play.agesignals.model.AgeSignalsErrorCode
import com.google.android.play.agesignals.model.AgeSignalsStatus
import com.google.android.play.agesignals.model.SignificantChangeStatus
import com.google.android.play.agesignals.testing.FakeAgeSignalsManager
import com.krdonon.ratio.AgeSignalsCompliance.awaitResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class AgeSignalsComplianceTest {

    @Test
    fun sharedVerifiedAdult_returnsAdultAndMetadata() = runBlocking {
        val manager = FakeAgeSignalsManager().apply {
            setNextAgeSignalsResult(
                AgeSignalsResult.builder()
                    .setAgeRangeSource(AgeRangeSource.TIER_D)
                    .setAgeLower(18)
                    .build(),
            )
        }

        val state = AgeSignalsCompliance.executeAgeSignalsFlow(
            requestAccess = {
                AgeSignalsAccessResult.builder()
                    .setAgeSignalsStatus(AgeSignalsStatus.SHARED)
                    .build()
            },
            fetchSignals = {
                manager.checkAgeSignals(AgeSignalsRequest.builder().build()).awaitResult()
            },
            delayFn = {},
        )

        assertEquals(AgeAccessStatus.SHARED, state.accessStatus)
        assertEquals(UserAgeCategory.ADULT, state.ageCategory)
        assertEquals(AgeRangeSource.TIER_D, state.ageRangeSource)
        assertEquals(18, state.ageLower)
        assertNull(state.ageUpper)
        assertNull(state.errorCode)
    }

    @Test
    fun sharedSupervisedMinorWithPendingSignificantChange_returnsMinorAndFlags() = runBlocking {
        val approvalDate = Date(1777939200000L)
        val manager = FakeAgeSignalsManager().apply {
            setNextAgeSignalsResult(
                AgeSignalsResult.builder()
                    .setAgeRangeSource(AgeRangeSource.TIER_B)
                    .setAgeLower(13)
                    .setAgeUpper(15)
                    .setSignificantChangeStatus(SignificantChangeStatus.PENDING)
                    .setSignificantChangeApprovalDate(approvalDate)
                    .setInstallId("fake_install_id_123")
                    .build(),
            )
        }

        val state = AgeSignalsCompliance.executeAgeSignalsFlow(
            requestAccess = {
                AgeSignalsAccessResult.builder()
                    .setAgeSignalsStatus(AgeSignalsStatus.SHARED)
                    .build()
            },
            fetchSignals = {
                manager.checkAgeSignals(AgeSignalsRequest.builder().build()).awaitResult()
            },
            delayFn = {},
        )

        assertEquals(AgeAccessStatus.SHARED, state.accessStatus)
        assertEquals(UserAgeCategory.MINOR, state.ageCategory)
        assertEquals(AgeRangeSource.TIER_B, state.ageRangeSource)
        assertEquals(13, state.ageLower)
        assertEquals(15, state.ageUpper)
        assertEquals("fake_install_id_123", state.installId)
        assertEquals(approvalDate, state.significantChangeApprovalDate)
        assertTrue(state.isSignificantChangePending)
        assertFalse(state.isSignificantChangeDeclined)
    }

    @Test
    fun sharedDeclinedSignificantChange_setsDeclinedFlag() = runBlocking {
        val manager = FakeAgeSignalsManager().apply {
            setNextAgeSignalsResult(
                AgeSignalsResult.builder()
                    .setAgeRangeSource(AgeRangeSource.TIER_B)
                    .setAgeLower(13)
                    .setAgeUpper(17)
                    .setSignificantChangeStatus(SignificantChangeStatus.DECLINED)
                    .build(),
            )
        }

        val state = AgeSignalsCompliance.executeAgeSignalsFlow(
            requestAccess = {
                AgeSignalsAccessResult.builder()
                    .setAgeSignalsStatus(AgeSignalsStatus.SHARED)
                    .build()
            },
            fetchSignals = {
                manager.checkAgeSignals(AgeSignalsRequest.builder().build()).awaitResult()
            },
            delayFn = {},
        )

        assertEquals(UserAgeCategory.MINOR, state.ageCategory)
        assertTrue(state.isSignificantChangeDeclined)
        assertFalse(state.isSignificantChangePending)
    }

    @Test
    fun notSharedAndVerificationRequired_skipCheckAgeSignalsAndSetDistinctStatus() = runBlocking {
        var checkSignalsCalls = 0

        val notShared = AgeSignalsCompliance.executeAgeSignalsFlow(
            requestAccess = {
                AgeSignalsAccessResult.builder()
                    .setAgeSignalsStatus(AgeSignalsStatus.NOT_SHARED)
                    .build()
            },
            fetchSignals = {
                checkSignalsCalls++
                error("Should not call checkAgeSignals")
            },
            delayFn = {},
        )

        assertEquals(AgeAccessStatus.NOT_SHARED, notShared.accessStatus)
        assertEquals(UserAgeCategory.UNKNOWN, notShared.ageCategory)
        assertFalse(notShared.isVerificationRequired)
        assertEquals(0, checkSignalsCalls)

        val verificationRequired = AgeSignalsCompliance.executeAgeSignalsFlow(
            requestAccess = {
                AgeSignalsAccessResult.builder()
                    .setAgeSignalsStatus(AgeSignalsStatus.VERIFICATION_REQUIRED)
                    .build()
            },
            fetchSignals = {
                checkSignalsCalls++
                error("Should not call checkAgeSignals")
            },
            delayFn = {},
        )

        assertEquals(AgeAccessStatus.VERIFICATION_REQUIRED, verificationRequired.accessStatus)
        assertEquals(UserAgeCategory.UNKNOWN, verificationRequired.ageCategory)
        assertTrue(verificationRequired.isVerificationRequired)
        assertEquals(0, checkSignalsCalls)
    }

    @Test
    fun transientError_retriesWithExponentialBackoffAndRecovers() = runBlocking {
        val backoffDelays = mutableListOf<Long>()
        var accessAttempts = 0

        val manager = FakeAgeSignalsManager().apply {
            setNextAgeSignalsResult(
                AgeSignalsResult.builder()
                    .setAgeRangeSource(AgeRangeSource.TIER_A)
                    .setAgeLower(18)
                    .build(),
            )
        }

        val state = AgeSignalsCompliance.executeAgeSignalsFlow(
            requestAccess = {
                accessAttempts++
                if (accessAttempts < 3) {
                    throw AgeSignalsException(AgeSignalsErrorCode.CLIENT_TRANSIENT_ERROR)
                }
                AgeSignalsAccessResult.builder()
                    .setAgeSignalsStatus(AgeSignalsStatus.SHARED)
                    .build()
            },
            fetchSignals = {
                manager.checkAgeSignals(AgeSignalsRequest.builder().build()).awaitResult()
            },
            delayFn = { backoffDelays += it },
        )

        assertEquals(3, accessAttempts)
        assertEquals(listOf(500L, 1500L), backoffDelays)
        assertEquals(AgeAccessStatus.SHARED, state.accessStatus)
        assertEquals(UserAgeCategory.ADULT, state.ageCategory)
    }

    @Test
    fun nonRetryableError_failsImmediatelyWithoutRetry() = runBlocking {
        val backoffDelays = mutableListOf<Long>()
        var signalAttempts = 0

        val manager = FakeAgeSignalsManager().apply {
            setNextAgeSignalsException(
                AgeSignalsException(AgeSignalsErrorCode.DEVICE_NOT_SUPPORTED),
            )
        }

        val state = AgeSignalsCompliance.executeAgeSignalsFlow(
            requestAccess = {
                AgeSignalsAccessResult.builder()
                    .setAgeSignalsStatus(AgeSignalsStatus.SHARED)
                    .build()
            },
            fetchSignals = {
                signalAttempts++
                manager.checkAgeSignals(AgeSignalsRequest.builder().build()).awaitResult()
            },
            delayFn = { backoffDelays += it },
        )

        assertEquals(1, signalAttempts)
        assertTrue(backoffDelays.isEmpty())
        assertEquals(AgeAccessStatus.ERROR, state.accessStatus)
        assertEquals(UserAgeCategory.UNKNOWN, state.ageCategory)
        assertEquals(AgeSignalsErrorCode.DEVICE_NOT_SUPPORTED, state.errorCode)
    }

    @Test
    fun errorClassification_matchesPlayAgeSignalsSpecification() {
        assertTrue(AgeSignalsCompliance.isRetryableErrorCode(AgeSignalsErrorCode.CANNOT_BIND_TO_SERVICE))
        assertTrue(AgeSignalsCompliance.isRetryableErrorCode(AgeSignalsErrorCode.CLIENT_TRANSIENT_ERROR))
        assertTrue(AgeSignalsCompliance.isRetryableErrorCode(AgeSignalsErrorCode.PLAY_STORE_NOT_FOUND))

        assertTrue(AgeSignalsCompliance.isTransientAutoRetryErrorCode(AgeSignalsErrorCode.CANNOT_BIND_TO_SERVICE))
        assertTrue(AgeSignalsCompliance.isTransientAutoRetryErrorCode(AgeSignalsErrorCode.CLIENT_TRANSIENT_ERROR))
        assertFalse(AgeSignalsCompliance.isTransientAutoRetryErrorCode(AgeSignalsErrorCode.PLAY_STORE_NOT_FOUND))

        assertFalse(AgeSignalsCompliance.isRetryableErrorCode(AgeSignalsErrorCode.APP_NOT_OWNED))
        assertFalse(AgeSignalsCompliance.isRetryableErrorCode(AgeSignalsErrorCode.SDK_VERSION_OUTDATED))
        assertFalse(AgeSignalsCompliance.isRetryableErrorCode(AgeSignalsErrorCode.DEVICE_NOT_SUPPORTED))
        assertFalse(AgeSignalsCompliance.isRetryableErrorCode(AgeSignalsErrorCode.INTERNAL_ERROR))
    }
}
