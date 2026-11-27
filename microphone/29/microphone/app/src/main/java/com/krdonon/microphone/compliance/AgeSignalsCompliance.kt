package com.krdonon.microphone.compliance

import android.app.Activity
import android.content.Context
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
import com.google.android.play.agesignals.model.AgeRangeSource
import com.google.android.play.agesignals.model.AgeSignalsErrorCode
import com.google.android.play.agesignals.model.AgeSignalsStatus
import com.google.android.play.agesignals.model.ApplicableAgeFeature
import com.google.android.play.agesignals.model.SignificantChangeStatus
import java.lang.ref.WeakReference
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.time.Duration.Companion.milliseconds

/**
 * Runtime-only wrapper around Google Play Age Signals 0.0.6.
 *
 * Age-related values are deliberately reduced to a coarse compliance category and are not
 * written to DataStore, SharedPreferences, a database, or files.
 */
class AgeSignalsCompliance internal constructor(
    private val ageSignalsManager: AgeSignalsManager,
    private val scope: CoroutineScope,
    private val retryDelaysMs: List<Long> = DEFAULT_RETRY_DELAYS_MS,
    private val delayFn: suspend (Long) -> Unit = { delay(it.milliseconds) }
) {

    constructor(
        context: Context,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    ) : this(
        ageSignalsManager = AgeSignalsManagerFactory.create(context.applicationContext),
        scope = scope
    )

    private val _state = MutableStateFlow(AgeSignalsComplianceState())
    val state: StateFlow<AgeSignalsComplianceState> = _state.asStateFlow()

    private var refreshJob: Job? = null

    /**
     * Queries applicable age features and requests age-signals access first, as required by
     * Age Signals 0.0.4+. Actual age signals are queried via [AgeSignalsManager.checkAgeSignals]
     * only when Google Play reports [AgeSignalsStatus.SHARED].
     *
     * Retryable errors (e.g., transient client/service/network errors) are retried with bounded
     * backoff to avoid disrupting the user experience.
     */
    fun refresh(activity: Activity) {
        val activityRef = WeakReference(activity)
        refreshWithGateway(
            object : AgeSignalsGateway {
                override fun getApplicableAgeFeatures(): Task<ApplicableAgeFeaturesResult> {
                    return ageSignalsManager.getApplicableAgeFeatures(
                        ApplicableAgeFeaturesRequest.builder().build()
                    )
                }

                override fun requestAgeSignalsAccess(): Task<AgeSignalsAccessResult> {
                    val currentActivity = activityRef.get()
                        ?.takeUnless { it.isFinishing || it.isDestroyed }
                        ?: throw CancellationException("Activity is no longer active")
                    val accessRequest = AgeSignalsAccessRequest.builder()
                        .setActivity(currentActivity)
                        .build()
                    return ageSignalsManager.requestAgeSignalsAccess(accessRequest)
                }

                override fun checkAgeSignals(): Task<AgeSignalsResult> {
                    return ageSignalsManager.checkAgeSignals(
                        AgeSignalsRequest.builder().build()
                    )
                }
            }
        )
    }

    internal fun refreshWithGateway(gateway: AgeSignalsGateway) {
        refreshJob?.cancel()

        refreshJob = scope.launch {
            _state.value = AgeSignalsComplianceState(isLoading = true)

            val mandatorySharing = fetchMandatorySharingOrNull(gateway)

            val accessResult = runWithRetry {
                gateway.requestAgeSignalsAccess().awaitResult()
            }

            accessResult.fold(
                onSuccess = { result ->
                    when (result.ageSignalsStatus()) {
                        AgeSignalsStatus.SHARED -> {
                            _state.value = AgeSignalsComplianceState(
                                category = AgeCategory.UNKNOWN,
                                accessState = AgeSignalsAccessState.SHARED,
                                mandatorySharingApplicable = mandatorySharing,
                                isLoading = true
                            )
                            fetchAgeSignals(gateway, mandatorySharing)
                        }

                        AgeSignalsStatus.NOT_SHARED -> {
                            _state.value = AgeSignalsComplianceState(
                                category = AgeCategory.UNKNOWN,
                                accessState = AgeSignalsAccessState.NOT_SHARED,
                                mandatorySharingApplicable = mandatorySharing,
                                isLoading = false
                            )
                        }

                        AgeSignalsStatus.VERIFICATION_REQUIRED -> {
                            _state.value = AgeSignalsComplianceState(
                                category = AgeCategory.UNKNOWN,
                                accessState = AgeSignalsAccessState.VERIFICATION_REQUIRED,
                                mandatorySharingApplicable = mandatorySharing,
                                isLoading = false
                            )
                        }

                        else -> {
                            _state.value = AgeSignalsComplianceState(
                                category = AgeCategory.UNKNOWN,
                                accessState = AgeSignalsAccessState.UNKNOWN,
                                mandatorySharingApplicable = mandatorySharing,
                                isLoading = false
                            )
                        }
                    }
                },
                onFailure = { throwable ->
                    _state.value = mapFailureState(throwable, mandatorySharing)
                }
            )
        }
    }

    private suspend fun fetchApplicableAgeFeaturesSafe(gateway: AgeSignalsGateway): Boolean? {
        return try {
            val result = gateway.getApplicableAgeFeatures().awaitResult()
            ApplicableAgeFeature.MANDATORY_SHARING in result.features()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun fetchMandatorySharingOrNull(gateway: AgeSignalsGateway): Boolean? =
        fetchApplicableAgeFeaturesSafe(gateway)

    private suspend fun fetchAgeSignals(
        gateway: AgeSignalsGateway,
        mandatorySharing: Boolean?
    ) {
        val signalsResult = runWithRetry {
            gateway.checkAgeSignals().awaitResult()
        }

        signalsResult.fold(
            onSuccess = { result ->
                _state.value = AgeSignalsComplianceState(
                    category = classifyAgeRange(
                        ageLower = result.ageLower(),
                        ageUpper = result.ageUpper()
                    ),
                    accessState = AgeSignalsAccessState.SHARED,
                    verificationTier = mapAgeRangeSource(result.ageRangeSource()),
                    significantChangeState = mapSignificantChangeStatus(result.significantChangeStatus()),
                    mandatorySharingApplicable = mandatorySharing,
                    errorCode = null,
                    isLoading = false
                )
            },
            onFailure = { throwable ->
                _state.value = mapFailureState(throwable, mandatorySharing)
            }
        )
    }

    private suspend fun <T> runWithRetry(block: suspend () -> T): Result<T> {
        var attempt = 0
        while (true) {
            try {
                return Result.success(block())
            } catch (ce: CancellationException) {
                throw ce
            } catch (throwable: Throwable) {
                val errorCode = (throwable as? AgeSignalsException)?.errorCode
                val canRetry = errorCode != null &&
                    isRetryableErrorCode(errorCode) &&
                    attempt < retryDelaysMs.size
                if (!canRetry) {
                    return Result.failure(throwable)
                }
                delayFn(retryDelaysMs[attempt])
                attempt++
            }
        }
    }

    private fun mapFailureState(
        throwable: Throwable,
        mandatorySharing: Boolean?
    ): AgeSignalsComplianceState {
        val errorCode = (throwable as? AgeSignalsException)?.errorCode
        val accessState = if (errorCode == AgeSignalsErrorCode.DEVICE_NOT_SUPPORTED) {
            AgeSignalsAccessState.DEVICE_NOT_SUPPORTED
        } else {
            AgeSignalsAccessState.ERROR
        }
        return AgeSignalsComplianceState(
            category = AgeCategory.UNKNOWN,
            accessState = accessState,
            mandatorySharingApplicable = mandatorySharing,
            errorCode = errorCode,
            isLoading = false
        )
    }

    /**
     * Classification is intentionally conservative and remains correct if Play Console custom
     * age bands are configured later:
     * - every possible age in the returned band is < 18 -> MINOR
     * - every possible age in the returned band is >= 18 -> ADULT
     * - a missing or mixed/ambiguous band -> UNKNOWN
     */
    internal fun classifyAgeRange(ageLower: Int?, ageUpper: Int?): AgeCategory {
        return when {
            ageLower != null && ageLower >= ADULT_AGE -> AgeCategory.ADULT
            ageLower != null && ageUpper != null && ageUpper < ADULT_AGE -> AgeCategory.MINOR
            else -> AgeCategory.UNKNOWN
        }
    }

    internal fun mapAgeRangeSource(source: Int?): AgeRangeVerificationTier {
        return when (source) {
            AgeRangeSource.TIER_A -> AgeRangeVerificationTier.SELF_DECLARED
            AgeRangeSource.TIER_B -> AgeRangeVerificationTier.SUPERVISED
            AgeRangeSource.TIER_C -> AgeRangeVerificationTier.ASSESSED
            AgeRangeSource.TIER_D -> AgeRangeVerificationTier.VERIFIED
            else -> AgeRangeVerificationTier.UNKNOWN
        }
    }

    internal fun mapSignificantChangeStatus(status: Int?): SignificantChangeState {
        return when (status) {
            SignificantChangeStatus.APPROVED -> SignificantChangeState.APPROVED
            SignificantChangeStatus.PENDING -> SignificantChangeState.PENDING
            SignificantChangeStatus.DECLINED -> SignificantChangeState.DECLINED
            else -> SignificantChangeState.NONE
        }
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
            AgeSignalsErrorCode.CLIENT_TRANSIENT_ERROR -> true
            else -> false
        }
    }

    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener(DIRECT_EXECUTOR) { result ->
            if (continuation.isActive) {
                continuation.resume(result)
            }
        }
        addOnFailureListener(DIRECT_EXECUTOR) { exception ->
            if (continuation.isActive) {
                continuation.resumeWithException(exception)
            }
        }
        addOnCanceledListener(DIRECT_EXECUTOR) {
            continuation.cancel()
        }
    }

    private companion object {
        const val ADULT_AGE = 18
        val DEFAULT_RETRY_DELAYS_MS = listOf(1_000L, 2_000L)
        val DIRECT_EXECUTOR = Executor { runnable -> runnable.run() }
    }
}

