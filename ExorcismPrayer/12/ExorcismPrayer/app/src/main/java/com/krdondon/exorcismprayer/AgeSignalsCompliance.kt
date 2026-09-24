package com.krdondon.exorcismprayer

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.play.agesignals.AgeSignalsAccessRequest
import com.google.android.play.agesignals.AgeSignalsManager
import com.google.android.play.agesignals.AgeSignalsManagerFactory
import com.google.android.play.agesignals.AgeSignalsRequest
import com.google.android.play.agesignals.model.AgeSignalsStatus

enum class AgeClassification {
    MINOR,
    ADULT,
    UNKNOWN
}

/**
 * Runtime-only wrapper around Google Play Age Signals 0.0.4.
 *
 * The result is deliberately not persisted. Callers receive only the coarse
 * MINOR / ADULT / UNKNOWN classification needed for age-appropriate behavior.
 */
class AgeSignalsCompliance(context: Context) {

    private val ageSignalsManager: AgeSignalsManager =
        AgeSignalsManagerFactory.create(context.applicationContext)

    fun requestAgeClassification(
        activity: Activity,
        onResult: (AgeClassification) -> Unit
    ) {
        val accessRequest = AgeSignalsAccessRequest.builder()
            .setActivity(activity)
            .build()

        ageSignalsManager.requestAgeSignalsAccess(accessRequest)
            .addOnSuccessListener { accessResult ->
                when (accessResult.ageSignalsStatus()) {
                    AgeSignalsStatus.SHARED -> checkAgeSignals(onResult)
                    AgeSignalsStatus.NOT_SHARED,
                    AgeSignalsStatus.VERIFICATION_REQUIRED -> onResult(AgeClassification.UNKNOWN)
                    else -> onResult(AgeClassification.UNKNOWN)
                }
            }
            .addOnFailureListener { exception ->
                Log.w(TAG, "Unable to request Age Signals access; using UNKNOWN.", exception)
                onResult(AgeClassification.UNKNOWN)
            }
    }

    private fun checkAgeSignals(onResult: (AgeClassification) -> Unit) {
        ageSignalsManager.checkAgeSignals(AgeSignalsRequest.builder().build())
            .addOnSuccessListener { result ->
                onResult(classifyAgeRange(result.ageLower(), result.ageUpper()))
            }
            .addOnFailureListener { exception ->
                Log.w(TAG, "Unable to read Age Signals; using UNKNOWN.", exception)
                onResult(AgeClassification.UNKNOWN)
            }
    }

    /**
     * Conservative classification that remains safe if Play Console custom age
     * ranges are configured. For example, an open-ended 15+ band does not prove
     * the user is an adult and is therefore UNKNOWN rather than ADULT.
     */
    internal fun classifyAgeRange(ageLower: Int?, ageUpper: Int?): AgeClassification {
        return when {
            ageLower != null && ageLower >= ADULT_AGE -> AgeClassification.ADULT
            ageUpper != null && ageUpper < ADULT_AGE -> AgeClassification.MINOR
            else -> AgeClassification.UNKNOWN
        }
    }

    private companion object {
        const val TAG = "AgeSignalsCompliance"
        const val ADULT_AGE = 18
    }
}
