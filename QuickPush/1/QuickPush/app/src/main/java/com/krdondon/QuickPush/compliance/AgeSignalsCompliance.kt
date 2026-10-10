package com.krdondon.QuickPush.compliance

import android.app.Activity
import android.util.Log
import com.google.android.play.agesignals.AgeSignalsAccessRequest
import com.google.android.play.agesignals.AgeSignalsManager
import com.google.android.play.agesignals.AgeSignalsManagerFactory
import com.google.android.play.agesignals.AgeSignalsRequest
import com.google.android.play.agesignals.AgeSignalsResult
import com.google.android.play.agesignals.model.AgeSignalsStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Internal classification of user's age category based on Play Age Signals API.
 */
enum class UserAgeCategory {
  MINOR,
  ADULT,
  UNKNOWN
}

/**
 * In-memory state for age signals compliance (never persisted to disk/DB).
 */
data class AgeSignalsState(
  val ageCategory: UserAgeCategory = UserAgeCategory.UNKNOWN,
  val rawAgeLower: Int? = null,
  val rawAgeUpper: Int? = null,
  val isChecking: Boolean = false,
  val errorMessage: String? = null
)

/**
 * Handles Google Play Age Signals API (0.0.4) compliance flow.
 */
class AgeSignalsCompliance(private val activity: Activity) {

  private val ageSignalsManager: AgeSignalsManager =
    AgeSignalsManagerFactory.create(activity.applicationContext)

  private val _state = MutableStateFlow(AgeSignalsState())
  val state: StateFlow<AgeSignalsState> = _state.asStateFlow()

  /**
   * Initiates the official two-step Age Signals verification workflow:
   * 1. requestAgeSignalsAccess(activity)
   * 2. if SHARED -> checkAgeSignals()
   */
  fun checkAgeSignals(onResult: ((UserAgeCategory) -> Unit)? = null) {
    if (_state.value.isChecking) return

    _state.value = _state.value.copy(isChecking = true, errorMessage = null)

    val accessRequest = AgeSignalsAccessRequest.builder()
      .setActivity(activity)
      .build()

    ageSignalsManager.requestAgeSignalsAccess(accessRequest)
      .addOnSuccessListener { accessResult ->
        val status = accessResult.ageSignalsStatus()
        Log.d(TAG, "requestAgeSignalsAccess status: $status")

        when (status) {
          AgeSignalsStatus.SHARED -> {
            retrieveAgeSignals(onResult)
          }
          AgeSignalsStatus.NOT_SHARED -> {
            Log.d(TAG, "Age signals NOT_SHARED by user or parent.")
            updateCategory(UserAgeCategory.UNKNOWN, null, null, onResult)
          }
          AgeSignalsStatus.VERIFICATION_REQUIRED -> {
            Log.d(TAG, "Age signals VERIFICATION_REQUIRED in applicable jurisdiction.")
            updateCategory(UserAgeCategory.UNKNOWN, null, null, onResult)
          }
          else -> {
            updateCategory(UserAgeCategory.UNKNOWN, null, null, onResult)
          }
        }
      }
      .addOnFailureListener { exception ->
        Log.w(TAG, "Failed requestAgeSignalsAccess: ${exception.message}", exception)
        _state.value = _state.value.copy(
          isChecking = false,
          ageCategory = UserAgeCategory.UNKNOWN,
          errorMessage = exception.message
        )
        onResult?.invoke(UserAgeCategory.UNKNOWN)
      }
  }

  private fun retrieveAgeSignals(onResult: ((UserAgeCategory) -> Unit)?) {
    val request = AgeSignalsRequest.builder().build()

    ageSignalsManager.checkAgeSignals(request)
      .addOnSuccessListener { result: AgeSignalsResult ->
        val lower = result.ageLower()
        val upper = result.ageUpper()
        val category = determineCategory(lower, upper)

        Log.d(TAG, "checkAgeSignals success: lower=$lower, upper=$upper -> category=$category")
        updateCategory(category, lower, upper, onResult)
      }
      .addOnFailureListener { exception ->
        Log.w(TAG, "Failed checkAgeSignals: ${exception.message}", exception)
        _state.value = _state.value.copy(
          isChecking = false,
          ageCategory = UserAgeCategory.UNKNOWN,
          errorMessage = exception.message
        )
        onResult?.invoke(UserAgeCategory.UNKNOWN)
      }
  }

  private fun determineCategory(ageLower: Int?, ageUpper: Int?): UserAgeCategory {
    return when {
      // If upper bound exists and is < 18, user is definitely minor
      ageUpper != null && ageUpper < 18 -> UserAgeCategory.MINOR
      // If lower bound is >= 18, user is definitely adult
      ageLower != null && ageLower >= 18 -> UserAgeCategory.ADULT
      // If lower is less than 18 and upper is null or >= 18, or bounds unavailable
      ageLower != null && ageLower < 18 -> UserAgeCategory.MINOR
      else -> UserAgeCategory.UNKNOWN
    }
  }

  private fun updateCategory(
    category: UserAgeCategory,
    lower: Int?,
    upper: Int?,
    callback: ((UserAgeCategory) -> Unit)?
  ) {
    _state.value = AgeSignalsState(
      ageCategory = category,
      rawAgeLower = lower,
      rawAgeUpper = upper,
      isChecking = false,
      errorMessage = null
    )
    callback?.invoke(category)
  }

  companion object {
    private const val TAG = "AgeSignalsCompliance"
  }
}
