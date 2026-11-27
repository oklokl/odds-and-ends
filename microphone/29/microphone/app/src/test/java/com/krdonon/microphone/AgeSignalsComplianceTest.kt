package com.krdonon.microphone

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.play.agesignals.AgeSignalsAccessResult
import com.google.android.play.agesignals.AgeSignalsException
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
import com.krdonon.microphone.compliance.AgeCategory
import com.krdonon.microphone.compliance.AgeRangeVerificationTier
import com.krdonon.microphone.compliance.AgeSignalsAccessState
import com.krdonon.microphone.compliance.AgeSignalsCompliance
import com.krdonon.microphone.compliance.AgeSignalsComplianceState
import com.krdonon.microphone.compliance.AgeSignalsGateway
import com.krdonon.microphone.compliance.SignificantChangeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgeSignalsComplianceTest {

    private class TestAgeSignalsGateway(
        private val fakeManager: FakeAgeSignalsManager,
        private val accessResults: ArrayDeque<() -> Task<AgeSignalsAccessResult>> = ArrayDeque(),
        private val checkOverride: (() -> Task<AgeSignalsResult>)? = null
    ) : AgeSignalsGateway {
        var checkAgeSignalsCallCount = 0
            private set

        override fun getApplicableAgeFeatures(): Task<ApplicableAgeFeaturesResult> {
            return fakeManager.getApplicableAgeFeatures(
                ApplicableAgeFeaturesRequest.builder().build()
            )
        }

        override fun requestAgeSignalsAccess(): Task<AgeSignalsAccessResult> {
            return accessResults.removeFirst().invoke()
        }

        override fun checkAgeSignals(): Task<AgeSignalsResult> {
            checkAgeSignalsCallCount++
            return checkOverride?.invoke()
                ?: fakeManager.checkAgeSignals(AgeSignalsRequest.builder().build())
        }
    }

    private fun createCompliance(
        fakeManager: FakeAgeSignalsManager,
        delaysRecorded: MutableList<Long> = mutableListOf()
    ): AgeSignalsCompliance {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        return AgeSignalsCompliance(
            ageSignalsManager = fakeManager,
            scope = scope,
            retryDelaysMs = listOf(100L, 200L),
            delayFn = { delaysRecorded.add(it) }
        )
    }

    @Test
    fun verifiedAdult_sharedStatus_updatesStateToAdultAndVerifiedTier() = runBlocking {
        val fakeManager = FakeAgeSignalsManager().apply {
            setNextApplicableAgeFeaturesResult(
                ApplicableAgeFeaturesResult.builder()
                    .setFeatures(emptySet())
                    .build()
            )
            setNextAgeSignalsResult(
                AgeSignalsResult.builder()
                    .setAgeRangeSource(AgeRangeSource.TIER_D)
                    .setAgeLower(18)
                    .build()
            )
        }
        val gateway = TestAgeSignalsGateway(
            fakeManager = fakeManager,
            accessResults = ArrayDeque(
                listOf {
                    Tasks.forResult(
                        AgeSignalsAccessResult.builder()
                            .setAgeSignalsStatus(AgeSignalsStatus.SHARED)
                            .build()
                    )
                }
            )
        )
        val compliance = createCompliance(fakeManager)

        compliance.refreshWithGateway(gateway)

        assertEquals(1, gateway.checkAgeSignalsCallCount)
        assertEquals(
            AgeSignalsComplianceState(
                category = AgeCategory.ADULT,
                accessState = AgeSignalsAccessState.SHARED,
                verificationTier = AgeRangeVerificationTier.VERIFIED,
                significantChangeState = SignificantChangeState.NONE,
                mandatorySharingApplicable = false,
                errorCode = null,
                isLoading = false
            ),
            compliance.state.value
        )
    }

    @Test
    fun supervisedMinor_declinedSignificantChange_tracksTierAndDeclinedStatus() = runBlocking {
        val fakeManager = FakeAgeSignalsManager().apply {
            setNextApplicableAgeFeaturesResult(
                ApplicableAgeFeaturesResult.builder()
                    .setFeatures(setOf(ApplicableAgeFeature.MANDATORY_SHARING))
                    .build()
            )
            setNextAgeSignalsResult(
                AgeSignalsResult.builder()
                    .setAgeRangeSource(AgeRangeSource.TIER_B)
                    .setAgeLower(13)
                    .setAgeUpper(15)
                    .setSignificantChangeStatus(SignificantChangeStatus.DECLINED)
                    .setInstallId("fake_install_id")
                    .build()
            )
        }
        val gateway = TestAgeSignalsGateway(
            fakeManager = fakeManager,
            accessResults = ArrayDeque(
                listOf {
                    Tasks.forResult(
                        AgeSignalsAccessResult.builder()
                            .setAgeSignalsStatus(AgeSignalsStatus.SHARED)
                            .build()
                    )
                }
            )
        )
        val compliance = createCompliance(fakeManager)

        compliance.refreshWithGateway(gateway)

        assertEquals(
            AgeSignalsComplianceState(
                category = AgeCategory.MINOR,
                accessState = AgeSignalsAccessState.SHARED,
                verificationTier = AgeRangeVerificationTier.SUPERVISED,
                significantChangeState = SignificantChangeState.DECLINED,
                mandatorySharingApplicable = true,
                errorCode = null,
                isLoading = false
            ),
            compliance.state.value
        )
    }

    @Test
    fun notShared_doesNotQueryCheckAgeSignals() = runBlocking {
        val fakeManager = FakeAgeSignalsManager().apply {
            setNextApplicableAgeFeaturesResult(
                ApplicableAgeFeaturesResult.builder()
                    .setFeatures(emptySet())
                    .build()
            )
        }
        val gateway = TestAgeSignalsGateway(
            fakeManager = fakeManager,
            accessResults = ArrayDeque(
                listOf {
                    Tasks.forResult(
                        AgeSignalsAccessResult.builder()
                            .setAgeSignalsStatus(AgeSignalsStatus.NOT_SHARED)
                            .build()
                    )
                }
            )
        )
        val compliance = createCompliance(fakeManager)

        compliance.refreshWithGateway(gateway)

        assertEquals(0, gateway.checkAgeSignalsCallCount)
        assertEquals(AgeSignalsAccessState.NOT_SHARED, compliance.state.value.accessState)
        assertEquals(AgeCategory.UNKNOWN, compliance.state.value.category)
        assertFalse(compliance.state.value.isLoading)
    }

    @Test
    fun verificationRequired_exposesStateWithoutCallingCheckAgeSignals() = runBlocking {
        val fakeManager = FakeAgeSignalsManager().apply {
            setNextApplicableAgeFeaturesResult(
                ApplicableAgeFeaturesResult.builder()
                    .setFeatures(setOf(ApplicableAgeFeature.MANDATORY_SHARING))
                    .build()
            )
        }
        val gateway = TestAgeSignalsGateway(
            fakeManager = fakeManager,
            accessResults = ArrayDeque(
                listOf {
                    Tasks.forResult(
                        AgeSignalsAccessResult.builder()
                            .setAgeSignalsStatus(AgeSignalsStatus.VERIFICATION_REQUIRED)
                            .build()
                    )
                }
            )
        )
        val compliance = createCompliance(fakeManager)

        compliance.refreshWithGateway(gateway)

        assertEquals(0, gateway.checkAgeSignalsCallCount)
        assertEquals(AgeSignalsAccessState.VERIFICATION_REQUIRED, compliance.state.value.accessState)
        assertEquals(true, compliance.state.value.mandatorySharingApplicable)
        assertFalse(compliance.state.value.isLoading)
    }

    @Test
    fun deviceNotSupported_doesNotRetryAndMapsDedicatedState() = runBlocking {
        val fakeManager = FakeAgeSignalsManager().apply {
            setNextApplicableAgeFeaturesException(
                AgeSignalsException(AgeSignalsErrorCode.DEVICE_NOT_SUPPORTED)
            )
        }
        val delays = mutableListOf<Long>()
        val gateway = TestAgeSignalsGateway(
            fakeManager = fakeManager,
            accessResults = ArrayDeque(
                listOf {
                    Tasks.forException(
                        AgeSignalsException(AgeSignalsErrorCode.DEVICE_NOT_SUPPORTED)
                    )
                }
            )
        )
        val compliance = createCompliance(fakeManager, delays)

        compliance.refreshWithGateway(gateway)

        assertTrue(delays.isEmpty())
        assertEquals(AgeSignalsAccessState.DEVICE_NOT_SUPPORTED, compliance.state.value.accessState)
        assertEquals(AgeSignalsErrorCode.DEVICE_NOT_SUPPORTED, compliance.state.value.errorCode)
        assertNull(compliance.state.value.mandatorySharingApplicable)
    }

    @Test
    fun transientError_retriesAndSucceedsOnSecondAttempt() = runBlocking {
        val fakeManager = FakeAgeSignalsManager().apply {
            setNextApplicableAgeFeaturesResult(
                ApplicableAgeFeaturesResult.builder().setFeatures(emptySet()).build()
            )
            setNextAgeSignalsResult(
                AgeSignalsResult.builder()
                    .setAgeRangeSource(AgeRangeSource.TIER_A)
                    .setAgeLower(18)
                    .build()
            )
        }
        val delays = mutableListOf<Long>()
        val gateway = TestAgeSignalsGateway(
            fakeManager = fakeManager,
            accessResults = ArrayDeque(
                listOf(
                    { Tasks.forException(AgeSignalsException(AgeSignalsErrorCode.CLIENT_TRANSIENT_ERROR)) },
                    {
                        Tasks.forResult(
                            AgeSignalsAccessResult.builder()
                                .setAgeSignalsStatus(AgeSignalsStatus.SHARED)
                                .build()
                        )
                    }
                )
            )
        )
        val compliance = createCompliance(fakeManager, delays)

        compliance.refreshWithGateway(gateway)

        assertEquals(listOf(100L), delays)
        assertEquals(AgeSignalsAccessState.SHARED, compliance.state.value.accessState)
        assertEquals(AgeCategory.ADULT, compliance.state.value.category)
        assertEquals(AgeRangeVerificationTier.SELF_DECLARED, compliance.state.value.verificationTier)
    }

    @Test
    fun networkError_exhaustsRetriesAndReportsErrorCode() = runBlocking {
        val fakeManager = FakeAgeSignalsManager().apply {
            setNextApplicableAgeFeaturesResult(
                ApplicableAgeFeaturesResult.builder().setFeatures(emptySet()).build()
            )
        }
        val delays = mutableListOf<Long>()
        val gateway = TestAgeSignalsGateway(
            fakeManager = fakeManager,
            accessResults = ArrayDeque(
                listOf(
                    { Tasks.forException(AgeSignalsException(AgeSignalsErrorCode.NETWORK_ERROR)) },
                    { Tasks.forException(AgeSignalsException(AgeSignalsErrorCode.NETWORK_ERROR)) },
                    { Tasks.forException(AgeSignalsException(AgeSignalsErrorCode.NETWORK_ERROR)) }
                )
            )
        )
        val compliance = createCompliance(fakeManager, delays)

        compliance.refreshWithGateway(gateway)

        assertEquals(listOf(100L, 200L), delays)
        assertEquals(AgeSignalsAccessState.ERROR, compliance.state.value.accessState)
        assertEquals(AgeSignalsErrorCode.NETWORK_ERROR, compliance.state.value.errorCode)
        assertFalse(compliance.state.value.isLoading)
    }

    @Test
    fun classifyAgeRange_handlesDefaultAndCustomBandsConservatively() {
        val compliance = createCompliance(FakeAgeSignalsManager())

        assertEquals(AgeCategory.MINOR, compliance.classifyAgeRange(0, 12))
        assertEquals(AgeCategory.MINOR, compliance.classifyAgeRange(13, 15))
        assertEquals(AgeCategory.MINOR, compliance.classifyAgeRange(16, 17))
        assertEquals(AgeCategory.ADULT, compliance.classifyAgeRange(18, null))
        assertEquals(AgeCategory.UNKNOWN, compliance.classifyAgeRange(15, null))
        assertEquals(AgeCategory.UNKNOWN, compliance.classifyAgeRange(16, 20))
        assertEquals(AgeCategory.UNKNOWN, compliance.classifyAgeRange(null, null))
    }
}
