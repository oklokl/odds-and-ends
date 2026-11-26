package com.krdondon.exorcismprayer

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Task
import com.google.android.play.agesignals.AgeSignalsAccessRequest
import com.google.android.play.agesignals.AgeSignalsAccessResult
import com.google.android.play.agesignals.AgeSignalsException
import com.google.android.play.agesignals.AgeSignalsManager
import com.google.android.play.agesignals.AgeSignalsManagerFactory
import com.google.android.play.agesignals.AgeSignalsRequest
import com.google.android.play.agesignals.model.AgeSignalsErrorCode
import com.google.android.play.agesignals.model.AgeSignalsStatus
import java.util.Date
import java.util.concurrent.Executor

enum class AgeClassification {
    MINOR,
    ADULT,
    UNKNOWN
}

enum class AgeSignalsAccessState {
    SHARED,
    NOT_SHARED,
    VERIFICATION_REQUIRED,
    UNAVAILABLE,
    ERROR
}

data class AgeSignalsSnapshot(
    val classification: AgeClassification = AgeClassification.UNKNOWN,
    val accessState: AgeSignalsAccessState = AgeSignalsAccessState.UNAVAILABLE,
    val ageRangeSource: Int? = null,
    val significantChangeStatus: Int? = null,
    val significantChangeApprovalDate: Date? = null,
    val installId: String? = null,
    val errorCode: Int? = null,
)

/**
 * Runtime-only wrapper around Google Play Age Signals (0.0.6).
 *
 * The result is deliberately not persisted. Callers receive the coarse
 * MINOR / ADULT / UNKNOWN classification along with runtime signal metadata
 * needed for age-appropriate behavior.
 */
