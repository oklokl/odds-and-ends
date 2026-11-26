package com.krdondon.txt.compliance

import android.app.Activity
import com.google.android.play.agesignals.AgeSignalsAccessResult
import com.google.android.play.agesignals.AgeSignalsException
import com.google.android.play.agesignals.AgeSignalsResult
import com.google.android.play.agesignals.ApplicableAgeFeaturesResult
import com.google.android.play.agesignals.model.AgeRangeSource
import com.google.android.play.agesignals.model.AgeSignalsErrorCode
import com.google.android.play.agesignals.model.AgeSignalsStatus
import com.google.android.play.agesignals.model.ApplicableAgeFeature
import com.google.android.play.agesignals.model.SignificantChangeStatus
import com.google.android.play.agesignals.testing.FakeAgeSignalsManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import java.util.concurrent.Executor

class AgeSignalsComplianceTest {

    private val directExecutor = Executor { runnable -> runnable.run() }

    private fun createComplianceWithFake(
        fakeManager: FakeAgeSignalsManager,
        retryDelaysMs: List<Long> = listOf(0L, 0L)
    ): AgeSignalsCompliance {
        val client = PlayAgeSignalsClient(fakeManager, directExecutor)
        return AgeSignalsCompliance(
            client = client,
            isActivityUsable = { true },
            retryDelaysMs = retryDelaysMs
        )
    }

    @Test
    fun verifiedAdult_returnsAdultAndPopulatesAllFields() = runBlocking {
        val fakeManager = FakeAgeSignalsManager().apply {
            setNextApplicableAgeFeaturesResult(
                ApplicableAgeFeaturesResult.builder()
                    .setFeatures(setOf(ApplicableAgeFeature.MANDATORY_SHARING))
                    .build()
            )
            setNextAgeSignalsResult(
                AgeSignalsResult.builder()
                    .setAgeRangeSource(AgeRangeSource.TIER_D)
                    .setAgeLower(18)
                    .build()
            )
        }
        val compliance = createComplianceWithFake(fakeManager)

        val state = compliance.refreshInternal(
            isUsable = { true },
            requestAccess = {
                AgeSignalsAccessResult.builder()
                    .setAgeSignalsStatus(AgeSignalsStatus.SHARED)
                    .build()
            }
        )

        assertEquals(AgeSignalsCheckStatus.SHARED, state.checkStatus)
        assertEquals(AgeClassification.ADULT, state.classification)
        assertEquals(18, state.ageLower)
        assertNull(state.ageUpper)
        assertEquals(AgeRangeSource.TIER_D, state.ageRangeSource)
        assertEquals(true, state.isMandatorySharingApplicable)
        assertNull(state.errorCode)
        assertFalse(compliance.shouldRecheckOnResume())
    }

    @Test
    fun supervisedMinor_withApprovedSignificantChange_returnsMinorAndMetadata() = runBlocking {
        val approvalDate = Date(1777939200000L)
        val fakeManager = FakeAgeSignalsManager().apply {
            setNextApplicableAgeFeaturesResult(
                ApplicableAgeFeaturesResult.builder()
                    .setFeatures(emptySet())
                    .build()
            )
            setNextAgeSignalsResult(
                AgeSignalsResult.builder()
                    .setAgeRangeSource(AgeRangeSource.TIER_B)
                    .setAgeLower(13)
                    .setAgeUpper(15)
                    .setSignificantChangeStatus(SignificantChangeStatus.APPROVED)
                    .setSignificantChangeApprovalDate(approvalDate)
                    .setInstallId("fake_install_id_123")
                    .build()
            )
        }
        val compliance = createComplianceWithFake(fakeManager)

        val state = compliance.refreshInternal(
            isUsable = { true },
            requestAccess = {
                AgeSignalsAccessResult.builder()
                    .setAgeSignalsStatus(AgeSignalsStatus.SHARED)
                    .build()
            }
        )

        assertEquals(AgeSignalsCheckStatus.SHARED, state.checkStatus)
        assertEquals(AgeClassification.MINOR, state.classification)
        assertEquals(13, state.ageLower)
        assertEquals(15, state.ageUpper)
        assertEquals(AgeRangeSource.TIER_B, state.ageRangeSource)
        assertEquals(SignificantChangeStatus.APPROVED, state.significantChangeStatus)
        assertEquals(approvalDate, state.significantChangeApprovalDate)
        assertEquals("fake_install_id_123", state.installId)
        assertEquals(false, state.isMandatorySharingApplicable)
    }

    @Test
    fun notSharedStatus_skipsCheckAgeSignalsAndReturnsUnknown() = runBlocking {
        val fakeManager = FakeAgeSignalsManager().apply {
            setNextApplicableAgeFeaturesResult(
                ApplicableAgeFeaturesResult.builder().setFeatures(emptySet()).build()
            )
            // If checkAgeSignals were called, this would throw.
            setNextAgeSignalsException(AgeSignalsException(AgeSignalsErrorCode.INTERNAL_ERROR))
        }
        val compliance = createComplianceWithFake(fakeManager)

        val state = compliance.refreshInternal(
            isUsable = { true },
            requestAccess = {
                AgeSignalsAccessResult.builder()
                    .setAgeSignalsStatus(AgeSignalsStatus.NOT_SHARED)
                    .build()
            }
        )

        assertEquals(AgeSignalsCheckStatus.NOT_SHARED, state.checkStatus)
        assertEquals(AgeClassification.UNKNOWN, state.classification)
        assertFalse(compliance.shouldRecheckOnResume())
    }

