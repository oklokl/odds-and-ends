package com.krdonon.ratio

import android.app.Activity
import com.google.android.gms.tasks.Task
import com.google.android.play.agesignals.AgeSignalsAccessRequest
import com.google.android.play.agesignals.AgeSignalsAccessResult
import com.google.android.play.agesignals.AgeSignalsException
import com.google.android.play.agesignals.AgeSignalsManager
import com.google.android.play.agesignals.AgeSignalsManagerFactory
import com.google.android.play.agesignals.AgeSignalsRequest
import com.google.android.play.agesignals.AgeSignalsResult
import com.google.android.play.agesignals.model.AgeSignalsErrorCode
import com.google.android.play.agesignals.model.AgeSignalsStatus
import com.google.android.play.agesignals.model.SignificantChangeStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Date
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.milliseconds

/**
 * Runtime-only Google Play Age Signals integration (SDK 0.0.6).
 *
 * Results are intentionally never persisted to disk, logs, or analytics, and must only be used
 * to provide age-appropriate experiences and legal compliance.
 */
enum class UserAgeCategory {
    MINOR,
    ADULT,
    UNKNOWN,
}

enum class AgeAccessStatus {
    NOT_CHECKED,
    SHARED,
    NOT_SHARED,
    VERIFICATION_REQUIRED,
    UNSPECIFIED,
    ERROR,
}

data class AgeSignalsState(
    val accessStatus: AgeAccessStatus = AgeAccessStatus.NOT_CHECKED,
    val ageCategory: UserAgeCategory = UserAgeCategory.UNKNOWN,
    val ageLower: Int? = null,
    val ageUpper: Int? = null,
    val ageRangeSource: Int? = null,
    val significantChangeStatus: Int? = null,
    val significantChangeApprovalDate: Date? = null,
    val installId: String? = null,
    val errorCode: Int? = null,
) {
    val isVerificationRequired: Boolean
        get() = accessStatus == AgeAccessStatus.VERIFICATION_REQUIRED

    val isSignificantChangePending: Boolean
        get() = significantChangeStatus == SignificantChangeStatus.PENDING

    val isSignificantChangeDeclined: Boolean
        get() = significantChangeStatus == SignificantChangeStatus.DECLINED
}

object AgeSignalsCompliance {

    private const val ADULT_AGE = 18
    private const val MAX_ATTEMPTS = 3
    private const val INITIAL_BACKOFF_MS = 500L

    /**
     * Requests Play Age Signals access using the two-step 0.0.4+ architecture
     * (`requestAgeSignalsAccess` followed by `checkAgeSignals` when status is `SHARED`),
     * with bounded exponential backoff for transient errors.
     *
     * Returns `null` if the Activity is no longer active before the access prompt is dispatched.
     */
    suspend fun checkUserAge(
        activityProvider: () -> Activity?,
        manager: AgeSignalsManager? = null,
        delayFn: suspend (Long) -> Unit = { delay(it.milliseconds) },
    ): AgeSignalsState? {
        val initialActivity = activityProvider()?.takeIf(::isActivityUsable) ?: return null
        val ageSignalsManager = manager
            ?: AgeSignalsManagerFactory.create(initialActivity.applicationContext)

        return executeAgeSignalsFlow(
            requestAccess = {
                val activeActivity = activityProvider()?.takeIf(::isActivityUsable)
                    ?: throw CancellationException("Activity is no longer active")
                val accessRequest = AgeSignalsAccessRequest.builder()
                    .setActivity(activeActivity)
                    .build()
                ageSignalsManager.requestAgeSignalsAccess(accessRequest).awaitResult()
            },
            fetchSignals = {
                ageSignalsManager.checkAgeSignals(AgeSignalsRequest.builder().build()).awaitResult()
            },
            delayFn = delayFn,
        )
    }

