package com.julia.sportapp

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

private const val GARMIN_WEEK_API = "https://personal.connectapi.garmin.com"

// Schritte je Tag für einen Zeitraum (nur Tage mit Werten sind enthalten)
data class WeekValues(
    val steps: Map<LocalDate, Int>
)

// Zugriffstoken aus dem Personal-API-Schlüssel holen
private fun weekToken(apiKey: String): String {
    val conn = URL("$GARMIN_WEEK_API/personalapitoken-service/personal-api/personal-api-key/token")
        .openConnection() as HttpURLConnection
    try {
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("Accept", "*/*")
        conn.setRequestProperty("Authorization", "ApiKey $apiKey")
        val code = conn.responseCode
        if (code == 401 || code == 403) {
            throw GarminException("Zugriff verweigert ($code). Schlüssel prüfen oder neu erstellen.")
        }
        if (code !in 200..299) throw GarminException("Garmin antwortet mit Fehler $code.")
        return conn.inputStream.bufferedReader().use { it.readText() }.trim()
    } finally {
        conn.disconnect()
    }
}

// Datenabruf bei Garmin mit Zugriffstoken
private fun weekGet(token: String, path: String): String {
    val conn = URL("$GARMIN_WEEK_API$path").openConnection() as HttpURLConnection
    try {
        conn.requestMethod = "GET"
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $token")
        val code = conn.responseCode
        if (code !in 200..299) throw GarminException("Garmin antwortet mit Fehler $code.")
        return conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
        conn.disconnect()
    }
}

// Schritte je Tag aus der Garmin-Tagesstatistik
private fun parseSteps(text: String): Map<LocalDate, Int> {
    val array = JSONArray(text.trim())
    val result = mutableMapOf<LocalDate, Int>()
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val day = runCatching { LocalDate.parse(item.optString("calendarDate")) }.getOrNull() ?: continue
        if (item.has("totalSteps") && !item.isNull("totalSteps")) {
            result[day] = item.optInt("totalSteps")
        }
    }
    return result
}

// Holt die Schritte für einen Zeitraum. Schlägt der Abruf fehl, bleibt die Liste leer.
fun fetchWeekValues(apiKey: String, start: LocalDate, end: LocalDate): WeekValues {
    val token = weekToken(apiKey)
    val from = start.toString()
    val to = end.toString()
    val steps = runCatching {
        parseSteps(weekGet(token, "/usersummary-service/stats/steps/daily/$from/$to"))
    }.getOrDefault(emptyMap())
    return WeekValues(steps = steps)
}
