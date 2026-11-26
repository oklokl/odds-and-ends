package com.krdonon.touch

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
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
import com.google.android.play.agesignals.model.SignificantChangeStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference
import java.util.Date
import java.util.concurrent.Executor

/**
 * 앱 내부에서 사용할 최소 연령 분류.
 *
 * Play Age Signals의 원본 응답은 영구 저장하지 않으며, 현재 프로세스 메모리에서만
 * MINOR / ADULT / UNKNOWN으로 축약해 유지합니다.
 */
enum class AgeCategory {
    MINOR,
    ADULT,
    UNKNOWN
}

/**
 * 현재 앱 세션 메모리에만 유지하는 Play Age Signals 0.0.6 상태.
 */
data class AgeSignalsState(
    val category: AgeCategory = AgeCategory.UNKNOWN,
    val accessStatus: Int? = null,
    val isMandatorySharing: Boolean? = null,
    val ageLower: Int? = null,
    val ageUpper: Int? = null,
    val ageRangeSource: Int? = null,
    val significantChangeStatus: Int? = null,
    val significantChangeApprovalDate: Date? = null,
    val installId: String? = null,
    val errorCode: Int? = null,
    val isLoading: Boolean = false
) {
    /**
     * 의무 연령 확인 지역에서 사용자가 Play 스토어를 통해 연령 확인을 완료해야 하는 경우.
     */
    val requiresPlayStoreVerification: Boolean
        get() = accessStatus == AgeSignalsStatus.VERIFICATION_REQUIRED

    /**
     * 감독 대상 계정에서 중요 변경 사항에 대한 보호자 승인이 대기 중인 경우.
     */
    val isSignificantChangePending: Boolean
        get() = significantChangeStatus == SignificantChangeStatus.PENDING

    /**
     * 감독 대상 계정에서 중요 변경 사항에 대한 보호자 승인이 거절된 경우.
     */
    val isSignificantChangeDeclined: Boolean
        get() = significantChangeStatus == SignificantChangeStatus.DECLINED

    /**
     * 재시도로 복구될 가능성이 있는 일시적 오류 여부.
     */
    val hasRetryableError: Boolean
        get() = errorCode != null && AgeSignalsCompliance.isRetryableErrorCode(errorCode)
}

internal interface ActivityHandle {
    val isActive: Boolean
    val activity: Activity?
}

private class WeakActivityHandle(activity: Activity) : ActivityHandle {
    private val ref = WeakReference(activity)

    override val activity: Activity?
        get() = ref.get()

    override val isActive: Boolean
        get() = activity?.let { !it.isFinishing && !it.isDestroyed } == true
}

internal interface AgeSignalsClient {
    fun getApplicableAgeFeatures(): Task<ApplicableAgeFeaturesResult>
    fun requestAgeSignalsAccess(activityHandle: ActivityHandle): Task<AgeSignalsAccessResult>
    fun checkAgeSignals(): Task<AgeSignalsResult>
}

private class PlayAgeSignalsClient(
    private val manager: AgeSignalsManager
) : AgeSignalsClient {
    override fun getApplicableAgeFeatures(): Task<ApplicableAgeFeaturesResult> =
        manager.getApplicableAgeFeatures(ApplicableAgeFeaturesRequest.builder().build())

    override fun requestAgeSignalsAccess(activityHandle: ActivityHandle): Task<AgeSignalsAccessResult> {
        val activity = activityHandle.activity
            ?: return Tasks.forException(IllegalStateException("Activity is no longer available"))
        return manager.requestAgeSignalsAccess(
            AgeSignalsAccessRequest.builder()
                .setActivity(activity)
                .build()
        )
    }

    override fun checkAgeSignals(): Task<AgeSignalsResult> =
        manager.checkAgeSignals(AgeSignalsRequest.builder().build())
}

internal interface RetryScheduler {
    fun schedule(delayMs: Long, block: () -> Unit)
    fun cancelAll()
}

