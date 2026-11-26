package com.krdondon.thelordsprayer

import android.app.Activity
import com.google.android.play.agesignals.AgeSignalsAccessRequest
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import java.util.concurrent.Executor

class AgeSignalsComplianceTest {

    private val directExecutor = Executor { runnable -> runnable.run() }

    private fun createCompliance(
        manager: FakeAgeSignalsManager,
        recordedDelays: MutableList<Long> = mutableListOf(),
    ): AgeSignalsCompliance {
        return AgeSignalsCompliance(
            managerProvider = { manager },
            callbackExecutor = directExecutor,
            delayScheduler = { delayMs, action ->
                recordedDelays += delayMs
                action()
            },
            logger = { _, _ -> },
        )
    }

    private fun dummyAccessRequest(): AgeSignalsAccessRequest {
        return object : AgeSignalsAccessRequest() {
            override fun activity(): Activity {
                throw UnsupportedOperationException("FakeAgeSignalsManager does not need Activity")
            }
        }
    }

    @Test
    fun sharedAdultUser_returnsAdultAndSharedOutcome() {
        val manager = FakeAgeSignalsManager().apply {
            setNextAgeSignalsAccessResult(
                AgeSignalsAccessResult.builder()
                    .setAgeSignalsStatus(AgeSignalsStatus.SHARED)
                    .build(),
            )
            setNextAgeSignalsResult(
                AgeSignalsResult.builder()
                    .setAgeRangeSource(AgeRangeSource.TIER_D)
                    .setAgeLower(18)
                    .build(),
            )
        }

        var result: AgeComplianceResult? = null
        createCompliance(manager).requestAndCheckInternal(
            isHostActive = { true },
            accessTaskProvider = { it.requestAgeSignalsAccess(dummyAccessRequest()) },
            onResult = { result = it },
        )

        assertNotNull(result)
        assertEquals(AgeComplianceCategory.ADULT, result?.category)
        assertEquals(AgeSignalsAccessOutcome.SHARED, result?.accessOutcome)
        assertEquals(AgeRangeSource.TIER_D, result?.ageRangeSource)
        assertEquals(18, result?.ageLower)
        assertNull(result?.ageUpper)
    }

    @Test
    fun sharedSupervisedMinor_returnsMinorAndSignificantChangeFields() {
        val approvalDate = Date(1777939200000L)
        val manager = FakeAgeSignalsManager().apply {
            setNextAgeSignalsAccessResult(
                AgeSignalsAccessResult.builder()
                    .setAgeSignalsStatus(AgeSignalsStatus.SHARED)
                    .build(),
            )
            setNextAgeSignalsResult(
                AgeSignalsResult.builder()
                    .setAgeRangeSource(AgeRangeSource.TIER_B)
                    .setAgeLower(13)
                    .setAgeUpper(15)
                    .setSignificantChangeStatus(SignificantChangeStatus.APPROVED)
                    .setSignificantChangeApprovalDate(approvalDate)
                    .setInstallId("fake_install_id_123")
                    .build(),
            )
        }

        var result: AgeComplianceResult? = null
        createCompliance(manager).requestAndCheckInternal(
            isHostActive = { true },
            accessTaskProvider = { it.requestAgeSignalsAccess(dummyAccessRequest()) },
            onResult = { result = it },
        )

        assertEquals(AgeComplianceCategory.MINOR, result?.category)
        assertEquals(AgeSignalsAccessOutcome.SHARED, result?.accessOutcome)
        assertEquals(SignificantChangeStatus.APPROVED, result?.significantChangeStatus)
        assertEquals(approvalDate, result?.significantChangeApprovalDate)
        assertEquals("fake_install_id_123", result?.installId)
    }

    @Test
    fun notSharedStatus_returnsUnknownAndNotSharedOutcome() {
        val manager = FakeAgeSignalsManager().apply {
            setNextAgeSignalsAccessResult(
                AgeSignalsAccessResult.builder()
                    .setAgeSignalsStatus(AgeSignalsStatus.NOT_SHARED)
                    .build(),
            )
        }

        var result: AgeComplianceResult? = null
        createCompliance(manager).requestAndCheckInternal(
            isHostActive = { true },
            accessTaskProvider = { it.requestAgeSignalsAccess(dummyAccessRequest()) },
            onResult = { result = it },
        )

        assertEquals(AgeComplianceCategory.UNKNOWN, result?.category)
        assertEquals(AgeSignalsAccessOutcome.NOT_SHARED, result?.accessOutcome)
    }

