package com.krdonon.timer

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max

class NumberPadFragment : Fragment() {

    private lateinit var inputTimeText: TextView
    private lateinit var nowOnPadText: TextView
    private lateinit var btnTomorrow: Button
    private lateinit var btnAmPm: Button

    private lateinit var lblH: TextView
    private lateinit var lblM: TextView
    private lateinit var lblS: TextView
    private lateinit var lblMS: TextView

    // 0=시, 1=분, 2=초, 3=천분(밀리초 3자리)
    private var selected = 1 // 기본: '분'
    private val lens = intArrayOf(2, 2, 2, 3)
    private val digits = arrayOf(
        CharArray(2) { '0' }, // 시
        CharArray(2) { '0' }, // 분
        CharArray(2) { '0' }, // 초
        CharArray(3) { '0' }  // 천분(밀리초)
    )
    private val cursors = intArrayOf(0, 0, 0, 0)

    private var isTomorrow = false
    private var isAm = true
    private var ampmSelected = false

    // 기본 색(라벨/숫자 복원용)
    private var labelDefaultColor: Int = Color.DKGRAY
    private var numberDefaultColor: Int = Color.DKGRAY

    private val handler = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.number_pad, container, false)

        inputTimeText = v.findViewById(R.id.inputTimeText)
        nowOnPadText  = v.findViewById(R.id.nowOnPadText)
        btnTomorrow   = v.findViewById(R.id.btnTomorrow)
        btnAmPm       = v.findViewById(R.id.btnAmPm)

        lblH  = v.findViewById(R.id.lblH)
        lblM  = v.findViewById(R.id.lblM)
        lblS  = v.findViewById(R.id.lblS)
        lblMS = v.findViewById(R.id.lblMS)

        // 현재 테마 기준 기본 색 기록
        labelDefaultColor = lblH.currentTextColor
        numberDefaultColor = inputTimeText.currentTextColor

        // 라벨 클릭 → 블록 선택
        lblH.setOnClickListener { select(0) }
        lblM.setOnClickListener { select(1) }
        lblS.setOnClickListener { select(2) }
        lblMS.setOnClickListener { select(3) }

        // 내일 토글
        btnTomorrow.setOnClickListener {
            isTomorrow = !isTomorrow
            btnTomorrow.text = if (isTomorrow)
                getString(R.string.label_tomorrow_checked)
            else
                getString(R.string.label_tomorrow)
        }

        // 오전/오후 토글(텍스트만 빨강)
        btnAmPm.setOnClickListener {
            ampmSelected = true
            isAm = !isAm
            btnAmPm.text = if (isAm) getString(R.string.label_am) else getString(R.string.label_pm)
            btnAmPm.setTextColor(Color.RED)
        }

        // 숫자 버튼
        val ids = listOf(
            R.id.btn0, R.id.btn1, R.id.btn2, R.id.btn3, R.id.btn4,
            R.id.btn5, R.id.btn6, R.id.btn7, R.id.btn8, R.id.btn9
        )
        for (id in ids) {
            v.findViewById<Button>(id).setOnClickListener { b ->
                val d = (b as Button).text.first()
                putDigit(d)
            }
        }

        // 지우기 / 다음
        v.findViewById<Button>(R.id.btnDelete).setOnClickListener { clearCurrentBlock() }
        v.findViewById<Button>(R.id.btnNext).setOnClickListener { finishAndReturn() }

        // 상단 현재 시각 100ms 갱신
        ticker = object : Runnable {
            override fun run() {
                updateNowOnPad()
                handler.postDelayed(this, 100L)
            }
        }
        handler.post(ticker!!)

        refreshPreview()
        select(selected) // 초기 선택 반영(라벨/숫자 즉시 빨강)
        return v
    }

    /** 블록 선택: 라벨만 빨강+볼드, 나머지는 원래색; 숫자도 즉시 갱신 */
    private fun select(idx: Int) {
        selected = idx
        // 사용자가 블록을 다시 선택해 수정할 때, 항상 "앞자리부터" 입력되도록 커서를 처음으로 되돌림
        cursors[selected] = 0
        updateLabelColors()
        refreshPreview() // 선택과 동시에 숫자 색 반영
    }

    private fun updateLabelColors() {
        fun normal(tv: TextView) {
            tv.setTextColor(labelDefaultColor)
            tv.setTypeface(null, Typeface.NORMAL)
        }
        fun picked(tv: TextView) {
            tv.setTextColor(Color.RED)
            tv.setTypeface(null, Typeface.BOLD)
        }

        // 모두 기본으로
        normal(lblH); normal(lblM); normal(lblS); normal(lblMS)
        // 선택만 빨강
        when (selected) {
            0 -> picked(lblH)
            1 -> picked(lblM)
            2 -> picked(lblS)
            3 -> picked(lblMS)
        }
    }

    /** 숫자 입력 */
    private fun putDigit(ch: Char) {
        val len = lens[selected]
        val cur = cursors[selected]
        digits[selected][cur] = ch
        val next = cur + 1

        // 기존 구현은 마지막 자리에서 커서가 고정되어 "앞자리"가 수정 불가해지는 문제가 있었음.
        // 개선: 자리 수를 다 채우면 커서를 0으로 되돌리고(=앞자리부터 다시),
        //      시/분/초 입력 완료 시에는 다음 블록으로 자동 이동.
        if (next >= len) {
            cursors[selected] = 0
            if (selected < 3) {
                select(selected + 1) // select() 안에서 refreshPreview()까지 수행
                return
            }
        } else {
            cursors[selected] = next
        }
        refreshPreview()
    }

    /** 현재 블록 초기화 */
    private fun clearCurrentBlock() {
        for (i in digits[selected].indices) digits[selected][i] = '0'
        cursors[selected] = 0
        refreshPreview()
    }

    /** 미리보기: 선택된 숫자 블록만 빨강 */
    private fun refreshPreview() {
        val rawH = String(digits[0]).toInt().coerceIn(0, 99)
        val hh  = if (isTomorrow || ampmSelected) rawH.coerceIn(0, 23) else rawH
        val mm  = String(digits[1]).toInt().coerceIn(0, 59)
        val ss  = String(digits[2]).toInt().coerceIn(0, 59)
        val mmm = String(digits[3]).toInt().coerceIn(0, 999)

        val text = String.format(Locale.getDefault(), "%02d:%02d:%02d.%03d", hh, mm, ss, mmm)
        val sp = SpannableString(text)

        fun span(start: Int, end: Int, idx: Int) {
            sp.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) { select(idx) }
                override fun updateDrawState(ds: android.text.TextPaint) {
                    ds.isUnderlineText = false
                    ds.isFakeBoldText = (selected == idx)
                    ds.color = if (selected == idx) Color.RED else numberDefaultColor
                }
            }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        span(0, 2, 0)   // HH
        span(3, 5, 1)   // MM
        span(6, 8, 2)   // SS
        span(9, 12, 3)  // MMM

        inputTimeText.text = sp
        inputTimeText.movementMethod = LinkMovementMethod.getInstance()
        inputTimeText.highlightColor = 0x00000000
    }

    /** 상단 현재 시간 */
    private fun updateNowOnPad() {
        val now = Calendar.getInstance()
        val ampm = if (now.get(Calendar.AM_PM) == Calendar.AM)
            getString(R.string.label_am) else getString(R.string.label_pm)
        val h   = now.get(Calendar.HOUR)
        val m   = now.get(Calendar.MINUTE)
        val s   = now.get(Calendar.SECOND)
        val ms = now.get(Calendar.MILLISECOND)
        nowOnPadText.text = getString(R.string.now_time_format, h, m, s, ms, ampm)
    }

    /** 입력 완료 → TimerFragment로 전달 */
    private fun finishAndReturn() {
        val rawH = String(digits[0]).toInt().coerceIn(0, 99)
        val m    = String(digits[1]).toInt().coerceIn(0, 59)
        val s    = String(digits[2]).toInt().coerceIn(0, 59)
        val ms   = String(digits[3]).toInt().coerceIn(0, 999)

        val clockMode = (isTomorrow || ampmSelected)
        var h = if (clockMode) rawH.coerceIn(0, 23) else rawH

        // 오전/오후 보정(시간 지정 모드에서만)
        if (clockMode) {
            if (h in 1..11 && !isAm) h += 12
            if (h == 12 && isAm) h = 0
            if (h == 12 && !isAm) h = 12
        }

        val bundle = Bundle()
        val now = Calendar.getInstance()

        if (isTomorrow) {
            val target = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, h)
                set(Calendar.MINUTE, m)
                set(Calendar.SECOND, s)
                set(Calendar.MILLISECOND, ms)
            }
            bundle.putLong("targetAt", target.timeInMillis)

        } else if (ampmSelected) {
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, h)
                set(Calendar.MINUTE, m)
                set(Calendar.SECOND, s)
                set(Calendar.MILLISECOND, ms)
            }
            if (target.before(now)) target.add(Calendar.DAY_OF_YEAR, 1)
            bundle.putLong("duration", target.timeInMillis - now.timeInMillis)

        } else {
            val duration =
                TimeUnit.HOURS.toMillis(h.toLong()) +
                        TimeUnit.MINUTES.toMillis(m.toLong()) +
                        TimeUnit.SECONDS.toMillis(s.toLong()) +
                        ms.toLong()
            bundle.putLong("duration", max(0L, duration))
        }

        parentFragmentManager.setFragmentResult("numberPadResult", bundle)
        parentFragmentManager.popBackStack()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        ticker?.let { handler.removeCallbacks(it) }
    }
}