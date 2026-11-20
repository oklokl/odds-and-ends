package com.krdonon.wweather

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.NumberPicker
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.os.CancellationSignal
import androidx.core.location.LocationManagerCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*
import java.util.Locale
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.appcompat.app.AlertDialog
import android.widget.ScrollView
import android.widget.Toast
import android.text.Editable
import android.text.TextWatcher
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.TimeUnit
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import androidx.core.content.FileProvider

class MainActivity : AppCompatActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    // WW1 조회용 클라이언트는 동일한 연결 풀/스레드 풀을 공유하면서 타임아웃만 7초로 제한합니다.
    private val ww1Client by lazy {
        Aws2Ww1Client(
            applicationContext,
            BuildConfig.KMA_AUTH_KEY,
            client.newBuilder()
                .connectTimeout(7, TimeUnit.SECONDS)
                .readTimeout(7, TimeUnit.SECONDS)
                .callTimeout(7, TimeUnit.SECONDS)
                .build()
        )
    }

    @Volatile private var isRefreshing: Boolean = false

    // Age Signals 원본 연령대는 영구 저장하지 않고 프로세스 메모리에서만 분류합니다.
    private val ageSignalsCompliance by lazy { AgeSignalsCompliance(applicationContext) }
    private var ageSignalsRequested: Boolean = false

    // --- WW1 (현천) 요청 제어: 비/기압과 같은 UX를 위해 "7초 내 1차 결론 + 조용한 재시도" ---
    private var ww1PrimaryJob: Job? = null
    private var ww1RetryJob: Job? = null
    private var ww1RequestSeq: Long = 0L


    private val timeHandler by lazy { Handler(Looper.getMainLooper()) }
    private val timeTicker = object : Runnable {
        override fun run() {
            updateTimeBox()
            // 다음 분 경계에 맞춰 갱신(배터리/성능 균형)
            val now = System.currentTimeMillis()
            val delay = 60_000L - (now % 60_000L)
            timeHandler.postDelayed(this, delay)
        }
    }

    private fun logD(msg: String) {
        Log.d(TAG, msg)
        AppLog.i(TAG, msg)
    }

    private fun logE(msg: String, t: Throwable? = null) {
        if (t != null) Log.e(TAG, msg, t) else Log.e(TAG, msg)
        val tail = if (t != null) " | ${t::class.java.simpleName}: ${t.message}" else ""
        AppLog.e(TAG, msg + tail)
    }

    private fun cacheTimestampMs(): Long =
        prefs.getLong("aws_cache_time", 0L)

    private fun cacheAgeMinutes(): Long {
        val ts = cacheTimestampMs()
        if (ts <= 0L) return Long.MAX_VALUE
        return (System.currentTimeMillis() - ts) / 60000L
    }

    private fun kstTm2Minus(mins: Int = 2): String {
        val tz = TimeZone.getTimeZone("Asia/Seoul")
        val cal = Calendar.getInstance(tz).apply { timeInMillis = System.currentTimeMillis() }
        cal.add(Calendar.MINUTE, -mins)
        return SimpleDateFormat("yyyyMMddHHmm", Locale.KOREA).apply { timeZone = tz }.format(cal.time)
    }

    private suspend fun httpGetTextWithRetry(url: String, label: String, retries: Int = 1): String? =
        withContext(Dispatchers.IO) {
            var last: Throwable? = null
            for (attempt in 0..retries) {
                try {
                    val req = Request.Builder().url(url).build()
                    client.newCall(req).execute().use { resp ->
                        val body = resp.body.string()
                        if (resp.isSuccessful && body.isNotBlank()) return@withContext body
                        last = RuntimeException("HTTP ${resp.code}")
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (t: Throwable) {
                    last = t
                }
                if (attempt < retries) delay(350L)
            }
            logE("$label error", last)
            null
        }


    private lateinit var statusText: TextView
    private lateinit var regionText: TextView
    private lateinit var tempLabelText: TextView
    private lateinit var tempValueText: TextView


    private lateinit var windDirValueText: TextView
    private lateinit var windSpeedValueText: TextView
    private lateinit var humidityValueText: TextView
    private lateinit var windUiButton: Button

    // 마지막으로 수신한 풍향(도). 팝업 풍향계에 사용.
    @Volatile private var lastWindDirectionDeg: Float = Float.NaN
    private lateinit var saveImageButton: Button
    private lateinit var shareImageButton: Button
    private var lastSavedImageUri: Uri? = null
    private var pendingSaveAfterPermission: Boolean = false

    private lateinit var pressureText: TextView
    private lateinit var weatherText: TextView
    private lateinit var wwNowText: TextView
    private lateinit var wwLegendText: TextView
    private lateinit var emojiView: ImageView
    private lateinit var conditionText: TextView
    private lateinit var settingsButton: Button
    private lateinit var retryButton: Button
    private lateinit var logButton: Button
    private lateinit var sourceToggleButton: Button
    private lateinit var sourceFooterText: TextView
    private var loadingDialog: AlertDialog? = null
    private var loadingLabel: TextView? = null
    private var attentionAnimator: ValueAnimator? = null
    private var pendingKmaParts = 0
    private var weatherRequestSeq = 0L
    private var locationRequestSeq = 0L
    private var awsJob: Job? = null
    private var openMeteoJob: Job? = null
    private val openMeteoClient by lazy { OpenMeteoClient(client) }
    private val csvRegions by lazy { LocalRegionRepository.read(applicationContext) }
    private var provider: WeatherProvider = WeatherProvider.KMA

    private enum class WeatherProvider { KMA, OPEN_METEO }
    private lateinit var retryStatusText: TextView
    private var timeText: TextView? = null

    private val REQ_LOCATION = 100
    private val REQ_WRITE_STORAGE = 300

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val PREF_LAST_STN = "last_stn"
    private val PREF_LAST_LAT = "last_lat"
    private val PREF_LAST_LON = "last_lon"
    private val PREF_SELECTED_STN = "selected_stn"
    private val PREF_PIN_ENABLED = "selected_stn_pinned"
    private val PREF_PROVIDER = "weather_provider"
    private val PREF_MANUAL_LAT = "manual_lat"
    private val PREF_MANUAL_LON = "manual_lon"
    private val PREF_MANUAL_NAME = "manual_name"
    private val PREF_CACHE_STN = "aws_cache_stn"

    /** 앱 공통 상수. 기상청 API 키는 local.properties → BuildConfig에서 주입합니다. */
    private companion object {
        private const val CACHE_NAME = "nph-aws2_min.cache"
        private const val TAG = "KMA"
    }

    // 분류 임계 구간 (보통: 1010.00 ~ 1016.00)
    private val PRESSURE_LOW = 1010.00
    private val PRESSURE_HIGH = 1016.00

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 네비게이션 바와 겹치지 않도록 하단 패딩을 시스템 인셋에 맞춰 자동 조절
        val rootLayout = findViewById<android.view.View>(R.id.rootLayout)
        val initialBottomPadding = rootLayout.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, initialBottomPadding + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(rootLayout)

        AppLog.clear()

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        statusText     = findViewById(R.id.statusText)
        regionText     = findViewById(R.id.regionText)
        pressureText   = findViewById(R.id.pressureText)
        weatherText    = findViewById(R.id.weatherText)
        wwNowText      = findViewById(R.id.wwNowText)
        wwLegendText   = findViewById(R.id.wwLegendText)
        wwLegendText.text = getString(R.string.ww_legend)
        emojiView      = findViewById(R.id.emojiView)
        conditionText  = findViewById(R.id.conditionText)
        settingsButton = findViewById(R.id.settingsButton)
        retryButton    = findViewById(R.id.retryButton)
        logButton      = findViewById(R.id.logButton)
        sourceToggleButton = findViewById(R.id.sourceToggleButton)
        sourceFooterText = findViewById(R.id.sourceFooterText)
        provider = if (prefs.getString(PREF_PROVIDER, "kma") == "open_meteo") WeatherProvider.OPEN_METEO else WeatherProvider.KMA
        updateProviderUi()

        timeText      = findViewById(R.id.timeText)
        retryStatusText=  findViewById(R.id.retryStatusText)

        tempLabelText  = findViewById(R.id.tempLabelText)
        tempValueText  = findViewById(R.id.tempValueText)


        windDirValueText   = findViewById(R.id.windDirValueText)
        windSpeedValueText = findViewById(R.id.windSpeedValueText)
        humidityValueText  = findViewById(R.id.humidityValueText)
        windUiButton       = findViewById(R.id.windUiButton)
        saveImageButton  = findViewById(R.id.saveImageButton)
        shareImageButton = findViewById(R.id.shareImageButton)
        shareImageButton.isEnabled = false

        // '고정'하지 않은 이전 세션의 일시 지역 선택은 앱 재실행 시 초기화합니다.
        if (!hasPinnedStationSelection()) {
            prefs.edit()
                .remove(PREF_SELECTED_STN)
                .remove(PREF_MANUAL_LAT)
                .remove(PREF_MANUAL_LON)
                .remove(PREF_MANUAL_NAME)
                .putBoolean(PREF_PIN_ENABLED, false)
                .apply()
        }

        initializeIdleWeatherUi()

        saveImageButton.setOnClickListener { saveCurrentScreenImage() }
        shareImageButton.setOnClickListener { shareLastSavedImage() }

        settingsButton.setOnClickListener {
            showRegionPickerDialog()
        }

        configureRetryAttention()
        retryButton.setOnClickListener { tryReconnect() }
        sourceToggleButton.setOnClickListener { switchWeatherProvider() }

        logButton.setOnClickListener {
            showLogDialog()
        }

        windUiButton.setOnClickListener {
            val deg = if (!lastWindDirectionDeg.isNaN()) {
                lastWindDirectionDeg
            } else {
                windDirValueText.text?.toString()?.toFloatOrNull() ?: Float.NaN
            }
            WindCompassDialogFragment
                .newInstance(deg)
                .show(supportFragmentManager, "wind_compass")
        }

        updateTimeBox()

        // 백그라운드 실행 금지 정책: 과거 버전에 등록됐을 수 있는 알람을 모두 취소합니다.
        AlarmScheduler.reschedule(this)
        // 시작 시 위치 권한을 요청하거나 GPS/네트워크를 사용하지 않습니다.
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        // 위치/알림 권한 다이얼로그와 Play의 연령 공유 UI가 겹치지 않도록
        // Activity가 실제 포커스를 얻은 뒤 한 번만 요청합니다.
        if (hasFocus && !ageSignalsRequested) {
            ageSignalsRequested = true
            ageSignalsCompliance.requestAgeCategory(this)
        }
    }

    override fun onResume() {
        super.onResume()
        attentionAnimator?.resume()
        // 화면이 보이는 동안 현재 시간 표시 갱신
        timeHandler.removeCallbacks(timeTicker)
        timeHandler.post(timeTicker)
        // 화면 복귀 시에도 사용자가 누르기 전에는 위치를 조회하지 않습니다.
    }

    override fun onPause() {
        super.onPause()
        attentionAnimator?.pause()
        timeHandler.removeCallbacks(timeTicker)
    }

    private fun updateTimeBox() {
        val tv = timeText ?: return
        try {
            val fmt = SimpleDateFormat("yyyy년 M월 d일 EEEE a h:mm", Locale.KOREA)
            tv.text = fmt.format(Date())
        } catch (e: Exception) {
            tv.text = getString(R.string.current_time_default)
            logE("time format error", e)
        }
    }



    /* ---------- 현재 온도 표시 ---------- */
    private fun formatTempDisplay(raw: String?): String {
        val v = raw?.trim()?.toDoubleOrNull() ?: return "-"
        // KMA AWS 표준에 따라 결측/이상값(예: -99, -50 이하 등)은 표시하지 않음
        if (v.isNaN() || v <= -50.0) return "-"

        // 소수값이 있으면 1자리까지 표시(예: -5.7º), 정수면 소수점 제거(예: 2º)
        val roundedInt = v.roundToInt().toDouble()
        val s = if (kotlin.math.abs(v - roundedInt) < 1e-9) {
            v.roundToInt().toString()
        } else {
            String.format(Locale.getDefault(), "%.1f", v)
        }
        return "${s}º"
    }


    private fun formatMetricDisplay(raw: String?): String {
        val v = raw?.trim()?.toDoubleOrNull() ?: return "-"
        // 화면 표기: 소수점 1자리(스크린샷 기준)
        return String.format(Locale.KOREA, "%.1f", v)
    }

    /* ---------- 화면 캡처 저장/공유 ---------- */
    private fun saveCurrentScreenImage() {
        // Android 9(P) 이하에서는 외부 저장소 쓰기 권한이 필요
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                pendingSaveAfterPermission = true
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    REQ_WRITE_STORAGE
                )
                return
            }
        }

        // 레이아웃이 완전히 잡힌 뒤(폭/높이 확정) 캡처합니다.
        val scroll = findViewById<NestedScrollView?>(R.id.rootLayout)
        val rootForPost = scroll ?: window.decorView.rootView
        rootForPost.post {
            val sv = scroll
            val content: View
            val padL: Int
            val padT: Int
            val padR: Int
            val padB: Int
            val finalW: Int

            if (sv != null && sv.childCount > 0) {
                content = sv.getChildAt(0)
                padL = sv.paddingLeft
                padT = sv.paddingTop
                padR = sv.paddingRight
                padB = sv.paddingBottom
                finalW = sv.width
            } else {
                // 혹시 rootLayout을 찾지 못한 경우(예외 케이스)
                content = window.decorView.rootView
                padL = 0; padT = 0; padR = 0; padB = 0
                finalW = content.width
            }

            if (finalW <= 0) {
                Toast.makeText(this, "화면이 아직 준비되지 않았습니다", Toast.LENGTH_SHORT).show()
                return@post
            }

            // ✅ 핵심: ScrollView 자체가 아니라 '컨텐츠(ViewGroup)'를 캡처하되,
            //         ScrollView의 padding(흰 여백/테두리)까지 포함해서 저장합니다.
            val contentW = (finalW - padL - padR).coerceAtLeast(1)

            // 캡처 중 레이아웃이 흔들려 버튼이 넓어지는 문제를 막기 위해
            // 현재 레이아웃 값을 저장해두고, 캡처 후 원복합니다.
            val oldL = content.left
            val oldT = content.top
            val oldR = content.right
            val oldB = content.bottom

            val wSpec = View.MeasureSpec.makeMeasureSpec(contentW, View.MeasureSpec.EXACTLY)
            val hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            content.measure(wSpec, hSpec)
            content.layout(0, 0, content.measuredWidth, content.measuredHeight)

            val fullW = finalW
            val fullH = (content.measuredHeight + padT + padB).coerceAtLeast(1)

            // 너무 긴 화면은 메모리(OOM) 위험이 있어서 픽셀 수 기준으로 자동 축소 저장합니다.
            val maxPixels = 12_000_000L // 약 12MP (안정성 우선)
            val pixels = fullW.toLong() * fullH.toLong()
            val scale = if (pixels > maxPixels) {
                kotlin.math.sqrt(maxPixels.toDouble() / pixels.toDouble()).toFloat()
            } else {
                1.0f
            }

            val bmpW = (fullW * scale).toInt().coerceAtLeast(1)
            val bmpH = (fullH * scale).toInt().coerceAtLeast(1)

            val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            // 배경이 투명하면 뷰어에서 검게 보일 수 있어 흰색으로 채웁니다.
            canvas.drawColor(Color.WHITE)

            if (scale != 1.0f) {
                canvas.scale(scale, scale)
            }

            // ScrollView 배경(있다면)도 동일한 크기로 포함
            if (sv != null) {
                sv.background?.let { bg ->
                    bg.bounds = android.graphics.Rect(0, 0, fullW, fullH)
                    bg.draw(canvas)
                }
            }

            // padding 영역을 남기고 컨텐츠를 그립니다.
            canvas.save()
            canvas.translate(padL.toFloat(), padT.toFloat())
            content.draw(canvas)
            canvas.restore()

            // 캡처가 끝났으면 원래 레이아웃으로 되돌립니다(화면 흔들림 방지)
            content.layout(oldL, oldT, oldR, oldB)

            lifecycleScope.launch(Dispatchers.IO) {
                val uri = try {
                    saveBitmapToDownloads(bitmap)
                } finally {
                    // 저장 후에는 큰 캡처 비트맵을 즉시 해제해 백그라운드 메모리 잔류를 줄입니다.
                    bitmap.recycle()
                }

                withContext(Dispatchers.Main) {
                    if (uri != null) {
                        lastSavedImageUri = uri
                        shareImageButton.isEnabled = true
                        Toast.makeText(this@MainActivity, "Downloads에 저장되었습니다", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "저장 실패", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }


    private fun shareLastSavedImage() {
        val uri = lastSavedImageUri
        if (uri == null) {
            Toast.makeText(this, "먼저 이미지를 저장하세요", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, "공유"))
        } catch (e: Exception) {
            Toast.makeText(this, "공유 가능한 앱이 없습니다", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveBitmapToDownloads(bitmap: Bitmap): Uri? {
        val filename = "wweather_${System.currentTimeMillis()}.png"

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return null

                val saved = try {
                    resolver.openOutputStream(uri, "w")?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    } ?: false
                } catch (t: Throwable) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw t
                }
                if (!saved) {
                    runCatching { resolver.delete(uri, null, null) }
                    return null
                }

                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, filename)
                file.outputStream().use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                        return null
                    }
                }
                FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            }
        } catch (t: Throwable) {
            logE("saveBitmapToDownloads error", t)
            null
        }
    }

    /* ---------- 권한 ---------- */
    /* 시/군/구 날씨는 ACCESS_COARSE_LOCATION으로 충분합니다. 정확한 위치 권한은 사용하지 않습니다. */
    private fun hasLocationPermission(): Boolean = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private fun askForOneTimeLocation() {
        // 사용자에게 목적을 알리고, 취소 시 아무 작업도 하지 않습니다.
        AlertDialog.Builder(this)
            .setTitle(R.string.location_consent_title)
            .setMessage(R.string.location_consent_message)
            .setPositiveButton(R.string.location_yes) { _, _ ->
                if (!isLocationEnabled()) {
                    AlertDialog.Builder(this)
                        .setMessage(R.string.gps_off)
                        .setPositiveButton("위치 설정") { _, _ ->
                            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        }
                        .setNegativeButton(R.string.location_no, null)
                        .show()
                } else if (hasLocationPermission()) {
                    beginLocationRequest()
                } else {
                    ActivityCompat.requestPermissions(
                        this, arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION
                    )
                }
            }
            .setNegativeButton(R.string.location_no, null)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_WRITE_STORAGE -> {
                val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                if (granted && pendingSaveAfterPermission) {
                    pendingSaveAfterPermission = false
                    saveCurrentScreenImage()
                } else {
                    pendingSaveAfterPermission = false
                    Toast.makeText(this, "저장 권한이 필요합니다", Toast.LENGTH_SHORT).show()
                }
            }
            REQ_LOCATION -> {
                if (hasLocationPermission() && isLocationEnabled()) beginLocationRequest()
                else {
                    statusText.text = getString(R.string.no_location_selected)
                    retryStatusText.text = getString(R.string.status_start)
                }
            }
        }
    }

    private fun beginLocationRequest() {
        if (isRefreshing) cancelCurrentWeatherRequest()
        val requestId = ++locationRequestSeq
        showLoading(getString(R.string.loading_location))
        isRefreshing = true
        regionText.text = getString(R.string.checking_location)
        statusText.text = getString(R.string.loading_location)
        retryStatusText.text = getString(R.string.status_in_progress)
        fetchLocation(requestId)
    }

    private fun onLocationFailure(message: String? = null) {
        isRefreshing = false
        hideLoading()
        statusText.text = message ?: getString(R.string.location_not_found)
        retryStatusText.text = getString(R.string.status_fail)
        regionText.text = getString(R.string.location_select_manual_hint)
    }

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    /** 대한민국 근처 좌표만 기상청 국내 관측소로 매칭합니다. 해외 에뮬레이터 좌표가 독도로 오인되는 현상을 방지합니다. */
    private fun isNearKorea(lat: Double, lon: Double): Boolean =
        lat in 32.0..40.0 && lon in 124.0..133.0

    /* ---------- 위치 ---------- */
    @Suppress("MissingPermission")
    private fun fetchLocation(requestId: Long) {
        if (!hasLocationPermission()) {
            onLocationFailure()
            return
        }

        // 정확한 위치 권한 없이도 HIGH_ACCURACY 요청이 가능하며, 시스템은 대략 위치만 반환합니다.
        // 에뮬레이터는 BALANCED_POWER_ACCURACY에서 위치를 돌려주지 않는 경우가 있어 보조 경로를 둡니다.
        val cts = CancellationTokenSource()
        var complete = false
        val timeout = Runnable {
            if (!complete && requestId == locationRequestSeq) {
                complete = true
                cts.cancel()
                logD("fused current location timeout; trying cached/system providers")
                fetchLastFusedLocation(requestId)
            }
        }
        timeHandler.postDelayed(timeout, 12_000L)
        try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .addOnSuccessListener { cur ->
                    if (complete || requestId != locationRequestSeq) return@addOnSuccessListener
                    complete = true
                    timeHandler.removeCallbacks(timeout)
                    if (cur != null && isUsableLocation(cur)) {
                        logLocation("fused current", cur)
                        onLocationReady(cur)
                    } else {
                        logD("fused current not usable; checking last/system location")
                        fetchLastFusedLocation(requestId)
                    }
                }
                .addOnFailureListener { error ->
                    if (complete || requestId != locationRequestSeq) return@addOnFailureListener
                    complete = true
                    timeHandler.removeCallbacks(timeout)
                    logE("fused current location failed", error)
                    fetchLastFusedLocation(requestId)
                }
        } catch (error: Exception) {
            complete = true
            timeHandler.removeCallbacks(timeout)
            logE("fused current location exception", error)
            if (requestId == locationRequestSeq) fetchLastFusedLocation(requestId)
        }
    }

    @Suppress("MissingPermission")
    private fun fetchLastFusedLocation(requestId: Long) {
        if (requestId != locationRequestSeq) return
        try {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { last ->
                    if (requestId != locationRequestSeq) return@addOnSuccessListener
                    if (last != null && isUsableLocation(last)) {
                        logLocation("fused last", last)
                        onLocationReady(last)
                    } else {
                        fetchSystemLocation(requestId)
                    }
                }
                .addOnFailureListener { error ->
                    if (requestId != locationRequestSeq) return@addOnFailureListener
                    logE("fused last location failed", error)
                    fetchSystemLocation(requestId)
                }
        } catch (error: Exception) {
            logE("fused last location exception", error)
            if (requestId == locationRequestSeq) fetchSystemLocation(requestId)
        }
    }

    /** Google Play 서비스가 위치를 못 주는 AVD에서도 기기의 위치 제공자를 한 번 더 확인합니다. */
    @Suppress("MissingPermission")
    private fun fetchSystemLocation(requestId: Long) {
        if (requestId != locationRequestSeq) return
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { provider -> runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false) }

        fun attempt(index: Int) {
            if (requestId != locationRequestSeq) return
            if (index >= providers.size) {
                logD("all enabled location providers returned no usable location")
                onLocationFailure(getString(R.string.location_not_found))
                return
            }
            val providerName = providers[index]
            val cancellation = CancellationSignal()
            var complete = false
            val timeout = Runnable {
                if (!complete && requestId == locationRequestSeq) {
                    complete = true
                    cancellation.cancel()
                    logD("system location timeout provider=$providerName")
                    attempt(index + 1)
                }
            }
            timeHandler.postDelayed(timeout, 8_000L)
            try {
                LocationManagerCompat.getCurrentLocation(
                    manager, providerName, cancellation, ContextCompat.getMainExecutor(this)
                ) { found ->
                    if (complete || requestId != locationRequestSeq) return@getCurrentLocation
                    complete = true
                    timeHandler.removeCallbacks(timeout)
                    if (found != null && isUsableLocation(found)) {
                        logLocation("system $providerName", found)
                        onLocationReady(found)
                    } else {
                        attempt(index + 1)
                    }
                }
            } catch (error: Exception) {
                complete = true
                timeHandler.removeCallbacks(timeout)
                logE("system location $providerName exception", error)
                attempt(index + 1)
            }
        }
        attempt(0)
    }

    private fun locationAgeSeconds(loc: android.location.Location): Long? {
        return try {
            val now = SystemClock.elapsedRealtimeNanos()
            val ageNanos = now - loc.elapsedRealtimeNanos
            (ageNanos / 1_000_000_000L).coerceAtLeast(0)
        } catch (_: Throwable) {
            null
        }
    }

    private fun isUsableLocation(loc: android.location.Location): Boolean {
        // 대략적인 위치는 수 km 오차가 정상입니다. 오래된 데이터만 배제합니다.
        val ageSec = locationAgeSeconds(loc)
        val acc = try { loc.accuracy } catch (_: Throwable) { Float.MAX_VALUE }

        val okAge = ageSec == null || ageSec <= 180L          // 3분 이내
        val okAcc = acc.isFinite() && acc <= 100000f          // 대략 위치 최대 100km

        return okAge && okAcc
    }

    private fun logLocation(tag: String, loc: android.location.Location) {
        val ageSec = locationAgeSeconds(loc)
        val acc = try { loc.accuracy } catch (_: Throwable) { Float.NaN }
        logD("approx location $tag accuracy=${acc}m ageSec=${ageSec ?: -1} provider=${loc.provider}")
    }

    private fun onLocationReady(loc: Location) {
        val domestic = isNearKorea(loc.latitude, loc.longitude)
        // 미국 등 에뮬레이터의 해외 GPS 좌표를 한국 관측소(특히 독도)에 잘못 매칭하지 않습니다.
        if (!domestic && provider == WeatherProvider.KMA) {
            logD("KMA unsupported location: outside Korean region")
            onLocationFailure(getString(R.string.location_outside_korea))
            return
        }
        val station = if (domestic) nearestStation(loc.latitude, loc.longitude).first else null
        val area = if (domestic) {
            LocalRegionRepository.nearest(csvRegions, loc.latitude, loc.longitude)?.name ?: "현재 위치 근처"
        } else {
            "현재 위치(해외)"
        }
        if (station != null) logD("approx nearest station=${station.id}")
        prefs.edit()
            .putInt(PREF_LAST_STN, station?.id ?: 0)
            .putFloat(PREF_LAST_LAT, loc.latitude.toFloat())
            .putFloat(PREF_LAST_LON, loc.longitude.toFloat())
            .remove(PREF_SELECTED_STN).remove(PREF_MANUAL_LAT).remove(PREF_MANUAL_LON)
            .remove(PREF_MANUAL_NAME).putBoolean(PREF_PIN_ENABLED, false)
            .apply()
        if (provider == WeatherProvider.OPEN_METEO) {
            startOpenMeteoWeather(loc.latitude, loc.longitude, area)
        } else if (station != null) {
            startKmaWeather(station.id, "$area (stn=${station.id} ${station.name})")
        }
    }

    private fun hasPinnedStationSelection(): Boolean =
        prefs.getBoolean(PREF_PIN_ENABLED, false) && prefs.getInt(PREF_SELECTED_STN, 0) > 0

    private fun hasActiveStationSelection(): Boolean = prefs.getInt(PREF_SELECTED_STN, 0) > 0

    private fun selectedStationId(): Int = prefs.getInt(PREF_SELECTED_STN, 0)

    private fun findStationById(stationId: Int): Station? =
        StationRepo.stations.firstOrNull { it.id == stationId }

    private fun applyStationSelection(station: Station, isPinned: Boolean = false) {
        prefs.edit()
            .putInt(PREF_SELECTED_STN, station.id)
            .putInt(PREF_LAST_STN, station.id)
            .putFloat(PREF_MANUAL_LAT, station.lat.toFloat())
            .putFloat(PREF_MANUAL_LON, station.lon.toFloat())
            .putString(PREF_MANUAL_NAME, station.name)
            .putBoolean(PREF_PIN_ENABLED, isPinned)
            .apply()
        startWeatherAtSavedLocation()
    }

    private fun clearPinnedAndResetToDefault() {
        prefs.edit().remove(PREF_SELECTED_STN).remove(PREF_LAST_STN)
            .remove(PREF_MANUAL_LAT).remove(PREF_MANUAL_LON).remove(PREF_MANUAL_NAME)
            .remove(PREF_LAST_LAT).remove(PREF_LAST_LON)
            .putBoolean(PREF_PIN_ENABLED, false).apply()
        cancelCurrentWeatherRequest()
        initializeIdleWeatherUi()
        Toast.makeText(this, "지역 선택을 초기화했습니다. 재접속을 누르면 위치를 확인합니다.", Toast.LENGTH_SHORT).show()
    }

    private fun showRegionPickerDialog() {
        if (provider == WeatherProvider.OPEN_METEO) {
            showOpenMeteoRegionPicker()
            return
        }
        val root = layoutInflater.inflate(R.layout.dialog_region_picker, null)
        val searchEditText = root.findViewById<EditText>(R.id.regionSearchEditText)
        val numberPicker = root.findViewById<NumberPicker>(R.id.regionNumberPicker)
        val pinnedText = root.findViewById<TextView>(R.id.regionDialogPinnedText)
        val searchButton = root.findViewById<Button>(R.id.regionSearchButton)
        val closeButton = root.findViewById<Button>(R.id.regionCloseButton)
        val resetButton = root.findViewById<Button>(R.id.regionResetButton)
        val pinButton = root.findViewById<Button>(R.id.regionPinButton)
        val confirmButton = root.findViewById<Button>(R.id.regionConfirmButton)

        val allStations = StationRepo.stations.distinctBy { it.id }
        var filteredStations = allStations
        var hasSearchResult = true
        val currentStationId = when {
            hasActiveStationSelection() -> selectedStationId()
            else -> prefs.getInt(PREF_LAST_STN, 0)
        }

        fun updatePinnedText() {
            val pinnedName = findStationById(selectedStationId())?.name
            pinnedText.text = if (hasPinnedStationSelection() && !pinnedName.isNullOrBlank()) {
                "고정: $pinnedName"
            } else {
                "고정: 없음"
            }
        }

        fun updatePicker(targetStationId: Int? = null) {
            val displayValues = filteredStations.map { "${it.name} (stn=${it.id})" }.toTypedArray()
            numberPicker.displayedValues = null
            numberPicker.value = 0
            if (displayValues.isEmpty()) {
                hasSearchResult = false
                numberPicker.minValue = 0
                numberPicker.maxValue = 0
                numberPicker.wrapSelectorWheel = false
                numberPicker.displayedValues = arrayOf("검색 결과 없음")
                return
            }
            hasSearchResult = true
            numberPicker.minValue = 0
            numberPicker.maxValue = displayValues.lastIndex
            numberPicker.wrapSelectorWheel = false
            numberPicker.displayedValues = displayValues
            val index = targetStationId?.let { id -> filteredStations.indexOfFirst { it.id == id } }?.takeIf { it >= 0 } ?: 0
            numberPicker.value = index
        }

        fun filterStations() {
            val query = searchEditText.text?.toString()?.trim().orEmpty()
            filteredStations = if (query.isBlank()) {
                allStations
            } else {
                allStations.filter { it.name.contains(query, ignoreCase = true) }
            }
            updatePicker(targetStationId = currentStationId)
        }

        updatePinnedText()
        updatePicker(targetStationId = currentStationId)

        val dialog = AlertDialog.Builder(this)
            .setTitle("지역 열기")
            .setView(root)
            .create()

        searchButton.setOnClickListener { filterStations() }
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                filterStations()
            }
        })

        confirmButton.setOnClickListener {
            if (!hasSearchResult) {
                Toast.makeText(this, "검색 결과가 없습니다", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val selected = filteredStations.getOrNull(numberPicker.value)
            if (selected != null) {
                prefs.edit().putBoolean(PREF_PIN_ENABLED, false).apply()
                applyStationSelection(selected, isPinned = false)
                Toast.makeText(this, "${selected.name} 지역으로 변경했습니다", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        pinButton.setOnClickListener {
            if (!hasSearchResult) {
                Toast.makeText(this, "검색 결과가 없습니다", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val selected = filteredStations.getOrNull(numberPicker.value)
            if (selected != null) {
                prefs.edit()
                    .putInt(PREF_SELECTED_STN, selected.id)
                    .putBoolean(PREF_PIN_ENABLED, true)
                    .apply()
                updatePinnedText()
                applyStationSelection(selected, isPinned = true)
                Toast.makeText(this, "${selected.name} 지역을 고정했습니다", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        resetButton.setOnClickListener {
            dialog.dismiss()
            clearPinnedAndResetToDefault()
        }

        closeButton.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /** Open-Meteo manual location list is read from the supplied CSV, with keyword search and pinning. */
    private fun showOpenMeteoRegionPicker() {
        val root = layoutInflater.inflate(R.layout.dialog_region_picker, null)
        val searchField = root.findViewById<EditText>(R.id.regionSearchEditText)
        val picker = root.findViewById<NumberPicker>(R.id.regionNumberPicker)
        val pinnedLabel = root.findViewById<TextView>(R.id.regionDialogPinnedText)
        val all = csvRegions
        if (all.isEmpty()) {
            Toast.makeText(this, "지역 CSV를 읽을 수 없습니다", Toast.LENGTH_LONG).show()
            return
        }
        var filtered = all
        fun updatePinned() {
            pinnedLabel.text = if (hasPinnedStationSelection()) {
                "고정: ${prefs.getString(PREF_MANUAL_NAME, "선택 지역")}"
            } else "고정: 없음"
        }
        fun filter() {
            val word = searchField.text?.toString()?.trim().orEmpty()
            filtered = if (word.isBlank()) all else all.filter { it.name.contains(word, ignoreCase = true) }
            picker.displayedValues = null
            picker.value = 0
            picker.minValue = 0
            picker.maxValue = (filtered.size - 1).coerceAtLeast(0)
            picker.wrapSelectorWheel = false
            picker.displayedValues = if (filtered.isEmpty()) arrayOf("검색 결과 없음") else filtered.map { it.name }.toTypedArray()
            val last = prefs.getString(PREF_MANUAL_NAME, null)
            val selectedIndex = filtered.indexOfFirst { it.name == last }
            picker.value = if (selectedIndex >= 0) selectedIndex else 0
        }
        updatePinned()
        filter()
        val dialog = AlertDialog.Builder(this).setTitle("지역 열기 · Open-Meteo (CSV)").setView(root).create()
        root.findViewById<Button>(R.id.regionSearchButton).setOnClickListener { filter() }
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { filter() }
        })
        fun select(pin: Boolean) {
            val region = filtered.getOrNull(picker.value) ?: run {
                Toast.makeText(this, "검색 결과가 없습니다", Toast.LENGTH_SHORT).show()
                return
            }
            val station = nearestStation(region.latitude, region.longitude).first
            prefs.edit()
                .putInt(PREF_SELECTED_STN, station.id)
                .putInt(PREF_LAST_STN, station.id)
                .putFloat(PREF_MANUAL_LAT, region.latitude.toFloat())
                .putFloat(PREF_MANUAL_LON, region.longitude.toFloat())
                .putString(PREF_MANUAL_NAME, region.name)
                .putBoolean(PREF_PIN_ENABLED, pin)
                .apply()
            dialog.dismiss()
            startOpenMeteoWeather(region.latitude, region.longitude, region.name)
        }
        root.findViewById<Button>(R.id.regionConfirmButton).setOnClickListener { select(false) }
        root.findViewById<Button>(R.id.regionPinButton).setOnClickListener { select(true) }
        root.findViewById<Button>(R.id.regionResetButton).setOnClickListener {
            dialog.dismiss()
            clearPinnedAndResetToDefault()
        }
        root.findViewById<Button>(R.id.regionCloseButton).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /* ---------- 관측소 ---------- */
    private fun nearestStation(lat: Double, lon: Double): Pair<Station, Double> {
        if (StationRepo.stations.isEmpty()) return Station(0, "UNKNOWN", 0.0, 0.0) to Double.MAX_VALUE
        var best = StationRepo.stations.first()
        var min = Double.MAX_VALUE
        for (s in StationRepo.stations) {
            val d = haversine(lat, lon, s.lat, s.lon)
            if (d < min) { min = d; best = s }
        }
        return best to min
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) *
                cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        return 2 * R * atan2(sqrt(a), sqrt(1 - a))
    }

    /* ---------- 공용: 칼럼 인덱스 기반 추출 (1-based) ---------- */
    private fun extractByColumnIndex(text: String, columnIndex1Based: Int): String? {
        if (columnIndex1Based <= 0) return null
        val tsRegex = Regex("""\b\d{12}\b""")
        var last: String? = null

        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .forEach { line ->
                if (tsRegex.containsMatchIn(line)) {
                    val parts = line.split(Regex("""\s+"""))
                    if (parts.size >= columnIndex1Based) {
                        last = parts[columnIndex1Based - 1].trim()
                    }
                }
            }
        return last
    }


    /* ---------- 네트워크 & 캐시 유틸 ---------- */
    private fun cacheFile(): File =
        (getExternalFilesDir(null) ?: filesDir).resolve(CACHE_NAME)

    private fun loadCacheText(stn: Int): String? {
        return try {
            if (prefs.getInt(PREF_CACHE_STN, 0) != stn) return null
            val f = cacheFile()
            if (f.exists() && f.length() > 0) f.readText(Charsets.UTF_8) else null
        } catch (_: Exception) { null }
    }

    private fun saveCacheText(stn: Int, text: String) {
        try {
            cacheFile().writeText(text, Charsets.UTF_8)
            prefs.edit()
                .putInt(PREF_CACHE_STN, stn)
                .putLong("aws_cache_time", System.currentTimeMillis())
                .apply()
        } catch (_: Exception) { /* ignore */ }
    }

    /* ---------- 기상청 AWS 통합 조회 (기압 · 온도 · 바람 · 습도 · 강수) ---------- */
    private fun fetchKmaAws(stn: Int, seq: Long) {
        weatherText.text = getString(R.string.weather_default)
        val tm2 = kstTm2Minus(2)
        val url = "https://apihub.kma.go.kr/api/typ01/cgi-bin/url/nph-aws2_min" +
                "?tm2=$tm2&stn=$stn&disp=0&help=1&authKey=${URLEncoder.encode(BuildConfig.KMA_AUTH_KEY, "UTF-8")}"

        logD("aws2_min request stn=$stn tm2=$tm2")

        awsJob = lifecycleScope.launch(Dispatchers.IO) {
            // 1) 네트워크 (1회 재시도) - 단일 요청으로 기압/온도/바람/습도/강수를 모두 파싱합니다.
            val body = httpGetTextWithRetry(url, "aws2_min", retries = 1)

            val usedCache: Boolean
            val textToParse: String? = if (!body.isNullOrBlank()) {
                saveCacheText(stn, body)
                usedCache = false
                body
            } else {
                usedCache = true
                loadCacheText(stn)
            }

            if (textToParse.isNullOrBlank()) {
                withContext(Dispatchers.Main) {
                    if (seq != weatherRequestSeq || provider != WeatherProvider.KMA) return@withContext
                    pressureText.text = getString(R.string.api_error, "조회 실패")
                    weatherText.text = "오늘 강수 확인 불가"
                    retryStatusText.text = getString(R.string.status_fail)

                    windDirValueText.text = "-"
                    lastWindDirectionDeg = Float.NaN
                    windSpeedValueText.text = "-"
                    humidityValueText.text = "-"
                }
                return@launch
            }

            logD("aws2_min http ${if (usedCache) "cache" else "ok"}, len=${textToParse.length}, ageMin=${cacheAgeMinutes()}")

            // PA는 16번째 칼럼(1-based)
            val paStr = extractByColumnIndex(textToParse, 16) ?: extractByHeader(textToParse, "PA")
            val pa = paStr?.toDoubleOrNull()

            // TA는 9번째 칼럼(1-based)
            val taStr = extractByColumnIndex(textToParse, 9) ?: extractByHeader(textToParse, "TA")
            val tempDisplay = formatTempDisplay(taStr)

            // WD1(풍향)=3번째, WS1(풍속)=4번째, HM(습도)=15번째 칼럼(1-based)
            val wdStr = extractByColumnIndex(textToParse, 3) ?: extractByHeader(textToParse, "WD1")
            val wsStr = extractByColumnIndex(textToParse, 4) ?: extractByHeader(textToParse, "WS1")
            val hmStr = extractByColumnIndex(textToParse, 15) ?: extractByHeader(textToParse, "HM")

            val wdDisplay = formatMetricDisplay(wdStr)
            val wsDisplay = formatMetricDisplay(wsStr)
            val hmDisplay = formatMetricDisplay(hmStr)

            // 강수 판정:
            // 1) RE가 정상값(0 이상)이면 RE로 판정
            // 2) RE가 -99.9 같은 음수 오류값이면 RN-15m(11번째 칼럼)로 대체 판정
            val re = (extractByColumnIndex(textToParse, 10) ?: extractByHeader(textToParse, "RE"))
                ?.toDoubleOrNull()
            val rn15 = (extractByHeader(textToParse, "RN-15m") ?: extractByColumnIndex(textToParse, 11))?.toDoubleOrNull()
            val rn60 = (extractByHeader(textToParse, "RN-60m") ?: extractByColumnIndex(textToParse, 12))?.toDoubleOrNull()
            val rn12 = (extractByHeader(textToParse, "RN-12H") ?: extractByColumnIndex(textToParse, 13))?.toDoubleOrNull()
            val rnDay = (extractByHeader(textToParse, "RN-DAY") ?: extractByColumnIndex(textToParse, 14))?.toDoubleOrNull()

            val rainDecision: Boolean? = when {
                re == null -> null
                re >= 0.0 -> re > 0.0
                rn15 != null && rn15 >= 0.0 -> rn15 > 0.0
                else -> null
            }

            val rainSource = when {
                re == null -> "unknown(RE missing)"
                re >= 0.0 -> "RE"
                rn15 != null && rn15 >= 0.0 -> "RN-15m fallback"
                else -> "unknown(RN-15m invalid)"
            }

            // 오래된 캐시에서 '비 안옴'으로 단정하지 않도록 보호
            val cacheOld = usedCache && cacheAgeMinutes() >= 60

            logD("rain parsed stn=$stn tm2=$tm2 re=${re ?: "null"} rn15=${rn15 ?: "null"} rn60=${rn60 ?: "null"} rn12=${rn12 ?: "null"} rnDay=${rnDay ?: "null"} source=$rainSource -> rainDecision=$rainDecision usedCache=$usedCache")

            withContext(Dispatchers.Main) {
                if (seq != weatherRequestSeq || provider != WeatherProvider.KMA) return@withContext
                tempValueText.text = tempDisplay

                windDirValueText.text = wdDisplay
                lastWindDirectionDeg = wdStr?.trim()?.toFloatOrNull() ?: Float.NaN
                windSpeedValueText.text = wsDisplay
                humidityValueText.text = hmDisplay

                if (pa != null && pa in 880.0..1100.0) {
                    val paOut = String.format(Locale.KOREA, "%.2f", pa)
                    pressureText.text = getString(R.string.pressure_value, paOut)
                    updateEmojiAndLabel(pa)
                    retryStatusText.text = if (usedCache) "캐시" else "성공"
                } else {
                    pressureText.text = getString(R.string.api_error, "데이터 없음")
                    retryStatusText.text = if (usedCache) "캐시" else "실패"
                }

                weatherText.text = when {
                    rainDecision == null -> getString(R.string.weather_unknown)
                    cacheOld && !rainDecision -> getString(R.string.weather_unknown)
                    rainDecision -> if (usedCache) getString(R.string.weather_rain_cache) else getString(R.string.weather_rain)
                    else -> if (usedCache) getString(R.string.weather_no_rain_cache) else getString(R.string.weather_no_rain)
                }
            }
        }.also { job ->
            job.invokeOnCompletion { runOnUiThread { finishKmaPart(seq) } }
        }
    }

    private fun extractByHeader(text: String, field: String): String? {
        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        for (i in lines.indices) {
            val line = lines[i]
            val isHeader = line.startsWith("#") && line.contains("YYMMDDHHMI")
            if (!isHeader) continue

            val headerCols = line.removePrefix("#").trim().split(Regex("""\s+"""))
            val colIdx = headerCols.indexOf(field)
            if (colIdx == -1) continue

            var last: String? = null
            for (j in i + 1 until lines.size) {
                val dl = lines[j]
                if (dl.startsWith("#")) break
                val parts = dl.split(Regex("""\s+"""))
                if (parts.size > colIdx) {
                    last = parts[colIdx].trim()
                }
            }
            if (last != null) return last
        }
        return null
    }

    /* ---------- 분류 ---------- */
    private fun updateEmojiAndLabel(pressure: Double) {
        when {
            pressure < PRESSURE_LOW -> {
                emojiView.setImageResource(R.drawable.sick)
                conditionText.text = "아픔 · 나쁨"
            }
            pressure > PRESSURE_HIGH -> {
                emojiView.setImageResource(R.drawable.happy)
                conditionText.text = "안아픔 · 좋음"
            }
            else -> {
                emojiView.setImageResource(R.drawable.normal)
                conditionText.text = "보통 · 정상"
            }
        }
    }


    /* ---------- 현천(WW1) ---------- */
    private fun fetchWw1Now(stn: Int, requestSeq: Long) {
        // stn=0으로 현천 전체 목록을 받은 뒤, 선택된 기준 관측소와
        // 지리적으로 가장 가까운 현천 관측소의 값을 사용합니다.
        val seq = ++ww1RequestSeq

        ww1PrimaryJob?.cancel()
        ww1RetryJob?.cancel()

        wwNowText.text = getString(R.string.ww_loading)
        wwNowText.visibility = View.VISIBLE

        val primaryTm2 = kstTm2Minus(2)
        logD("ww1 all request(primary) seq=$seq targetStn=$stn tm2=$primaryTm2 timeoutMs=7000")

        ww1PrimaryJob = lifecycleScope.launch {
            val res = withTimeoutOrNull(7_000L) {
                withContext(Dispatchers.IO) {
                    ww1Client.fetchNearest(
                        targetStn = stn,
                        tm2 = primaryTm2,
                        allowCache = true
                    )
                }
            }

            // 최신 요청만 UI에 반영합니다.
            if (seq != ww1RequestSeq || requestSeq != weatherRequestSeq || provider != WeatherProvider.KMA) return@launch

            if (res == null) {
                wwNowText.text = getString(R.string.ww_unavailable)
                logD("현천 실패 targetStn=$stn tm2=$primaryTm2 cache=none")
                startWw1SilentRetry(seq = seq, stn = stn, requestSeq = requestSeq)
                return@launch
            }

            wwNowText.text = "${res.code}(${res.label})"
            val distanceText = res.distanceKm?.let { String.format(Locale.US, "%.2f", it) } ?: "unknown"

            if (res.networkOk) {
                logD(
                    "현천 ok targetStn=$stn sourceStn=${res.sourceStn} sourceName=${res.sourceName} " +
                            "distKm=$distanceText dataTm=${res.dataTm} code=${res.code}(${res.label}) min=${res.nnMin}"
                )
            } else {
                logD(
                    "현천 실패 targetStn=$stn tm2=$primaryTm2 cache=used " +
                            "sourceStn=${res.sourceStn} sourceName=${res.sourceName} distKm=$distanceText " +
                            "dataTm=${res.dataTm} code=${res.code}(${res.label})"
                )
                // 캐시 표시는 유지하면서 네트워크 복구를 조용히 재시도합니다.
                startWw1SilentRetry(seq = seq, stn = stn, requestSeq = requestSeq)
            }
        }.also { job ->
            job.invokeOnCompletion { runOnUiThread { finishKmaPart(requestSeq) } }
        }
    }

    private fun startWw1SilentRetry(seq: Long, stn: Int, requestSeq: Long) {
        val maxAttempts = 3
        val offsets = intArrayOf(0, 2, 5, 10)

        ww1RetryJob?.cancel()
        ww1RetryJob = lifecycleScope.launch {
            for (attempt in 1..maxAttempts) {
                delay(700L)

                for (off in offsets) {
                    val tm2 = kstTm2Minus(off)
                    logD("ww1 all retry attempt=$attempt seq=$seq targetStn=$stn tm2=$tm2")

                    // 재시도에서는 캐시를 다시 읽지 않고 실제 다운로드 성공만 확인합니다.
                    val res = withTimeoutOrNull(3_000L) {
                        withContext(Dispatchers.IO) {
                            ww1Client.fetchNearest(
                                targetStn = stn,
                                tm2 = tm2,
                                allowCache = false
                            )
                        }
                    }

                    if (seq != ww1RequestSeq || requestSeq != weatherRequestSeq || provider != WeatherProvider.KMA) return@launch

                    if (res != null && res.networkOk) {
                        wwNowText.text = "${res.code}(${res.label})"
                        val distanceText = res.distanceKm?.let { String.format(Locale.US, "%.2f", it) } ?: "unknown"
                        logD(
                            "현천 ok retry=$attempt targetStn=$stn sourceStn=${res.sourceStn} " +
                                    "sourceName=${res.sourceName} distKm=$distanceText dataTm=${res.dataTm} " +
                                    "code=${res.code}(${res.label}) min=${res.nnMin}"
                        )
                        return@launch
                    }

                    logD("현천 실패 retry=$attempt targetStn=$stn tm2=$tm2")
                }
            }

            // 1차에서 캐시가 표시되었다면 캐시 표시를 유지하고,
            // 캐시도 없었다면 이미 표시된 '정보 없음' 상태를 유지합니다.
            logD("ww1 all retry exhausted seq=$seq targetStn=$stn")
        }
    }




    /* ---------- 제공처 전환, 로딩, CSV 위치 복원 ---------- */
    private fun updateProviderUi() {
        val open = provider == WeatherProvider.OPEN_METEO
        sourceToggleButton.setText(if (open) R.string.provider_to_kma else R.string.provider_to_open)
        sourceFooterText.setText(if (open) R.string.provider_footer_open else R.string.provider_footer_kma)
        wwLegendText.setText(if (open) R.string.open_wmo_legend else R.string.ww_legend)
    }

    private fun initializeIdleWeatherUi() {
        statusText.text = getString(R.string.press_reconnect)
        retryStatusText.text = getString(R.string.status_start)
        regionText.text = if (hasActiveStationSelection()) {
            val name = prefs.getString(PREF_MANUAL_NAME, null)
            "${name ?: findStationById(selectedStationId())?.name ?: "저장된 지역"} (선택됨)"
        } else ""
        resetWeatherValues()
    }

    private fun resetWeatherValues() {
        pressureText.setText(R.string.pressure_default)
        weatherText.setText(R.string.weather_unknown)
        wwNowText.setText(R.string.ww_unavailable)
        tempValueText.text = "-"
        windDirValueText.text = "-"
        windSpeedValueText.text = "-"
        humidityValueText.text = "-"
        lastWindDirectionDeg = Float.NaN
        emojiView.setImageResource(R.drawable.normal)
        conditionText.text = ""
    }

    private fun cancelCurrentWeatherRequest() {
        ++locationRequestSeq
        ++weatherRequestSeq
        ++ww1RequestSeq
        awsJob?.cancel()
        openMeteoJob?.cancel()
        ww1PrimaryJob?.cancel()
        ww1RetryJob?.cancel()
        pendingKmaParts = 0
        isRefreshing = false
        hideLoading()
    }

    private fun switchWeatherProvider() {
        cancelCurrentWeatherRequest()
        provider = if (provider == WeatherProvider.KMA) WeatherProvider.OPEN_METEO else WeatherProvider.KMA
        prefs.edit().putString(PREF_PROVIDER, if (provider == WeatherProvider.KMA) "kma" else "open_meteo").apply()
        updateProviderUi()
        resetWeatherValues()
        startWeatherAtSavedLocation()
    }

    private fun startWeatherAtSavedLocation() {
        if (hasActiveStationSelection()) {
            val stn = findStationById(selectedStationId())
            val lat = if (prefs.contains(PREF_MANUAL_LAT)) prefs.getFloat(PREF_MANUAL_LAT, 0f).toDouble() else stn?.lat
            val lon = if (prefs.contains(PREF_MANUAL_LON)) prefs.getFloat(PREF_MANUAL_LON, 0f).toDouble() else stn?.lon
            val name = prefs.getString(PREF_MANUAL_NAME, null) ?: stn?.name ?: "선택 지역"
            if (provider == WeatherProvider.OPEN_METEO && lat != null && lon != null) {
                startOpenMeteoWeather(lat, lon, name)
                return
            }
            if (provider == WeatherProvider.KMA && stn != null) {
                val prefix = if (hasPinnedStationSelection()) "고정 지역" else "선택 지역"
                startKmaWeather(stn.id, "$prefix $name (stn=${stn.id} ${stn.name})")
                return
            }
        }
        if (prefs.contains(PREF_LAST_LAT) && prefs.contains(PREF_LAST_LON)) {
            val lat = prefs.getFloat(PREF_LAST_LAT, 0f).toDouble()
            val lon = prefs.getFloat(PREF_LAST_LON, 0f).toDouble()
            val region = LocalRegionRepository.nearest(csvRegions, lat, lon)?.name ?: "이전 위치 근처"
            if (provider == WeatherProvider.OPEN_METEO) startOpenMeteoWeather(lat, lon, if (isNearKorea(lat, lon)) region else "현재 위치(해외)")
            else if (isNearKorea(lat, lon)) {
                val station = nearestStation(lat, lon).first
                startKmaWeather(station.id, "$region (stn=${station.id} ${station.name})")
            } else {
                regionText.setText(R.string.location_select_manual_hint)
                statusText.setText(R.string.location_outside_korea)
                retryStatusText.setText(R.string.status_fail)
            }
        } else {
            regionText.text = ""
            statusText.setText(R.string.no_location_selected)
            retryStatusText.setText(R.string.status_start)
        }
    }

    private fun beginWeatherRequest(): Long {
        cancelCurrentWeatherRequest()
        ++weatherRequestSeq
        val seq = weatherRequestSeq
        showLoading(getString(R.string.loading_now))
        retryStatusText.setText(R.string.status_in_progress)
        statusText.setText(R.string.loading_now)
        resetWeatherValues()
        isRefreshing = true
        return seq
    }

    private fun showLoading(message: String) {
        if (isFinishing || isDestroyed) return
        val previous = loadingDialog
        if (previous?.isShowing == true) {
            loadingLabel?.text = message
            return
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(32, 28, 32, 28)
        }
        val spinner = ProgressBar(this)
        content.addView(spinner, LinearLayout.LayoutParams(50, 50))
        val label = TextView(this).apply {
            text = message
            textSize = 18f
            setPadding(24, 0, 0, 0)
        }
        loadingLabel = label
        content.addView(label)
        loadingDialog = AlertDialog.Builder(this).setView(content).setCancelable(false).create().also { it.show() }
    }

    private fun hideLoading() {
        loadingDialog?.dismiss()
        loadingDialog = null
        loadingLabel = null
    }

    private fun startKmaWeather(stn: Int, areaLabel: String) {
        val seq = beginWeatherRequest()
        regionText.text = areaLabel
        statusText.text = "기상청 API 요청 기준: ${kstTm2Minus(2)} (한국시간)"
        pendingKmaParts = 2
        fetchKmaAws(stn, seq)
        fetchWw1Now(stn, seq)
    }

    private fun finishKmaPart(seq: Long) {
        if (seq != weatherRequestSeq || provider != WeatherProvider.KMA) return
        pendingKmaParts = (pendingKmaParts - 1).coerceAtLeast(0)
        if (pendingKmaParts == 0) {
            isRefreshing = false
            hideLoading()
        }
    }

    private fun startOpenMeteoWeather(lat: Double, lon: Double, areaLabel: String) {
        val seq = beginWeatherRequest()
        regionText.text = areaLabel
        openMeteoJob = lifecycleScope.launch {
            try {
                val result = openMeteoClient.fetch(lat, lon)
                if (seq != weatherRequestSeq || provider != WeatherProvider.OPEN_METEO) return@launch
                tempValueText.text = formatTempDisplay(result.temperature?.toString())
                windDirValueText.text = formatMetricDisplay(result.windDirection?.toString())
                lastWindDirectionDeg = result.windDirection?.toFloat() ?: Float.NaN
                windSpeedValueText.text = formatMetricDisplay(result.windSpeed?.toString())
                humidityValueText.text = formatMetricDisplay(result.humidity?.toString())
                val pressure = result.pressure?.takeIf { it in 880.0..1100.0 }
                if (pressure != null) {
                    pressureText.text = getString(R.string.pressure_value, String.format(Locale.KOREA, "%.2f", pressure))
                    updateEmojiAndLabel(pressure)
                } else pressureText.text = getString(R.string.api_error, "기압 없음")

                val code = result.weatherCode
                wwNowText.text = if (code != null) "$code(${OpenMeteoClient.description(code)})" else "현천 정보없음"
                weatherText.text = when {
                    (result.snow ?: 0.0) > 0.0 || OpenMeteoClient.hasSnow(code) -> getString(R.string.open_snow)
                    (result.rain ?: 0.0) > 0.0 || OpenMeteoClient.hasRain(code) -> getString(R.string.weather_rain)
                    (result.precipitation ?: 0.0) > 0.0 -> "지금 강수 있음"
                    result.precipitation != null || code != null -> getString(R.string.weather_no_rain)
                    else -> getString(R.string.weather_unknown)
                }
                val asOf = result.validTime.replace('T', ' ')
                statusText.text = "모델 자료 기준: $asOf (한국시간)"
                retryStatusText.text = getString(R.string.status_success)
                logD("open-meteo current model validTime=${result.validTime} code=$code")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                if (seq == weatherRequestSeq && provider == WeatherProvider.OPEN_METEO) {
                    logE("Open-Meteo request failed", e)
                    statusText.text = "Open-Meteo 현재 날씨를 불러오지 못했습니다"
                    retryStatusText.text = getString(R.string.status_fail)
                }
            } finally {
                if (seq == weatherRequestSeq && provider == WeatherProvider.OPEN_METEO) {
                    isRefreshing = false
                    hideLoading()
                }
            }
        }
    }

    private fun configureRetryAttention() {
        // Red outline pulses around the green reconnect button; no layout replacements needed.
        val scale = resources.displayMetrics.density
        val shape = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 5 * scale
            setColor(Color.rgb(99, 157, 0))
        }
        retryButton.backgroundTintList = null
        retryButton.background = shape
        attentionAnimator = ValueAnimator.ofInt(60, 255).apply {
            duration = 850L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                val alpha = it.animatedValue as Int
                shape.setStroke((2 * scale + 0.5f).toInt(), Color.argb(alpha, 226, 20, 20))
            }
            start()
        }
    }

    /* ---------- 앱 로그 보기 ---------- */
    private fun showLogDialog() {
        val logText = AppLog.dump().ifBlank { getString(R.string.log_empty) }

        val tv = TextView(this).apply {
            text = logText
            setTextIsSelectable(true)
            textSize = 12f
            setPadding(24, 16, 24, 16)
        }
        val sv = ScrollView(this).apply { addView(tv) }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.diagnostics_log_title))
            .setView(sv)
            .setPositiveButton(getString(R.string.close), null)
            .setNeutralButton(getString(R.string.copy)) { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("wweather-log", logText))
                Toast.makeText(this, getString(R.string.log_copied), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.share)) { _, _ ->
                val it = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, logText)
                }
                startActivity(Intent.createChooser(it, getString(R.string.share_log_title)))
            }
            .show()
    }

    /* ---------- 재접속 버튼 ---------- */
    private fun tryReconnect() {
        AppLog.clear()
        if (hasActiveStationSelection()) {
            startWeatherAtSavedLocation() // manual/pinned area: no GPS required
        } else {
            askForOneTimeLocation() // ask again per user action; negative does nothing
        }
    }

    override fun onDestroy() {
        ++locationRequestSeq
        super.onDestroy()
        timeHandler.removeCallbacksAndMessages(null)
        ww1PrimaryJob?.cancel()
        ww1RetryJob?.cancel()
        awsJob?.cancel()
        openMeteoJob?.cancel()
        attentionAnimator?.cancel()
        hideLoading()
    }

}
