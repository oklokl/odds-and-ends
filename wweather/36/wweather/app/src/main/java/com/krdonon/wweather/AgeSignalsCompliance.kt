package com.krdonon.wweather

import android.app.Activity
import android.content.Context
import com.google.android.play.agesignals.*
import com.google.android.play.agesignals.model.*

/**
 * Google Play Age Signals 0.0.4 연동 계층.
 *
 * 연령대 원본 값은 파일/SharedPreferences/DB에 저장하지 않고,
 * 현재 프로세스 메모리에서 MINOR / ADULT / UNKNOWN 분류만 유지합니다.
 * 이 값은 연령 적합성/법규 준수 목적에만 사용하고 광고, 마케팅, 프로파일링, 분석에는 사용하지 않습니다.
 */
class AgeSignalsCompliance(context: Context) {

    enum class AgeCategory {
        MINOR,
        ADULT,
        UNKNOWN
    }

    private val manager: AgeSignalsManager =
        AgeSignalsManagerFactory.create(context.applicationContext)

    @Volatile
    var currentCategory: AgeCategory = AgeCategory.UNKNOWN
        private set

    /**
     * 0.0.4 공식 순서:
     * requestAgeSignalsAccess() -> SHARED일 때 checkAgeSignals().
     */
    fun requestAgeCategory(
        activity: Activity,
        onResult: (AgeCategory) -> Unit = {}
    ) {
        try {
            val accessRequest = AgeSignalsAccessRequest.builder()
                .setActivity(activity)
                .build()

            manager.requestAgeSignalsAccess(accessRequest)
                .addOnSuccessListener { accessResult ->
                    if (accessResult.ageSignalsStatus() == AgeSignalsStatus.SHARED) {
                        checkAgeSignals(onResult)
                    } else {
                        // NOT_SHARED, VERIFICATION_REQUIRED 및 향후 알 수 없는 상태는 안전하게 UNKNOWN.
                        publish(AgeCategory.UNKNOWN, onResult)
                    }
                }
                .addOnFailureListener {
                    publish(AgeCategory.UNKNOWN, onResult)
                }
        } catch (_: Exception) {
            // Play Store/Play Services/API 환경이 준비되지 않은 경우 앱 기능은 계속 동작시킵니다.
            publish(AgeCategory.UNKNOWN, onResult)
        }
    }

    private fun checkAgeSignals(onResult: (AgeCategory) -> Unit) {
        try {
            manager.checkAgeSignals(AgeSignalsRequest.builder().build())
                .addOnSuccessListener { result ->
                    publish(
                        classifyAgeRange(
                            ageLower = result.ageLower(),
                            ageUpper = result.ageUpper()
                        ),
                        onResult
                    )
                }
                .addOnFailureListener {
                    publish(AgeCategory.UNKNOWN, onResult)
                }
        } catch (_: Exception) {
            publish(AgeCategory.UNKNOWN, onResult)
        }
    }

    private fun publish(category: AgeCategory, onResult: (AgeCategory) -> Unit) {
        currentCategory = category
        onResult(category)
    }

    companion object {
        /**
         * 18세 경계를 확실히 포함하는 연령대만 판정합니다.
         * - 18세 이상으로 시작: ADULT
         * - 상한이 17세 이하: MINOR
         * - 18세 경계를 가로지르거나 값이 없음: UNKNOWN
         */
        internal fun classifyAgeRange(ageLower: Int?, ageUpper: Int?): AgeCategory {
            return when {
                ageLower != null && ageLower >= 18 -> AgeCategory.ADULT
                ageUpper != null && ageUpper < 18 -> AgeCategory.MINOR
                else -> AgeCategory.UNKNOWN
            }
        }
    }
}
