package com.krdondon.read.compliance

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.play.agesignals.AgeSignalsAccessRequest
import com.google.android.play.agesignals.AgeSignalsManager
import com.google.android.play.agesignals.AgeSignalsManagerFactory
import com.google.android.play.agesignals.AgeSignalsRequest
import com.google.android.play.agesignals.model.AgeSignalsStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 사용자의 연령 구분
 *
 * MINOR: 미성년자 (18세 미만)
 * ADULT: 성인 (18세 이상)
 * UNKNOWN: 확인 불가, 오류, 동의 거부 또는 알 수 없음
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
    val ageLower: Int? = null,
    val ageUpper: Int? = null,
    val statusDescription: String = "초기화되지 않음",
)

/**
 * Google Play Age Signals API (0.0.4) 공식 최신 스펙을 준수하는 연령 신호 관리 클래스
 */
class AgeSignalsCompliance(context: Context) {

    companion object {
        private const val TAG = "AgeSignalsCompliance"
    }

    private val ageSignalsManager: AgeSignalsManager =
        AgeSignalsManagerFactory.create(context.applicationContext)

    private val _ageState = MutableStateFlow(AgeSignalsState())
    val ageState: StateFlow<AgeSignalsState> = _ageState.asStateFlow()

    val currentCategory: UserAgeCategory
        get() = _ageState.value.category

    /**
     * 런타임에서 연령 신호 접근을 요청하고 결과를 확인합니다.
     * 공식 문서 가이드에 따른 순서:
     * 1. requestAgeSignalsAccess(accessRequest)
     * 2. accessResult.ageSignalsStatus() == AgeSignalsStatus.SHARED 확인
     * 3. checkAgeSignals(AgeSignalsRequest.builder().build())
     */
    fun checkAgeSignals(activity: Activity, onComplete: ((UserAgeCategory) -> Unit)? = null) {
        val accessRequest = AgeSignalsAccessRequest.builder()
            .setActivity(activity)
            .build()

        ageSignalsManager.requestAgeSignalsAccess(accessRequest)
            .addOnSuccessListener { accessResult ->
                when (accessResult.ageSignalsStatus()) {
                    AgeSignalsStatus.SHARED -> {
                        Log.d(TAG, "Age signals access granted: SHARED")
                        retrieveActualSignals(onComplete)
                    }
                    AgeSignalsStatus.NOT_SHARED -> {
                        Log.d(TAG, "Age signals not shared by user or parent")
                        updateState(
                            description = "사용자 또는 보호자가 연령 신호를 공유하지 않음",
                            onComplete = onComplete
                        )
                    }
                    AgeSignalsStatus.VERIFICATION_REQUIRED -> {
                        Log.d(TAG, "Age verification required in Google Play")
                        updateState(
                            description = "Google Play에서 연령 인증 필요",
                            onComplete = onComplete
                        )
                    }
                    else -> {
                        Log.d(TAG, "Age signals status unspecified: ${accessResult.ageSignalsStatus()}")
                        updateState(
                            description = "알 수 없는 연령 신호 상태",
                            onComplete = onComplete
                        )
                    }
                }
            }
            .addOnFailureListener { exception ->
                Log.w(TAG, "Failed to request age signals access", exception)
                updateState(
                    description = "접근 요청 실패: ${exception.localizedMessage}",
                    onComplete = onComplete
                )
            }
    }

    private fun retrieveActualSignals(onComplete: ((UserAgeCategory) -> Unit)?) {
        val request = AgeSignalsRequest.builder().build()

        ageSignalsManager.checkAgeSignals(request)
            .addOnSuccessListener { ageSignalsResult ->
                val ageLower = ageSignalsResult.ageLower()
                val ageUpper = ageSignalsResult.ageUpper()

                Log.d(TAG, "AgeSignals received: lower=$ageLower, upper=$ageUpper")

                val category = determineCategory(ageLower, ageUpper)
                val description = when (category) {
                    UserAgeCategory.MINOR -> "미성년자 (ageLower: $ageLower, ageUpper: $ageUpper)"
                    UserAgeCategory.ADULT -> "성인 (ageLower: $ageLower, ageUpper: $ageUpper)"
                    UserAgeCategory.UNKNOWN -> "연령 구분 불가"
                }

                _ageState.value = AgeSignalsState(
                    category = category,
                    ageLower = ageLower,
                    ageUpper = ageUpper,
                    statusDescription = description
                )
                onComplete?.invoke(category)
            }
            .addOnFailureListener { exception ->
                Log.w(TAG, "Failed to check age signals", exception)
                updateState(
                    description = "연령 신호 조회 실패: ${exception.localizedMessage}",
                    onComplete = onComplete
                )
            }
    }

    /**
     * 연령 신호로부터 MINOR / ADULT / UNKNOWN 결정
     *
     * - ageUpper가 18세 미만이면 MINOR
     * - ageLower가 18세 이상이면 ADULT
     * - 둘 다 null인 경우: 공식 문서 기준 연령을 공유하지 않는 확인된 성인(verified adult)
     */
    private fun determineCategory(ageLower: Int?, ageUpper: Int?): UserAgeCategory {
        return when {
            // 상한선이 18세 미만인 경우 (예: 0-12, 13-15, 16-17) -> MINOR
            (ageUpper != null && ageUpper < 18) -> UserAgeCategory.MINOR

            // 하한선이 18세 이상인 경우 (예: 18+) -> ADULT
            (ageLower != null && ageLower >= 18) -> UserAgeCategory.ADULT

            // 양쪽 범위가 모두 null인 경우 (공식 문서: verified adult who is not sharing age)
            (ageLower == null && ageUpper == null) -> UserAgeCategory.ADULT

            else -> UserAgeCategory.UNKNOWN
        }
    }

    private fun updateState(
        description: String,
        category: UserAgeCategory = UserAgeCategory.UNKNOWN,
        onComplete: ((UserAgeCategory) -> Unit)?
    ) {
        _ageState.value = AgeSignalsState(
            category = category,
            ageLower = null,
            ageUpper = null,
            statusDescription = description,
        )
        onComplete?.invoke(category)
    }
}
