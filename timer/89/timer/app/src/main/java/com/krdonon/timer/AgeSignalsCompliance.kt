package com.krdonon.timer

import android.app.Activity
import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.play.agesignals.AgeSignalsAccessRequest
import com.google.android.play.agesignals.AgeSignalsException
import com.google.android.play.agesignals.AgeSignalsManager
import com.google.android.play.agesignals.AgeSignalsManagerFactory
import com.google.android.play.agesignals.AgeSignalsRequest
import com.google.android.play.agesignals.ApplicableAgeFeaturesRequest
import com.google.android.play.agesignals.model.AgeSignalsStatus
import com.google.android.play.agesignals.model.ApplicableAgeFeature
import com.google.android.play.agesignals.model.SignificantChangeStatus
import java.util.concurrent.Executor

/**
 * Google Play Age Signals API 0.0.6 integration.
 *
 * All results are intentionally kept in memory only. They are never written
 * to logs, SharedPreferences, files, analytics, or a database.
 */
class AgeSignalsCompliance internal constructor(
    private val manager: AgeSignalsManager,
    private val callbackExecutor: Executor? = null
) {

    constructor(context: Context) : this(
        manager = AgeSignalsManagerFactory.create(context.applicationContext),
        callbackExecutor = null
    )

    enum class Classification {
        MINOR,
        ADULT,
        UNKNOWN
    }

    data class Snapshot(
        val mandatorySharingApplicable: Boolean = false,
        val accessStatus: Int = AgeSignalsStatus.UNSPECIFIED,
        val classification: Classification = Classification.UNKNOWN,
        val ageRangeSource: Int? = null,
        val significantChangeStatus: Int? = null,
        val lastErrorCode: Int? = null
    )

    @Volatile
    var currentSnapshot: Snapshot = Snapshot()
        private set

    val currentClassification: Classification
        get() = currentSnapshot.classification

    /**
     * 0.0.6 flow:
     * 1) getApplicableAgeFeatures() to verify whether MANDATORY_SHARING applies
     * 2) only when MANDATORY_SHARING applies, call requestAgeSignalsAccess(Activity)
     * 3) only when SHARED, call checkAgeSignals()
     * 4) otherwise remain UNKNOWN
     */
    fun refresh(activity: Activity) {
        refreshInternal(
            canProceed = { !activity.isFinishing && !activity.isDestroyed },
            createAccessRequest = {
                AgeSignalsAccessRequest.builder()
                    .setActivity(activity)
                    .build()
            }
        )
    }

    internal fun refreshInternal(
        canProceed: () -> Boolean,
        createAccessRequest: () -> AgeSignalsAccessRequest
    ) {
        currentSnapshot = Snapshot()

        val featuresRequest = ApplicableAgeFeaturesRequest.builder().build()
        addListeners(
            task = manager.getApplicableAgeFeatures(featuresRequest),
            onSuccess = { featuresResult ->
                val mandatorySharing = featuresResult.features()
                    .contains(ApplicableAgeFeature.MANDATORY_SHARING)

                if (!mandatorySharing) {
                    currentSnapshot = Snapshot(
                        mandatorySharingApplicable = false,
                        accessStatus = AgeSignalsStatus.UNSPECIFIED,
                        classification = Classification.UNKNOWN
                    )
                    return@addListeners
                }

                if (!canProceed()) {
                    currentSnapshot = Snapshot(
                        mandatorySharingApplicable = true,
                        classification = Classification.UNKNOWN
                    )
                    return@addListeners
                }

                requestAccess(createAccessRequest())
            },
            onFailure = { errorCode ->
                currentSnapshot = Snapshot(
                    classification = Classification.UNKNOWN,
                    lastErrorCode = errorCode
                )
            }
        )
    }

    private fun requestAccess(accessRequest: AgeSignalsAccessRequest) {
        addListeners(
            task = manager.requestAgeSignalsAccess(accessRequest),
            onSuccess = { accessResult ->
                val status = accessResult.ageSignalsStatus() ?: AgeSignalsStatus.UNSPECIFIED
                if (status == AgeSignalsStatus.SHARED) {
                    checkSharedAgeSignals(status)
                } else {
                    // NOT_SHARED, VERIFICATION_REQUIRED, UNSPECIFIED, or any future
                    // status that is not explicitly SHARED must not be treated as adult.
                    currentSnapshot = Snapshot(
                        mandatorySharingApplicable = true,
                        accessStatus = status,
                        classification = Classification.UNKNOWN
                    )
                }
            },
            onFailure = { errorCode ->
                currentSnapshot = Snapshot(
                    mandatorySharingApplicable = true,
                    classification = Classification.UNKNOWN,
                    lastErrorCode = errorCode
                )
            }
        )
    }

    private fun checkSharedAgeSignals(accessStatus: Int) {
        addListeners(
            task = manager.checkAgeSignals(AgeSignalsRequest.builder().build()),
            onSuccess = { result ->
                val sigStatus = result.significantChangeStatus()
                val rawClassification = classifyAgeRange(
                    ageLower = result.ageLower(),
                    ageUpper = result.ageUpper()
                )
                // If a minor has a pending or declined significant-change approval, keep MINOR.
                val finalClassification = if (
                    rawClassification == Classification.MINOR &&
                    (sigStatus == SignificantChangeStatus.PENDING ||
                        sigStatus == SignificantChangeStatus.DECLINED)
                ) {
                    Classification.MINOR
                } else {
                    rawClassification
                }

                currentSnapshot = Snapshot(
                    mandatorySharingApplicable = true,
                    accessStatus = accessStatus,
                    classification = finalClassification,
                    ageRangeSource = result.ageRangeSource(),
                    significantChangeStatus = sigStatus
                )
            },
            onFailure = { errorCode ->
                currentSnapshot = Snapshot(
                    mandatorySharingApplicable = true,
                    accessStatus = accessStatus,
                    classification = Classification.UNKNOWN,
                    lastErrorCode = errorCode
                )
            }
        )
    }

    private fun <T> addListeners(
        task: Task<T>,
        onSuccess: (T) -> Unit,
        onFailure: (Int?) -> Unit
    ) {
        val executor = callbackExecutor
        if (executor != null) {
            task.addOnSuccessListener(executor, onSuccess)
            task.addOnFailureListener(executor) { error ->
                onFailure((error as? AgeSignalsException)?.errorCode)
            }
        } else {
            task.addOnSuccessListener(onSuccess)
            task.addOnFailureListener { error ->
                onFailure((error as? AgeSignalsException)?.errorCode)
            }
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
