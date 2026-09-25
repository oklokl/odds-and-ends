package com.krdondon.thelordsprayer

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.play.agesignals.*
import com.google.android.play.agesignals.model.*

/**
 * 앱 내부에서만 사용하는 연령 분류입니다.
 *
 * Play Age Signals 값은 법률 준수를 위한 연령 적합 경험 제공에만 사용하며,
 * 광고 타기팅/마케팅/프로파일링/분석 용도로 사용하거나 영구 저장하지 않습니다.
 */
enum class AgeComplianceCategory {
    MINOR,
    ADULT,
    UNKNOWN
}

/**
 * Google Play Age Signals API 0.0.4 연동 클래스.
 *
 * 공식 0.0.4 호출 순서:
 * 1) AgeSignalsManagerFactory.create(...)
 * 2) requestAgeSignalsAccess(...)
 * 3) SHARED인 경우에만 checkAgeSignals(...)
 */
class AgeSignalsCompliance(context: Context) {

    private val appContext = context.applicationContext

    // 실제 접근 시 생성하여, 생성 단계에서 예외가 발생해도 UNKNOWN으로 안전하게 처리할 수 있게 합니다.
    private val ageSignalsManager: AgeSignalsManager by lazy {
        AgeSignalsManagerFactory.create(appContext)
    }

    /**
     * 현재 사용자의 연령 신호 공유 가능 여부를 확인한 뒤 연령대를 분류합니다.
     * 결과는 콜백으로만 전달하며 SharedPreferences/파일/DB 등에 저장하지 않습니다.
     */
    fun requestAndCheck(
        activity: Activity,
        onResult: (AgeComplianceCategory) -> Unit
    ) {
        try {
            val accessRequest = AgeSignalsAccessRequest.builder()
                .setActivity(activity)
                .build()

            ageSignalsManager.requestAgeSignalsAccess(accessRequest)
                .addOnSuccessListener { accessResult ->
                    when (accessResult.ageSignalsStatus()) {
                        AgeSignalsStatus.SHARED -> checkAgeSignals(onResult)

                        // 사용자가 공유하지 않았거나, Play Store에서 연령 확인이 필요한 경우
                        // 실제 연령을 추측하지 않고 UNKNOWN으로 처리합니다.
                        AgeSignalsStatus.NOT_SHARED,
                        AgeSignalsStatus.VERIFICATION_REQUIRED -> onResult(AgeComplianceCategory.UNKNOWN)

                        else -> onResult(AgeComplianceCategory.UNKNOWN)
                    }
                }
                .addOnFailureListener { exception ->
                    handleFailure(exception, onResult)
                }
        } catch (exception: Exception) {
            handleFailure(exception, onResult)
        }
    }

    private fun checkAgeSignals(onResult: (AgeComplianceCategory) -> Unit) {
        try {
            val request = AgeSignalsRequest.builder().build()

            ageSignalsManager.checkAgeSignals(request)
                .addOnSuccessListener { result ->
                    val ageLower: Int? = result.ageLower()
                    val ageUpper: Int? = result.ageUpper()

                    onResult(classifyAgeRange(ageLower, ageUpper))
                }
                .addOnFailureListener { exception ->
                    handleFailure(exception, onResult)
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

        if (ageUpper != null && ageUpper < ADULT_AGE) {
            return AgeComplianceCategory.MINOR
        }

        return AgeComplianceCategory.UNKNOWN
    }

    private fun handleFailure(
        exception: Exception,
        onResult: (AgeComplianceCategory) -> Unit
    ) {
        val errorCode = (exception as? AgeSignalsException)?.getErrorCode()
        if (errorCode != null) {
            Log.w(TAG, "Play Age Signals API errorCode=$errorCode")
        } else {
            Log.w(TAG, "Play Age Signals API request failed", exception)
        }
        onResult(AgeComplianceCategory.UNKNOWN)
    }

    private companion object {
        const val TAG = "AgeSignalsCompliance"
        const val ADULT_AGE = 18
    }
}
