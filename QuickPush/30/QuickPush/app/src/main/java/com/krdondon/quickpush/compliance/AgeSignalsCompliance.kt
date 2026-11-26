package com.krdondon.quickpush.compliance

import android.app.Activity
import android.util.Log
import com.google.android.play.agesignals.AgeSignalsAccessRequest
import com.google.android.play.agesignals.AgeSignalsException
import com.google.android.play.agesignals.AgeSignalsManager
import com.google.android.play.agesignals.AgeSignalsManagerFactory
import com.google.android.play.agesignals.AgeSignalsRequest
import com.google.android.play.agesignals.AgeSignalsResult
import com.google.android.play.agesignals.ApplicableAgeFeaturesRequest
import com.google.android.play.agesignals.model.AgeRangeSource
import com.google.android.play.agesignals.model.AgeSignalsErrorCode
import com.google.android.play.agesignals.model.AgeSignalsStatus
import com.google.android.play.agesignals.model.ApplicableAgeFeature
import com.google.android.play.agesignals.model.SignificantChangeStatus
import com.krdondon.quickpush.util.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Date

/**
 * Internal classification of user's age category based on Play Age Signals API.
 */
enum class UserAgeCategory {
  MINOR,
  ADULT,
  UNKNOWN
}

/**
 * In-memory state for Play Age Signals API (0.0.6) compliance.
 * Never persisted to disk or databases.
 */
data class AgeSignalsState(
  val ageCategory: UserAgeCategory = UserAgeCategory.UNKNOWN,
  val accessStatus: Int? = null,
  val ageRangeSource: Int? = null,
  val rawAgeLower: Int? = null,
  val rawAgeUpper: Int? = null,
  val significantChangeStatus: Int? = null,
  val significantChangeApprovalDate: Date? = null,
  val installId: String? = null,
  val isMandatorySharingApplicable: Boolean? = null,
  val requiresPlayStoreVerification: Boolean = false,
  val isChecking: Boolean = false,
  val errorCode: Int? = null,
  val errorMessage: String? = null,
)

/**
 * Handles Google Play Age Signals API (0.0.6) compliance workflow:
 * 1. Optionally checks applicable age features (e.g., MANDATORY_SHARING).
 * 2. Calls requestAgeSignalsAccess(activity) to check user/parent sharing or jurisdiction requirement.
 * 3. Calls checkAgeSignals() when AgeSignalsStatus.SHARED is returned.
 */