internal interface AgeSignalsGateway {
    fun getApplicableAgeFeatures(): Task<ApplicableAgeFeaturesResult>
    fun requestAgeSignalsAccess(): Task<AgeSignalsAccessResult>
    fun checkAgeSignals(): Task<AgeSignalsResult>
}

enum class AgeCategory {
    MINOR,
    ADULT,
    UNKNOWN
}

enum class AgeSignalsAccessState {
    SHARED,
    NOT_SHARED,
    VERIFICATION_REQUIRED,
    DEVICE_NOT_SUPPORTED,
    ERROR,
    UNKNOWN
}

enum class AgeRangeVerificationTier {
    SELF_DECLARED,
    SUPERVISED,
    ASSESSED,
    VERIFIED,
    UNKNOWN
}

enum class SignificantChangeState {
    APPROVED,
    PENDING,
    DECLINED,
    NONE
}

data class AgeSignalsComplianceState(
    val category: AgeCategory = AgeCategory.UNKNOWN,
    val accessState: AgeSignalsAccessState = AgeSignalsAccessState.UNKNOWN,
    val verificationTier: AgeRangeVerificationTier = AgeRangeVerificationTier.UNKNOWN,
    val significantChangeState: SignificantChangeState = SignificantChangeState.NONE,
    val mandatorySharingApplicable: Boolean? = null,
    val errorCode: Int? = null,
    val isLoading: Boolean = false
)
