package com.krdondon.Replacestring

import android.app.Activity
import android.content.Context
import com.google.android.play.agesignals.*
import com.google.android.play.agesignals.model.*

/**
 * App-local classification derived from Google Play Age Signals.
 *
 * This value is intentionally kept in memory only. It must not be used for
 * advertising, marketing, user profiling, or analytics.
 */
enum class AgeClassification {
    MINOR,
    ADULT,
    UNKNOWN
}

/**
 * Google Play Age Signals 0.0.4 integration.
 *
 * Official flow:
 * 1) AgeSignalsManagerFactory.create(...)
 * 2) requestAgeSignalsAccess(AgeSignalsAccessRequest)
 * 3) Only when status is SHARED, call checkAgeSignals(AgeSignalsRequest)
 */
class AgeSignalsCompliance(context: Context) {

    private val ageSignalsManager: AgeSignalsManager =
        AgeSignalsManagerFactory.create(context.applicationContext)

    @Volatile
    var currentClassification: AgeClassification = AgeClassification.UNKNOWN
        private set

    /**
     * Requests the current age-sharing status and, when shared, retrieves the age range.
     * NOT_SHARED, VERIFICATION_REQUIRED, unexpected responses, and API errors are all
     * treated as UNKNOWN rather than guessing the user's age.
     */
    fun refresh(
        activity: Activity,
        onResult: (AgeClassification) -> Unit = {}
    ) {
        publish(AgeClassification.UNKNOWN, onResult)

        val accessRequest = AgeSignalsAccessRequest.builder()
            .setActivity(activity)
            .build()

        ageSignalsManager.requestAgeSignalsAccess(accessRequest)
            .addOnSuccessListener { accessResult ->
                when (accessResult.ageSignalsStatus()) {
                    AgeSignalsStatus.SHARED -> retrieveSharedAgeSignals(onResult)
                    AgeSignalsStatus.NOT_SHARED,
                    AgeSignalsStatus.VERIFICATION_REQUIRED -> {
                        publish(AgeClassification.UNKNOWN, onResult)
                    }
                    else -> publish(AgeClassification.UNKNOWN, onResult)
                }
            }
            .addOnFailureListener {
                publish(AgeClassification.UNKNOWN, onResult)
            }
    }

    private fun retrieveSharedAgeSignals(
        onResult: (AgeClassification) -> Unit
    ) {
        val request = AgeSignalsRequest.builder().build()

        ageSignalsManager.checkAgeSignals(request)
            .addOnSuccessListener { result ->
                publish(
                    classifyAgeRange(
                        ageLower = result.ageLower(),
                        ageUpper = result.ageUpper()
                    ),
                    onResult
                )
            }
            .addOnFailureListener {
                publish(AgeClassification.UNKNOWN, onResult)
            }
    }

    /**
     * Safe classification that also remains correct if Play Console custom age ranges
     * replace the default 0-12 / 13-15 / 16-17 / 18+ bands.
     *
     * - A lower bound of 18+ proves the returned band is adult-only.
     * - A closed upper bound below 18 proves the returned band is minor-only.
     * - Null or a band that could contain both minors and adults stays UNKNOWN.
     */
    internal fun classifyAgeRange(
        ageLower: Int?,
        ageUpper: Int?
    ): AgeClassification {
        // A missing lower bound or an internally inconsistent range is not enough
        // evidence to make an age decision.
        if (ageLower == null || (ageUpper != null && ageUpper < ageLower)) {
            return AgeClassification.UNKNOWN
        }

        return when {
            ageLower >= ADULT_AGE -> AgeClassification.ADULT
            ageUpper != null && ageUpper < ADULT_AGE -> AgeClassification.MINOR
            else -> AgeClassification.UNKNOWN
        }
    }

    private fun publish(
        classification: AgeClassification,
        onResult: (AgeClassification) -> Unit
    ) {
        currentClassification = classification
        onResult(classification)
    }

    private companion object {
        const val ADULT_AGE = 18
    }
}
