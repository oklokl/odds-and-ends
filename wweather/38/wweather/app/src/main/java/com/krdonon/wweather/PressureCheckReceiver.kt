package com.krdonon.wweather

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class PressureCheckReceiver : BroadcastReceiver() {

    private companion object {
        private const val CACHE_NAME = "nph-aws2_min.cache"
        private const val TAG = "KMA_RC"

        // 리시버 실행마다 새 연결 풀/스레드 풀을 만들지 않고, 백그라운드 실행 시간도 짧게 제한합니다.
        private val HTTP_CLIENT: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(7, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val pendingResult = goAsync()
        val appCtx = context.applicationContext

        val prefs = appCtx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val alertEnabled = prefs.getBoolean("alert", false)
        val conditionEnabled = prefs.getBoolean("condition", false)
        if (!alertEnabled && !conditionEnabled) { pendingResult.finish(); return }

        val min = prefs.getFloat("min", 900f).toDouble()
        val max = prefs.getFloat("max", 1040f).toDouble()
        val stn = prefs.getInt("last_stn", 108)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val tm2 = SimpleDateFormat("yyyyMMddHHmm", Locale.KOREA)
                    .format(Date(System.currentTimeMillis() - 12 * 60 * 1000L))
                val url = "https://apihub.kma.go.kr/api/typ01/cgi-bin/url/nph-aws2_min" +
                        "?tm2=$tm2&stn=$stn&disp=0&help=1&authKey=${URLEncoder.encode(BuildConfig.KMA_AUTH_KEY, "UTF-8")}"

                val bodyNet: String? = try {
                    val req = Request.Builder().url(url).build()
                    HTTP_CLIENT.newCall(req).execute().use { it.body?.string() }
                } catch (_: Exception) { null }

                val finalBody = when {
                    !bodyNet.isNullOrEmpty() -> { saveCache(appCtx, bodyNet); bodyNet }
                    else -> loadCache(appCtx)
                }

                if (finalBody.isNullOrEmpty()) return@launch

                val pa = extractByColumnIndex(finalBody, 16)?.toDoubleOrNull()
                    ?: extractByHeader(finalBody, "PA")?.toDoubleOrNull()
                    ?: extractPressureFallback(finalBody)

                if (pa != null) {
                    if (alertEnabled) {
                        showNotification(appCtx, "허리 날씨", "현재 기압: " + String.format(Locale.KOREA, "%.2f hPa", pa))
                    }
                    if (conditionEnabled && (pa < min || pa > max)) {
                        showNotification(appCtx, "허리 날씨", "범위를 벗어났습니다 (" + String.format(Locale.KOREA, "%.2f hPa", pa) + ")")
                    }
                }
            } finally { pendingResult.finish() }
        }
    }

    /* ---- 캐시 ---- */
    private fun cacheFile(ctx: Context): File =
        (ctx.getExternalFilesDir(null) ?: ctx.filesDir).resolve(CACHE_NAME)

    private fun saveCache(ctx: Context, text: String) {
        try { cacheFile(ctx).writeText(text, Charsets.UTF_8) } catch (_: Exception) {}
    }

    private fun loadCache(ctx: Context): String? {
        return try {
            val f = cacheFile(ctx)
            if (f.exists() && f.length() > 0) f.readText(Charsets.UTF_8) else null
        } catch (_: Exception) { null }
    }

    /* ---- 파싱 ---- */
    private fun extractByColumnIndex(text: String, columnIndex1Based: Int): String? {
        if (columnIndex1Based <= 0) return null
        val tsRegex = Regex("""\b\d{12}\b""")
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
            if (tsRegex.containsMatchIn(line)) {
                val parts = line.split(Regex("""\s+"""))
                if (parts.size >= columnIndex1Based) return parts[columnIndex1Based - 1].trim()
            }
        }
        return null
    }

    private fun extractByHeader(text: String, field: String): String? {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        for (i in lines.indices) {
            val line = lines[i]
            val isRealHeader = line.startsWith("#") && line.contains("YYMMDDHHMI") && line.contains(field)
            if (!isRealHeader) continue
            val headerCols = line.removePrefix("#").trim().split(Regex("""\s+"""))
            val colIdx = headerCols.indexOf(field)
            if (colIdx == -1) continue
            for (j in i + 1 until lines.size) {
                val dl = lines[j]
                if (dl.isNotEmpty() && !dl.startsWith("#")) {
                    val parts = dl.split(Regex("""\s+"""))
                    if (parts.size > colIdx) return parts[colIdx].trim()
                    break
                }
            }
        }
        return null
    }

    private fun extractPressureFallback(text: String): Double? {
        Regex("""\bPA\s*[:=]\s*([0-9]+(?:\.[0-9]+)?)\b""", RegexOption.IGNORE_CASE)
            .find(text)?.let { return it.groupValues[1].toDoubleOrNull() }
        Regex("""\b([0-9]+(?:\.[0-9]+)?)\s*hPa\b""", RegexOption.IGNORE_CASE)
            .find(text)?.let { return it.groupValues[1].toDoubleOrNull() }
        Regex("""\b([0-9]{3,4})\b""").findAll(text).forEach { m ->
            val v = m.groupValues[1].toIntOrNull() ?: return@forEach
            if (v in 880..1100) return v.toDouble()
        }
        return null
    }

    private fun showNotification(context: Context, title: String, msg: String) {
        val channelId = "weather_alerts"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(msg)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        NotificationManagerCompat.from(context)
            .notify(System.currentTimeMillis().toInt(), builder.build())
    }
}
