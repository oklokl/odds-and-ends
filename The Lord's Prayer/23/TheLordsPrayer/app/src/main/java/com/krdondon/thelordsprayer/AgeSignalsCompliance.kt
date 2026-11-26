package com.krdondon.thelordsprayer

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
import com.google.android.play.agesignals.ApplicableAgeFeaturesRequest
import com.google.android.play.agesignals.model.AgeSignalsErrorCode
import com.google.android.play.agesignals.model.AgeSignalsStatus
import com.google.android.play.agesignals.model.ApplicableAgeFeature
import java.lang.ref.WeakReference
import java.util.Date
import java.util.concurrent.Executor

/**
 * 앱 내부에서만 사용하는 연령 분류입니다.
 *
 * Play Age Signals 값은 법률 준수를 위한 연령 적합 경험 제공에만 사용하며,
 * 광고 타기팅/마케팅/프로파일링/분석 용도로 사용하거나 영구 저장하지 않습니다.
 */
enum class AgeComplianceCategory {
    MINOR,
    ADULT,
    UNKNOWN,
}

/**
 * Play Age Signals API 접근 요청(`requestAgeSignalsAccess`)의 결과 상태입니다.
 */
enum class AgeSignalsAccessOutcome {
    SHARED,
    NOT_SHARED,
    VERIFICATION_REQUIRED,
    ERROR,
    UNKNOWN,
}

/**
 * Google Play Age Signals API 0.0.6 조회 결과 모델(메모리 전용, 영구 저장 금지).
 */
data class AgeComplianceResult(
    val category: AgeComplianceCategory,
    val accessOutcome: AgeSignalsAccessOutcome,
    val ageLower: Int? = null,
    val ageUpper: Int? = null,
    val ageRangeSource: Int? = null,
    val significantChangeStatus: Int? = null,
    val significantChangeApprovalDate: Date? = null,
    val installId: String? = null,
    val errorCode: Int? = null,
)

/**
 * Google Play Age Signals API 0.0.6 연동 클래스.
 *
 * 공식 호출 순서:
 * 1) AgeSignalsManagerFactory.create(...)
 * 2) requestAgeSignalsAccess(...)
 * 3) SHARED인 경우에만 checkAgeSignals(...)
 *
 * 일시적 오류(CLIENT_TRANSIENT_ERROR, CANNOT_BIND_TO_SERVICE, NETWORK_ERROR)는
 * 최대 [MAX_RETRY_ATTEMPTS]회까지 지수 백오프로 재시도합니다.
 */
