package com.krdonon.touch

import android.app.Activity
import android.content.Context
import com.google.android.play.agesignals.AgeSignalsAccessRequest
import com.google.android.play.agesignals.AgeSignalsManagerFactory
import com.google.android.play.agesignals.AgeSignalsRequest
import com.google.android.play.agesignals.model.AgeSignalsStatus

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
 * Google Play Age Signals SDK 0.0.4용 런타임 연동 클래스.
 *
 * 공식 호출 순서:
 * 1) requestAgeSignalsAccess()
 * 2) AgeSignalsStatus.SHARED인 경우에만 checkAgeSignals()
 *
 * 광고, 마케팅, 프로파일링, 분석 용도로 사용하지 않습니다.
 */
class AgeSignalsCompliance(context: Context) {

    private val manager = AgeSignalsManagerFactory.create(context.applicationContext)

    @Volatile
    var currentCategory: AgeCategory = AgeCategory.UNKNOWN
        private set

    /**
     * 현재 Activity를 이용해 연령대 공유 접근 상태를 확인한 뒤 실제 연령 신호를 조회합니다.
     *
     * NOT_SHARED, VERIFICATION_REQUIRED, API 오류, 값 누락처럼 확실하게 판정할 수 없는
     * 모든 경우는 보수적으로 UNKNOWN으로 처리합니다.
     */
    fun refresh(
        activity: Activity,
        onResult: (AgeCategory) -> Unit = {}
    ) {
        currentCategory = AgeCategory.UNKNOWN

        val accessRequest = AgeSignalsAccessRequest.builder()
            .setActivity(activity)
            .build()

        manager.requestAgeSignalsAccess(accessRequest)
            .addOnSuccessListener { accessResult ->
                if (accessResult.ageSignalsStatus() == AgeSignalsStatus.SHARED) {
                    checkSharedAgeSignals(onResult)
                } else {
                    updateCategory(AgeCategory.UNKNOWN, onResult)
                }
            }
            .addOnFailureListener {
                updateCategory(AgeCategory.UNKNOWN, onResult)
            }
    }

    private fun checkSharedAgeSignals(onResult: (AgeCategory) -> Unit) {
        manager.checkAgeSignals(AgeSignalsRequest.builder().build())
            .addOnSuccessListener { result ->
                updateCategory(
                    classifyAgeRange(
                        ageLower = result.ageLower(),
                        ageUpper = result.ageUpper()
                    ),
                    onResult
                )
            }
            .addOnFailureListener {
                updateCategory(AgeCategory.UNKNOWN, onResult)
            }
    }

    private fun updateCategory(
        category: AgeCategory,
        onResult: (AgeCategory) -> Unit
    ) {
        currentCategory = category
        onResult(category)
    }

    companion object {
        private const val ADULT_AGE = 18

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
