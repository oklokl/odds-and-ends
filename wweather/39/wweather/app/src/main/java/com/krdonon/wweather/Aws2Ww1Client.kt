package com.krdonon.wweather

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * AWS2 현천(WW1) 전체 관측소 조회 클라이언트.
 *
 * 동작 방식
 * 1) stn=0으로 현천 관측소 전체 목록을 내려받습니다.
 * 2) 사용자가 선택한 기준 관측소와 지리적으로 가장 가까운 현천 관측소를 찾습니다.
 * 3) 다운로드 성공 시 이전 현천 캐시를 교체합니다.
 * 4) 다운로드 실패 시 저장된 캐시에서 같은 방식으로 가장 가까운 현천을 찾습니다.
 */
class Aws2Ww1Client(
    context: Context,
    private val authKey: String,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
) {
    private val appContext = context.applicationContext

    data class Result(
        val code: Int,
        val label: String,
        val nnMin: Int,
        val targetStn: Int,
        val sourceStn: Int,
        val sourceName: String,
        val distanceKm: Double?,
        val dataTm: String,
        val fromCache: Boolean,
        val networkOk: Boolean,
        val note: String = ""
    )

    private data class Observation(
        val tm: String,
        val stn: Int,
        val lon: Double,
        val lat: Double,
        val code: Int,
        val nnMin: Int,
        val n: Int
    )

    suspend fun fetchNearest(
        targetStn: Int,
        tm2: String,
        allowCache: Boolean = true
    ): Result? = withContext(Dispatchers.IO) {
        val networkBytes = downloadAll(tm2)

        if (networkBytes != null) {
            val parsed = selectNearest(networkBytes, targetStn)
            if (parsed != null) {
                replaceCache(networkBytes)
                return@withContext parsed.copy(
                    fromCache = false,
                    networkOk = true,
                    note = "${parsed.note}; download"
                )
            }
        }

        if (!allowCache) return@withContext null

        val cachedBytes = loadCache() ?: return@withContext null
        val cached = selectNearest(cachedBytes, targetStn) ?: return@withContext null
        cached.copy(
            fromCache = true,
            networkOk = false,
            note = "${cached.note}; cache"
        )
    }

    private fun downloadAll(tm2: String): ByteArray? {
        val url = "https://apihub.kma.go.kr/api/typ01/cgi-bin/url/nph-aws2_min_ww1" +
                "?tm2=$tm2&itv=60&range=60&stn=0&disp=1&help=1&authKey=${URLEncoder.encode(authKey, "UTF-8")}"

        return try {
            val request = Request.Builder().url(url).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bytes = response.body?.bytes() ?: return null
                if (bytes.isEmpty()) null else bytes
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun selectNearest(bytes: ByteArray, targetStn: Int): Result? {
        // 데이터 행은 ASCII 숫자/기호이므로 헤더 문자셋과 무관하게 안전하게 파싱됩니다.
        val text = bytes.toString(Charsets.ISO_8859_1)
        val observations = text.lineSequence()
            .mapNotNull(::parseObservation)
            .toList()

        if (observations.isEmpty()) return null

        val target = StationRepo.stations.firstOrNull { it.id == targetStn }

        val selected: Observation
        val distanceKm: Double?
        val selectionNote: String

        if (target != null) {
            selected = observations.minWithOrNull(
                compareBy<Observation> {
                    haversineMeters(target.lat, target.lon, it.lat, it.lon)
                }.thenByDescending { it.tm.toLongOrNull() ?: 0L }
            ) ?: return null
            distanceKm = haversineMeters(target.lat, target.lon, selected.lat, selected.lon) / 1000.0
            selectionNote = "geo"
        } else {
            // StationRepo에 없는 번호가 들어온 경우에만 안전장치로 번호 차이를 사용합니다.
            selected = observations.minWithOrNull(
                compareBy<Observation> { abs(it.stn - targetStn) }
                    .thenByDescending { it.tm.toLongOrNull() ?: 0L }
            ) ?: return null
            distanceKm = null
            selectionNote = "stn-number-fallback"
        }

        val sourceName = StationRepo.stations.firstOrNull { it.id == selected.stn }?.name
            ?: "관측소 ${selected.stn}"

        return Result(
            code = selected.code,
            label = labelFor(selected.code),
            nnMin = selected.nnMin,
            targetStn = targetStn,
            sourceStn = selected.stn,
            sourceName = sourceName,
            distanceKm = distanceKm,
            dataTm = selected.tm,
            fromCache = false,
            networkOk = false,
            note = "n=${selected.n}; $selectionNote"
        )
    }

    /**
     * 컬럼(1-based):
     * 1:TM, 2:STN, 3:LON, 4:LAT, 5:S, 6:N, 7:WW1, 8:NN1, ...
     */
    private fun parseObservation(rawLine: String): Observation? {
        val line = rawLine.trim()
        if (line.isEmpty() || !line[0].isDigit()) return null

        val tokens = splitTokens(line)
        if (tokens.size < 8) return null

        val tm = tokens[0].takeIf { it.length == 12 && it.all(Char::isDigit) } ?: return null
        val stn = tokens[1].toIntOrNull() ?: return null
        val lon = tokens[2].toDoubleOrNull() ?: return null
        val lat = tokens[3].toDoubleOrNull() ?: return null
        val n = tokens[5].toIntOrNull() ?: return null
        if (n <= 0) return null

        var bestCode: Int? = null
        var bestMinutes = -1
        var index = 6

        repeat(n) {
            val code = tokens.getOrNull(index)?.toIntOrNull()
            val minutes = tokens.getOrNull(index + 1)?.toIntOrNull()
            if (code != null && minutes != null && minutes > bestMinutes) {
                bestCode = code
                bestMinutes = minutes
            }
            index += 2
        }

        return Observation(
            tm = tm,
            stn = stn,
            lon = lon,
            lat = lat,
            code = bestCode ?: return null,
            nnMin = bestMinutes.coerceAtLeast(0),
            n = n
        )
    }

    private fun splitTokens(line: String): List<String> =
        if (line.contains(',')) {
            line.split(',').map { it.trim() }
        } else {
            line.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        }

    private fun cacheFile(): File = File(appContext.filesDir, CACHE_NAME)

    private fun loadCache(): ByteArray? = try {
        val file = cacheFile()
        if (file.exists() && file.length() > 0L) file.readBytes() else null
    } catch (_: Throwable) {
        null
    }

    /** 다운로드가 정상 파싱된 경우에만 이전 캐시를 새 파일로 교체합니다. */
    private fun replaceCache(bytes: ByteArray) {
        try {
            val cache = cacheFile()
            val temp = File(cache.parentFile, "$CACHE_NAME.tmp")

            if (temp.exists()) temp.delete()
            temp.writeBytes(bytes)

            // 요청대로 새 다운로드가 정상일 때 종전 캐시를 먼저 제거합니다.
            if (cache.exists()) cache.delete()

            if (!temp.renameTo(cache)) {
                cache.writeBytes(bytes)
                temp.delete()
            }
        } catch (_: Throwable) {
            // 캐시 저장 실패가 현재 네트워크 결과 표시를 막지는 않게 합니다.
        }
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val radius = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) *
                cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        return 2 * radius * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun labelFor(code: Int): String = when (code) {
        0, 1, 2 -> "맑음"
        4 -> "연무"
        10 -> "박무"
        30 -> "안개"
        in 40..42 -> "비"
        in 50..59 -> "안개비"
        in 60..68 -> "비"
        in 71..76 -> "눈"
        else -> "기타"
    }

    companion object {
        private const val CACHE_NAME = "nph-aws2_min_ww1.cache"
    }
}
