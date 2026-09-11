package com.krdonon.wweather

import android.app.Dialog
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import kotlin.math.roundToInt
import java.util.Locale

/**
 * 풍향계(데이터 기반) + 나침판(센서 기반) 팝업.
 *
 * - 상단: 풍향값(0=N, 90=E, 180=S, 270=W)에 따라 바늘 회전
 * - 하단: 폰 센서(TYPE_ROTATION_VECTOR)로 azimuth를 구해 실시간으로 바늘 회전
 */
class WindCompassDialogFragment : DialogFragment(), SensorEventListener {

    private var windDeg: Float = Float.NaN

    private var sensorManager: SensorManager? = null
    private var rotationVector: Sensor? = null

    private var windVaneView: WindVaneView? = null
    private var compassView: CompassView? = null
    private var windValueText: TextView? = null
    private var compassValueText: TextView? = null

    private val rotMat = FloatArray(9)
    private val orient = FloatArray(3)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        windDeg = arguments?.getFloat(ARG_WIND_DEG, Float.NaN) ?: Float.NaN
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // Use Fragment's inflater to avoid lint warning about LayoutInflater.from(context)
        val v = layoutInflater.inflate(R.layout.dialog_wind_compass, null)

        windVaneView = v.findViewById(R.id.windVaneView)
        compassView = v.findViewById(R.id.compassView)
        windValueText = v.findViewById(R.id.windValueText)
        compassValueText = v.findViewById(R.id.compassValueText)

        applyWindValue(windDeg)

        sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationVector = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        if (rotationVector == null) {
            compassValueText?.text = getString(R.string.compass_sensor_none)
        }

        return AlertDialog.Builder(requireContext())
            .setView(v)
            .setPositiveButton(R.string.close) { _, _ -> dismissAllowingStateLoss() }
            .create()
    }

    override fun onStart() {
        super.onStart()
        // 다이얼로그 폭을 조금 넓게
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).roundToInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val alert = dialog as? AlertDialog ?: return
        val decor = alert.window?.decorView ?: return

        ViewCompat.setOnApplyWindowInsetsListener(decor) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            // 스크롤/컨텐츠가 네비게이션 바와 겹치지 않도록 하단 패딩 추가
            alert.findViewById<View>(R.id.dialogScrollView)?.let { sv ->
                sv.setPadding(sv.paddingLeft, sv.paddingTop, sv.paddingRight, bars.bottom)
            }

            // "닫기" 버튼이 겹치지 않도록 하단 마진 추가
            alert.getButton(AlertDialog.BUTTON_POSITIVE)?.let { btn ->
                val lp = btn.layoutParams as? ViewGroup.MarginLayoutParams
                if (lp != null) {
                    lp.bottomMargin = bars.bottom
                    btn.layoutParams = lp
                }
            }
            insets
        }
        ViewCompat.requestApplyInsets(decor)
    }

    override fun onResume() {
        super.onResume()
        rotationVector?.let { s ->
            sensorManager?.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        windVaneView = null
        compassView = null
        windValueText = null
        compassValueText = null
    }

    @Suppress("unused")
    fun updateWindDirection(newWindDeg: Float) {
        windDeg = newWindDeg
        if (isAdded) {
            applyWindValue(newWindDeg)
        }
    }
    private fun applyWindValue(deg: Float) {
        windVaneView?.windDirectionDeg = deg
        windValueText?.text = if (deg.isNaN()) {
            getString(R.string.wind_from_na)
        } else {
            val nd = normalizeDeg(deg)
            val num = String.format(Locale.getDefault(), "%.1f", nd)
            getString(R.string.wind_from_format, num, directionKo8(nd))
        }
    }
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

        SensorManager.getRotationMatrixFromVector(rotMat, event.values)
        // 화면 회전(가로/세로)에 따라 좌표계를 보정하지 않으면 기기/자세에 따라
        // 방위각이 뒤집히거나 90도씩 틀어져 보일 수 있다.
        val remapped = FloatArray(9)
        // Do NOT use Context.display / Context#getDisplay (API 30+). View.display works on API 17+.
        val rotation = view?.display?.rotation ?: android.view.Surface.ROTATION_0
        val (axisX, axisY) = when (rotation) {
            android.view.Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            android.view.Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            android.view.Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(rotMat, axisX, axisY, remapped)

        SensorManager.getOrientation(remapped, orient)

        // orient[0] = azimuth(rad). -pi..pi. 0 = north.
        val azimuthDeg = Math.toDegrees(orient[0].toDouble()).toFloat()
        val heading = normalizeDeg(azimuthDeg)

        compassView?.headingDeg = heading
        compassValueText?.text = getString(R.string.compass_heading_format, heading.roundToInt(), directionKo8(heading))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 필요 시, 정확도 안내 표시 가능
    }


    private fun directionKo8(deg: Float): String {
        val d = normalizeDeg(deg)
        // 8방위: 45도 간격 (북, 북동, 동, 남동, 남, 남서, 서, 북서)
        val names = arrayOf("북", "북동", "동", "남동", "남", "남서", "서", "북서")
        val idx = (((d + 22.5f) % 360f) / 45f).toInt().coerceIn(0, 7)
        return names[idx]
    }

    private fun normalizeDeg(deg: Float): Float {
        var d = deg % 360f
        if (d < 0f) d += 360f
        return d
    }

    companion object {
        private const val ARG_WIND_DEG = "windDeg"

        fun newInstance(windDeg: Float): WindCompassDialogFragment {
            return WindCompassDialogFragment().apply {
                arguments = Bundle().apply {
                    putFloat(ARG_WIND_DEG, windDeg)
                }
            }
        }
    }
}