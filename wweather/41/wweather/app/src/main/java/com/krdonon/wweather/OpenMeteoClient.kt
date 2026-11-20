package com.krdonon.wweather

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** The Open-Meteo "current" endpoint represents modeled current conditions, not KMA observations. */
internal class OpenMeteoClient(private val http: OkHttpClient) {
    data class CurrentWeather(
        val validTime: String,
        val temperature: Double?,
        val pressure: Double?,
        val windDirection: Double?,
        val windSpeed: Double?,
        val humidity: Double?,
        val rain: Double?,
        val snow: Double?,
        val precipitation: Double?,
        val weatherCode: Int?
    )

    suspend fun fetch(lat: Double, lon: Double): CurrentWeather = withContext(Dispatchers.IO) {
        require(lat in -90.0..90.0 && lon in -180.0..180.0)
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,relative_humidity_2m,surface_pressure," +
            "wind_speed_10m,wind_direction_10m,precipitation,rain,snowfall,weather_code" +
            "&wind_speed_unit=ms&timezone=Asia%2FSeoul&forecast_days=1"
        val req = Request.Builder().url(url).header("Accept", "application/json").build()
        http.newCall(req).execute().use { response ->
            if (!response.isSuccessful) error("Open-Meteo HTTP ${response.code}")
            val body = response.body.string()
            val current = JSONObject(body).getJSONObject("current")
            fun num(field: String): Double? = current.optDouble(field, Double.NaN)
                .takeIf { it.isFinite() }
            CurrentWeather(
                validTime = current.optString("time"),
                temperature = num("temperature_2m"),
                pressure = num("surface_pressure"),
                windDirection = num("wind_direction_10m"),
                windSpeed = num("wind_speed_10m"),
                humidity = num("relative_humidity_2m"),
                rain = num("rain"),
                snow = num("snowfall"),
                precipitation = num("precipitation"),
                weatherCode = num("weather_code")?.toInt()
            )
        }
    }

    companion object {
        fun description(code: Int?): String = when (code) {
            0 -> "맑음"
            1 -> "대체로 맑음"
            2 -> "구름 조금"
            3 -> "흐림"
            45, 48 -> "안개"
            51, 53, 55, 56, 57 -> "이슬비"
            61, 63, 65, 66, 67 -> "비"
            71, 73, 75, 77, 85, 86 -> "눈"
            80, 81, 82 -> "소나기"
            95, 96, 99 -> "뇌우"
            else -> "정보 없음"
        }
        fun hasSnow(code: Int?): Boolean = code in listOf(71, 73, 75, 77, 85, 86)
        fun hasRain(code: Int?): Boolean = code in listOf(51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82, 95, 96, 99)
    }
}
