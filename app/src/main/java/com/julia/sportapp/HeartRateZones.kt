package com.julia.sportapp

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

private const val ZONES_API = "https://personal.connectapi.garmin.com"

/** Laufzone aus dem persönlichen Garmin-Profil. Grenzen können fehlen, wenn Garmin sie nicht liefert. */
data class HeartRateZone(
    val number: Int,
    val minBpm: Int?,
    val maxBpm: Int?
)

private data class ZoneGroup(
    val sport: String,
    val values: JSONArray,
    val maxHeartRate: Double?,
    val restingHeartRate: Double?,
    val trainingMethod: String?
)

// Liest nur Julias persönliche Laufzonen; Schlüssel und Antworten bleiben im Arbeitsspeicher des Geräts.
fun fetchRunningHeartRateZones(apiKey: String): List<HeartRateZone> {
    val token = zoneRequest(
        "$ZONES_API/personalapitoken-service/personal-api/personal-api-key/token",
        "POST",
        "ApiKey $apiKey",
        "*/*"
    ).trim()
    val json = zoneRequest(
        "$ZONES_API/biometric-service/heartRateZones/v2",
        "GET",
        "Bearer $token",
        "application/json"
    )
    val root: Any = if (json.trimStart().startsWith("[")) JSONArray(json) else JSONObject(json)
    val groups = mutableListOf<ZoneGroup>()
    collectZoneGroups(root, "", null, null, null, groups)

    val runningGroups = groups.filter { it.sport.contains("RUN", ignoreCase = true) }
    val selected = when {
        runningGroups.isNotEmpty() -> runningGroups.first()
        groups.count { it.sport.isBlank() } == 1 -> groups.first { it.sport.isBlank() }
        else -> null
    } ?: return emptyList()

    val zones = parseZones(selected)
    return zones.filter { it.number in 1..5 }.distinctBy { it.number }.sortedBy { it.number }
        .takeIf { it.size == 5 } ?: emptyList()
}

private fun zoneRequest(url: String, method: String, authorization: String, accept: String): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.requestMethod = method
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.setRequestProperty("Accept", accept)
        connection.setRequestProperty("Authorization", authorization)
        val code = connection.responseCode
        if (code !in 200..299) throw GarminException("Garmin-Zonen konnten nicht geladen werden ($code).")
        return connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection.disconnect()
    }
}

private fun collectZoneGroups(
    value: Any?,
    inheritedSport: String,
    inheritedMax: Double?,
    inheritedResting: Double?,
    inheritedMethod: String?,
    result: MutableList<ZoneGroup>
) {
    when (value) {
        is JSONObject -> {
            val sport = sportText(value) ?: inheritedSport
            val maxHeartRate = value.firstNumber("maxHeartRate", "maximumHeartRate", "maxHr") ?: inheritedMax
            val restingHeartRate = value.firstNumber("restingHeartRate", "restingHr") ?: inheritedResting
            val method = value.firstText("trainingMethod", "zoneCalculationMethod", "heartRateZoneType") ?: inheritedMethod

            listOf("zones", "heartRateZones").forEach { key ->
                val array = value.optJSONArray(key)
                if (array != null && looksLikeZoneList(array)) {
                    result.add(ZoneGroup(sport, array, maxHeartRate, restingHeartRate, method))
                }
            }
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val child = value.opt(key)
                if (child is JSONObject || child is JSONArray) {
                    collectZoneGroups(child, sport, maxHeartRate, restingHeartRate, method, result)
                }
            }
        }
        is JSONArray -> {
            if (looksLikeZoneList(value)) {
                result.add(ZoneGroup(inheritedSport, value, inheritedMax, inheritedResting, inheritedMethod))
            }
            for (index in 0 until value.length()) {
                val child = value.opt(index)
                if (child is JSONObject || child is JSONArray) {
                    collectZoneGroups(child, inheritedSport, inheritedMax, inheritedResting, inheritedMethod, result)
                }
            }
        }
    }
}

