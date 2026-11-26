package com.krdondon.read.compliance

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgeSignalsComplianceTest {

    private lateinit var activity: Activity
    private lateinit var fakeManager: FakeAgeSignalsManager

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).create().get()
        fakeManager = FakeAgeSignalsManager()
    }

    @Test
    fun `shared verified adult 18+ returns ADULT`() {
        fakeManager.setNextAgeSignalsAccessResult(
            AgeSignalsAccessResult.builder()
                .setAgeSignalsStatus(AgeSignalsStatus.SHARED)
                .build()
        )
        fakeManager.setNextAgeSignalsResult(
            AgeSignalsResult.builder()
                .setAgeRangeSource(AgeRangeSource.TIER_D)
                .setAgeLower(18)
                .build()
        )

        val compliance = createCompliance()
        var callbackCategory: UserAgeCategory? = null
        compliance.checkAgeSignals(activity) { callbackCategory = it }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(UserAgeCategory.ADULT, callbackCategory)
        val state = compliance.ageState.value
        assertEquals(UserAgeCategory.ADULT, state.category)
        assertEquals(AgeSignalsStatus.SHARED, state.accessStatus)
        assertEquals(18, state.ageLower)
        assertNull(state.ageUpper)
        assertEquals(AgeRangeSource.TIER_D, state.ageRangeSource)
        assertFalse(state.isVerificationRequired)
        assertNull(state.errorCode)
    }

    @Test
    fun `shared supervised minor returns MINOR with metadata`() {
        val approvalDate = Date(1777939200000L)
        fakeManager.setNextAgeSignalsAccessResult(
            AgeSignalsAccessResult.builder()
                .setAgeSignalsStatus(AgeSignalsStatus.SHARED)
                .build()
        )
        fakeManager.setNextAgeSignalsResult(
            AgeSignalsResult.builder()
                .setAgeRangeSource(AgeRangeSource.TIER_B)
                .setAgeLower(13)
                .setAgeUpper(15)
                .setSignificantChangeStatus(SignificantChangeStatus.APPROVED)
                .setSignificantChangeApprovalDate(approvalDate)
                .setInstallId("fake_install_id_123")
                .build()
        )

        val compliance = createCompliance()
        var callbackCategory: UserAgeCategory? = null
        compliance.checkAgeSignals(activity) { callbackCategory = it }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(UserAgeCategory.MINOR, callbackCategory)
        val state = compliance.ageState.value
        assertEquals(UserAgeCategory.MINOR, state.category)
        assertEquals(13, state.ageLower)
        assertEquals(15, state.ageUpper)
        assertEquals(AgeRangeSource.TIER_B, state.ageRangeSource)
        assertEquals(SignificantChangeStatus.APPROVED, state.significantChangeStatus)
        assertEquals(approvalDate, state.significantChangeApprovalDate)
        assertEquals("fake_install_id_123", state.installId)
    }

    @Test
    fun `shared with null age bounds returns UNKNOWN`() {
        fakeManager.setNextAgeSignalsAccessResult(
            AgeSignalsAccessResult.builder()
                .setAgeSignalsStatus(AgeSignalsStatus.SHARED)
                .build()
        )
        fakeManager.setNextAgeSignalsResult(
            AgeSignalsResult.builder().build()
        )

        val compliance = createCompliance()
        var callbackCategory: UserAgeCategory? = null
        compliance.checkAgeSignals(activity) { callbackCategory = it }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(UserAgeCategory.UNKNOWN, callbackCategory)
        assertEquals(UserAgeCategory.UNKNOWN, compliance.ageState.value.category)
    }

    @Test
    fun `custom open-ended age band below 18 returns UNKNOWN`() {
        val compliance = createCompliance()
        assertEquals(UserAgeCategory.UNKNOWN, compliance.determineCategory(ageLower = 15, ageUpper = null))
    }

    @Test
    fun `not shared access status returns UNKNOWN`() {
        fakeManager.setNextAgeSignalsAccessResult(
            AgeSignalsAccessResult.builder()
                .setAgeSignalsStatus(AgeSignalsStatus.NOT_SHARED)
                .build()
        )

        val compliance = createCompliance()
        var callbackCategory: UserAgeCategory? = null
        compliance.checkAgeSignals(activity) { callbackCategory = it }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(UserAgeCategory.UNKNOWN, callbackCategory)
        val state = compliance.ageState.value
        assertEquals(AgeSignalsStatus.NOT_SHARED, state.accessStatus)
        assertFalse(state.isVerificationRequired)
    }

    @Test
    fun `verification required sets isVerificationRequired flag`() {
        fakeManager.setNextAgeSignalsAccessResult(
            AgeSignalsAccessResult.builder()
                .setAgeSignalsStatus(AgeSignalsStatus.VERIFICATION_REQUIRED)
                .build()
        )

        val compliance = createCompliance()
        var callbackCategory: UserAgeCategory? = null
        compliance.checkAgeSignals(activity) { callbackCategory = it }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(UserAgeCategory.UNKNOWN, callbackCategory)
        val state = compliance.ageState.value
        assertEquals(AgeSignalsStatus.VERIFICATION_REQUIRED, state.accessStatus)
        assertTrue(state.isVerificationRequired)
    }

    @Test
    fun `checkApplicableAgeFeatures detects MANDATORY_SHARING`() {
        fakeManager.setNextApplicableAgeFeaturesResult(
            ApplicableAgeFeaturesResult.builder()
                .setFeatures(setOf(ApplicableAgeFeature.MANDATORY_SHARING))
                .build()
        )

        val compliance = createCompliance()
        var mandatoryResult: Boolean? = null
        compliance.checkApplicableAgeFeatures { mandatoryResult = it }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(true, mandatoryResult)
        assertEquals(true, compliance.ageState.value.isMandatorySharingApplicable)
    }

    @Test
    fun `transient error retries up to maxRetries then reports errorCode`() {
        fakeManager.setNextRequestAgeSignalsAccessException(
            AgeSignalsException(AgeSignalsErrorCode.CLIENT_TRANSIENT_ERROR)
        )

        var retryCount = 0
        val compliance = AgeSignalsCompliance(
            ageSignalsManager = fakeManager,
            retryScheduler = { _, block ->
                retryCount++
                block()
            },
            maxRetries = 2,
            initialRetryDelayMs = 10L
        )

        var callbackCategory: UserAgeCategory? = null
        compliance.checkAgeSignals(activity) { callbackCategory = it }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(2, retryCount)
        assertEquals(UserAgeCategory.UNKNOWN, callbackCategory)
        assertEquals(AgeSignalsErrorCode.CLIENT_TRANSIENT_ERROR, compliance.ageState.value.errorCode)
    }

    @Test
    fun `non-retryable error does not retry and records errorCode`() {
        fakeManager.setNextRequestAgeSignalsAccessException(
            AgeSignalsException(AgeSignalsErrorCode.DEVICE_NOT_SUPPORTED)
        )

        var retryCount = 0
        val compliance = AgeSignalsCompliance(
            ageSignalsManager = fakeManager,
            retryScheduler = { _, block ->
                retryCount++
                block()
            },
            maxRetries = 2,
            initialRetryDelayMs = 10L
        )

        var callbackCategory: UserAgeCategory? = null
        compliance.checkAgeSignals(activity) { callbackCategory = it }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, retryCount)
        assertEquals(UserAgeCategory.UNKNOWN, callbackCategory)
        assertEquals(AgeSignalsErrorCode.DEVICE_NOT_SUPPORTED, compliance.ageState.value.errorCode)
    }

    private fun createCompliance(): AgeSignalsCompliance {
        return AgeSignalsCompliance(
            ageSignalsManager = fakeManager,
            retryScheduler = { _, block -> block() },
            maxRetries = 2,
            initialRetryDelayMs = 10L
        )
    }
}