    internal suspend fun executeAgeSignalsFlow(
        requestAccess: suspend () -> AgeSignalsAccessResult,
        fetchSignals: suspend () -> AgeSignalsResult,
        delayFn: suspend (Long) -> Unit = { delay(it.milliseconds) },
    ): AgeSignalsState {
        return try {
            val accessResult = runWithTransientRetry(delayFn = delayFn) {
                requestAccess()
            }
            when (accessResult.ageSignalsStatus()) {
                AgeSignalsStatus.SHARED -> {
                    val result = runWithTransientRetry(delayFn = delayFn) {
                        fetchSignals()
                    }
                    mapAgeSignalsResult(result)
                }

                AgeSignalsStatus.NOT_SHARED -> AgeSignalsState(
                    accessStatus = AgeAccessStatus.NOT_SHARED,
                    ageCategory = UserAgeCategory.UNKNOWN,
                )

                AgeSignalsStatus.VERIFICATION_REQUIRED -> AgeSignalsState(
                    accessStatus = AgeAccessStatus.VERIFICATION_REQUIRED,
                    ageCategory = UserAgeCategory.UNKNOWN,
                )

                else -> AgeSignalsState(
                    accessStatus = AgeAccessStatus.UNSPECIFIED,
                    ageCategory = UserAgeCategory.UNKNOWN,
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: AgeSignalsException) {
            AgeSignalsState(
                accessStatus = AgeAccessStatus.ERROR,
                ageCategory = UserAgeCategory.UNKNOWN,
                errorCode = e.errorCode,
            )
        } catch (_: Exception) {
            AgeSignalsState(
                accessStatus = AgeAccessStatus.ERROR,
                ageCategory = UserAgeCategory.UNKNOWN,
            )
        }
    }

    internal fun mapAgeSignalsResult(result: AgeSignalsResult): AgeSignalsState {
        val ageLower = result.ageLower()
        val ageUpper = result.ageUpper()
        return AgeSignalsState(
            accessStatus = AgeAccessStatus.SHARED,
            ageCategory = classifyAgeRange(ageLower, ageUpper),
            ageLower = ageLower,
            ageUpper = ageUpper,
            ageRangeSource = result.ageRangeSource(),
            significantChangeStatus = result.significantChangeStatus(),
            significantChangeApprovalDate = result.significantChangeApprovalDate(),
            installId = result.installId(),
        )
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

        if ((ageUpper != null) && (ageUpper < ADULT_AGE)) {
            return UserAgeCategory.MINOR
        }

        return UserAgeCategory.UNKNOWN
    }

    internal fun isRetryableErrorCode(errorCode: Int): Boolean {
        return when (errorCode) {
            AgeSignalsErrorCode.API_NOT_AVAILABLE,
            AgeSignalsErrorCode.PLAY_STORE_NOT_FOUND,
            AgeSignalsErrorCode.NETWORK_ERROR,
            AgeSignalsErrorCode.PLAY_SERVICES_NOT_FOUND,
            AgeSignalsErrorCode.CANNOT_BIND_TO_SERVICE,
            AgeSignalsErrorCode.PLAY_STORE_VERSION_OUTDATED,
            AgeSignalsErrorCode.PLAY_SERVICES_VERSION_OUTDATED,
            AgeSignalsErrorCode.CLIENT_TRANSIENT_ERROR,
            -> true
            else -> false
        }
    }

    internal fun isTransientAutoRetryErrorCode(errorCode: Int): Boolean {
        return when (errorCode) {
            AgeSignalsErrorCode.API_NOT_AVAILABLE,
            AgeSignalsErrorCode.NETWORK_ERROR,
            AgeSignalsErrorCode.CANNOT_BIND_TO_SERVICE,
            AgeSignalsErrorCode.CLIENT_TRANSIENT_ERROR -> true
            else -> false
        }
    }

    private suspend fun <T> runWithTransientRetry(
        maxAttempts: Int = MAX_ATTEMPTS,
        initialBackoffMs: Long = INITIAL_BACKOFF_MS,
        delayFn: suspend (Long) -> Unit,
        block: suspend () -> T,
    ): T {
        var attempt = 0
        var backoffMs = initialBackoffMs
        while (true) {
            attempt++
            val error = try {
                return block()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: AgeSignalsException) {
                e
            }
            if ((attempt >= maxAttempts) || !isTransientAutoRetryErrorCode(error.errorCode)) {
                throw error
            }
            delayFn(backoffMs)
            backoffMs *= 3L
        }
    }

    private fun isActivityUsable(activity: Activity): Boolean {
        return !activity.isFinishing && !activity.isDestroyed
    }

    private val directExecutor = java.util.concurrent.Executor { runnable -> runnable.run() }

    internal suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
        addOnCompleteListener(directExecutor) { task ->
            if (!continuation.isActive) return@addOnCompleteListener
            when {
                task.isCanceled -> continuation.cancel()
                task.isSuccessful -> continuation.resume(task.result)
                else -> continuation.resumeWithException(
                    task.exception ?: IllegalStateException("Task failed without exception"),
                )
            }
        }
    }
}
