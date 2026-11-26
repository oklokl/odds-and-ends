package com.krdondon.txt.compliance

import android.app.Activity
import android.content.Context
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Task
import com.google.android.play.agesignals.AgeSignalsAccessRequest
import com.google.android.play.agesignals.AgeSignalsAccessResult
import com.google.android.play.agesignals.AgeSignalsException
import com.google.android.play.agesignals.AgeSignalsManager
import com.google.android.play.agesignals.AgeSignalsManagerFactory
import com.google.android.play.agesignals.AgeSignalsRequest
import com.google.android.play.agesignals.AgeSignalsResult
import com.google.android.play.agesignals.ApplicableAgeFeaturesRequest
import com.google.android.play.agesignals.ApplicableAgeFeaturesResult
import com.google.android.play.agesignals.model.AgeSignalsErrorCode
import com.google.android.play.agesignals.model.AgeSignalsStatus
import com.google.android.play.agesignals.model.ApplicableAgeFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.ref.WeakReference
import java.util.Date
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.milliseconds

/**
 * Runtime-only wrapper for Google Play Age Signals (0.0.6+).
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

enum class AgeSignalsCheckStatus {
    IDLE,
    LOADING,
    SHARED,
    NOT_SHARED,
    VERIFICATION_REQUIRED,
    UNSPECIFIED,
    ERROR
}

data class AgeSignalsState(
    val checkStatus: AgeSignalsCheckStatus = AgeSignalsCheckStatus.IDLE,
    val classification: AgeClassification = AgeClassification.UNKNOWN,
    val ageLower: Int? = null,
    val ageUpper: Int? = null,
    val ageRangeSource: Int? = null,
    val significantChangeStatus: Int? = null,
    val significantChangeApprovalDate: Date? = null,
    val installId: String? = null,
    val isMandatorySharingApplicable: Boolean? = null,
    val errorCode: Int? = null
)

internal interface AgeSignalsClient {
    suspend fun getApplicableAgeFeatures(): ApplicableAgeFeaturesResult
    suspend fun requestAgeSignalsAccess(activity: Activity): AgeSignalsAccessResult
    suspend fun checkAgeSignals(): AgeSignalsResult
}

internal class PlayAgeSignalsClient(
    private val manager: AgeSignalsManager,
    private val callbackExecutor: Executor
) : AgeSignalsClient {
    override suspend fun getApplicableAgeFeatures(): ApplicableAgeFeaturesResult =
        manager.getApplicableAgeFeatures(ApplicableAgeFeaturesRequest.builder().build())
            .awaitWithExecutor(callbackExecutor)

    override suspend fun requestAgeSignalsAccess(activity: Activity): AgeSignalsAccessResult =
        manager.requestAgeSignalsAccess(
            AgeSignalsAccessRequest.builder()
                .setActivity(activity)
                .build()
        ).awaitWithExecutor(callbackExecutor)

    override suspend fun checkAgeSignals(): AgeSignalsResult =
        manager.checkAgeSignals(AgeSignalsRequest.builder().build())
            .awaitWithExecutor(callbackExecutor)
}

class AgeSignalsCompliance internal constructor(
    private val client: AgeSignalsClient,
    private val isActivityUsable: (Activity) -> Boolean = { !it.isFinishing && !it.isDestroyed },
    private val retryDelaysMs: List<Long> = DEFAULT_RETRY_DELAYS_MS
) {
    constructor(context: Context) : this(
        client = PlayAgeSignalsClient(
            manager = AgeSignalsManagerFactory.create(context.applicationContext),
            callbackExecutor = ContextCompat.getMainExecutor(context.applicationContext)
        )
    )

    private val mutex = Mutex()
    private val _state = MutableStateFlow(AgeSignalsState())
    val state: StateFlow<AgeSignalsState> = _state.asStateFlow()

    /**
     * Official 0.0.6 flow:
     * 1. Best-effort check of getApplicableAgeFeatures() (MANDATORY_SHARING).
     * 2. requestAgeSignalsAccess(activity) with retry on transient errors.
     * 3. If SHARED -> checkAgeSignals() with retry on transient errors.
     */
    suspend fun refresh(activity: Activity): AgeSignalsState {
        val activityRef = WeakReference(activity)
        return refreshInternal(
            isUsable = {
                val active = activityRef.get()
                active != null && isActivityUsable(active)
            },
            requestAccess = {
                val active = activityRef.get()
                if (active == null || !isActivityUsable(active)) {
                    throw CancellationException("Activity is no longer usable")
                }
                client.requestAgeSignalsAccess(active)
            }
        )
    }

    internal suspend fun refreshInternal(
        isUsable: () -> Boolean,
        requestAccess: suspend () -> AgeSignalsAccessResult
    ): AgeSignalsState = mutex.withLock {
        if (!isUsable()) {
            return _state.value
        }

        _state.value = _state.value.copy(
            checkStatus = AgeSignalsCheckStatus.LOADING,
            errorCode = null
        )

        val mandatorySharing = runCatching {
            val featuresResult = client.getApplicableAgeFeatures()
            ApplicableAgeFeature.MANDATORY_SHARING in featuresResult.features()
        }.getOrNull()

        val newState = try {
            val accessResult = withRetry {
                if (!isUsable()) {
                    throw CancellationException("Activity is no longer usable")
                }
                requestAccess()
            }

            when (accessResult.ageSignalsStatus()) {
                AgeSignalsStatus.SHARED -> {
                    val result = withRetry { client.checkAgeSignals() }
                    val ageLower = result.ageLower()
                    val ageUpper = result.ageUpper()
                    AgeSignalsState(
                        checkStatus = AgeSignalsCheckStatus.SHARED,
                        classification = classifyAgeRange(ageLower, ageUpper),
                        ageLower = ageLower,
                        ageUpper = ageUpper,
                        ageRangeSource = result.ageRangeSource(),
                        significantChangeStatus = result.significantChangeStatus(),
                        significantChangeApprovalDate = result.significantChangeApprovalDate(),
                        installId = result.installId(),
                        isMandatorySharingApplicable = mandatorySharing,
                        errorCode = null
                    )
                }

                AgeSignalsStatus.NOT_SHARED -> AgeSignalsState(
                    checkStatus = AgeSignalsCheckStatus.NOT_SHARED,
                    classification = AgeClassification.UNKNOWN,
                    isMandatorySharingApplicable = mandatorySharing
                )

                AgeSignalsStatus.VERIFICATION_REQUIRED -> AgeSignalsState(
                    checkStatus = AgeSignalsCheckStatus.VERIFICATION_REQUIRED,
                    classification = AgeClassification.UNKNOWN,
                    isMandatorySharingApplicable = mandatorySharing
                )

                else -> AgeSignalsState(
                    checkStatus = AgeSignalsCheckStatus.UNSPECIFIED,
                    classification = AgeClassification.UNKNOWN,
                    isMandatorySharingApplicable = mandatorySharing
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            val errorCode = (t as? AgeSignalsException)?.errorCode
            AgeSignalsState(
                checkStatus = AgeSignalsCheckStatus.ERROR,
                classification = AgeClassification.UNKNOWN,
                isMandatorySharingApplicable = mandatorySharing,
                errorCode = errorCode
            )
        }

        _state.value = newState
        return newState
    }

    fun shouldRecheckOnResume(): Boolean {
        val current = _state.value
        return when (current.checkStatus) {
            AgeSignalsCheckStatus.IDLE,
            AgeSignalsCheckStatus.VERIFICATION_REQUIRED -> true
            AgeSignalsCheckStatus.ERROR -> current.errorCode?.let { isRetryableOnResume(it) } ?: true
            else -> false
        }
    }

    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: AgeSignalsException) {
                if (!isRetryableErrorCode(e.errorCode) || attempt >= retryDelaysMs.size) {
                    throw e
                }
                val delayMs = retryDelaysMs[attempt]
                attempt++
                if (delayMs > 0L) {
                    delay(delayMs.milliseconds)
                }
            }
        }
    }

    companion object {
        private const val ADULT_AGE = 18
        private val DEFAULT_RETRY_DELAYS_MS = listOf(500L, 1500L)

        /**
         * Safely maps an age band to the app's three internal states.
         *
         * Also remains safe if custom age ranges are configured in Play Console:
         * - A band wholly below 18 is MINOR.
         * - A band whose lower bound is 18+ is ADULT.
         * - A band that could contain both minors and adults is UNKNOWN.
         */
        fun classifyAgeRange(ageLower: Int?, ageUpper: Int?): AgeClassification {
            if (ageLower == null) return AgeClassification.UNKNOWN
            if (ageLower >= ADULT_AGE) return AgeClassification.ADULT
            if (ageUpper != null && ageUpper < ADULT_AGE) return AgeClassification.MINOR
            return AgeClassification.UNKNOWN
        }

        fun isRetryableErrorCode(errorCode: Int): Boolean = when (errorCode) {
            AgeSignalsErrorCode.API_NOT_AVAILABLE,
            AgeSignalsErrorCode.NETWORK_ERROR,
            AgeSignalsErrorCode.CANNOT_BIND_TO_SERVICE,
            AgeSignalsErrorCode.CLIENT_TRANSIENT_ERROR,
            AgeSignalsErrorCode.INTERNAL_ERROR -> true
            else -> false
        }

        fun isRetryableOnResume(errorCode: Int): Boolean = when (errorCode) {
            AgeSignalsErrorCode.APP_NOT_OWNED,
            AgeSignalsErrorCode.SDK_VERSION_OUTDATED,
            AgeSignalsErrorCode.DEVICE_NOT_SUPPORTED -> false
            else -> true
        }
    }
}

private suspend fun <T> Task<T>.awaitWithExecutor(executor: Executor): T =
    suspendCancellableCoroutine { cont ->
        addOnSuccessListener(executor) { result ->
            if (cont.isActive) {
                cont.resume(result)
            }
        }
        addOnFailureListener(executor) { exception ->
            if (cont.isActive) {
                cont.resumeWithException(exception)
            }
        }
    }
