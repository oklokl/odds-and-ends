package com.krdonon.wweather

import android.content.Context
import java.util.Locale

/** User-provided administrative-coordinate CSV, copied to assets for offline area selection. */
data class LocalRegion(val name: String, val latitude: Double, val longitude: Double)

object LocalRegionRepository {
    fun read(context: Context): List<LocalRegion> = try {
        context.assets.open("korea_regions.csv").bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.drop(1).mapNotNull { line ->
                val fields = line.trim().split(',')
                if (fields.size < 5) return@mapNotNull null
                val lon = fields[3].trim().toDoubleOrNull() ?: return@mapNotNull null
                val lat = fields[4].trim().toDoubleOrNull() ?: return@mapNotNull null
                if (lat !in 32.0..40.0 || lon !in 124.0..132.0) return@mapNotNull null
                val province = fields[1].trim().removeSuffix("시")
                val city = fields[2].trim()
                LocalRegion("$province $city".trim(), lat, lon)
            }.distinctBy { it.name }.sortedBy { it.name }.toList()
        }
    } catch (_: Exception) { emptyList() }

    fun nearest(regions: List<LocalRegion>, lat: Double, lon: Double): LocalRegion? =
        regions.minByOrNull { r ->
            val dLat = Math.toRadians(r.latitude - lat)
            val dLon = Math.toRadians(r.longitude - lon)
            val a = kotlin.math.sin(dLat / 2).let { it * it } +
                kotlin.math.cos(Math.toRadians(lat)) * kotlin.math.cos(Math.toRadians(r.latitude)) *
                kotlin.math.sin(dLon / 2).let { it * it }
            2.0 * kotlin.math.atan2(kotlin.math.sqrt(a.coerceIn(0.0, 1.0)), kotlin.math.sqrt((1 - a).coerceIn(0.0, 1.0)))
        }
}
