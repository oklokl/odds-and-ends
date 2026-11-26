package com.krdondon.Replacestring

import android.app.Activity
import android.content.Context
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskExecutors
import com.google.android.play.agesignals.AgeSignalsAccessRequest
import com.google.android.play.agesignals.AgeSignalsAccessResult
import com.google.android.play.agesignals.AgeSignalsException
import com.google.android.play.agesignals.AgeSignalsManager
import com.google.android.play.agesignals.AgeSignalsManagerFactory
import com.google.android.play.agesignals.AgeSignalsRequest
import com.google.android.play.agesignals.AgeSignalsResult
import com.google.android.play.agesignals.ApplicableAgeFeaturesRequest
import com.google.android.play.agesignals.model.AgeSignalsErrorCode
import com.google.android.play.agesignals.model.AgeSignalsStatus
import com.google.android.play.agesignals.model.ApplicableAgeFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Date
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.milliseconds

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
 * High-level access status returned by Play Age Signals access check.
 */
enum class AgeAccessStatus {
    NOT_CHECKED,
    SHARED,
    NOT_SHARED,
    VERIFICATION_REQUIRED,
    UNSPECIFIED,
    ERROR
}

/**
 * In-memory snapshot of the latest Play Age Signals response.
 * Kept strictly in memory and never persisted or sent to analytics/ads.
 */
data class AgeSignalsState(
    val classification: AgeClassification = AgeClassification.UNKNOWN,
    val accessStatus: AgeAccessStatus = AgeAccessStatus.NOT_CHECKED,
    val ageLower: Int? = null,
    val ageUpper: Int? = null,
    val ageRangeSource: Int? = null,
    val significantChangeStatus: Int? = null,
    val significantChangeApprovalDate: Date? = null,
    val installId: String? = null,
    val mandatorySharingApplicable: Boolean? = null,
    val lastErrorCode: Int? = null,
    val isLoading: Boolean = false
)

/**
 * Google Play Age Signals 0.0.6 integration.
 *
 * Official flow:
 * 1) AgeSignalsManagerFactory.create(...)
 * 2) requestAgeSignalsAccess(AgeSignalsAccessRequest)
 * 3) Only when status is SHARED, call checkAgeSignals(AgeSignalsRequest)
 * 4) Retry retryable AgeSignalsException errors up to maxRetryAttempts with exponential backoff.
 */
