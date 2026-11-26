package com.krdonon.touch

import android.app.Activity
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import java.util.concurrent.Executor

class AgeSignalsComplianceTest {

    private val directExecutor = Executor { runnable -> runnable.run() }

    private val activeHandle = object : ActivityHandle {
        override val isActive: Boolean = true
        override val activity: Activity? = null
    }

    private class ImmediateRetryScheduler : RetryScheduler {
        val scheduledDelays = mutableListOf<Long>()
        var cancelled = false

        override fun schedule(delayMs: Long, block: () -> Unit) {
            scheduledDelays.add(delayMs)
            block()
        }

        override fun cancelAll() {
            cancelled = true
        }
    }

    private class DeferredRetryScheduler : RetryScheduler {
        val pendingBlocks = mutableListOf<() -> Unit>()

        override fun schedule(delayMs: Long, block: () -> Unit) {
            pendingBlocks.add(block)
        }

        override fun cancelAll() {
            pendingBlocks.clear()
        }

        fun runNext() {
            if (pendingBlocks.isNotEmpty()) {
                pendingBlocks.removeAt(0).invoke()
            }
        }
    }

    /**
     * FakeAgeSignalsManager를 기반으로 getApplicableAgeFeatures / checkAgeSignals를 그대로 위임하고,
     * JVM 단위 테스트에서 Activity 인스턴스 없이 requestAgeSignalsAccess 시나리오까지 제어할 수 있는 클라이언트.
     */
    private class TestAgeSignalsClient(
        private val fakeManager: FakeAgeSignalsManager = FakeAgeSignalsManager()
    ) : AgeSignalsClient {

        private val accessOutcomes = ArrayDeque<Result<AgeSignalsAccessResult>>()
        private val featureOutcomes = ArrayDeque<Result<ApplicableAgeFeaturesResult>>()
        private val signalOutcomes = ArrayDeque<Result<AgeSignalsResult>>()

        var featuresCallCount = 0
            private set
        var accessCallCount = 0
            private set
        var checkSignalsCallCount = 0
            private set

        fun enqueueFeaturesResult(features: Set<Int>) {
            val result = ApplicableAgeFeaturesResult.builder()
                .setFeatures(features)
                .build()
            featureOutcomes.addLast(Result.success(result))
        }

        fun enqueueFeaturesError(errorCode: Int) {
            featureOutcomes.addLast(Result.failure(AgeSignalsException(errorCode)))
        }

        fun enqueueAccessStatus(status: Int) {
            val result = AgeSignalsAccessResult.builder()
                .setAgeSignalsStatus(status)
                .build()
            accessOutcomes.addLast(Result.success(result))
        }

        fun enqueueAccessError(errorCode: Int) {
            accessOutcomes.addLast(Result.failure(AgeSignalsException(errorCode)))
        }

        fun enqueueAgeSignalsResult(result: AgeSignalsResult) {
            signalOutcomes.addLast(Result.success(result))
        }

        fun enqueueAgeSignalsError(errorCode: Int) {
            signalOutcomes.addLast(Result.failure(AgeSignalsException(errorCode)))
        }

        override fun getApplicableAgeFeatures(): Task<ApplicableAgeFeaturesResult> {
            featuresCallCount++
            val outcome = featureOutcomes.removeFirstOrNull()
                ?: return Tasks.forResult(
                    ApplicableAgeFeaturesResult.builder().setFeatures(emptySet()).build()
                )
            return outcome.fold(
                onSuccess = {
                    fakeManager.setNextApplicableAgeFeaturesResult(it)
                    fakeManager.getApplicableAgeFeatures(ApplicableAgeFeaturesRequest.builder().build())
                },
                onFailure = {
                    fakeManager.setNextApplicableAgeFeaturesException(it as AgeSignalsException)
                    fakeManager.getApplicableAgeFeatures(ApplicableAgeFeaturesRequest.builder().build())
                }
            )
        }

        override fun requestAgeSignalsAccess(activityHandle: ActivityHandle): Task<AgeSignalsAccessResult> {
            accessCallCount++
            val outcome = accessOutcomes.removeFirstOrNull()
                ?: return Tasks.forResult(
                    AgeSignalsAccessResult.builder()
                        .setAgeSignalsStatus(AgeSignalsStatus.NOT_SHARED)
                        .build()
                )
            return outcome.fold(
                onSuccess = { Tasks.forResult(it) },
                onFailure = { Tasks.forException(it as Exception) }
            )
        }

        override fun checkAgeSignals(): Task<AgeSignalsResult> {
            checkSignalsCallCount++
            val outcome = signalOutcomes.removeFirstOrNull()
                ?: return Tasks.forResult(AgeSignalsResult.builder().build())
            return outcome.fold(
                onSuccess = {
                    fakeManager.setNextAgeSignalsResult(it)
                    fakeManager.checkAgeSignals(AgeSignalsRequest.builder().build())
                },
                onFailure = {
                    fakeManager.setNextAgeSignalsException(it as AgeSignalsException)
                    fakeManager.checkAgeSignals(AgeSignalsRequest.builder().build())
                }
            )
        }
    }