class AgeSignalsCompliance(
  private val activity: Activity,
  private val ageSignalsManager: AgeSignalsManager =
    AgeSignalsManagerFactory.create(activity.applicationContext),
) {

  private val _state = MutableStateFlow(AgeSignalsState())
  val state: StateFlow<AgeSignalsState> = _state.asStateFlow()

  private var hasCompletedCheck = false

  /**
   * Initiates the Play Age Signals 0.0.6 verification workflow.
   */
  fun checkAgeSignals(onResult: ((UserAgeCategory) -> Unit)? = null) {
    if (_state.value.isChecking) return

    _state.value = _state.value.copy(
      isChecking = true,
      errorCode = null,
      errorMessage = null,
    )

    // Query applicable age features (0.0.5+/0.0.6) without blocking the access request if unsupported
    queryApplicableAgeFeatures()

    val accessRequest = AgeSignalsAccessRequest.builder()
      .setActivity(activity)
      .build()

    ageSignalsManager.requestAgeSignalsAccess(accessRequest)
      .addOnSuccessListener { accessResult ->
        val status = accessResult.ageSignalsStatus()
        Log.d(TAG, "requestAgeSignalsAccess status: ${formatAccessStatus(status)}")

        when (status) {
          AgeSignalsStatus.SHARED -> {
            retrieveAgeSignals(status, onResult)
          }
          AgeSignalsStatus.NOT_SHARED -> {
            hasCompletedCheck = true
            resetToUnknownState(
              accessStatus = status,
              requiresVerification = false,
              onResult = onResult,
            )
            AppLogger.updateAgeSignalsSummary("NOT_SHARED")
            AppLogger.logAgeSignals("NOT_SHARED (user/parent opted out)")
          }
          AgeSignalsStatus.VERIFICATION_REQUIRED -> {
            hasCompletedCheck = true
            resetToUnknownState(
              accessStatus = status,
              requiresVerification = true,
              onResult = onResult,
            )
            AppLogger.updateAgeSignalsSummary("VERIFICATION_REQUIRED")
            AppLogger.logAgeSignals("VERIFICATION_REQUIRED in jurisdiction", isWarning = true)
          }
          else -> {
            hasCompletedCheck = true
            resetToUnknownState(
              accessStatus = status,
              requiresVerification = false,
              onResult = onResult,
            )
            AppLogger.updateAgeSignalsSummary(formatAccessStatus(status))
          }
        }
      }
      .addOnFailureListener { exception ->
        handleFailure("requestAgeSignalsAccess", exception, onResult)
      }
  }

  /**
   * Re-checks age signals when returning to the app ONLY if Play Store verification was required.
   * Avoids repeatedly prompting users who explicitly chose NOT_SHARED.
   */
  fun refreshIfVerificationRequired(onResult: ((UserAgeCategory) -> Unit)? = null) {
    if (hasCompletedCheck && _state.value.requiresPlayStoreVerification) {
      checkAgeSignals(onResult)
    }
  }

  private fun queryApplicableAgeFeatures() {
    try {
      val request = ApplicableAgeFeaturesRequest.builder().build()
      ageSignalsManager.getApplicableAgeFeatures(request)
        .addOnSuccessListener { featuresResult ->
          val mandatory = featuresResult.features().contains(ApplicableAgeFeature.MANDATORY_SHARING)
          _state.value = _state.value.copy(isMandatorySharingApplicable = mandatory)
        }
        .addOnFailureListener { exception ->
          // Non-fatal: older Play Store builds or test environments may not support this call
          Log.d(TAG, "getApplicableAgeFeatures skipped: ${exception.message}")
        }
    } catch (e: Exception) {
      Log.d(TAG, "getApplicableAgeFeatures unavailable: ${e.message}")
    }
  }

  private fun retrieveAgeSignals(
    accessStatus: Int?,
    onResult: ((UserAgeCategory) -> Unit)?,
  ) {
    val request = AgeSignalsRequest.builder().build()

    ageSignalsManager.checkAgeSignals(request)
      .addOnSuccessListener { result: AgeSignalsResult ->
        hasCompletedCheck = true
        val lower = result.ageLower()
        val upper = result.ageUpper()
        val source = result.ageRangeSource()
        val sigStatus = result.significantChangeStatus()
        val sigDate = result.significantChangeApprovalDate()
        val installId = result.installId()
        val category = determineCategory(lower, upper)

        val summary = "SHARED/$category (${formatAgeRangeSource(source)})"
        Log.d(TAG, "checkAgeSignals success: lower=$lower, upper=$upper -> $summary")
        AppLogger.updateAgeSignalsSummary(summary)
        AppLogger.logAgeSignals(
          buildString {
            append("$summary range=[${lower ?: "?"}..${upper ?: "+"}]")
            if (sigStatus != null && sigStatus != SignificantChangeStatus.UNSPECIFIED) {
              append(" sigChange=${formatSignificantChangeStatus(sigStatus)}")
            }
          }
        )

        _state.value = _state.value.copy(
          ageCategory = category,
          accessStatus = accessStatus,
          ageRangeSource = source,
          rawAgeLower = lower,
          rawAgeUpper = upper,
          significantChangeStatus = sigStatus,
          significantChangeApprovalDate = sigDate,
          installId = installId,
          requiresPlayStoreVerification = false,
          isChecking = false,
          errorCode = null,
          errorMessage = null,
        )
        onResult?.invoke(category)
      }
      .addOnFailureListener { exception ->
        handleFailure("checkAgeSignals", exception, onResult)
      }
  }

  private fun handleFailure(
    step: String,
    exception: Exception,
    onResult: ((UserAgeCategory) -> Unit)?,
  ) {
    hasCompletedCheck = true
    val code = (exception as? AgeSignalsException)?.errorCode
    val codeLabel = formatErrorCode(code)
    Log.w(TAG, "Failed $step ($codeLabel): ${exception.message}", exception)
    AppLogger.updateAgeSignalsSummary("ERROR:$codeLabel")
    AppLogger.logAgeSignals("$step failed ($codeLabel)", isWarning = true)

    _state.value = _state.value.copy(
      isChecking = false,
      ageCategory = UserAgeCategory.UNKNOWN,
      errorCode = code,
      errorMessage = exception.message ?: codeLabel,
    )
    onResult?.invoke(UserAgeCategory.UNKNOWN)
  }

  private fun resetToUnknownState(
    accessStatus: Int?,
    requiresVerification: Boolean,
    onResult: ((UserAgeCategory) -> Unit)?,
  ) {
    _state.value = _state.value.copy(
      ageCategory = UserAgeCategory.UNKNOWN,
      accessStatus = accessStatus,
      ageRangeSource = null,
      rawAgeLower = null,
      rawAgeUpper = null,
      significantChangeStatus = null,
      significantChangeApprovalDate = null,
      installId = null,
      requiresPlayStoreVerification = requiresVerification,
      isChecking = false,
      errorCode = null,
      errorMessage = null,
    )
    onResult?.invoke(UserAgeCategory.UNKNOWN)
  }

  private fun determineCategory(ageLower: Int?, ageUpper: Int?): UserAgeCategory {
    return when {
      (ageUpper != null) && (ageUpper < 18) -> UserAgeCategory.MINOR
      (ageLower != null) && (ageLower >= 18) -> UserAgeCategory.ADULT
      ageLower != null -> UserAgeCategory.MINOR
      else -> UserAgeCategory.UNKNOWN
    }
  }

  companion object {
    private const val TAG = "AgeSignalsCompliance"

    fun formatAccessStatus(status: Int?): String = when (status) {
      AgeSignalsStatus.SHARED -> "SHARED"
      AgeSignalsStatus.NOT_SHARED -> "NOT_SHARED"
      AgeSignalsStatus.VERIFICATION_REQUIRED -> "VERIFICATION_REQUIRED"
      AgeSignalsStatus.UNSPECIFIED -> "UNSPECIFIED"
      else -> "UNKNOWN($status)"
    }

    fun formatAgeRangeSource(source: Int?): String = when (source) {
      AgeRangeSource.TIER_A -> "TIER_A"
      AgeRangeSource.TIER_B -> "TIER_B"
      AgeRangeSource.TIER_C -> "TIER_C"
      AgeRangeSource.TIER_D -> "TIER_D"
      AgeRangeSource.UNSPECIFIED -> "UNSPECIFIED"
      null -> "NONE"
      else -> "UNKNOWN($source)"
    }

    fun formatSignificantChangeStatus(status: Int?): String = when (status) {
      SignificantChangeStatus.APPROVED -> "APPROVED"
      SignificantChangeStatus.PENDING -> "PENDING"
      SignificantChangeStatus.DECLINED -> "DECLINED"
      SignificantChangeStatus.UNSPECIFIED -> "UNSPECIFIED"
      null -> "NONE"
      else -> "UNKNOWN($status)"
    }

    fun formatErrorCode(code: Int?): String = when (code) {
      AgeSignalsErrorCode.NO_ERROR -> "NO_ERROR"
      AgeSignalsErrorCode.API_NOT_AVAILABLE -> "API_NOT_AVAILABLE"
      AgeSignalsErrorCode.PLAY_STORE_NOT_FOUND -> "PLAY_STORE_NOT_FOUND"
      AgeSignalsErrorCode.NETWORK_ERROR -> "NETWORK_ERROR"
      AgeSignalsErrorCode.PLAY_SERVICES_NOT_FOUND -> "PLAY_SERVICES_NOT_FOUND"
      AgeSignalsErrorCode.CANNOT_BIND_TO_SERVICE -> "CANNOT_BIND_TO_SERVICE"
      AgeSignalsErrorCode.PLAY_STORE_VERSION_OUTDATED -> "PLAY_STORE_VERSION_OUTDATED"
      AgeSignalsErrorCode.PLAY_SERVICES_VERSION_OUTDATED -> "PLAY_SERVICES_VERSION_OUTDATED"
      AgeSignalsErrorCode.CLIENT_TRANSIENT_ERROR -> "CLIENT_TRANSIENT_ERROR"
      AgeSignalsErrorCode.APP_NOT_OWNED -> "APP_NOT_OWNED"
      AgeSignalsErrorCode.SDK_VERSION_OUTDATED -> "SDK_VERSION_OUTDATED"
      AgeSignalsErrorCode.DEVICE_NOT_SUPPORTED -> "DEVICE_NOT_SUPPORTED"
      AgeSignalsErrorCode.INTERNAL_ERROR -> "INTERNAL_ERROR"
      null -> "UNKNOWN"
      else -> "CODE_$code"
    }
  }
}