class AgeSignalsCompliance internal constructor(
    private val ageSignalsManager: AgeSignalsManager,
    private val callbackExecutor: Executor = TaskExecutors.MAIN_THREAD,
    private val maxRetryAttempts: Int = DEFAULT_MAX_RETRY_ATTEMPTS,
    private val initialRetryDelayMs: Long = DEFAULT_INITIAL_RETRY_DELAY_MS,
    private val delayFn: suspend (Long) -> Unit = { delay(it.milliseconds) },
    private val isActivityActive: (Activity) -> Boolean = { !it.isFinishing && !it.isDestroyed }
) {
    constructor(context: Context) : this(
        ageSignalsManager = AgeSignalsManagerFactory.create(context.applicationContext)
    )

    private val fallbackScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    private val _state = MutableStateFlow(AgeSignalsState())
    val state: StateFlow<AgeSignalsState> = _state.asStateFlow()

    val currentClassification: AgeClassification
        get() = _state.value.classification

    private var refreshJob: Job? = null
    private var featuresJob: Job? = null

    /**
     * Requests the current age-sharing status and, when shared, retrieves the age range.
     * NOT_SHARED, VERIFICATION_REQUIRED, unexpected responses, and API errors are all
     * treated as UNKNOWN rather than guessing the user's age.
     */
    fun refresh(
        activity: Activity,
        scope: CoroutineScope? = null,
        onResult: (AgeClassification) -> Unit = {}
    ) {
        refreshJob?.cancel()
        val targetScope = scope ?: (activity as? LifecycleOwner)?.lifecycleScope ?: fallbackScope

        _state.update {
            AgeSignalsState(
                mandatorySharingApplicable = it.mandatorySharingApplicable,
                isLoading = true
            )
        }

        refreshJob = targetScope.launch {
            val finalState = executeRefreshFlow(activity)
            _state.value = finalState
            onResult(finalState.classification)
        }
    }

    /**
     * Queries Play Age Signals 0.0.6 applicable age features (e.g. MANDATORY_SHARING).
     */
    fun refreshApplicableAgeFeatures(
        scope: CoroutineScope = fallbackScope,
        onResult: (Set<Int>?) -> Unit = {}
    ) {
        featuresJob?.cancel()
        featuresJob = scope.launch {
            val features = try {
                val request = ApplicableAgeFeaturesRequest.builder().build()
                val result = executeWithRetry {
                    ageSignalsManager.getApplicableAgeFeatures(request).awaitOn(callbackExecutor)
                }
                result.features()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }

            if (features != null) {
                val mandatorySharing = ApplicableAgeFeature.MANDATORY_SHARING in features
                _state.update { it.copy(mandatorySharingApplicable = mandatorySharing) }
            }
            onResult(features)
        }
    }

    fun cancel() {
        refreshJob?.cancel()
        featuresJob?.cancel()
    }

    internal suspend fun executeRefreshFlow(activity: Activity): AgeSignalsState {
        val currentMandatorySharing = _state.value.mandatorySharingApplicable

        val accessResult = try {
            executeWithRetry {
                if (!isActivityActive(activity)) {
                    throw CancellationException("Activity is no longer active")
                }
                val accessRequest = AgeSignalsAccessRequest.builder()
                    .setActivity(activity)
                    .build()
                ageSignalsManager.requestAgeSignalsAccess(accessRequest).awaitOn(callbackExecutor)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val errorCode = (e as? AgeSignalsException)?.errorCode
            return AgeSignalsState(
                classification = AgeClassification.UNKNOWN,
                accessStatus = AgeAccessStatus.ERROR,
                mandatorySharingApplicable = currentMandatorySharing,
                lastErrorCode = errorCode,
                isLoading = false
            )
        }

        return handleAccessResult(accessResult, currentMandatorySharing)
    }

    internal suspend fun handleAccessResult(
        accessResult: AgeSignalsAccessResult,
        mandatorySharingApplicable: Boolean? = _state.value.mandatorySharingApplicable
    ): AgeSignalsState {
        return when (accessResult.ageSignalsStatus()) {
            AgeSignalsStatus.SHARED -> retrieveSharedAgeSignals(mandatorySharingApplicable)
            AgeSignalsStatus.NOT_SHARED -> AgeSignalsState(
                classification = AgeClassification.UNKNOWN,
                accessStatus = AgeAccessStatus.NOT_SHARED,
                mandatorySharingApplicable = mandatorySharingApplicable,
                isLoading = false
            )
            AgeSignalsStatus.VERIFICATION_REQUIRED -> AgeSignalsState(
                classification = AgeClassification.UNKNOWN,
                accessStatus = AgeAccessStatus.VERIFICATION_REQUIRED,
                mandatorySharingApplicable = mandatorySharingApplicable,
                isLoading = false
            )
            else -> AgeSignalsState(
                classification = AgeClassification.UNKNOWN,
                accessStatus = AgeAccessStatus.UNSPECIFIED,
                mandatorySharingApplicable = mandatorySharingApplicable,
                isLoading = false
            )
        }
    }

    internal suspend fun retrieveSharedAgeSignals(
        mandatorySharingApplicable: Boolean? = _state.value.mandatorySharingApplicable
    ): AgeSignalsState {
        val result = try {
            executeWithRetry {
                val request = AgeSignalsRequest.builder().build()
                ageSignalsManager.checkAgeSignals(request).awaitOn(callbackExecutor)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val errorCode = (e as? AgeSignalsException)?.errorCode
            return AgeSignalsState(
                classification = AgeClassification.UNKNOWN,
                accessStatus = AgeAccessStatus.ERROR,
                mandatorySharingApplicable = mandatorySharingApplicable,
                lastErrorCode = errorCode,
                isLoading = false
            )
        }

        return mapAgeSignalsResult(result, mandatorySharingApplicable)
    }

    internal fun mapAgeSignalsResult(
        result: AgeSignalsResult,
        mandatorySharingApplicable: Boolean? = _state.value.mandatorySharingApplicable
    ): AgeSignalsState {
        val ageLower = result.ageLower()
        val ageUpper = result.ageUpper()
        return AgeSignalsState(
            classification = classifyAgeRange(ageLower = ageLower, ageUpper = ageUpper),
            accessStatus = AgeAccessStatus.SHARED,
            ageLower = ageLower,
            ageUpper = ageUpper,
            ageRangeSource = result.ageRangeSource(),
            significantChangeStatus = result.significantChangeStatus(),
            significantChangeApprovalDate = result.significantChangeApprovalDate(),
            installId = result.installId(),
            mandatorySharingApplicable = mandatorySharingApplicable,
            lastErrorCode = null,
            isLoading = false
        )
    }

    internal suspend fun <T> executeWithRetry(block: suspend () -> T): T {
        var attempt = 1
        while (true) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val errorCode = (e as? AgeSignalsException)?.errorCode
                val canRetry = errorCode != null &&
                    isRetryableErrorCode(errorCode) &&
                    attempt < maxRetryAttempts
                if (!canRetry) {
                    throw e
                }
                val delayMs = initialRetryDelayMs * (1L shl (attempt - 1))
                delayFn(delayMs)
                attempt++
            }
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

    internal fun isRetryableErrorCode(errorCode: Int): Boolean = when (errorCode) {
        AgeSignalsErrorCode.API_NOT_AVAILABLE,
        AgeSignalsErrorCode.PLAY_STORE_NOT_FOUND,
        AgeSignalsErrorCode.NETWORK_ERROR,
        AgeSignalsErrorCode.PLAY_SERVICES_NOT_FOUND,
        AgeSignalsErrorCode.CANNOT_BIND_TO_SERVICE,
        AgeSignalsErrorCode.PLAY_STORE_VERSION_OUTDATED,
        AgeSignalsErrorCode.PLAY_SERVICES_VERSION_OUTDATED,
        AgeSignalsErrorCode.CLIENT_TRANSIENT_ERROR -> true
        else -> false
    }

    private suspend fun <T> Task<T>.awaitOn(executor: Executor): T =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener(executor) { result ->
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }
            addOnFailureListener(executor) { exception ->
                if (continuation.isActive) {
                    continuation.resumeWithException(exception)
                }
            }
        }

    private companion object {
        const val ADULT_AGE = 18
        const val DEFAULT_MAX_RETRY_ATTEMPTS = 3
        const val DEFAULT_INITIAL_RETRY_DELAY_MS = 1_000L
    }
}
