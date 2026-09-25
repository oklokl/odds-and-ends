package com.krdonon.ratio

import android.app.Activity
import com.google.android.play.agesignals.AgeSignalsAccessRequest
import com.google.android.play.agesignals.AgeSignalsManager
import com.google.android.play.agesignals.AgeSignalsManagerFactory
import com.google.android.play.agesignals.AgeSignalsRequest
import com.google.android.play.agesignals.model.AgeSignalsStatus

/**
 * Runtime-only Google Play Age Signals integration.
 *
 * The result is intentionally reduced to MINOR / ADULT / UNKNOWN and is not persisted.
 * UNKNOWN is the safe fallback for declined sharing, required verification, errors, or an
 * age band that cannot prove whether the user is under 18.
 */
enum class UserAgeCategory {
    MINOR,
    ADULT,
    UNKNOWN
}

object AgeSignalsCompliance {

    fun checkUserAge(
        activity: Activity,
        onResult: (UserAgeCategory) -> Unit
    ) {
        val manager = AgeSignalsManagerFactory.create(activity.applicationContext)
        val accessRequest = AgeSignalsAccessRequest.builder()
            .setActivity(activity)
            .build()

        // Age Signals 0.0.4 requires access to be requested/checked before age signals are read.
        manager.requestAgeSignalsAccess(accessRequest)
            .addOnSuccessListener { accessResult ->
                if (accessResult.ageSignalsStatus() == AgeSignalsStatus.SHARED) {
                    checkSharedAgeSignals(manager, onResult)
                } else {
                    // NOT_SHARED and VERIFICATION_REQUIRED both reveal no usable age range.
                    onResult(UserAgeCategory.UNKNOWN)
                }
            }
            .addOnFailureListener {
                onResult(UserAgeCategory.UNKNOWN)
            }
    }

    private fun checkSharedAgeSignals(
        manager: AgeSignalsManager,
        onResult: (UserAgeCategory) -> Unit
    ) {
        manager.checkAgeSignals(AgeSignalsRequest.builder().build())
            .addOnSuccessListener { result ->
                onResult(classifyAgeRange(result.ageLower(), result.ageUpper()))
            }
            .addOnFailureListener {
                onResult(UserAgeCategory.UNKNOWN)
            }
    }

    /**
     * Classifies only when the returned band proves the user is wholly below or at/above 18.
     * Custom Play Console age bands can make a band span the legal-adult boundary, so ambiguous
     * bands are deliberately UNKNOWN rather than guessed.
     */
    internal fun classifyAgeRange(ageLower: Int?, ageUpper: Int?): UserAgeCategory {
        if (ageLower == null) return UserAgeCategory.UNKNOWN

        if (ageLower >= ADULT_AGE) {
            return UserAgeCategory.ADULT
        }

        if (ageUpper != null && ageUpper < ADULT_AGE) {
            return UserAgeCategory.MINOR
        }

        return UserAgeCategory.UNKNOWN
    }

    private const val ADULT_AGE = 18
}