class AgeSignalsCompliance internal constructor(
    private val managerProvider: () -> AgeSignalsManager,
    private val callbackExecutor: Executor,
    private val delayScheduler: (delayMs: Long, action: () -> Unit) -> Unit,
    private val logger: (message: String, throwable: Throwable?) -> Unit = { message, throwable ->
        if (throwable != null) {
            Log.w(TAG, message, throwable)
        } else {
            Log.w(TAG, message)
        }
    },
) {

    constructor(context: Context) : this(
        managerProvider = { AgeSignalsManagerFactory.create(context.applicationContext) },
        callbackExecutor = ContextCompat.getMainExecutor(context.applicationContext),
        delayScheduler = createMainHandlerDelayScheduler(),
    )

    // 실제 접근 시 생성하여, 생성 단계에서 예외가 발생해도 UNKNOWN으로 안전하게 처리할 수 있게 합니다.
    private val ageSignalsManager: AgeSignalsManager by lazy {
        managerProvider()
    }

    /**
     * 현재 사용자의 연령 신호 공유 가능 여부를 확인한 뒤 연령대 분류만 전달합니다.
     * 결과는 콜백으로만 전달하며 SharedPreferences/파일/DB 등에 저장하지 않습니다.
     */
    @Suppress("unused")
    fun requestAndCheck(
        activity: Activity,
        onResult: (AgeComplianceCategory) -> Unit,
    ) {
        requestAndCheckDetails(activity) { result ->
            onResult(result.category)
        }
    }

    /**
     * 현재 사용자의 연령 신호 공유 가능 여부, 연령 구간, 중대한 변경 승인 상태 등을 확인합니다.
     * 결과는 메모리에서만 사용하고 영구 저장하지 않습니다.
     */
    fun requestAndCheckDetails(
        activity: Activity,
        onResult: (AgeComplianceResult) -> Unit,
    ) {
        val weakActivity = WeakReference(activity)
        requestAndCheckInternal(
            isHostActive = {
                val current = weakActivity.get()
                current != null && !current.isFinishing && !current.isDestroyed
            },
            accessTaskProvider = { manager ->
                val current = weakActivity.get()
                    ?: return@requestAndCheckInternal null
                val accessRequest = AgeSignalsAccessRequest.builder()
                    .setActivity(current)
                    .build()
                manager.requestAgeSignalsAccess(accessRequest)
            },
            onResult = onResult,
        )
    }

    /**
     * Play Age Signals 0.0.6에 추가된 적용 가능 기능 목록(예: [ApplicableAgeFeature.MANDATORY_SHARING])을 조회합니다.
     */
    @Suppress("unused")
    fun checkApplicableAgeFeatures(onResult: (Set<Int>) -> Unit) {
        try {
            val request = ApplicableAgeFeaturesRequest.builder().build()
            ageSignalsManager.getApplicableAgeFeatures(request)
                .addOnSuccessListener(callbackExecutor) { result ->
                    onResult(result.features())
                }
                .addOnFailureListener(callbackExecutor) { exception ->
                    logFailure(exception)
                    onResult(emptySet())
                }
        } catch (exception: Exception) {
            logFailure(exception)
            onResult(emptySet())
        }
    }

    internal fun requestAndCheckInternal(
        isHostActive: () -> Boolean,
        accessTaskProvider: (AgeSignalsManager) -> Task<AgeSignalsAccessResult>?,
        onResult: (AgeComplianceResult) -> Unit,
        attempt: Int = 0,
    ) {
        if (!isHostActive()) return

        try {
            val task = accessTaskProvider(ageSignalsManager)
            if (task == null) {
                return
            }

            task
                .addOnSuccessListener(callbackExecutor) { accessResult ->
                    if (!isHostActive()) return@addOnSuccessListener

                    when (accessResult.ageSignalsStatus()) {
                        AgeSignalsStatus.SHARED -> {
                            checkAgeSignals(
                                isHostActive = isHostActive,
                                onResult = onResult,
                            )
                        }

                        // 사용자가 공유하지 않았거나, Play Store에서 연령 확인이 필요한 경우
                        // 실제 연령을 추측하지 않고 UNKNOWN으로 처리합니다.
                        AgeSignalsStatus.NOT_SHARED -> {
                            onResult(
                                AgeComplianceResult(
                                    category = AgeComplianceCategory.UNKNOWN,
                                    accessOutcome = AgeSignalsAccessOutcome.NOT_SHARED,
                                ),
                            )
                        }

                        AgeSignalsStatus.VERIFICATION_REQUIRED -> {
                            onResult(
                                AgeComplianceResult(
                                    category = AgeComplianceCategory.UNKNOWN,
                                    accessOutcome = AgeSignalsAccessOutcome.VERIFICATION_REQUIRED,
                                ),
                            )
                        }

                        else -> {
                            onResult(
                                AgeComplianceResult(
                                    category = AgeComplianceCategory.UNKNOWN,
                                    accessOutcome = AgeSignalsAccessOutcome.UNKNOWN,
                                ),
                            )
                        }
                    }
                }
                .addOnFailureListener(callbackExecutor) { exception ->
                    if (!isHostActive()) return@addOnFailureListener

                    if (shouldRetry(exception, attempt)) {
                        val delayMs = retryDelayMillis(attempt)
                        delayScheduler(delayMs) {
                            requestAndCheckInternal(
                                isHostActive = isHostActive,
                                accessTaskProvider = accessTaskProvider,
                                onResult = onResult,
                                attempt = attempt + 1,
                            )
                        }
                    } else {
                        handleFailure(exception, onResult)
                    }
                }
        } catch (exception: Exception) {
            handleFailure(exception, onResult)
        }
    }

    private fun checkAgeSignals(
        isHostActive: () -> Boolean,
        onResult: (AgeComplianceResult) -> Unit,
        attempt: Int = 0,
    ) {
        if (!isHostActive()) return

        try {
            val request = AgeSignalsRequest.builder().build()

            ageSignalsManager.checkAgeSignals(request)
                .addOnSuccessListener(callbackExecutor) { result ->
                    if (!isHostActive()) return@addOnSuccessListener

                    val ageLower: Int? = result.ageLower()
                    val ageUpper: Int? = result.ageUpper()

                    onResult(
                        AgeComplianceResult(
                            category = classifyAgeRange(ageLower, ageUpper),
                            accessOutcome = AgeSignalsAccessOutcome.SHARED,
                            ageLower = ageLower,
                            ageUpper = ageUpper,
                            ageRangeSource = result.ageRangeSource(),
                            significantChangeStatus = result.significantChangeStatus(),
                            significantChangeApprovalDate = result.significantChangeApprovalDate(),
                            installId = result.installId(),
                        ),
                    )
                }
                .addOnFailureListener(callbackExecutor) { exception ->
                    if (!isHostActive()) return@addOnFailureListener

                    if (shouldRetry(exception, attempt)) {
                        val delayMs = retryDelayMillis(attempt)
                        delayScheduler(delayMs) {
                            checkAgeSignals(
                                isHostActive = isHostActive,
                                onResult = onResult,
                                attempt = attempt + 1,
                            )
                        }
                    } else {
                        handleFailure(exception, onResult)
                    }
                }
        } catch (exception: Exception) {
            handleFailure(exception, onResult)
        }
    }

    /**
     * 기본 구간(0-12, 13-15, 16-17, 18+)뿐 아니라 Play Console의 커스텀 구간도
     * 안전하게 처리합니다.
     *
     * - 하한이 18 이상이면 전체 구간이 성인 -> ADULT
     * - 상한이 18 미만이면 전체 구간이 미성년 -> MINOR
     * - 18세 경계를 걸치거나 값이 없으면 -> UNKNOWN
     */
    internal fun classifyAgeRange(ageLower: Int?, ageUpper: Int?): AgeComplianceCategory {
        if (ageLower == null) {
            return AgeComplianceCategory.UNKNOWN
        }

        if (ageLower >= ADULT_AGE) {
            return AgeComplianceCategory.ADULT
        }

        if ((ageUpper != null) && (ageUpper < ADULT_AGE)) {
            return AgeComplianceCategory.MINOR
        }

        return AgeComplianceCategory.UNKNOWN
    }

    internal fun isRetryableErrorCode(errorCode: Int): Boolean {
        return when (errorCode) {
            AgeSignalsErrorCode.NETWORK_ERROR,
            AgeSignalsErrorCode.CANNOT_BIND_TO_SERVICE,
            AgeSignalsErrorCode.CLIENT_TRANSIENT_ERROR -> true
            else -> false
        }
    }

    private fun shouldRetry(exception: Exception, attempt: Int): Boolean {
        if (attempt >= MAX_RETRY_ATTEMPTS) return false
        val errorCode = (exception as? AgeSignalsException)?.errorCode ?: return false
        return isRetryableErrorCode(errorCode)
    }

    private fun retryDelayMillis(attempt: Int): Long {
        return INITIAL_RETRY_DELAY_MS * (1L shl attempt)
    }

    private fun handleFailure(
        exception: Exception,
        onResult: (AgeComplianceResult) -> Unit,
    ) {
        val errorCode = logFailure(exception)
        onResult(
            AgeComplianceResult(
                category = AgeComplianceCategory.UNKNOWN,
                accessOutcome = AgeSignalsAccessOutcome.ERROR,
                errorCode = errorCode,
            ),
        )
    }

    private fun logFailure(exception: Exception): Int? {
        val errorCode = (exception as? AgeSignalsException)?.errorCode
        if (errorCode != null) {
            logger("Play Age Signals API errorCode=$errorCode", null)
        } else {
            logger("Play Age Signals API request failed", exception)
        }
        return errorCode
    }

    private companion object {
        const val TAG = "AgeSignalsCompliance"
        const val ADULT_AGE = 18
        const val MAX_RETRY_ATTEMPTS = 2
        const val INITIAL_RETRY_DELAY_MS = 500L

        fun createMainHandlerDelayScheduler(): (Long, () -> Unit) -> Unit {
            val handler = Handler(Looper.getMainLooper())
            return { delayMs, action ->
                handler.postDelayed(action, delayMs)
            }
        }
    }
}
