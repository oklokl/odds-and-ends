package com.krdonon.timer.alarmclock

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.krdonon.timer.R
import java.time.LocalDate
import java.time.YearMonth

/**
 * 상단에 뜨는 "미니 달력" 팝업.
 * - 좌/우 스와이프: 이전/다음 달
 * - 오늘: 현재 월로 이동 및 오늘 표시
 * - 배경/나가기: 닫기
 */
class MiniCalendarDialogFragment : DialogFragment() {

    private lateinit var overlayRoot: View
    private lateinit var card: MaterialCardView
    private lateinit var tvMonthTitle: TextView
    private lateinit var weeksContainer: LinearLayout
    private lateinit var btnToday: MaterialButton
    private lateinit var btnClose: MaterialButton

    private val today: LocalDate = LocalDate.now()
    private var current: YearMonth = YearMonth.now()

    private val dayCells: MutableList<TextView> = mutableListOf()

    // 스와이프 제스처용 터치 추적
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var isTrackingSwipe: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 투명 풀스크린 다이얼로그로 띄우고, 내부에서 카드 형태로 "팝업"을 구성
        setStyle(STYLE_NO_TITLE, android.R.style.Theme_Translucent_NoTitleBar)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.dialog_mini_calendar, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        overlayRoot = view.findViewById(R.id.overlayRoot)
        card = view.findViewById(R.id.calendarCard)
        tvMonthTitle = view.findViewById(R.id.tvMonthTitle)
        weeksContainer = view.findViewById(R.id.weeksContainer)
        btnToday = view.findViewById(R.id.btnToday)
        btnClose = view.findViewById(R.id.btnClose)

        // 배경 클릭 시 닫기
        overlayRoot.setOnClickListener { dismissAllowingStateLoss() }
        // 카드 내부 클릭은 닫힘 방지
        card.setOnClickListener { /* no-op */ }

        btnClose.setOnClickListener { dismissAllowingStateLoss() }
        btnToday.setOnClickListener {
            current = YearMonth.now()
            updateCalendar()
        }

        ensureGrid()

        // 달력 영역(요일+날짜)에서 좌/우 스와이프를 받도록 터치 리스너 설정
        // - GestureDetector의 fling 기반은 속도/기기 환경에 따라 인식이 불안정할 수 있어
        //   "거리 기반"으로 처리해 반복 스와이프에서도 안정적으로 월이 넘어가도록 개선
        val swipeTarget = view.findViewById<View>(R.id.calendarSwipeTarget)
        val swipeThresholdPx = 48f * resources.displayMetrics.density
        swipeTarget.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    isTrackingSwipe = true
                    // 부모/오버레이가 터치를 가로채지 않도록
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // MOVE에서도 true를 반환해야 이벤트가 계속 전달되어 ACTION_UP이 안정적으로 들어옵니다.
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!isTrackingSwipe) return@setOnTouchListener false
                    isTrackingSwipe = false
                    v.parent?.requestDisallowInterceptTouchEvent(false)

                    val dx = event.rawX - downX
                    val dy = event.rawY - downY

                    // 수평 스와이프만 처리 (세로 스크롤/탭 오동작 방지)
                    if (kotlin.math.abs(dx) <= kotlin.math.abs(dy)) return@setOnTouchListener false
                    if (kotlin.math.abs(dx) < swipeThresholdPx) return@setOnTouchListener false

                    // dx < 0 : 왼쪽으로 밀기(다음 달), dx > 0 : 오른쪽으로 밀기(이전 달)
                    current = if (dx < 0) current.plusMonths(1) else current.minusMonths(1)
                    updateCalendar()
                    true
                }
                else -> false
            }
        }

        updateCalendar()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            // 상단에 뜨는 느낌을 주기 위해 Window gravity를 TOP으로 설정
            setGravity(Gravity.TOP)
            // 기본 dim 제거(우리는 overlayRoot로 처리)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
    }

    private fun ensureGrid() {
        if (dayCells.isNotEmpty()) return

        // 6주(행) x 7일(열) = 42칸
        repeat(6) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            repeat(7) {
                val cell = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_mini_calendar_day, row, false) as TextView
                row.addView(cell)
                dayCells.add(cell)
            }
            weeksContainer.addView(row)
        }
    }

    private fun updateCalendar() {
        tvMonthTitle.text = getString(R.string.calendar_month_title_fmt, current.year, current.monthValue)

        // 모두 초기화
        dayCells.forEach { cell ->
            cell.text = ""
            cell.background = null
            cell.alpha = 1f
            cell.isVisible = true
        }

        val firstDow = current.atDay(1).dayOfWeek.value
        // java.time: Monday=1..Sunday=7  ->  Sunday=0..Saturday=6
        val startIndex = firstDow % 7
        val daysInMonth = current.lengthOfMonth()

        for (day in 1..daysInMonth) {
            val idx = startIndex + (day - 1)
            if (idx !in dayCells.indices) continue
            val cell = dayCells[idx]
            cell.text = day.toString()

            val isToday = (current.year == today.year && current.monthValue == today.monthValue && day == today.dayOfMonth)
            if (isToday) {
                cell.setBackgroundResource(R.drawable.bg_calendar_today)
                cell.setTextColor(requireContext().getColor(R.color.white))
            } else {
                cell.setBackgroundColor(Color.TRANSPARENT)
                cell.setTextColor(requireContext().getColor(R.color.black))
            }
        }
    }

    companion object {
        fun newInstance(): MiniCalendarDialogFragment = MiniCalendarDialogFragment()
    }
}