    @Test
    fun verificationRequiredStatus_returnsUnknownAndVerificationRequiredOutcome() {
        val manager = FakeAgeSignalsManager().apply {
            setNextAgeSignalsAccessResult(
                AgeSignalsAccessResult.builder()
                    .setAgeSignalsStatus(AgeSignalsStatus.VERIFICATION_REQUIRED)
                    .build(),
            )
        }

        var result: AgeComplianceResult? = null
        createCompliance(manager).requestAndCheckInternal(
            isHostActive = { true },
            accessTaskProvider = { it.requestAgeSignalsAccess(dummyAccessRequest()) },
            onResult = { result = it },
        )

        assertEquals(AgeComplianceCategory.UNKNOWN, result?.category)
        assertEquals(AgeSignalsAccessOutcome.VERIFICATION_REQUIRED, result?.accessOutcome)
    }

    @Test
    fun transientError_retriesWithExponentialBackoffAndFailsAfterMaxAttempts() {
        val manager = FakeAgeSignalsManager().apply {
            setNextAgeSignalsException(
                AgeSignalsException(AgeSignalsErrorCode.CLIENT_TRANSIENT_ERROR),
            )
            setNextAgeSignalsAccessResult(
                AgeSignalsAccessResult.builder()
                    .setAgeSignalsStatus(AgeSignalsStatus.SHARED)
                    .build(),
            )
        }

        val delays = mutableListOf<Long>()
        var result: AgeComplianceResult? = null
        createCompliance(manager, delays).requestAndCheckInternal(
            isHostActive = { true },
            accessTaskProvider = { it.requestAgeSignalsAccess(dummyAccessRequest()) },
            onResult = { result = it },
        )

        assertEquals(listOf(500L, 1000L), delays)
        assertEquals(AgeComplianceCategory.UNKNOWN, result?.category)
        assertEquals(AgeSignalsAccessOutcome.ERROR, result?.accessOutcome)
        assertEquals(AgeSignalsErrorCode.CLIENT_TRANSIENT_ERROR, result?.errorCode)
    }

    @Test
    fun deviceNotSupportedError_doesNotRetry() {
        val manager = FakeAgeSignalsManager().apply {
            setNextRequestAgeSignalsAccessException(
                AgeSignalsException(AgeSignalsErrorCode.DEVICE_NOT_SUPPORTED),
            )
        }

        val delays = mutableListOf<Long>()
        var result: AgeComplianceResult? = null
        createCompliance(manager, delays).requestAndCheckInternal(
            isHostActive = { true },
            accessTaskProvider = { it.requestAgeSignalsAccess(dummyAccessRequest()) },
            onResult = { result = it },
        )

        assertTrue(delays.isEmpty())
        assertEquals(AgeComplianceCategory.UNKNOWN, result?.category)
        assertEquals(AgeSignalsAccessOutcome.ERROR, result?.accessOutcome)
        assertEquals(AgeSignalsErrorCode.DEVICE_NOT_SUPPORTED, result?.errorCode)
    }

    @Test
    fun checkApplicableAgeFeatures_returnsMandatorySharingFeature() {
        val manager = FakeAgeSignalsManager().apply {
            setNextApplicableAgeFeaturesResult(
                ApplicableAgeFeaturesResult.builder()
                    .setFeatures(setOf(ApplicableAgeFeature.MANDATORY_SHARING))
                    .build(),
            )
        }

        var features: Set<Int> = emptySet()
        createCompliance(manager).checkApplicableAgeFeatures { features = it }

        assertEquals(setOf(ApplicableAgeFeature.MANDATORY_SHARING), features)
    }

    @Test
    fun classifyAgeRange_handlesDefaultAndCustomBands() {
        val compliance = createCompliance(FakeAgeSignalsManager())

        assertEquals(AgeComplianceCategory.UNKNOWN, compliance.classifyAgeRange(null, null))
        assertEquals(AgeComplianceCategory.ADULT, compliance.classifyAgeRange(18, null))
        assertEquals(AgeComplianceCategory.ADULT, compliance.classifyAgeRange(21, 25))
        assertEquals(AgeComplianceCategory.MINOR, compliance.classifyAgeRange(0, 12))
        assertEquals(AgeComplianceCategory.MINOR, compliance.classifyAgeRange(13, 17))
        assertEquals(AgeComplianceCategory.UNKNOWN, compliance.classifyAgeRange(15, 20))
    }
}