private class MainHandlerRetryScheduler : RetryScheduler {
    private val handler = Handler(Looper.getMainLooper())
    private val token = Any()

    override fun schedule(delayMs: Long, block: () -> Unit) {
        handler.postAtTime(
            { block() },
            token,
            SystemClock.uptimeMillis() + delayMs
        )
    }

    override fun cancelAll() {
        handler.removeCallbacksAndMessages(token)
    }
}

/**
 * Google Play Age Signals SDK 0.0.6 런타임 연동 클래스.
 *
 * 공식 호출 순서:
 * 1) getApplicableAgeFeatures() (0.0.6: 의무 공유 대상 지역 여부 확인)
 * 2) requestAgeSignalsAccess(activity)
 * 3) AgeSignalsStatus.SHARED인 경우에만 checkAgeSignals()
 *
 * 일시적 오류(NETWORK_ERROR, CLIENT_TRANSIENT_ERROR 등)는 제한된 횟수로 지수 백오프 재시도하며,
 * 광고, 마케팅, 프로파일링, 분석 용도로 사용하지 않습니다.
 */
class AgeSignalsCompliance internal constructor(
    private val client: AgeSignalsClient,
    private val callbackExecutor: Executor,
    private val retryDelaysMs: List<Long> = DEFAULT_RETRY_DELAYS_MS,
    private val retryScheduler: RetryScheduler = MainHandlerRetryScheduler()
) {

    constructor(context: Context) : this(
        client = PlayAgeSignalsClient(
            AgeSignalsManagerFactory.create(context.applicationContext)
        ),
        callbackExecutor = ContextCompat.getMainExecutor(context.applicationContext)
    )

    private val _state = MutableStateFlow(AgeSignalsState())
    val state: StateFlow<AgeSignalsState> = _state.asStateFlow()

    val currentState: AgeSignalsState
        get() = _state.value

    val currentCategory: AgeCategory
        get() = _state.value.category

    private var refreshGeneration = 0L

    /**
     * 현재 Activity를 이용해 적용 가능한 연령 기능과 공유 상태를 확인한 뒤 실제 연령 신호를 조회합니다.
     *
     * NOT_SHARED, VERIFICATION_REQUIRED, API 오류, 값 누락처럼 확실하게 판정할 수 없는
     * 모든 경우는 보수적으로 AgeCategory.UNKNOWN으로 처리합니다.
     */
    fun refresh(
        activity: Activity,
        onResult: (AgeCategory) -> Unit = {}
    ) {
        refreshState(activity) { updatedState ->
            onResult(updatedState.category)
        }
    }

    /**
     * 전체 [AgeSignalsState] 결과를 콜백으로 전달받는 갱신 메서드.
     */
    fun refreshState(
        activity: Activity,
        onState: (AgeSignalsState) -> Unit = {}
    ) {
        startRefresh(WeakActivityHandle(activity), onState)
    }

    internal fun refreshStateForTest(
        activityHandle: ActivityHandle,
        onState: (AgeSignalsState) -> Unit = {}
    ) {
        startRefresh(activityHandle, onState)
    }

    private fun startRefresh(
        activityHandle: ActivityHandle,
        onState: (AgeSignalsState) -> Unit
    ) {
        val generation = ++refreshGeneration
        retryScheduler.cancelAll()
        _state.value = AgeSignalsState(isLoading = true)

        fetchApplicableFeatures(
            generation = generation,
            activityHandle = activityHandle,
            attempt = 0,
            onState = onState
        )
    }

    /**
     * 진행 중이거나 예약된 재시도 작업을 취소합니다.
     */
    fun cancel() {
        refreshGeneration++
        retryScheduler.cancelAll()
        if (_state.value.isLoading) {
            _state.value = _state.value.copy(isLoading = false)
        }
    }

    private fun fetchApplicableFeatures(
        generation: Long,
        activityHandle: ActivityHandle,
        attempt: Int,
        onState: (AgeSignalsState) -> Unit
    ) {
        if (generation != refreshGeneration) return

        client.getApplicableAgeFeatures()
            .addOnSuccessListener(callbackExecutor) { featuresResult ->
                if (generation != refreshGeneration) return@addOnSuccessListener
                val isMandatory = featuresResult.features()
                    .contains(ApplicableAgeFeature.MANDATORY_SHARING)
                requestAccess(
                    generation = generation,
                    activityHandle = activityHandle,
                    isMandatorySharing = isMandatory,
                    attempt = 0,
                    onState = onState
                )
            }
            .addOnFailureListener(callbackExecutor) { exception ->
                if (generation != refreshGeneration) return@addOnFailureListener
                val errorCode = extractErrorCode(exception)

                if (shouldRetry(errorCode, attempt)) {
                    scheduleRetry(generation, attempt) {
                        fetchApplicableFeatures(generation, activityHandle, attempt + 1, onState)
                    }
                    return@addOnFailureListener
                }

                // 구버전 Play 스토어 등에서 getApplicableAgeFeatures만 미지원(API_NOT_AVAILABLE)인 경우
                // requestAgeSignalsAccess로 폴백 시도합니다.
                if (errorCode == AgeSignalsErrorCode.API_NOT_AVAILABLE) {
                    requestAccess(
                        generation = generation,
                        activityHandle = activityHandle,
                        isMandatorySharing = null,
                        attempt = 0,
                        onState = onState
                    )
                } else {
                    publishState(
                        generation = generation,
                        newState = AgeSignalsState(
                            category = AgeCategory.UNKNOWN,
                            errorCode = errorCode,
                            isLoading = false
                        ),
                        onState = onState
                    )
                }
            }
    }

    private fun requestAccess(
        generation: Long,
        activityHandle: ActivityHandle,
        isMandatorySharing: Boolean?,
        attempt: Int,
        onState: (AgeSignalsState) -> Unit
    ) {
        if (generation != refreshGeneration) return

        if (!activityHandle.isActive) {
            publishState(
                generation = generation,
                newState = AgeSignalsState(
                    category = AgeCategory.UNKNOWN,
                    isMandatorySharing = isMandatorySharing,
                    isLoading = false
                ),
                onState = onState
            )
            return
        }

        client.requestAgeSignalsAccess(activityHandle)
            .addOnSuccessListener(callbackExecutor) { accessResult ->
                if (generation != refreshGeneration) return@addOnSuccessListener
                val status = accessResult.ageSignalsStatus()
                if (status == AgeSignalsStatus.SHARED) {
                    checkSharedAgeSignals(
                        generation = generation,
                        isMandatorySharing = isMandatorySharing,
                        attempt = 0,
                        onState = onState
                    )
                } else {
                    publishState(
                        generation = generation,
                        newState = AgeSignalsState(
                            category = AgeCategory.UNKNOWN,
                            accessStatus = status,
                            isMandatorySharing = isMandatorySharing,
                            isLoading = false
                        ),
                        onState = onState
                    )
                }
            }
            .addOnFailureListener(callbackExecutor) { exception ->
                if (generation != refreshGeneration) return@addOnFailureListener
                val errorCode = extractErrorCode(exception)

                if (shouldRetry(errorCode, attempt)) {
                    scheduleRetry(generation, attempt) {
                        requestAccess(generation, activityHandle, isMandatorySharing, attempt + 1, onState)
                    }
                    return@addOnFailureListener
                }

                publishState(
                    generation = generation,
                    newState = AgeSignalsState(
                        category = AgeCategory.UNKNOWN,
                        isMandatorySharing = isMandatorySharing,
                        errorCode = errorCode,
                        isLoading = false
                    ),
                    onState = onState
                )
            }
    }

    private fun checkSharedAgeSignals(
        generation: Long,
        isMandatorySharing: Boolean?,
        attempt: Int,
        onState: (AgeSignalsState) -> Unit
    ) {
        if (generation != refreshGeneration) return

        client.checkAgeSignals()
            .addOnSuccessListener(callbackExecutor) { result ->
                if (generation != refreshGeneration) return@addOnSuccessListener
                val ageLower = result.ageLower()
                val ageUpper = result.ageUpper()
                val category = classifyAgeRange(ageLower = ageLower, ageUpper = ageUpper)

                publishState(
                    generation = generation,
                    newState = AgeSignalsState(
                        category = category,
                        accessStatus = AgeSignalsStatus.SHARED,
                        isMandatorySharing = isMandatorySharing,
                        ageLower = ageLower,
                        ageUpper = ageUpper,
                        ageRangeSource = result.ageRangeSource(),
                        significantChangeStatus = result.significantChangeStatus(),
                        significantChangeApprovalDate = result.significantChangeApprovalDate(),
                        installId = result.installId(),
                        errorCode = null,
                        isLoading = false
                    ),
                    onState = onState
                )
            }
            .addOnFailureListener(callbackExecutor) { exception ->
                if (generation != refreshGeneration) return@addOnFailureListener
                val errorCode = extractErrorCode(exception)

                if (shouldRetry(errorCode, attempt)) {
                    scheduleRetry(generation, attempt) {
                        checkSharedAgeSignals(generation, isMandatorySharing, attempt + 1, onState)
                    }
                    return@addOnFailureListener
                }

                publishState(
                    generation = generation,
                    newState = AgeSignalsState(
                        category = AgeCategory.UNKNOWN,
                        accessStatus = AgeSignalsStatus.SHARED,
                        isMandatorySharing = isMandatorySharing,
                        errorCode = errorCode,
                        isLoading = false
                    ),
                    onState = onState
                )
            }
    }

    private fun shouldRetry(errorCode: Int?, attempt: Int): Boolean {
        return errorCode != null &&
            attempt < retryDelaysMs.size &&
            isRetryableErrorCode(errorCode)
    }

    private fun scheduleRetry(
        generation: Long,
        attempt: Int,
        block: () -> Unit
    ) {
        val delayMs = retryDelaysMs[attempt]
        retryScheduler.schedule(delayMs) {
            if (generation == refreshGeneration) {
                block()
            }
        }
    }

    private fun publishState(
        generation: Long,
        newState: AgeSignalsState,
        onState: (AgeSignalsState) -> Unit
    ) {
        if (generation != refreshGeneration) return
        _state.value = newState
        onState(newState)
    }

    companion object {
        private const val ADULT_AGE = 18
        internal val DEFAULT_RETRY_DELAYS_MS = listOf(500L, 1500L)

        internal fun extractErrorCode(exception: Throwable): Int? =
            (exception as? AgeSignalsException)?.errorCode

        internal fun isRetryableErrorCode(errorCode: Int): Boolean = when (errorCode) {
            AgeSignalsErrorCode.API_NOT_AVAILABLE,
            AgeSignalsErrorCode.NETWORK_ERROR,
            AgeSignalsErrorCode.CANNOT_BIND_TO_SERVICE,
            AgeSignalsErrorCode.CLIENT_TRANSIENT_ERROR -> true
            else -> false
        }

        /**
         * Age Signals가 반환한 범위만으로 확실하게 판정 가능한 경우에만 분류합니다.
         *
         * - lower >= 18          -> ADULT
         * - upper < 18           -> MINOR
         * - 경계가 18세를 걸치거나 값이 부족함 -> UNKNOWN
         *
         * 이렇게 하면 Play Console의 custom age ranges가 15+처럼 18세 경계를 포함하는
         * 열린 범위를 반환하더라도 성인/미성년자로 임의 추정하지 않습니다.
         */
        internal fun classifyAgeRange(
            ageLower: Int?,
            ageUpper: Int?
        ): AgeCategory {
            if (ageLower == null) return AgeCategory.UNKNOWN

            if (ageLower >= ADULT_AGE) {
                return AgeCategory.ADULT
            }

            if (ageUpper != null && ageUpper < ADULT_AGE) {
                return AgeCategory.MINOR
            }

            return AgeCategory.UNKNOWN
        }
    }
}
