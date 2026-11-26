package com.krdondon.read.compliance

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.play.agesignals.AgeSignalsAccessRequest
import com.google.android.play.agesignals.AgeSignalsException
import com.google.android.play.agesignals.AgeSignalsManager
import com.google.android.play.agesignals.AgeSignalsManagerFactory
import com.google.android.play.agesignals.AgeSignalsRequest
import com.google.android.play.agesignals.ApplicableAgeFeaturesRequest
import com.google.android.play.agesignals.model.AgeRangeSource
import com.google.android.play.agesignals.model.AgeSignalsErrorCode
import com.google.android.play.agesignals.model.AgeSignalsStatus
import com.google.android.play.agesignals.model.ApplicableAgeFeature
import com.google.android.play.agesignals.model.SignificantChangeStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.lang.ref.WeakReference
import java.util.Date

/**
 * 사용자의 연령 구분
 *
 * MINOR: 미성년자 (18세 미만)
 * ADULT: 성인 (18세 이상)
 * UNKNOWN: 확인 불가, 오류, 동의 거부, 인증 필요 또는 알 수 없음
 */
enum class UserAgeCategory {
    MINOR,
    ADULT,
    UNKNOWN
}

/**
 * Age Signals 상태 데이터 클래스
 * 보안 및 Google 정책 준수를 위해 파일, DB, SharedPreferences 등에 영구 저장하지 않고
 * 런타임 메모리(StateFlow)에서만 유지합니다.
 */
data class AgeSignalsState(
    val category: UserAgeCategory = UserAgeCategory.UNKNOWN,
    val accessStatus: Int? = null,
    val ageLower: Int? = null,
    val ageUpper: Int? = null,
    val ageRangeSource: Int? = null,
    val significantChangeStatus: Int? = null,
    val significantChangeApprovalDate: Date? = null,
    val installId: String? = null,
    val isVerificationRequired: Boolean = false,
    val isMandatorySharingApplicable: Boolean? = null,
    val errorCode: Int? = null,
    val statusDescription: String = "초기화되지 않음"
)

/**
 * Google Play Age Signals API (0.0.6) 공식 스펙을 준수하는 연령 신호 관리 클래스
 */