    @Test
    fun mandatorySharing_verifiedAdult_returnsAdultAndPopulatesState() {
        val client = TestAgeSignalsClient().apply {
            enqueueFeaturesResult(setOf(ApplicableAgeFeature.MANDATORY_SHARING))
            enqueueAccessStatus(AgeSignalsStatus.SHARED)
            enqueueAgeSignalsResult(
                AgeSignalsResult.builder()
                    .setAgeRangeSource(AgeRangeSource.TIER_D)
                    .setAgeLower(18)
                    .build()
            )
        }
        val compliance = AgeSignalsCompliance(
            client = client,
            callbackExecutor = directExecutor,
            retryScheduler = ImmediateRetryScheduler()
        )

        var observed: AgeSignalsState? = null
        compliance.refreshStateForTest(activeHandle) { observed = it }

        assertEquals(AgeCategory.ADULT, observed?.category)
        assertEquals(AgeCategory.ADULT, compliance.currentCategory)
        assertEquals(true, observed?.isMandatorySharing)
        assertEquals(AgeSignalsStatus.SHARED, observed?.accessStatus)
        assertEquals(AgeRangeSource.TIER_D, observed?.ageRangeSource)
        assertEquals(18, observed?.ageLower)
        assertNull(observed?.ageUpper)
        assertNull(observed?.errorCode)
        assertFalse(observed?.isLoading == true)
    }

    @Test
    fun supervisedMinor_withSignificantChangeDeclined_returnsMinorAndTracksStatus() {
        val approvalDate = Date(1777939200000L)
        val client = TestAgeSignalsClient().apply {
            enqueueFeaturesResult(emptySet())
            enqueueAccessStatus(AgeSignalsStatus.SHARED)
            enqueueAgeSignalsResult(
                AgeSignalsResult.builder()
                    .setAgeRangeSource(AgeRangeSource.TIER_B)
                    .setAgeLower(13)
                    .setAgeUpper(15)
                    .setSignificantChangeStatus(SignificantChangeStatus.DECLINED)
                    .setSignificantChangeApprovalDate(approvalDate)
                    .setInstallId("install_123")
                    .build()
            )
        }
        val compliance = AgeSignalsCompliance(
            client = client,
            callbackExecutor = directExecutor,
            retryScheduler = ImmediateRetryScheduler()
        )

        var observed: AgeSignalsState? = null
        compliance.refreshStateForTest(activeHandle) { observed = it }

        assertEquals(AgeCategory.MINOR, observed?.category)
        assertEquals(false, observed?.isMandatorySharing)
        assertEquals(true, observed?.isSignificantChangeDeclined)
        assertEquals(false, observed?.isSignificantChangePending)
        assertEquals(approvalDate, observed?.significantChangeApprovalDate)
        assertEquals("install_123", observed?.installId)
    }

    @Test
    fun openRangeCrossing18_isClassifiedConservativelyAsUnknown() {
        assertEquals(AgeCategory.UNKNOWN, AgeSignalsCompliance.classifyAgeRange(15, null))
        assertEquals(AgeCategory.UNKNOWN, AgeSignalsCompliance.classifyAgeRange(16, 18))
        assertEquals(AgeCategory.UNKNOWN, AgeSignalsCompliance.classifyAgeRange(null, 15))
        assertEquals(AgeCategory.MINOR, AgeSignalsCompliance.classifyAgeRange(13, 17))
        assertEquals(AgeCategory.ADULT, AgeSignalsCompliance.classifyAgeRange(18, null))
    }

    @Test
    fun notShared_skipsCheckAgeSignals_andReturnsUnknown() {
        val client = TestAgeSignalsClient().apply {
            enqueueFeaturesResult(emptySet())
            enqueueAccessStatus(AgeSignalsStatus.NOT_SHARED)
        }
        val compliance = AgeSignalsCompliance(
            client = client,
            callbackExecutor = directExecutor,
            retryScheduler = ImmediateRetryScheduler()
        )

        var observed: AgeSignalsState? = null
        compliance.refreshStateForTest(activeHandle) { observed = it }

        assertEquals(AgeCategory.UNKNOWN, observed?.category)
        assertEquals(AgeSignalsStatus.NOT_SHARED, observed?.accessStatus)
        assertEquals(0, client.checkSignalsCallCount)
        assertFalse(observed?.requiresPlayStoreVerification == true)
    }

    @Test
    fun verificationRequired_marksRequiresPlayStoreVerification() {
        val client = TestAgeSignalsClient().apply {
            enqueueFeaturesResult(setOf(ApplicableAgeFeature.MANDATORY_SHARING))
            enqueueAccessStatus(AgeSignalsStatus.VERIFICATION_REQUIRED)
        }
        val compliance = AgeSignalsCompliance(
            client = client,
            callbackExecutor = directExecutor,
            retryScheduler = ImmediateRetryScheduler()
        )

        var observed: AgeSignalsState? = null
        compliance.refreshStateForTest(activeHandle) { observed = it }

        assertEquals(AgeCategory.UNKNOWN, observed?.category)
        assertEquals(true, observed?.requiresPlayStoreVerification)
        assertEquals(true, observed?.isMandatorySharing)
        assertEquals(0, client.checkSignalsCallCount)
    }

