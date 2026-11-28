package com.krdonon.timer

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class AgeSignalsComplianceTest {

    private val directExecutor = Executor { runnable -> runnable.run() }

    private fun stubAccessRequest(): AgeSignalsAccessRequest =
        object : AgeSignalsAccessRequest() {
            override fun activity(): Activity {
                throw UnsupportedOperationException("Not needed for FakeAgeSignalsManager")
            }
        }

    @Test
    fun `when mandatory sharing is not applicable, skips access request and remains unknown`() {
        val fake = FakeAgeSignalsManager().apply {
            setNextApplicableAgeFeaturesResult(
                ApplicableAgeFeaturesResult.builder()
                    .setFeatures(emptySet())
                    .build()
            )
        }
        val compliance = AgeSignalsCompliance(fake, directExecutor)

        compliance.refreshInternal(canProceed = { true }, createAccessRequest = ::stubAccessRequest)

        assertFalse(compliance.currentSnapshot.mandatorySharingApplicable)
        assertEquals(AgeSignalsCompliance.Classification.UNKNOWN, compliance.currentClassification)
    }

    @Test
    fun `when mandatory sharing applies and access is shared for 18+, classifies as adult`() {
        val fake = FakeAgeSignalsManager().apply {
            setNextApplicableAgeFeaturesResult(
                ApplicableAgeFeaturesResult.builder()
                    .setFeatures(setOf(ApplicableAgeFeature.MANDATORY_SHARING))
                    .build()
            )
            setNextAgeSignalsAccessResult(
                AgeSignalsAccessResult.builder()
                    .setAgeSignalsStatus(AgeSignalsStatus.SHARED)
                    .build()
            )
            setNextAgeSignalsResult(
                AgeSignalsResult.builder()
                    .setAgeLower(18)
                    .setAgeRangeSource(AgeRangeSource.TIER_A)
                    .setSignificantChangeStatus(SignificantChangeStatus.APPROVED)
                    .build()
            )
        }
        val compliance = AgeSignalsCompliance(fake, directExecutor)

        compliance.refreshInternal(canProceed = { true }, createAccessRequest = ::stubAccessRequest)

        assertTrue(compliance.currentSnapshot.mandatorySharingApplicable)
        assertEquals(AgeSignalsStatus.SHARED, compliance.currentSnapshot.accessStatus)
        assertEquals(AgeSignalsCompliance.Classification.ADULT, compliance.currentClassification)
        assertEquals(AgeRangeSource.TIER_A, compliance.currentSnapshot.ageRangeSource)
        assertEquals(SignificantChangeStatus.APPROVED, compliance.currentSnapshot.significantChangeStatus)
    }

    @Test
    fun `when mandatory sharing applies and access is shared for under 18, classifies as minor`() {
        val fake = FakeAgeSignalsManager().apply {
            setNextApplicableAgeFeaturesResult(
                ApplicableAgeFeaturesResult.builder()
                    .setFeatures(setOf(ApplicableAgeFeature.MANDATORY_SHARING))
                    .build()
            )
            setNextAgeSignalsAccessResult(
                AgeSignalsAccessResult.builder()
                    .setAgeSignalsStatus(AgeSignalsStatus.SHARED)
                    .build()
            )
            setNextAgeSignalsResult(
                AgeSignalsResult.builder()
                    .setAgeLower(13)
                    .setAgeUpper(17)
                    .setSignificantChangeStatus(SignificantChangeStatus.PENDING)
                    .build()
            )
        }
        val compliance = AgeSignalsCompliance(fake, directExecutor)

        compliance.refreshInternal(canProceed = { true }, createAccessRequest = ::stubAccessRequest)

        assertEquals(AgeSignalsCompliance.Classification.MINOR, compliance.currentClassification)
        assertEquals(SignificantChangeStatus.PENDING, compliance.currentSnapshot.significantChangeStatus)
    }

    @Test
    fun `when access is not shared or verification required, remains unknown`() {
        val fake = FakeAgeSignalsManager().apply {
            setNextApplicableAgeFeaturesResult(
                ApplicableAgeFeaturesResult.builder()
                    .setFeatures(setOf(ApplicableAgeFeature.MANDATORY_SHARING))
                    .build()
            )
            setNextAgeSignalsAccessResult(
                AgeSignalsAccessResult.builder()
                    .setAgeSignalsStatus(AgeSignalsStatus.VERIFICATION_REQUIRED)
                    .build()
            )
        }
        val compliance = AgeSignalsCompliance(fake, directExecutor)

        compliance.refreshInternal(canProceed = { true }, createAccessRequest = ::stubAccessRequest)

        assertEquals(AgeSignalsStatus.VERIFICATION_REQUIRED, compliance.currentSnapshot.accessStatus)
        assertEquals(AgeSignalsCompliance.Classification.UNKNOWN, compliance.currentClassification)
    }

    @Test
    fun `when api returns error, remains unknown and records error code in memory`() {
        val fake = FakeAgeSignalsManager().apply {
            setNextApplicableAgeFeaturesException(
                AgeSignalsException(AgeSignalsErrorCode.API_NOT_AVAILABLE)
            )
        }
        val compliance = AgeSignalsCompliance(fake, directExecutor)

        compliance.refreshInternal(canProceed = { true }, createAccessRequest = ::stubAccessRequest)

        assertEquals(AgeSignalsCompliance.Classification.UNKNOWN, compliance.currentClassification)
        assertEquals(AgeSignalsErrorCode.API_NOT_AVAILABLE, compliance.currentSnapshot.lastErrorCode)
    }

    @Test
    fun `classifyAgeRange handles open-ended and inconsistent bounds conservatively`() {
        val compliance = AgeSignalsCompliance(FakeAgeSignalsManager(), directExecutor)

        assertEquals(AgeSignalsCompliance.Classification.UNKNOWN, compliance.classifyAgeRange(null, null))
        assertEquals(AgeSignalsCompliance.Classification.UNKNOWN, compliance.classifyAgeRange(17, null))
        assertEquals(AgeSignalsCompliance.Classification.UNKNOWN, compliance.classifyAgeRange(16, 18))
        assertEquals(AgeSignalsCompliance.Classification.UNKNOWN, compliance.classifyAgeRange(18, 16))
        assertEquals(AgeSignalsCompliance.Classification.MINOR, compliance.classifyAgeRange(13, 17))
        assertEquals(AgeSignalsCompliance.Classification.ADULT, compliance.classifyAgeRange(18, 25))
        assertEquals(AgeSignalsCompliance.Classification.ADULT, compliance.classifyAgeRange(18, null))
    }
}