class AgeSignalsCompliance internal constructor(
    private val ageSignalsManager: AgeSignalsManager,
    private val callbackExecutor: Executor,
    private val scheduleRetry: (delayMs: Long, action: Runnable) -> Unit,
    private val logWarning: (message: String, throwable: Throwable) -> Unit = { message, throwable ->
        Log.w(TAG, message, throwable)
    },
) {

    constructor(context: Context) : this(
        ageSignalsManager = AgeSignalsManagerFactory.create(context.applicationContext),
        callbackExecutor = ContextCompat.getMainExecutor(context.applicationContext),
        scheduleRetry = { delayMs, action ->
            Handler(Looper.getMainLooper()).postDelayed(action, delayMs)
        },
    )

    fun requestAgeSignalsSnapshot(
        activity: Activity,
        onResult: (AgeSignalsSnapshot) -> Unit,
    ) {
        requestAgeSignalsSnapshot(
            isActive = { !activity.isFinishing && !activity.isDestroyed },
            requestAccess = {
                val accessRequest = AgeSignalsAccessRequest.builder()
                    .setActivity(activity)
                    .build()
                ageSignalsManager.requestAgeSignalsAccess(accessRequest)
            },
            onResult = onResult,
        )
    }

    internal fun requestAgeSignalsSnapshot(
        isActive: () -> Boolean,
        requestAccess: () -> Task<AgeSignalsAccessResult>,
        onResult: (AgeSignalsSnapshot) -> Unit,
    ) {
        requestAccessWithRetry(
            isActive = isActive,
            requestAccess = requestAccess,
            attempt = 0,
            onResult = onResult,
        )
    }

    private fun requestAccessWithRetry(
        isActive: () -> Boolean,
        requestAccess: () -> Task<AgeSignalsAccessResult>,
        attempt: Int,
        onResult: (AgeSignalsSnapshot) -> Unit,
    ) {
        if (!isActive()) {
            onResult(AgeSignalsSnapshot(accessState = AgeSignalsAccessState.UNAVAILABLE))
            return
        }

        requestAccess()
            .addOnSuccessListener(callbackExecutor) { accessResult ->
                when (accessResult.ageSignalsStatus()) {
                    AgeSignalsStatus.SHARED -> checkAgeSignalsWithRetry(
                        isActive = isActive,
                        attempt = 0,
                        onResult = onResult,
                    )

                    AgeSignalsStatus.NOT_SHARED -> onResult(
                        AgeSignalsSnapshot(
                            classification = AgeClassification.UNKNOWN,
                            accessState = AgeSignalsAccessState.NOT_SHARED,
                        ),
                    )

                    AgeSignalsStatus.VERIFICATION_REQUIRED -> onResult(
                        AgeSignalsSnapshot(
                            classification = AgeClassification.UNKNOWN,
                            accessState = AgeSignalsAccessState.VERIFICATION_REQUIRED,
                        )
                    )

                    else -> onResult(
                        AgeSignalsSnapshot(
                            classification = AgeClassification.UNKNOWN,
                            accessState = AgeSignalsAccessState.UNAVAILABLE,
                        )
                    )
                }
            }
            .addOnFailureListener(callbackExecutor) { exception ->
                val errorCode = (exception as? AgeSignalsException)?.errorCode
                if (isTransientRetryable(errorCode) && (attempt < MAX_RETRY_ATTEMPTS)) {
                    val delayMs = INITIAL_RETRY_DELAY_MS * (1L shl attempt)
                    scheduleRetry(delayMs) {
                        requestAccessWithRetry(isActive, requestAccess, attempt + 1, onResult)
                    }
                    return@addOnFailureListener
                }
                logWarning(
                    "Unable to request Age Signals access (errorCode=$errorCode); using UNKNOWN.",
                    exception,
                )
                onResult(
                    AgeSignalsSnapshot(
                        classification = AgeClassification.UNKNOWN,
                        accessState = AgeSignalsAccessState.ERROR,
                        errorCode = errorCode,
                    )
                )
            }
    }

    private fun checkAgeSignalsWithRetry(
        isActive: () -> Boolean,
        attempt: Int,
        onResult: (AgeSignalsSnapshot) -> Unit,
    ) {
        if (!isActive()) {
            onResult(AgeSignalsSnapshot(accessState = AgeSignalsAccessState.UNAVAILABLE))
            return
        }

        ageSignalsManager.checkAgeSignals(AgeSignalsRequest.builder().build())
            .addOnSuccessListener(callbackExecutor) { result ->
                onResult(
                    AgeSignalsSnapshot(
                        classification = classifyAgeRange(result.ageLower(), result.ageUpper()),
                        accessState = AgeSignalsAccessState.SHARED,
                        ageRangeSource = result.ageRangeSource(),
                        significantChangeStatus = result.significantChangeStatus(),
                        significantChangeApprovalDate = result.significantChangeApprovalDate(),
                        installId = result.installId(),
                    )
                )
            }
            .addOnFailureListener(callbackExecutor) { exception ->
                val errorCode = (exception as? AgeSignalsException)?.errorCode
                if (isTransientRetryable(errorCode) && (attempt < MAX_RETRY_ATTEMPTS)) {
                    val delayMs = INITIAL_RETRY_DELAY_MS * (1L shl attempt)
                    scheduleRetry(delayMs) {
                        checkAgeSignalsWithRetry(isActive, attempt + 1, onResult)
                    }
                    return@addOnFailureListener
                }
                logWarning(
                    "Unable to read Age Signals (errorCode=$errorCode); using UNKNOWN.",
                    exception,
                )
                onResult(
                    AgeSignalsSnapshot(
                        classification = AgeClassification.UNKNOWN,
                        accessState = AgeSignalsAccessState.ERROR,
                        errorCode = errorCode,
                    )
                )
            }
    }

    /**
     * Conservative classification that remains safe if Play Console custom age
     * ranges are configured. For example, an open-ended 15+ band does not prove
     * the user is an adult and is therefore UNKNOWN rather than ADULT.
     */
    internal fun classifyAgeRange(ageLower: Int?, ageUpper: Int?): AgeClassification {
        return when {
            ((ageLower != null) && (ageLower >= ADULT_AGE)) -> AgeClassification.ADULT
            ((ageUpper != null) && (ageUpper < ADULT_AGE)) -> AgeClassification.MINOR
            else -> AgeClassification.UNKNOWN
        }
    }

    internal fun isTransientRetryable(errorCode: Int?): Boolean {
        return when (errorCode) {
            AgeSignalsErrorCode.NETWORK_ERROR,
            AgeSignalsErrorCode.CANNOT_BIND_TO_SERVICE,
            AgeSignalsErrorCode.CLIENT_TRANSIENT_ERROR,
            -> true

            else -> false
        }
    }

    private companion object {
        const val TAG = "AgeSignalsCompliance"
        const val ADULT_AGE = 18
        const val MAX_RETRY_ATTEMPTS = 2
        const val INITIAL_RETRY_DELAY_MS = 500L
    }
}
