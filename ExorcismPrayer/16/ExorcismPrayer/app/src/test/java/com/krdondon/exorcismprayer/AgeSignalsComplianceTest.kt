package com.krdondon.exorcismprayer

import com.google.android.gms.tasks.Tasks
import com.google.android.play.agesignals.AgeSignalsAccessResult
import com.google.android.play.agesignals.AgeSignalsException
import com.google.android.play.agesignals.AgeSignalsResult
import com.google.android.play.agesignals.model.AgeRangeSource
import com.google.android.play.agesignals.model.AgeSignalsErrorCode
import com.google.android.play.agesignals.model.AgeSignalsStatus
import com.google.android.play.agesignals.model.SignificantChangeStatus
import com.google.android.play.agesignals.testing.FakeAgeSignalsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Date
import java.util.concurrent.Executor

class AgeSignalsComplianceTest {

    private val directExecutor = Executor { command -> command.run() }

    private fun createCompliance(
        fakeManager: FakeAgeSignalsManager,
        retrySchedule: MutableList<Long> = mutableListOf(),
    ): AgeSignalsCompliance {
        return AgeSignalsCompliance(
            ageSignalsManager = fakeManager,
            callbackExecutor = directExecutor,
            scheduleRetry = { delayMs, action ->
                retrySchedule.add(delayMs)
                action.run()
            },
            logWarning = { _, _ -> },
        )
    }

    @Test
    fun sharedAdultUser_returnsAdultAndMetadata() {
        val fakeManager = FakeAgeSignalsManager().apply {
            setNextAgeSignalsResult(
                AgeSignalsResult.builder()
                    .setAgeRangeSource(AgeRangeSource.TIER_D)
                    .setAgeLower(18)
                    .build()
            )
        }
        val compliance = createCompliance(fakeManager)

        var snapshot: AgeSignalsSnapshot? = null
        compliance.requestAgeSignalsSnapshot(
            isActive = { true },
            requestAccess = {
                Tasks.forResult(
                    AgeSignalsAccessResult.builder()
                        .setAgeSignalsStatus(AgeSignalsStatus.SHARED)
                        .build()
                )
            },
        ) { snapshot = it }

        assertEquals(AgeClassification.ADULT, snapshot?.classification)
        assertEquals(AgeSignalsAccessState.SHARED, snapshot?.accessState)
        assertEquals(AgeRangeSource.TIER_D, snapshot?.ageRangeSource)
    }

    @Test
    fun sharedSupervisedMinor_returnsMinorAndSignificantChangeDetails() {
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
        val compliance = createCompliance(fakeManager)

        var snapshot: AgeSignalsSnapshot? = null
        compliance.requestAgeSignalsSnapshot(
            isActive = { true },
            requestAccess = {
                Tasks.forResult(
                    AgeSignalsAccessResult.builder()
                        .setAgeSignalsStatus(AgeSignalsStatus.SHARED)
                        .build()
                )
            },
        ) { snapshot = it }

        assertEquals(AgeClassification.MINOR, snapshot?.classification)
        assertEquals(AgeSignalsAccessState.SHARED, snapshot?.accessState)
        assertEquals(AgeRangeSource.TIER_B, snapshot?.ageRangeSource)
        assertEquals(SignificantChangeStatus.APPROVED, snapshot?.significantChangeStatus)
        assertEquals(approvalDate, snapshot?.significantChangeApprovalDate)
        assertEquals("fake_install_id_123", snapshot?.installId)
    }

    @Test
    fun sharedCustomOpenEndedBandBelow18_returnsUnknown() {
        val fakeManager = FakeAgeSignalsManager().apply {
            setNextAgeSignalsResult(
                AgeSignalsResult.builder()
                    .setAgeRangeSource(AgeRangeSource.TIER_A)
                    .setAgeLower(15)
                    .build()
            )
        }
        val compliance = createCompliance(fakeManager)

        var snapshot: AgeSignalsSnapshot? = null
        compliance.requestAgeSignalsSnapshot(
            isActive = { true },
            requestAccess = {
                Tasks.forResult(
                    AgeSignalsAccessResult.builder()
                        .setAgeSignalsStatus(AgeSignalsStatus.SHARED)
                        .build()
                )
            },
        ) { snapshot = it }

        assertEquals(AgeClassification.UNKNOWN, snapshot?.classification)
        assertEquals(AgeSignalsAccessState.SHARED, snapshot?.accessState)
    }

