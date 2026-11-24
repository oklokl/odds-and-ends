package com.krdondon.txt.compliance

import android.app.Activity
import android.content.Context
import com.google.android.play.agesignals.AgeSignalsAccessRequest
import com.google.android.play.agesignals.AgeSignalsManager
import com.google.android.play.agesignals.AgeSignalsManagerFactory
import com.google.android.play.agesignals.AgeSignalsRequest
import com.google.android.play.agesignals.model.AgeSignalsStatus

/**
 * Runtime-only wrapper for Google Play Age Signals.
 *
 * No age signal is written to SharedPreferences, files, or a database.
 * UNKNOWN is intentionally used whenever the signal is unavailable, not shared,
 * requires verification, fails, or cannot safely prove minor/adult status.
 */
enum class AgeClassification {
    MINOR,
    ADULT,
    UNKNOWN
}

class AgeSignalsCompliance(context: Context) {

    private val manager: AgeSignalsManager =
        AgeSignalsManagerFactory.create(context.applicationContext)

    /**
     * Official 0.0.4 flow:
     * requestAgeSignalsAccess(...) -> if SHARED -> checkAgeSignals(...)
     */
    fun requestClassification(
        activity: Activity,
        onResult: (AgeClassification) -> Unit
    ) {
        val accessRequest = AgeSignalsAccessRequest.builder()
            .setActivity(activity)
            .build()

        manager.requestAgeSignalsAccess(accessRequest)
            .addOnSuccessListener { accessResult ->
                when (accessResult.ageSignalsStatus()) {
                    AgeSignalsStatus.SHARED -> retrieveSharedAgeSignals(onResult)
                    AgeSignalsStatus.NOT_SHARED,
                    AgeSignalsStatus.VERIFICATION_REQUIRED -> onResult(AgeClassification.UNKNOWN)
                    else -> onResult(AgeClassification.UNKNOWN)
                }
            }
            .addOnFailureListener {
                onResult(AgeClassification.UNKNOWN)
            }
    }

    private fun retrieveSharedAgeSignals(onResult: (AgeClassification) -> Unit) {
        manager.checkAgeSignals(AgeSignalsRequest.builder().build())
            .addOnSuccessListener { result ->
                onResult(
                    classifyAgeRange(
                        ageLower = result.ageLower(),
                        ageUpper = result.ageUpper()
                    )
                )
            }
            .addOnFailureListener {
                onResult(AgeClassification.UNKNOWN)
            }
    }

    /**
     * Safely maps an age band to the app's three internal states.
     *
     * This also remains safe if custom age ranges are configured in Play Console:
     * - A band wholly below 18 is MINOR.
     * - A band whose lower bound is 18+ is ADULT.
     * - A band that could contain both minors and adults is UNKNOWN.
     */
    private fun classifyAgeRange(ageLower: Int?, ageUpper: Int?): AgeClassification {
        if (ageLower == null) return AgeClassification.UNKNOWN

        if (ageLower >= ADULT_AGE) {
            return AgeClassification.ADULT
        }

        if (ageUpper != null && ageUpper < ADULT_AGE) {
            return AgeClassification.MINOR
        }

        return AgeClassification.UNKNOWN
    }

    private companion object {
        const val ADULT_AGE = 18
    }
}
