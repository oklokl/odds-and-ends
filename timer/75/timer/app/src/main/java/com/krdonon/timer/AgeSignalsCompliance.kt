package com.krdonon.timer

import android.app.Activity
import android.content.Context
import com.google.android.play.agesignals.AgeSignalsAccessRequest
import com.google.android.play.agesignals.AgeSignalsManager
import com.google.android.play.agesignals.AgeSignalsManagerFactory
import com.google.android.play.agesignals.AgeSignalsRequest
import com.google.android.play.agesignals.model.AgeSignalsStatus

/**
 * Google Play Age Signals API 0.0.4 integration.
 *
 * The result is intentionally kept in memory only. It is not written to logs,
 * SharedPreferences, files, analytics, or a database.
 */
class AgeSignalsCompliance(context: Context) {

    enum class Classification {
        MINOR,
        ADULT,
        UNKNOWN
    }

    private val manager: AgeSignalsManager =
        AgeSignalsManagerFactory.create(context.applicationContext)

    @Volatile
    var currentClassification: Classification = Classification.UNKNOWN
        private set

    /**
     * 0.0.4 flow:
     * 1) requestAgeSignalsAccess(Activity)
     * 2) only when SHARED, call checkAgeSignals()
     * 3) otherwise remain UNKNOWN
     */
    fun refresh(activity: Activity) {
        currentClassification = Classification.UNKNOWN

        val accessRequest = AgeSignalsAccessRequest.builder()
            .setActivity(activity)
            .build()

        manager.requestAgeSignalsAccess(accessRequest)
            .addOnSuccessListener { accessResult ->
                if (accessResult.ageSignalsStatus() == AgeSignalsStatus.SHARED) {
                    checkSharedAgeSignals()
                } else {
                    // NOT_SHARED, VERIFICATION_REQUIRED, UNSPECIFIED, or any future
                    // status that is not explicitly SHARED must not be treated as adult.
                    currentClassification = Classification.UNKNOWN
                }
            }
            .addOnFailureListener {
                currentClassification = Classification.UNKNOWN
            }
    }

    private fun checkSharedAgeSignals() {
        manager.checkAgeSignals(AgeSignalsRequest.builder().build())
            .addOnSuccessListener { result ->
                currentClassification = classifyAgeRange(
                    ageLower = result.ageLower(),
                    ageUpper = result.ageUpper()
                )
            }
            .addOnFailureListener {
                currentClassification = Classification.UNKNOWN
            }
    }

    /**
     * Conservative 18+ classification that remains safe with custom Play Console ranges.
     *
     * - lower >= 18             -> definitely ADULT
     * - upper <= 17             -> definitely MINOR
     * - missing/crossing bounds -> UNKNOWN
     *
     * An open-ended custom band such as 15+ or 17+ crosses the legal 18 boundary,
     * so it must not be guessed as either minor or adult.
     */
    internal fun classifyAgeRange(ageLower: Int?, ageUpper: Int?): Classification {
        // A missing lower bound means the age range is incomplete/unavailable.
        if (ageLower == null) {
            return Classification.UNKNOWN
        }

        // Reject an inconsistent range rather than guessing.
        if (ageUpper != null && ageUpper < ageLower) {
            return Classification.UNKNOWN
        }

        if (ageLower >= ADULT_AGE) {
            return Classification.ADULT
        }

        if (ageUpper != null && ageUpper < ADULT_AGE) {
            return Classification.MINOR
        }

        return Classification.UNKNOWN
    }

    private companion object {
        const val ADULT_AGE = 18
    }
}