    @Test
    fun notSharedAndVerificationRequired_returnExpectedAccessStates() {
        val compliance = createCompliance(FakeAgeSignalsManager())

        var notShared: AgeSignalsSnapshot? = null
        compliance.requestAgeSignalsSnapshot(
            isActive = { true },
            requestAccess = {
                Tasks.forResult(
                    AgeSignalsAccessResult.builder()
                        .setAgeSignalsStatus(AgeSignalsStatus.NOT_SHARED)
                        .build()
                )
            },
        ) { notShared = it }
        assertEquals(AgeClassification.UNKNOWN, notShared?.classification)
        assertEquals(AgeSignalsAccessState.NOT_SHARED, notShared?.accessState)

        var verificationRequired: AgeSignalsSnapshot? = null
        compliance.requestAgeSignalsSnapshot(
            isActive = { true },
            requestAccess = {
                Tasks.forResult(
                    AgeSignalsAccessResult.builder()
                        .setAgeSignalsStatus(AgeSignalsStatus.VERIFICATION_REQUIRED)
                        .build()
                )
            },
        ) { verificationRequired = it }
        assertEquals(AgeClassification.UNKNOWN, verificationRequired?.classification)
        assertEquals(AgeSignalsAccessState.VERIFICATION_REQUIRED, verificationRequired?.accessState)
    }

    @Test
    fun transientError_retriesAndRecovers() {
        val retryDelays = mutableListOf<Long>()
        val fakeManager = FakeAgeSignalsManager().apply {
            setNextAgeSignalsResult(
                AgeSignalsResult.builder()
                    .setAgeRangeSource(AgeRangeSource.TIER_C)
                    .setAgeLower(18)
                    .build()
            )
        }
        val compliance = createCompliance(fakeManager, retryDelays)
        var attempts = 0

        var snapshot: AgeSignalsSnapshot? = null
        compliance.requestAgeSignalsSnapshot(
            isActive = { true },
            requestAccess = {
                attempts++
                if (attempts == 1) {
                    Tasks.forException(AgeSignalsException(AgeSignalsErrorCode.NETWORK_ERROR))
                } else {
                    Tasks.forResult(
                        AgeSignalsAccessResult.builder()
                            .setAgeSignalsStatus(AgeSignalsStatus.SHARED)
                            .build()
                    )
                }
            },
        ) { snapshot = it }

        assertEquals(2, attempts)
        assertEquals(mutableListOf(500L), retryDelays)
        assertEquals(AgeClassification.ADULT, snapshot?.classification)
        assertEquals(AgeSignalsAccessState.SHARED, snapshot?.accessState)
    }

    @Test
    fun transientErrorExhausted_stopsAfterMaxRetries() {
        val retryDelays = mutableListOf<Long>()
        val fakeManager = FakeAgeSignalsManager().apply {
            setNextAgeSignalsException(
                AgeSignalsException(AgeSignalsErrorCode.CLIENT_TRANSIENT_ERROR)
            )
        }
        val compliance = createCompliance(fakeManager, retryDelays)

        var snapshot: AgeSignalsSnapshot? = null
        compliance.requestAgeSignalsSnapshot(
            isActive = { true },
            requestAccess = {
                Tasks.forResult(
                    AgeSignalsAccessResult.builder()
                        .setAgeSignalsStatus(AgeSignalsStatus.SHARED)
                        .build()
                )
            },
        ) { snapshot = it }

        assertEquals(mutableListOf(500L, 1000L), retryDelays)
        assertEquals(AgeClassification.UNKNOWN, snapshot?.classification)
        assertEquals(AgeSignalsAccessState.ERROR, snapshot?.accessState)
        assertEquals(AgeSignalsErrorCode.CLIENT_TRANSIENT_ERROR, snapshot?.errorCode)
    }

    @Test
    fun nonRetryableError_doesNotRetryAndReportsErrorCode() {
        val retryDelays = mutableListOf<Long>()
        val fakeManager = FakeAgeSignalsManager().apply {
            setNextAgeSignalsException(
                AgeSignalsException(AgeSignalsErrorCode.DEVICE_NOT_SUPPORTED)
            )
        }
        val compliance = createCompliance(fakeManager, retryDelays)

        var snapshot: AgeSignalsSnapshot? = null
        compliance.requestAgeSignalsSnapshot(
            isActive = { true },
            requestAccess = {
                Tasks.forResult(
                    AgeSignalsAccessResult.builder()
                        .setAgeSignalsStatus(AgeSignalsStatus.SHARED)
                        .build()
                )
            },
        ) { snapshot = it }

        assertEquals(emptyList<Long>(), retryDelays)
        assertEquals(AgeClassification.UNKNOWN, snapshot?.classification)
        assertEquals(AgeSignalsAccessState.ERROR, snapshot?.accessState)
        assertEquals(AgeSignalsErrorCode.DEVICE_NOT_SUPPORTED, snapshot?.errorCode)
    }

    @Test
    fun inactiveActivity_skipsRequestAndReturnsUnavailable() {
        val compliance = createCompliance(FakeAgeSignalsManager())
        var snapshot: AgeSignalsSnapshot? = null
        compliance.requestAgeSignalsSnapshot(
            isActive = { false },
            requestAccess = { error("Should not be called when inactive") },
        ) { snapshot = it }

        assertEquals(AgeClassification.UNKNOWN, snapshot?.classification)
        assertEquals(AgeSignalsAccessState.UNAVAILABLE, snapshot?.accessState)
        assertNull(snapshot?.errorCode)
    }
}
