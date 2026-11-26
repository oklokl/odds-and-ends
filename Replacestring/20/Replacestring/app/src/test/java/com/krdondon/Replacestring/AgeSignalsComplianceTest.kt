package com.krdondon.Replacestring

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.play.agesignals.AgeSignalsAccessRequest
import com.google.android.play.agesignals.AgeSignalsAccessResult
import com.google.android.play.agesignals.AgeSignalsException
import com.google.android.play.agesignals.AgeSignalsManager
import com.google.android.play.agesignals.AgeSignalsRequest
import com.google.android.play.agesignals.AgeSignalsResult
import com.google.android.play.agesignals.ApplicableAgeFeaturesRequest
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

    private val directExecutor = Executor(Runnable::run)

    @Test
    fun sharedAccess_verifiedAdult_returnsAdultAndPopulatesFields() = runBlocking {
        val fakeManager = FakeAgeSignalsManager().apply {
            setNextAgeSignalsResult(
                AgeSignalsResult.builder()
                    .setAgeRangeSource(AgeRangeSource.TIER_D)
                    .setAgeLower(18)
                    .build()
            )
        }
        val compliance = AgeSignalsCompliance(
            ageSignalsManager = fakeManager,
            callbackExecutor = directExecutor
        )

        val accessResult = AgeSignalsAccessResult.builder()
            .setAgeSignalsStatus(AgeSignalsStatus.SHARED)
            .build()

        val state = compliance.handleAccessResult(accessResult)

        assertEquals(AgeClassification.ADULT, state.classification)
        assertEquals(AgeAccessStatus.SHARED, state.accessStatus)
        assertEquals(18, state.ageLower)
        assertNull(state.ageUpper)
        assertEquals(AgeRangeSource.TIER_D, state.ageRangeSource)
        assertNull(state.lastErrorCode)
        assertFalse(state.isLoading)
    }

    @Test
    fun sharedAccess_supervisedMinorWithSignificantChange_returnsMinorAndPreservesMetadata() = runBlocking {
        val approvalDate = Date(1777939200000L)
        val fakeManager = FakeAgeSignalsManager().apply {
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
        val compliance = AgeSignalsCompliance(
            ageSignalsManager = fakeManager,
            callbackExecutor = directExecutor
        )

        val accessResult = AgeSignalsAccessResult.builder()
            .setAgeSignalsStatus(AgeSignalsStatus.SHARED)
            .build()

        val state = compliance.handleAccessResult(accessResult)

        assertEquals(AgeClassification.MINOR, state.classification)
        assertEquals(AgeAccessStatus.SHARED, state.accessStatus)
        assertEquals(13, state.ageLower)
        assertEquals(15, state.ageUpper)
        assertEquals(AgeRangeSource.TIER_B, state.ageRangeSource)
        assertEquals(SignificantChangeStatus.APPROVED, state.significantChangeStatus)
        assertEquals(approvalDate, state.significantChangeApprovalDate)
        assertEquals("fake_install_id_123", state.installId)
    }

    @Test
    fun notSharedAccess_returnsUnknownWithoutCheckingSignals() = runBlocking {
        val fakeManager = FakeAgeSignalsManager().apply {
            // If checkAgeSignals were called, this adult result would leak through.
            setNextAgeSignalsResult(
                AgeSignalsResult.builder()
                    .setAgeRangeSource(AgeRangeSource.TIER_A)
                    .setAgeLower(18)
                    .build()
            )
        }
        val compliance = AgeSignalsCompliance(
            ageSignalsManager = fakeManager,
            callbackExecutor = directExecutor
        )

        val accessResult = AgeSignalsAccessResult.builder()
            .setAgeSignalsStatus(AgeSignalsStatus.NOT_SHARED)
            .build()

        val state = compliance.handleAccessResult(accessResult)

        assertEquals(AgeClassification.UNKNOWN, state.classification)
        assertEquals(AgeAccessStatus.NOT_SHARED, state.accessStatus)
        assertNull(state.ageLower)
    }

    @Test
    fun verificationRequired_returnsUnknownWithVerificationRequiredStatus() = runBlocking {
        val fakeManager = FakeAgeSignalsManager()
        val compliance = AgeSignalsCompliance(
            ageSignalsManager = fakeManager,
            callbackExecutor = directExecutor
        )

        val accessResult = AgeSignalsAccessResult.builder()
            .setAgeSignalsStatus(AgeSignalsStatus.VERIFICATION_REQUIRED)
            .build()

        val state = compliance.handleAccessResult(accessResult)

        assertEquals(AgeClassification.UNKNOWN, state.classification)
        assertEquals(AgeAccessStatus.VERIFICATION_REQUIRED, state.accessStatus)
    }

    @Test
    fun classifyAgeRange_handlesDefaultCustomAndInvalidBoundsSafely() {
        val compliance = AgeSignalsCompliance(
            ageSignalsManager = FakeAgeSignalsManager(),
            callbackExecutor = directExecutor
        )

        assertEquals(AgeClassification.MINOR, compliance.classifyAgeRange(0, 12))
        assertEquals(AgeClassification.MINOR, compliance.classifyAgeRange(13, 15))
        assertEquals(AgeClassification.MINOR, compliance.classifyAgeRange(16, 17))
        assertEquals(AgeClassification.ADULT, compliance.classifyAgeRange(18, null))
        assertEquals(AgeClassification.ADULT, compliance.classifyAgeRange(18, 25))
        // Custom age band crossing 18 cannot prove adult vs minor
        assertEquals(AgeClassification.UNKNOWN, compliance.classifyAgeRange(15, null))
        assertEquals(AgeClassification.UNKNOWN, compliance.classifyAgeRange(16, 20))
        // Missing or inconsistent bounds
        assertEquals(AgeClassification.UNKNOWN, compliance.classifyAgeRange(null, null))
        assertEquals(AgeClassification.UNKNOWN, compliance.classifyAgeRange(15, 13))
    }

    @Test
    fun retryableError_retriesWithExponentialBackoffAndRecovers() = runBlocking {
        val recordedDelays = mutableListOf<Long>()
        var attempts = 0
        val stubManager = object : AgeSignalsManager {
            override fun requestAgeSignalsAccess(
                request: AgeSignalsAccessRequest
            ): Task<AgeSignalsAccessResult> = throw UnsupportedOperationException()

            override fun checkAgeSignals(request: AgeSignalsRequest): Task<AgeSignalsResult> {
                attempts++
                return if (attempts < 3) {
                    Tasks.forException(AgeSignalsException(AgeSignalsErrorCode.NETWORK_ERROR))
                } else {
                    Tasks.forResult(
                        AgeSignalsResult.builder()
                            .setAgeRangeSource(AgeRangeSource.TIER_C)
                            .setAgeLower(18)
                            .build()
                    )
                }
            }

            override fun getApplicableAgeFeatures(
                request: ApplicableAgeFeaturesRequest
            ): Task<ApplicableAgeFeaturesResult> = throw UnsupportedOperationException()
        }

        val compliance = AgeSignalsCompliance(
            ageSignalsManager = stubManager,
            callbackExecutor = directExecutor,
            maxRetryAttempts = 3,
            initialRetryDelayMs = 1_000L,
            delayFn = { recordedDelays.add(it) }
        )

        val state = compliance.retrieveSharedAgeSignals()

        assertEquals(3, attempts)
        assertEquals(listOf(1_000L, 2_000L), recordedDelays)
        assertEquals(AgeClassification.ADULT, state.classification)
        assertEquals(AgeAccessStatus.SHARED, state.accessStatus)
        assertNull(state.lastErrorCode)
    }

    @Test
    fun nonRetryableError_deviceNotSupported_doesNotRetry() = runBlocking {
        val recordedDelays = mutableListOf<Long>()
        var attempts = 0
        val stubManager = object : AgeSignalsManager {
            override fun requestAgeSignalsAccess(
                request: AgeSignalsAccessRequest
            ): Task<AgeSignalsAccessResult> = throw UnsupportedOperationException()

            override fun checkAgeSignals(request: AgeSignalsRequest): Task<AgeSignalsResult> {
                attempts++
                return Tasks.forException(AgeSignalsException(AgeSignalsErrorCode.DEVICE_NOT_SUPPORTED))
            }

            override fun getApplicableAgeFeatures(
                request: ApplicableAgeFeaturesRequest
            ): Task<ApplicableAgeFeaturesResult> = throw UnsupportedOperationException()
        }

        val compliance = AgeSignalsCompliance(
            ageSignalsManager = stubManager,
            callbackExecutor = directExecutor,
            maxRetryAttempts = 3,
            initialRetryDelayMs = 1_000L,
            delayFn = { recordedDelays.add(it) }
        )

        val state = compliance.retrieveSharedAgeSignals()

        assertEquals(1, attempts)
        assertTrue(recordedDelays.isEmpty())
        assertEquals(AgeClassification.UNKNOWN, state.classification)
        assertEquals(AgeAccessStatus.ERROR, state.accessStatus)
        assertEquals(AgeSignalsErrorCode.DEVICE_NOT_SUPPORTED, state.lastErrorCode)
    }

    @Test
    fun refreshApplicableAgeFeatures_updatesMandatorySharingFlag() = runBlocking {
        val fakeManager = FakeAgeSignalsManager().apply {
            setNextApplicableAgeFeaturesResult(
                ApplicableAgeFeaturesResult.builder()
                    .setFeatures(setOf(ApplicableAgeFeature.MANDATORY_SHARING))
                    .build()
            )
        }
        val compliance = AgeSignalsCompliance(
            ageSignalsManager = fakeManager,
            callbackExecutor = directExecutor
        )

        var receivedFeatures: Set<Int>? = null
        compliance.refreshApplicableAgeFeatures(scope = this) { features ->
            receivedFeatures = features
        }
        // Let launched coroutine run to completion inside runBlocking
        kotlinx.coroutines.yield()

        assertEquals(setOf(ApplicableAgeFeature.MANDATORY_SHARING), receivedFeatures)
        assertEquals(true, compliance.state.value.mandatorySharingApplicable)
        assertEquals(AgeClassification.UNKNOWN, compliance.currentClassification)
    }

    @Test
    fun isRetryableErrorCode_matchesOfficialErrorTable() {
        val compliance = AgeSignalsCompliance(
            ageSignalsManager = FakeAgeSignalsManager(),
            callbackExecutor = directExecutor
        )

        assertTrue(compliance.isRetryableErrorCode(AgeSignalsErrorCode.API_NOT_AVAILABLE))
        assertTrue(compliance.isRetryableErrorCode(AgeSignalsErrorCode.PLAY_STORE_NOT_FOUND))
        assertTrue(compliance.isRetryableErrorCode(AgeSignalsErrorCode.NETWORK_ERROR))
        assertTrue(compliance.isRetryableErrorCode(AgeSignalsErrorCode.PLAY_SERVICES_NOT_FOUND))
        assertTrue(compliance.isRetryableErrorCode(AgeSignalsErrorCode.CANNOT_BIND_TO_SERVICE))
        assertTrue(compliance.isRetryableErrorCode(AgeSignalsErrorCode.PLAY_STORE_VERSION_OUTDATED))
        assertTrue(compliance.isRetryableErrorCode(AgeSignalsErrorCode.PLAY_SERVICES_VERSION_OUTDATED))
        assertTrue(compliance.isRetryableErrorCode(AgeSignalsErrorCode.CLIENT_TRANSIENT_ERROR))

        assertFalse(compliance.isRetryableErrorCode(AgeSignalsErrorCode.APP_NOT_OWNED))
        assertFalse(compliance.isRetryableErrorCode(AgeSignalsErrorCode.SDK_VERSION_OUTDATED))
        assertFalse(compliance.isRetryableErrorCode(AgeSignalsErrorCode.DEVICE_NOT_SUPPORTED))
        assertFalse(compliance.isRetryableErrorCode(AgeSignalsErrorCode.INTERNAL_ERROR))
    }
}
