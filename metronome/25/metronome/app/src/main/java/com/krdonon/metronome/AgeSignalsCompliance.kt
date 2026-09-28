package com.krdonon.metronome

import android.app.Activity
import android.content.Context
import com.google.android.play.agesignals.AgeSignalsAccessRequest
import com.google.android.play.agesignals.AgeSignalsManager
import com.google.android.play.agesignals.AgeSignalsManagerFactory
import com.google.android.play.agesignals.AgeSignalsRequest
import com.google.android.play.agesignals.model.AgeSignalsStatus

/**
 * Runtime-only wrapper for Google Play Age Signals API 0.0.4.
 *
 * Age Signals data must only be used to provide age-appropriate experiences and
 * comply with applicable law. Do not use it for advertising, marketing,
 * profiling, or analytics, and do not persist the returned age information.
 */
class AgeSignalsCompliance(context: Context) {

    enum class AgeCategory {
        MINOR,
        ADULT,
        UNKNOWN
    }

    private val ageSignalsManager: AgeSignalsManager? =
        runCatching { AgeSignalsManagerFactory.create(context.applicationContext) }.getOrNull()

    /**
     * Uses the 0.0.4 two-function flow:
     * requestAgeSignalsAccess() -> checkAgeSignals() only when status is SHARED.
     */
    fun requestAgeCategory(
        activity: Activity,
        onResult: (AgeCategory) -> Unit
    ) {
        val manager = ageSignalsManager
        if (manager == null) {
            onResult(AgeCategory.UNKNOWN)
            return
        }

        val accessRequest = AgeSignalsAccessRequest.builder()
            .setActivity(activity)
            .build()

        manager.requestAgeSignalsAccess(accessRequest)
            .addOnSuccessListener { accessResult ->
                if (accessResult.ageSignalsStatus() == AgeSignalsStatus.SHARED) {
                    checkAgeSignals(manager, onResult)
                } else {
                    // NOT_SHARED and VERIFICATION_REQUIRED are intentionally
                    // treated as UNKNOWN. Do not infer an age when Play does not
                    // provide a usable age range.
                    onResult(AgeCategory.UNKNOWN)
                }
            }
            .addOnFailureListener {
                // API/Play Store/network/system errors fail closed to UNKNOWN.
                onResult(AgeCategory.UNKNOWN)
            }
    }

    private fun checkAgeSignals(
        manager: AgeSignalsManager,
        onResult: (AgeCategory) -> Unit
    ) {
        val request = AgeSignalsRequest.builder().build()

        manager.checkAgeSignals(request)
            .addOnSuccessListener { result ->
                onResult(
                    classifyAgeRange(
                        ageLower = result.ageLower(),
                        ageUpper = result.ageUpper()
                    )
                )
            }
            .addOnFailureListener {
                onResult(AgeCategory.UNKNOWN)
            }
    }

    companion object {
        private const val ADULT_AGE = 18

        /**
         * Classifies only ranges that are unambiguous around the 18-year boundary.
         * A custom Play Console age band that crosses 18 is therefore UNKNOWN.
         */
        internal fun classifyAgeRange(ageLower: Int?, ageUpper: Int?): AgeCategory {
            return when {
                ageLower != null && ageLower >= ADULT_AGE -> AgeCategory.ADULT
                ageLower != null && ageUpper != null && ageUpper < ADULT_AGE -> AgeCategory.MINOR
                else -> AgeCategory.UNKNOWN
            }
        }
    }
}