class AgeSignalsCompliance(
    private val ageSignalsManager: AgeSignalsManager,
    private val retryScheduler: (delayMs: Long, block: () -> Unit) -> Unit = defaultRetryScheduler(),
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
    private val initialRetryDelayMs: Long = DEFAULT_INITIAL_RETRY_DELAY_MS
) {

    constructor(context: Context) : this(
        ageSignalsManager = AgeSignalsManagerFactory.create(context.applicationContext)
    )

    companion object {
        private const val TAG = "AgeSignalsCompliance"
        private const val DEFAULT_MAX_RETRIES = 2
        private const val DEFAULT_INITIAL_RETRY_DELAY_MS = 500L

        private fun defaultRetryScheduler(): (Long, () -> Unit) -> Unit {
            val handler = Handler(Looper.getMainLooper())
            return { delayMs, block -> handler.postDelayed(block, delayMs) }
        }
    }

    private val _ageState = MutableStateFlow(AgeSignalsState())
    val ageState: StateFlow<AgeSignalsState> = _ageState.asStateFlow()

    val currentCategory: UserAgeCategory
        get() = _ageState.value.category

    /**
     * Play Age Signals SDK 0.0.6에서 도입된 getApplicableAgeFeatures()를 조회하여
     * 현재 사용자 지역/계정에 의무 연령 공유(MANDATORY_SHARING) 등이 적용되는지 확인합니다.
     */
    fun checkApplicableAgeFeatures(onComplete: ((Boolean) -> Unit)? = null) {
        val request = ApplicableAgeFeaturesRequest.builder().build()
        ageSignalsManager.getApplicableAgeFeatures(request)
            .addOnSuccessListener { result ->
                val isMandatory = result.features().contains(ApplicableAgeFeature.MANDATORY_SHARING)
                Log.d(TAG, "Applicable age features: ${result.features()}, mandatorySharing=$isMandatory")
                _ageState.update { it.copy(isMandatorySharingApplicable = isMandatory) }
                onComplete?.invoke(isMandatory)
            }
            .addOnFailureListener { exception ->
                val code = (exception as? AgeSignalsException)?.errorCode
                Log.w(TAG, "Failed to get applicable age features (code=$code)", exception)
                onComplete?.invoke(false)
            }
    }

    /**
     * 런타임에서 연령 신호 접근을 요청하고 결과를 확인합니다.
     * 공식 문서 가이드에 따른 2단계 순서:
     * 1. requestAgeSignalsAccess(accessRequest)
     * 2. accessResult.ageSignalsStatus() == AgeSignalsStatus.SHARED 확인
     * 3. checkAgeSignals(AgeSignalsRequest.builder().build())
     */
    fun checkAgeSignals(activity: Activity, onComplete: ((UserAgeCategory) -> Unit)? = null) {
        requestAccessWithRetry(
            activityRef = WeakReference(activity),
            attempt = 0,
            onComplete = onComplete
        )
    }

    private fun requestAccessWithRetry(
        activityRef: WeakReference<Activity>,
        attempt: Int,
        onComplete: ((UserAgeCategory) -> Unit)?
    ) {
        val activity = activityRef.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            updateUnknownState(
                description = "유효한 Activity가 없어 연령 신호 요청을 중단함",
                onComplete = onComplete
            )
            return
        }

        val accessRequest = AgeSignalsAccessRequest.builder()
            .setActivity(activity)
            .build()

        ageSignalsManager.requestAgeSignalsAccess(accessRequest)
            .addOnSuccessListener { accessResult ->
                val status = accessResult.ageSignalsStatus()
                when (status) {
                    AgeSignalsStatus.SHARED -> {
                        Log.d(TAG, "Age signals access granted: SHARED")
                        retrieveActualSignalsWithRetry(attempt = 0, onComplete = onComplete)
                    }
                    AgeSignalsStatus.NOT_SHARED -> {
                        Log.d(TAG, "Age signals not shared by user or parent")
                        updateUnknownState(
                            accessStatus = status,
                            description = "사용자 또는 보호자가 연령 신호를 공유하지 않음",
                            onComplete = onComplete
                        )
                    }
                    AgeSignalsStatus.VERIFICATION_REQUIRED -> {
                        Log.d(TAG, "Age verification required in Google Play")
                        updateUnknownState(
                            accessStatus = status,
                            isVerificationRequired = true,
                            description = "Google Play에서 연령 인증 또는 보호자 관리 설정 필요",
                            onComplete = onComplete
                        )
                    }
                    else -> {
                        Log.d(TAG, "Age signals status unspecified: $status")
                        updateUnknownState(
                            accessStatus = status,
                            description = "알 수 없는 연령 신호 접근 상태 ($status)",
                            onComplete = onComplete
                        )
                    }
                }
            }
            .addOnFailureListener { exception ->
                if (shouldRetry(exception, attempt)) {
                    val delayMs = retryDelayMillis(attempt)
                    Log.w(TAG, "Transient failure on requestAgeSignalsAccess; retrying in ${delayMs}ms (attempt=${attempt + 1})", exception)
                    retryScheduler(delayMs) {
                        requestAccessWithRetry(activityRef, attempt + 1, onComplete)
                    }
                } else {
                    val errorCode = (exception as? AgeSignalsException)?.errorCode
                    Log.w(TAG, "Failed to request age signals access (errorCode=$errorCode)", exception)
                    updateUnknownState(
                        errorCode = errorCode,
                        description = "접근 요청 실패: ${describeError(exception)}",
                        onComplete = onComplete
                    )
                }
            }
    }

    private fun retrieveActualSignalsWithRetry(
        attempt: Int,
        onComplete: ((UserAgeCategory) -> Unit)?
    ) {
        val request = AgeSignalsRequest.builder().build()

        ageSignalsManager.checkAgeSignals(request)
            .addOnSuccessListener { ageSignalsResult ->
                val ageLower = ageSignalsResult.ageLower()
                val ageUpper = ageSignalsResult.ageUpper()
                val ageRangeSource = ageSignalsResult.ageRangeSource()
                val significantChangeStatus = ageSignalsResult.significantChangeStatus()
                val significantChangeApprovalDate = ageSignalsResult.significantChangeApprovalDate()
                val installId = ageSignalsResult.installId()

                Log.d(
                    TAG,
                    "AgeSignals received: lower=$ageLower, upper=$ageUpper, source=${describeAgeRangeSource(ageRangeSource)}, significantChange=${describeSignificantChangeStatus(significantChangeStatus)}"
                )

                val category = determineCategory(ageLower, ageUpper)
                val description = buildString {
                    when (category) {
                        UserAgeCategory.MINOR -> append("미성년자 (ageLower: $ageLower, ageUpper: $ageUpper)")
                        UserAgeCategory.ADULT -> append("성인 (ageLower: $ageLower, ageUpper: $ageUpper)")
                        UserAgeCategory.UNKNOWN -> append("연령 미공유 또는 확인 불가 (ageLower: $ageLower, ageUpper: $ageUpper)")
                    }
                    ageRangeSource?.let { append(", source=${describeAgeRangeSource(it)}") }
                    significantChangeStatus?.let { append(", change=${describeSignificantChangeStatus(it)}") }
                }

                _ageState.update { current ->
                    current.copy(
                        category = category,
                        accessStatus = AgeSignalsStatus.SHARED,
                        ageLower = ageLower,
                        ageUpper = ageUpper,
                        ageRangeSource = ageRangeSource,
                        significantChangeStatus = significantChangeStatus,
                        significantChangeApprovalDate = significantChangeApprovalDate,
                        installId = installId,
                        isVerificationRequired = false,
                        errorCode = null,
                        statusDescription = description
                    )
                }
                onComplete?.invoke(category)
            }
            .addOnFailureListener { exception ->
                if (shouldRetry(exception, attempt)) {
                    val delayMs = retryDelayMillis(attempt)
                    Log.w(TAG, "Transient failure on checkAgeSignals; retrying in ${delayMs}ms (attempt=${attempt + 1})", exception)
                    retryScheduler(delayMs) {
                        retrieveActualSignalsWithRetry(attempt + 1, onComplete)
                    }
                } else {
                    val errorCode = (exception as? AgeSignalsException)?.errorCode
                    Log.w(TAG, "Failed to check age signals (errorCode=$errorCode)", exception)
                    updateUnknownState(
                        accessStatus = AgeSignalsStatus.SHARED,
                        errorCode = errorCode,
                        description = "연령 신호 조회 실패: ${describeError(exception)}",
                        onComplete = onComplete
                    )
                }
            }
    }

    /**
     * 연령 신호로부터 MINOR / ADULT / UNKNOWN 결정
     *
     * 공식 문서 기준:
     * - ageLower != null && ageUpper != null: 닫힌 연령 구간 [ageLower, ageUpper]
     *   - ageUpper < 18 이면 MINOR
     *   - ageLower >= 18 이면 ADULT
     * - ageLower != null && ageUpper == null: 최상위 열린 연령 구간 (예: 18+)
     *   - ageLower >= 18 이면 ADULT
     *   - ageLower < 18 이면(커스텀 최소 연령 설정 시) 성인 단정 불가 -> UNKNOWN
     * - ageLower == null && ageUpper == null: 사용자가 연령을 공유하지 않음 -> UNKNOWN
     */
    internal fun determineCategory(ageLower: Int?, ageUpper: Int?): UserAgeCategory {
        return when {
            ageUpper != null && ageUpper < 18 -> UserAgeCategory.MINOR
            ageLower != null && ageLower >= 18 -> UserAgeCategory.ADULT
            else -> UserAgeCategory.UNKNOWN
        }
    }

    private fun shouldRetry(exception: Exception, attempt: Int): Boolean {
        if (attempt >= maxRetries) return false
        val code = (exception as? AgeSignalsException)?.errorCode ?: return false
        return code == AgeSignalsErrorCode.CLIENT_TRANSIENT_ERROR ||
            code == AgeSignalsErrorCode.CANNOT_BIND_TO_SERVICE ||
            code == AgeSignalsErrorCode.NETWORK_ERROR
    }

    private fun retryDelayMillis(attempt: Int): Long {
        return initialRetryDelayMs shl attempt
    }

    private fun describeAgeRangeSource(source: Int?): String = when (source) {
        AgeRangeSource.TIER_A -> "TIER_A(SELF_DECLARED)"
        AgeRangeSource.TIER_B -> "TIER_B(SUPERVISED)"
        AgeRangeSource.TIER_C -> "TIER_C(ASSESSED)"
        AgeRangeSource.TIER_D -> "TIER_D(VERIFIED_ID)"
        AgeRangeSource.UNSPECIFIED -> "UNSPECIFIED"
        null -> "NONE"
        else -> "UNKNOWN($source)"
    }

    private fun describeSignificantChangeStatus(status: Int?): String = when (status) {
        SignificantChangeStatus.APPROVED -> "APPROVED"
        SignificantChangeStatus.PENDING -> "PENDING"
        SignificantChangeStatus.DECLINED -> "DECLINED"
        SignificantChangeStatus.UNSPECIFIED -> "UNSPECIFIED"
        null -> "NONE"
        else -> "UNKNOWN($status)"
    }

    private fun describeError(exception: Exception): String {
        val ageException = exception as? AgeSignalsException ?: return (exception.localizedMessage ?: "알 수 없는 오류")
        return when (ageException.errorCode) {
            AgeSignalsErrorCode.API_NOT_AVAILABLE -> "API_NOT_AVAILABLE (Play 스토어 업데이트 필요)"
            AgeSignalsErrorCode.PLAY_STORE_NOT_FOUND -> "PLAY_STORE_NOT_FOUND (Play 스토어 없음)"
            AgeSignalsErrorCode.NETWORK_ERROR -> "NETWORK_ERROR (네트워크 연결 확인 필요)"
            AgeSignalsErrorCode.PLAY_SERVICES_NOT_FOUND -> "PLAY_SERVICES_NOT_FOUND (Play 서비스 없음)"
            AgeSignalsErrorCode.CANNOT_BIND_TO_SERVICE -> "CANNOT_BIND_TO_SERVICE (Play 스토어 서비스 바인딩 실패)"
            AgeSignalsErrorCode.PLAY_STORE_VERSION_OUTDATED -> "PLAY_STORE_VERSION_OUTDATED (Play 스토어 버전 구형)"
            AgeSignalsErrorCode.PLAY_SERVICES_VERSION_OUTDATED -> "PLAY_SERVICES_VERSION_OUTDATED (Play 서비스 버전 구형)"
            AgeSignalsErrorCode.CLIENT_TRANSIENT_ERROR -> "CLIENT_TRANSIENT_ERROR (일시적 클라이언트 오류)"
            AgeSignalsErrorCode.APP_NOT_OWNED -> "APP_NOT_OWNED (Google Play 설치 앱 아님)"
            AgeSignalsErrorCode.SDK_VERSION_OUTDATED -> "SDK_VERSION_OUTDATED (Age Signals SDK 업데이트 필요)"
            AgeSignalsErrorCode.DEVICE_NOT_SUPPORTED -> "DEVICE_NOT_SUPPORTED (지원되지 않는 기기)"
            AgeSignalsErrorCode.INTERNAL_ERROR -> "INTERNAL_ERROR (내부 오류)"
            else -> "ERROR(${ageException.errorCode}): ${ageException.localizedMessage}"
        }
    }

    private fun updateUnknownState(
        accessStatus: Int? = null,
        isVerificationRequired: Boolean = false,
        errorCode: Int? = null,
        description: String,
        onComplete: ((UserAgeCategory) -> Unit)?
    ) {
        _ageState.update { current ->
            current.copy(
                category = UserAgeCategory.UNKNOWN,
                accessStatus = accessStatus,
                ageLower = null,
                ageUpper = null,
                ageRangeSource = null,
                significantChangeStatus = null,
                significantChangeApprovalDate = null,
                installId = null,
                isVerificationRequired = isVerificationRequired,
                errorCode = errorCode,
                statusDescription = description
            )
        }
        onComplete?.invoke(UserAgeCategory.UNKNOWN)
    }
}
