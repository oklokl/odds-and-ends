package com.krdondon.week

import android.app.Activity
import com.google.android.play.agesignals.AgeSignalsAccessRequest
import com.google.android.play.agesignals.AgeSignalsManager
import com.google.android.play.agesignals.AgeSignalsManagerFactory
import com.google.android.play.agesignals.AgeSignalsRequest
import com.google.android.play.agesignals.model.AgeSignalsStatus

/**
 * Google Play Age Signals API 0.0.4 연동부.
 *
 * 이 앱은 현재 광고, 인앱 결제, 채팅/소셜 기능이 없으므로 연령 신호로
 * 실제 기능을 차단하거나 광고 설정을 변경하지 않는다. 연령 정보는 파일/DB에
 * 저장하지 않고 메모리에서만 현재 보호 상태를 유지한다.
 *
 * IMPORTANT:
 * Play Age Signals 데이터는 법률 준수를 위한 연령 적합 경험 제공에만 사용해야 한다.
 * 광고 타기팅/마케팅/프로파일링/분석 목적으로 사용하면 안 된다.
 */
object AgeSignalsCompliance {

    enum class ProtectionState {
        /** 18세 미만임이 연령 구간으로 확인됨. */
        MINOR,

        /** 18세 이상임이 연령 구간으로 확인됨. */
        ADULT,

        /** 공유 안 함, 확인 필요, API 오류, 또는 성인/미성년을 확정할 수 없는 구간. */
        UNKNOWN
    }

    @Volatile
    var currentState: ProtectionState = ProtectionState.UNKNOWN
        private set

    /**
     * Activity가 화면에 올라온 런타임 시점에 Age Signals 접근 상태를 확인한다.
     *
     * 0.0.4부터는 먼저 requestAgeSignalsAccess()를 호출한 뒤 SHARED인 경우에만
     * checkAgeSignals()로 실제 연령 구간을 가져와야 한다.
     */
    fun refresh(activity: Activity) {
        val manager = AgeSignalsManagerFactory.create(activity.applicationContext)
        val accessRequest = AgeSignalsAccessRequest.builder()
            .setActivity(activity)
            .build()

        manager.requestAgeSignalsAccess(accessRequest)
            .addOnSuccessListener { accessResult ->
                when (accessResult.ageSignalsStatus()) {
                    AgeSignalsStatus.SHARED -> retrieveAgeSignals(manager)

                    // NOT_SHARED 또는 VERIFICATION_REQUIRED에서는 연령을 추정하지 않는다.
                    // 이 앱은 전연령가이며 연령 제한 기능이 없으므로 기본 기능은 유지한다.
                    else -> currentState = ProtectionState.UNKNOWN
                }
            }
            .addOnFailureListener {
                // Play Store/Play services/네트워크/API 가용성 문제 등.
                // 연령을 임의 추정하지 않고 안전하게 UNKNOWN으로 둔다.
                currentState = ProtectionState.UNKNOWN
            }
    }

    private fun retrieveAgeSignals(manager: AgeSignalsManager) {
        manager.checkAgeSignals(AgeSignalsRequest.builder().build())
            .addOnSuccessListener { result ->
                currentState = classifyAgeRange(
                    ageLower = result.ageLower(),
                    ageUpper = result.ageUpper()
                )

                // result.significantChangeStatus(), result.significantChangeApprovalDate(),
                // result.installId()는 현재 앱 기능에 필요하지 않으므로 저장하지 않는다.
            }
            .addOnFailureListener {
                currentState = ProtectionState.UNKNOWN
            }
    }

    /**
     * 기본 Play 구간(0-12, 13-15, 16-17, 18+)뿐 아니라 커스텀 구간도 보수적으로 처리한다.
     *
     * - 상한이 17 이하이면 확실한 미성년자
     * - 하한이 18 이상이면 확실한 성인
     * - 그 외(예: 17+처럼 성인/미성년이 섞인 개방 구간)는 UNKNOWN
     */
    internal fun classifyAgeRange(ageLower: Int?, ageUpper: Int?): ProtectionState {
        return when {
            ageUpper != null && ageUpper < ADULT_AGE -> ProtectionState.MINOR
            ageLower != null && ageLower >= ADULT_AGE -> ProtectionState.ADULT
            else -> ProtectionState.UNKNOWN
        }
    }

    private const val ADULT_AGE = 18
}
