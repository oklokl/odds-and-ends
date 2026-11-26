package com.krdondon.quickpush

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
import com.krdondon.quickpush.compliance.AgeSignalsCompliance
import com.krdondon.quickpush.compliance.UserAgeCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgeSignalsComplianceTest {

  private fun createActivity(): Activity {
    return Robolectric.buildActivity(Activity::class.java).setup().get()
  }

  private fun drainMainLooper() {
    shadowOf(Looper.getMainLooper()).idle()
  }

  @Test
  fun `shared verified adult returns ADULT category`() {
    val activity = createActivity()
    val fakeManager = FakeAgeSignalsManager().apply {
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
          .setAgeRangeSource(AgeRangeSource.TIER_D)
          .setAgeLower(18)
          .build()
      )
    }

    val compliance = AgeSignalsCompliance(activity, fakeManager)
    var callbackCategory: UserAgeCategory? = null
    compliance.checkAgeSignals { callbackCategory = it }
    drainMainLooper()

    val state = compliance.state.value
    assertEquals(UserAgeCategory.ADULT, callbackCategory)
    assertEquals(UserAgeCategory.ADULT, state.ageCategory)
    assertEquals(AgeSignalsStatus.SHARED, state.accessStatus)
    assertEquals(AgeRangeSource.TIER_D, state.ageRangeSource)
    assertEquals(18, state.rawAgeLower)
    assertNull(state.rawAgeUpper)
    assertEquals(true, state.isMandatorySharingApplicable)
    assertFalse(state.requiresPlayStoreVerification)
    assertFalse(state.isChecking)
    assertNull(state.errorCode)
  }

  @Test
  fun `shared supervised minor with pending significant change returns MINOR category`() {
    val activity = createActivity()
    val approvalDate = Date(1777939200000L)
    val fakeManager = FakeAgeSignalsManager().apply {
      setNextApplicableAgeFeaturesResult(
        ApplicableAgeFeaturesResult.builder()
          .setFeatures(emptySet())
          .build()
      )
      setNextAgeSignalsAccessResult(
        AgeSignalsAccessResult.builder()
          .setAgeSignalsStatus(AgeSignalsStatus.SHARED)
          .build()
      )
      setNextAgeSignalsResult(
        AgeSignalsResult.builder()
          .setAgeRangeSource(AgeRangeSource.TIER_B)
          .setAgeLower(13)
          .setAgeUpper(15)
          .setSignificantChangeStatus(SignificantChangeStatus.PENDING)
          .setSignificantChangeApprovalDate(approvalDate)
          .setInstallId("fake_install_id_123")
          .build()
      )
    }

    val compliance = AgeSignalsCompliance(activity, fakeManager)
    compliance.checkAgeSignals()
    drainMainLooper()

    val state = compliance.state.value
    assertEquals(UserAgeCategory.MINOR, state.ageCategory)
    assertEquals(AgeRangeSource.TIER_B, state.ageRangeSource)
    assertEquals(13, state.rawAgeLower)
    assertEquals(15, state.rawAgeUpper)
    assertEquals(SignificantChangeStatus.PENDING, state.significantChangeStatus)
    assertEquals(approvalDate, state.significantChangeApprovalDate)
    assertEquals("fake_install_id_123", state.installId)
    assertFalse(state.requiresPlayStoreVerification)
  }

  @Test
  fun `not shared returns UNKNOWN without verification prompt`() {
    val activity = createActivity()
    val fakeManager = FakeAgeSignalsManager().apply {
      setNextAgeSignalsAccessResult(
        AgeSignalsAccessResult.builder()
          .setAgeSignalsStatus(AgeSignalsStatus.NOT_SHARED)
          .build()
      )
    }

    val compliance = AgeSignalsCompliance(activity, fakeManager)
    compliance.checkAgeSignals()
    drainMainLooper()

    val state = compliance.state.value
    assertEquals(UserAgeCategory.UNKNOWN, state.ageCategory)
    assertEquals(AgeSignalsStatus.NOT_SHARED, state.accessStatus)
    assertFalse(state.requiresPlayStoreVerification)
    assertFalse(state.isChecking)
  }

  @Test
  fun `verification required flags requiresPlayStoreVerification and refreshes on retry`() {
    val activity = createActivity()
    val fakeManager = FakeAgeSignalsManager().apply {
      setNextAgeSignalsAccessResult(
        AgeSignalsAccessResult.builder()
          .setAgeSignalsStatus(AgeSignalsStatus.VERIFICATION_REQUIRED)
          .build()
      )
    }

    val compliance = AgeSignalsCompliance(activity, fakeManager)
    compliance.checkAgeSignals()
    drainMainLooper()

    assertEquals(UserAgeCategory.UNKNOWN, compliance.state.value.ageCategory)
    assertTrue(compliance.state.value.requiresPlayStoreVerification)

    // Simulate user completing verification in Play Store and returning to app
    fakeManager.setNextAgeSignalsAccessResult(
      AgeSignalsAccessResult.builder()
        .setAgeSignalsStatus(AgeSignalsStatus.SHARED)
        .build()
    )
    fakeManager.setNextAgeSignalsResult(
      AgeSignalsResult.builder()
        .setAgeRangeSource(AgeRangeSource.TIER_C)
        .setAgeLower(18)
        .build()
    )

    compliance.refreshIfVerificationRequired()
    drainMainLooper()

    assertEquals(UserAgeCategory.ADULT, compliance.state.value.ageCategory)
    assertFalse(compliance.state.value.requiresPlayStoreVerification)
  }

  @Test
  fun `api exception captures AgeSignalsErrorCode safely`() {
    val activity = createActivity()
    val fakeManager = FakeAgeSignalsManager().apply {
      setNextRequestAgeSignalsAccessException(
        AgeSignalsException(AgeSignalsErrorCode.NETWORK_ERROR)
      )
    }

    val compliance = AgeSignalsCompliance(activity, fakeManager)
    compliance.checkAgeSignals()
    drainMainLooper()

    val state = compliance.state.value
    assertEquals(UserAgeCategory.UNKNOWN, state.ageCategory)
    assertEquals(AgeSignalsErrorCode.NETWORK_ERROR, state.errorCode)
    assertFalse(state.isChecking)
  }
}