    @Test
    fun verificationRequiredStatus_marksVerificationRequiredAndAllowsResumeRecheck() = runBlocking {
        val fakeManager = FakeAgeSignalsManager().apply {
            setNextApplicableAgeFeaturesResult(
                ApplicableAgeFeaturesResult.builder()
                    .setFeatures(setOf(ApplicableAgeFeature.MANDATORY_SHARING))
                    .build()
            )
        }
        val compliance = createComplianceWithFake(fakeManager)

        val state = compliance.refreshInternal(
            isUsable = { true },
            requestAccess = {
                AgeSignalsAccessResult.builder()
                    .setAgeSignalsStatus(AgeSignalsStatus.VERIFICATION_REQUIRED)
                    .build()
            }
        )

        assertEquals(AgeSignalsCheckStatus.VERIFICATION_REQUIRED, state.checkStatus)
        assertEquals(AgeClassification.UNKNOWN, state.classification)
        assertEquals(true, state.isMandatorySharingApplicable)
        assertTrue(compliance.shouldRecheckOnResume())
    }

    @Test
    fun transientError_retriesAndSucceedsOnSecondAttempt() = runBlocking {
        var accessAttempts = 0
        val fakeClient = object : AgeSignalsClient {
            override suspend fun getApplicableAgeFeatures(): ApplicableAgeFeaturesResult =
                ApplicableAgeFeaturesResult.builder().setFeatures(emptySet()).build()

            override suspend fun requestAgeSignalsAccess(activity: Activity): AgeSignalsAccessResult =
                error("Unused in refreshInternal")

            override suspend fun checkAgeSignals(): AgeSignalsResult =
                AgeSignalsResult.builder()
                    .setAgeRangeSource(AgeRangeSource.TIER_A)
                    .setAgeLower(18)
                    .build()
        }
        val compliance = AgeSignalsCompliance(
            client = fakeClient,
            retryDelaysMs = listOf(0L, 0L)
        )

        val state = compliance.refreshInternal(
            isUsable = { true },
            requestAccess = {
                accessAttempts++
                if (accessAttempts == 1) {
                    throw AgeSignalsException(AgeSignalsErrorCode.NETWORK_ERROR)
                }
                AgeSignalsAccessResult.builder()
                    .setAgeSignalsStatus(AgeSignalsStatus.SHARED)
                    .build()
            }
        )

        assertEquals(2, accessAttempts)
        assertEquals(AgeSignalsCheckStatus.SHARED, state.checkStatus)
        assertEquals(AgeClassification.ADULT, state.classification)
        assertNull(state.errorCode)
    }

    @Test
    fun nonRetryableError_deviceNotSupported_failsImmediatelyWithoutRetry() = runBlocking {
        var accessAttempts = 0
        val fakeManager = FakeAgeSignalsManager().apply {
            setNextApplicableAgeFeaturesException(
                AgeSignalsException(AgeSignalsErrorCode.DEVICE_NOT_SUPPORTED)
            )
        }
        val compliance = createComplianceWithFake(fakeManager)

        val state = compliance.refreshInternal(
            isUsable = { true },
            requestAccess = {
                accessAttempts++
                throw AgeSignalsException(AgeSignalsErrorCode.DEVICE_NOT_SUPPORTED)
            }
        )

        assertEquals(1, accessAttempts)
        assertEquals(AgeSignalsCheckStatus.ERROR, state.checkStatus)
        assertEquals(AgeClassification.UNKNOWN, state.classification)
        assertEquals(AgeSignalsErrorCode.DEVICE_NOT_SUPPORTED, state.errorCode)
        assertNull(state.isMandatorySharingApplicable)
        assertFalse(compliance.shouldRecheckOnResume())
    }

    @Test
    fun customAgeRangeSpanningAdultBoundary_returnsUnknown() {
        assertEquals(AgeClassification.UNKNOWN, AgeSignalsCompliance.classifyAgeRange(15, null))
        assertEquals(AgeClassification.UNKNOWN, AgeSignalsCompliance.classifyAgeRange(17, null))
        assertEquals(AgeClassification.UNKNOWN, AgeSignalsCompliance.classifyAgeRange(null, null))
        assertEquals(AgeClassification.MINOR, AgeSignalsCompliance.classifyAgeRange(0, 12))
        assertEquals(AgeClassification.MINOR, AgeSignalsCompliance.classifyAgeRange(16, 17))
        assertEquals(AgeClassification.ADULT, AgeSignalsCompliance.classifyAgeRange(18, null))
    }

    @Test
    fun applicableAgeFeaturesFailure_doesNotBreakMainFlow() = runBlocking {
        val fakeManager = FakeAgeSignalsManager().apply {
            setNextApplicableAgeFeaturesException(
                AgeSignalsException(AgeSignalsErrorCode.API_NOT_AVAILABLE)
            )
            setNextAgeSignalsResult(
                AgeSignalsResult.builder()
                    .setAgeRangeSource(AgeRangeSource.TIER_C)
                    .setAgeLower(18)
                    .build()
            )
        }
        val compliance = createComplianceWithFake(fakeManager)

        val state = compliance.refreshInternal(
            isUsable = { true },
            requestAccess = {
                AgeSignalsAccessResult.builder()
                    .setAgeSignalsStatus(AgeSignalsStatus.SHARED)
                    .build()
            }
        )

        assertEquals(AgeSignalsCheckStatus.SHARED, state.checkStatus)
        assertEquals(AgeClassification.ADULT, state.classification)
        assertNull(state.isMandatorySharingApplicable)
    }
}
