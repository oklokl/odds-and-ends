package com.krdonon.microphone.compliance

import android.app.Activity
import android.content.Context
import com.google.android.play.agesignals.AgeSignalsAccessRequest
import com.google.android.play.agesignals.AgeSignalsManager
import com.google.android.play.agesignals.AgeSignalsManagerFactory
import com.google.android.play.agesignals.AgeSignalsRequest
import com.google.android.play.agesignals.model.AgeSignalsStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Runtime-only wrapper around Google Play Age Signals 0.0.4.
 *
 * Age-related values are deliberately reduced to a coarse compliance category and are not
 * written to DataStore, SharedPreferences, a database, or files.
 */
class AgeSignalsCompliance(context: Context) {

    private val ageSignalsManager: AgeSignalsManager =
        AgeSignalsManagerFactory.create(context.applicationContext)

    private val _state = MutableStateFlow(AgeSignalsComplianceState())
    val state: StateFlow<AgeSignalsComplianceState> = _state.asStateFlow()

    /**
     * Requests access first, as required by Age Signals 0.0.4. Actual age signals are queried
     * only when Google Play reports that sharing is active.
     */
    fun refresh(activity: Activity) {
        // Fail-safe default while the asynchronous request is in progress.
        _state.value = AgeSignalsComplianceState()

        val accessRequest = AgeSignalsAccessRequest.builder()
            .setActivity(activity)
            .build()

        ageSignalsManager.requestAgeSignalsAccess(accessRequest)
            .addOnSuccessListener { accessResult ->
                when (accessResult.ageSignalsStatus()) {
                    AgeSignalsStatus.SHARED -> {
                        _state.value = AgeSignalsComplianceState(
                            category = AgeCategory.UNKNOWN,
                            accessState = AgeSignalsAccessState.SHARED
                        )
                        checkAgeSignals()
                    }

                    AgeSignalsStatus.NOT_SHARED -> {
                        _state.value = AgeSignalsComplianceState(
                            category = AgeCategory.UNKNOWN,
                            accessState = AgeSignalsAccessState.NOT_SHARED
                        )
                    }

                    AgeSignalsStatus.VERIFICATION_REQUIRED -> {
                        _state.value = AgeSignalsComplianceState(
                            category = AgeCategory.UNKNOWN,
                            accessState = AgeSignalsAccessState.VERIFICATION_REQUIRED
                        )
                    }

                    else -> {
                        _state.value = AgeSignalsComplianceState(
                            category = AgeCategory.UNKNOWN,
                            accessState = AgeSignalsAccessState.UNKNOWN
                        )
                    }
                }
            }
            .addOnFailureListener {
                _state.value = AgeSignalsComplianceState(
                    category = AgeCategory.UNKNOWN,
                    accessState = AgeSignalsAccessState.ERROR
                )
            }
    }

    private fun checkAgeSignals() {
        val request = AgeSignalsRequest.builder().build()

        ageSignalsManager.checkAgeSignals(request)
            .addOnSuccessListener { result ->
                _state.value = AgeSignalsComplianceState(
                    category = classifyAgeRange(
                        ageLower = result.ageLower(),
                        ageUpper = result.ageUpper()
                    ),
                    accessState = AgeSignalsAccessState.SHARED
                )
            }
            .addOnFailureListener {
                _state.value = AgeSignalsComplianceState(
                    category = AgeCategory.UNKNOWN,
                    accessState = AgeSignalsAccessState.ERROR
                )
            }
    }

    /**
     * Classification is intentionally conservative and remains correct if Play Console custom
     * age bands are configured later:
     * - every possible age in the returned band is < 18 -> MINOR
     * - every possible age in the returned band is >= 18 -> ADULT
     * - a missing or mixed/ambiguous band -> UNKNOWN
     */
    internal fun classifyAgeRange(ageLower: Int?, ageUpper: Int?): AgeCategory {
        return when {
            ageLower != null && ageLower >= ADULT_AGE -> AgeCategory.ADULT
            ageLower != null && ageUpper != null && ageUpper < ADULT_AGE -> AgeCategory.MINOR
            else -> AgeCategory.UNKNOWN
        }
    }

    private companion object {
        const val ADULT_AGE = 18
    }
}

enum class AgeCategory {
    MINOR,
    ADULT,
    UNKNOWN
}

enum class AgeSignalsAccessState {
    SHARED,
    NOT_SHARED,
    VERIFICATION_REQUIRED,
    ERROR,
    UNKNOWN
}

data class AgeSignalsComplianceState(
    val category: AgeCategory = AgeCategory.UNKNOWN,
    val accessState: AgeSignalsAccessState = AgeSignalsAccessState.UNKNOWN
)