    @Test
    fun transientError_retriesWithBackoffAndRecovers() {
        val scheduler = ImmediateRetryScheduler()
        val client = TestAgeSignalsClient().apply {
            enqueueFeaturesResult(emptySet())
            enqueueAccessError(AgeSignalsErrorCode.CLIENT_TRANSIENT_ERROR)
            enqueueAccessStatus(AgeSignalsStatus.SHARED)
            enqueueAgeSignalsError(AgeSignalsErrorCode.NETWORK_ERROR)
            enqueueAgeSignalsResult(
                AgeSignalsResult.builder()
                    .setAgeRangeSource(AgeRangeSource.TIER_A)
                    .setAgeLower(18)
                    .build()
            )
        }
        val compliance = AgeSignalsCompliance(
            client = client,
            callbackExecutor = directExecutor,
            retryDelaysMs = listOf(500L, 1500L),
            retryScheduler = scheduler
        )

        var observed: AgeSignalsState? = null
        compliance.refreshStateForTest(activeHandle) { observed = it }

        assertEquals(AgeCategory.ADULT, observed?.category)
        assertEquals(listOf(500L, 500L), scheduler.scheduledDelays)
        assertEquals(2, client.accessCallCount)
        assertEquals(2, client.checkSignalsCallCount)
    }

    @Test
    fun nonRetryableDeviceNotSupported_failsImmediatelyWithoutRetry() {
        val scheduler = ImmediateRetryScheduler()
        val client = TestAgeSignalsClient().apply {
            enqueueFeaturesError(AgeSignalsErrorCode.DEVICE_NOT_SUPPORTED)
        }
        val compliance = AgeSignalsCompliance(
            client = client,
            callbackExecutor = directExecutor,
            retryDelaysMs = listOf(500L, 1500L),
            retryScheduler = scheduler
        )

        var observed: AgeSignalsState? = null
        compliance.refreshStateForTest(activeHandle) { observed = it }

        assertEquals(AgeCategory.UNKNOWN, observed?.category)
        assertEquals(AgeSignalsErrorCode.DEVICE_NOT_SUPPORTED, observed?.errorCode)
        assertFalse(observed?.hasRetryableError == true)
        assertTrue(scheduler.scheduledDelays.isEmpty())
        assertEquals(1, client.featuresCallCount)
        assertEquals(0, client.accessCallCount)
    }

    @Test
    fun apiNotAvailableOnApplicableFeatures_fallsBackToRequestAccess() {
        val scheduler = ImmediateRetryScheduler()
        val client = TestAgeSignalsClient().apply {
            enqueueFeaturesError(AgeSignalsErrorCode.API_NOT_AVAILABLE)
            enqueueFeaturesError(AgeSignalsErrorCode.API_NOT_AVAILABLE)
            enqueueFeaturesError(AgeSignalsErrorCode.API_NOT_AVAILABLE)
            enqueueAccessStatus(AgeSignalsStatus.SHARED)
            enqueueAgeSignalsResult(
                AgeSignalsResult.builder()
                    .setAgeRangeSource(AgeRangeSource.TIER_C)
                    .setAgeLower(13)
                    .setAgeUpper(15)
                    .build()
            )
        }
        val compliance = AgeSignalsCompliance(
            client = client,
            callbackExecutor = directExecutor,
            retryDelaysMs = listOf(500L, 1500L),
            retryScheduler = scheduler
        )

        var observed: AgeSignalsState? = null
        compliance.refreshStateForTest(activeHandle) { observed = it }

        assertEquals(AgeCategory.MINOR, observed?.category)
        assertNull(observed?.isMandatorySharing)
        assertEquals(3, client.featuresCallCount)
        assertEquals(1, client.accessCallCount)
    }

    @Test
    fun cancel_clearsPendingRetriesAndStopsLoading() {
        val scheduler = DeferredRetryScheduler()
        val client = TestAgeSignalsClient().apply {
            enqueueFeaturesResult(emptySet())
            enqueueAccessError(AgeSignalsErrorCode.CLIENT_TRANSIENT_ERROR)
            enqueueAccessStatus(AgeSignalsStatus.SHARED)
        }
        val compliance = AgeSignalsCompliance(
            client = client,
            callbackExecutor = directExecutor,
            retryDelaysMs = listOf(500L, 1500L),
            retryScheduler = scheduler
        )

        var callbackCount = 0
        compliance.refreshStateForTest(activeHandle) { callbackCount++ }
        assertTrue(compliance.currentState.isLoading)
        assertEquals(1, scheduler.pendingBlocks.size)

        compliance.cancel()
        assertFalse(compliance.currentState.isLoading)
        assertTrue(scheduler.pendingBlocks.isEmpty())
        scheduler.runNext()
        assertEquals(0, callbackCount)
    }
}
