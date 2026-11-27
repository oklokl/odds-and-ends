package com.krdonon.metronome

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.play.agesignals.AgeSignalsAccessRequest
import com.google.android.play.agesignals.AgeSignalsException
import com.google.android.play.agesignals.AgeSignalsManager
import com.google.android.play.agesignals.AgeSignalsManagerFactory
import com.google.android.play.agesignals.AgeSignalsRequest
import com.google.android.play.agesignals.ApplicableAgeFeaturesRequest
import com.google.android.play.agesignals.model.AgeSignalsErrorCode
import com.google.android.play.agesignals.model.AgeSignalsStatus
import com.google.android.play.agesignals.model.ApplicableAgeFeature
import com.google.android.play.agesignals.model.SignificantChangeStatus
import java.lang.ref.WeakReference
import java.util.concurrent.Executor

/**
 * Runtime-only wrapper for Google Play Age Signals API (0.0.6).
 *
 * Age Signals data must only be used to provide age-appropriate experiences and
 * comply with applicable law. Do not use it for advertising, marketing,
 * profiling, or analytics, and do not persist the returned age information.
 */
class AgeSignalsCompliance internal constructor(
    private val ageSignalsManager: AgeSignalsManager?,
    private val callbackExecutor: Executor,
    private val retryScheduler: (delayMs: Long, action: () -> Unit) -> Unit,
) {

    constructor(context: Context) : this(
        ageSignalsManager = createManager(context.applicationContext),
        callbackExecutor = ContextCompat.getMainExecutor(context.applicationContext),
        retryScheduler = { delayMs, action ->
            Handler(Looper.getMainLooper()).postDelayed(action, delayMs)
        },
    )

    enum class AgeCategory {
        MINOR,
        ADULT,
        UNKNOWN,
    }

    data class AgeSignalsState(
        val ageCategory: AgeCategory = AgeCategory.UNKNOWN,
        val accessStatus: Int? = null,
        val ageRangeSource: Int? = null,
        val significantChangeStatus: Int? = null,
        val errorCode: Int? = null,
    ) {
        val isVerificationRequired: Boolean
            get() = accessStatus == AgeSignalsStatus.VERIFICATION_REQUIRED

        val isSignificantChangePending: Boolean
            get() = significantChangeStatus == SignificantChangeStatus.PENDING

        val isSignificantChangeDeclined: Boolean
            get() = significantChangeStatus == SignificantChangeStatus.DECLINED
    }

    /**
     * Uses the 0.0.4+ two-function flow:
     * requestAgeSignalsAccess() -> checkAgeSignals() only when status is SHARED.
     */
    fun requestAgeCategory(
        activity: Activity,
        onResult: (AgeCategory) -> Unit,
    ) {
        requestAgeSignals(activity) { state ->
            onResult(state.ageCategory)
        }
    }

    /**
     * Requests age signals access for the given [activity] and retrieves the
     * user's age range and significant change status when shared.
     *
     * Transient errors are retried with bounded exponential backoff. Any
     * unshared status, verification-required status, or terminal error safely
     * resolves to [AgeCategory.UNKNOWN].
     */
    fun requestAgeSignals(
        activity: Activity,
        onResult: (AgeSignalsState) -> Unit,
    ) {
        val manager = ageSignalsManager
        if (manager == null) {
            onResult(AgeSignalsState())
            return
        }

        requestAccessWithRetry(
            manager = manager,
            activityRef = WeakReference(activity),
            attempt = 0,
            onResult = onResult,
        )
    }

    /**
     * Queries Play Age Signals 0.0.6+ applicable age features to determine
     * whether [ApplicableAgeFeature.MANDATORY_SHARING] applies to this user.
     */
    fun checkMandatorySharingApplicable(onResult: (Boolean?) -> Unit) {
        val manager = ageSignalsManager
        if (manager == null) {
            onResult(null)
            return
        }

        val request = ApplicableAgeFeaturesRequest.builder().build()
        manager.getApplicableAgeFeatures(request)
            .addOnSuccessListener(callbackExecutor) { result ->
                onResult(result.features().contains(ApplicableAgeFeature.MANDATORY_SHARING))
            }
            .addOnFailureListener(callbackExecutor) {
                onResult(null)
            }
    }

    private fun requestAccessWithRetry(
        manager: AgeSignalsManager,
        activityRef: WeakReference<Activity>,
        attempt: Int,
        onResult: (AgeSignalsState) -> Unit,
    ) {
        val activity = activityRef.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            return
        }

        val accessRequest = AgeSignalsAccessRequest.builder()
            .setActivity(activity)
            .build()

        manager.requestAgeSignalsAccess(accessRequest)
            .addOnSuccessListener(callbackExecutor) { accessResult ->
                val status = accessResult.ageSignalsStatus()
                if (status == AgeSignalsStatus.SHARED) {
                    checkAgeSignalsWithRetry(
                        manager = manager,
                        accessStatus = status,
                        attempt = 0,
                        onResult = onResult,
                    )
                } else {
                    // NOT_SHARED, VERIFICATION_REQUIRED, and UNSPECIFIED are
                    // intentionally treated as UNKNOWN. Do not infer an age
                    // when Play does not provide a usable age range.
                    onResult(
                        AgeSignalsState(
                            ageCategory = AgeCategory.UNKNOWN,
                            accessStatus = status,
                        )
                    )
                }
            }
            .addOnFailureListener(callbackExecutor) { exception ->
                val errorCode = extractErrorCode(exception)
                if (attempt < MAX_RETRY_ATTEMPTS && isTransientRetryableError(errorCode)) {
                    retryScheduler(retryDelayMs(attempt)) {
                        requestAccessWithRetry(
                            manager = manager,
                            activityRef = activityRef,
                            attempt = attempt + 1,
                            onResult = onResult,
                        )
                    }
                } else {
                    onResult(
                        AgeSignalsState(
                            ageCategory = AgeCategory.UNKNOWN,
                            errorCode = errorCode,
                        )
                    )
                }
            }
    }

    private fun checkAgeSignalsWithRetry(
        manager: AgeSignalsManager,
        accessStatus: Int?,
        attempt: Int,
        onResult: (AgeSignalsState) -> Unit,
    ) {
        val request = AgeSignalsRequest.builder().build()

        manager.checkAgeSignals(request)
            .addOnSuccessListener(callbackExecutor) { result ->
                onResult(
                    AgeSignalsState(
                        ageCategory = classifyAgeRange(
                            ageLower = result.ageLower(),
                            ageUpper = result.ageUpper(),
                        ),
                        accessStatus = accessStatus,
                        ageRangeSource = result.ageRangeSource(),
                        significantChangeStatus = result.significantChangeStatus(),
                    )
                )
            }
            .addOnFailureListener(callbackExecutor) { exception ->
                val errorCode = extractErrorCode(exception)
                if (attempt < MAX_RETRY_ATTEMPTS && isTransientRetryableError(errorCode)) {
                    retryScheduler(retryDelayMs(attempt)) {
                        checkAgeSignalsWithRetry(
                            manager = manager,
                            accessStatus = accessStatus,
                            attempt = attempt + 1,
                            onResult = onResult,
                        )
                    }
                } else {
                    onResult(
                        AgeSignalsState(
                            ageCategory = AgeCategory.UNKNOWN,
                            accessStatus = accessStatus,
                            errorCode = errorCode,
                        )
                    )
                }
            }
    }

    companion object {
        private const val ADULT_AGE = 18
        internal const val MAX_RETRY_ATTEMPTS = 2
        private const val BASE_RETRY_DELAY_MS = 500L

        private fun createManager(context: Context): AgeSignalsManager? =
            runCatching { AgeSignalsManagerFactory.create(context) }.getOrNull()

        internal fun retryDelayMs(attempt: Int): Long =
            BASE_RETRY_DELAY_MS * (1L shl attempt)

        internal fun extractErrorCode(exception: Throwable): Int? =
            (exception as? AgeSignalsException)?.errorCode

        internal fun isTransientRetryableError(errorCode: Int?): Boolean {
            return errorCode == AgeSignalsErrorCode.NETWORK_ERROR ||
                errorCode == AgeSignalsErrorCode.CANNOT_BIND_TO_SERVICE ||
                errorCode == AgeSignalsErrorCode.CLIENT_TRANSIENT_ERROR ||
                errorCode == AgeSignalsErrorCode.INTERNAL_ERROR
        }

        /**
         * Classifies only ranges that are unambiguous around the 18-year boundary.
         * A custom Play Console age band that crosses 18 is therefore UNKNOWN.
         */
        internal fun classifyAgeRange(ageLower: Int?, ageUpper: Int?): AgeCategory {
            return when {
                (ageLower != null) && (ageLower >= ADULT_AGE) -> AgeCategory.ADULT
                (ageLower != null) && (ageUpper != null) && (ageUpper < ADULT_AGE) -> AgeCategory.MINOR
                else -> AgeCategory.UNKNOWN
            }
        }
    }
}