private fun looksLikeZoneList(array: JSONArray): Boolean {
    if (array.length() < 5) return false
    var zoneLikeCount = 0
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        if (item.has("zoneNumber") || item.has("zoneIndex") || item.has("zoneLowBoundary") ||
            item.has("zoneHighBoundary") || item.has("lowBoundary") || item.has("highBoundary")
        ) zoneLikeCount++
    }
    return zoneLikeCount >= 3
}

private fun parseZones(group: ZoneGroup): List<HeartRateZone> = buildList {
    for (index in 0 until group.values.length()) {
        val item = group.values.optJSONObject(index) ?: continue
        val number = item.firstInt("zoneNumber", "zoneIndex", "number") ?: index + 1
        val low = item.firstNumberWithKey(
            "zoneLowBoundary", "lowBoundary", "lowerBoundary", "minHeartRate", "minimumHeartRate", "lowBpm", "minBpm", "low"
        )
        val high = item.firstNumberWithKey(
            "zoneHighBoundary", "highBoundary", "upperBoundary", "maxHeartRate", "maximumHeartRate", "highBpm", "maxBpm", "high"
        )
        val lowBpm = low?.let { boundaryToBpm(it.first, it.second, group) }
        val highBpm = high?.let { boundaryToBpm(it.first, it.second, group) }
        if ((lowBpm != null || highBpm != null) &&
            (lowBpm == null || lowBpm in 35..240) && (highBpm == null || highBpm in 35..240) &&
            (lowBpm == null || highBpm == null || highBpm >= lowBpm)
        ) {
            add(HeartRateZone(number, lowBpm, highBpm))
        }
    }
}

private fun boundaryToBpm(value: Double, key: String, group: ZoneGroup): Int? {
    if (!value.isFinite() || value < 0.0) return null
    val method = group.trainingMethod.orEmpty().uppercase()
    val usesPercent = method.contains("PERCENT") || method.contains("HEART_RATE_MAX") || method == "HR_MAX" ||
        method.contains("RESERVE") || method == "HRR"
    val isBpmField = key.contains("bpm", ignoreCase = true) || key.contains("heartRate", ignoreCase = true)
    val percent = when {
        value <= 1.0 && !isBpmField -> value * 100.0
        usesPercent && !isBpmField && value <= 100.0 -> value
        else -> null
    } ?: return value.roundToInt().takeIf { it in 35..240 }

    val max = group.maxHeartRate ?: return null
    val bpm = if (method.contains("RESERVE") || method == "HRR") {
        val resting = group.restingHeartRate ?: return null
        resting + (max - resting) * percent / 100.0
    } else {
        max * percent / 100.0
    }
    return bpm.roundToInt().takeIf { it in 35..240 }
}

private fun sportText(value: JSONObject): String? {
    listOf("sport", "sportType", "sportName", "activityType").forEach { key ->
        val field = value.opt(key)
        when (field) {
            is String -> if (field.isNotBlank()) return field
            is JSONObject -> field.firstText("sportType", "sportName", "typeKey", "name")?.let { return it }
        }
    }
    return null
}

private fun JSONObject.firstText(vararg keys: String): String? =
    keys.asSequence().map { optString(it) }.firstOrNull { it.isNotBlank() && it != "null" }

private fun JSONObject.firstInt(vararg keys: String): Int? =
    keys.firstNotNullOfOrNull { key ->
        if (!has(key) || isNull(key)) null else optDouble(key, Double.NaN).takeIf { it.isFinite() }?.roundToInt()
    }

private fun JSONObject.firstNumber(vararg keys: String): Double? =
    keys.firstNotNullOfOrNull { key ->
        if (!has(key) || isNull(key)) null else optDouble(key, Double.NaN).takeIf { it.isFinite() }
    }

private fun JSONObject.firstNumberWithKey(vararg keys: String): Pair<Double, String>? =
    keys.firstNotNullOfOrNull { key ->
        if (!has(key) || isNull(key)) null else optDouble(key, Double.NaN).takeIf { it.isFinite() }?.let { it to key }
    }
