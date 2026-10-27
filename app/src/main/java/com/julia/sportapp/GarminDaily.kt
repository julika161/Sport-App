package com.julia.sportapp

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

private const val GARMIN_API = "https://personal.connectapi.garmin.com"

// Die Tageswerte für den Startbildschirm. Fehlende Werte sind null.
data class DailyStats(
    val sleepMinutes: Int?,
    val sleepScore: Int?,
    val bodyBattery: Int?,
    val hrv: Int?,
    val hrvStatus: String?,
    val restingHr: Int?,
    val vo2max: Double?,
    val readiness: Int?,
    val readinessLevel: String?
)

// Zugriffstoken aus dem Personal-API-Schlüssel holen
private fun obtainToken(apiKey: String): String {
    val conn = URL("$GARMIN_API/personalapitoken-service/personal-api/personal-api-key/token")
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

// Ein Datenabruf bei Garmin mit Zugriffstoken
private fun garminGet(token: String, path: String): String {
    val conn = URL("$GARMIN_API$path").openConnection() as HttpURLConnection
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

// Ein einzelner Wert darf fehlen, ohne dass die ganze Übersicht ausfällt
private fun <T> attempt(block: () -> T?): T? = try {
    block()
} catch (e: Exception) {
    null
}

private fun JSONObject.intOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

private fun JSONObject.doubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key) else null

// Die letzte Body-Battery-Stufe des Tages
private fun latestBodyBattery(reports: JSONArray): Int? {
    for (i in reports.length() - 1 downTo 0) {
        val values = reports.optJSONObject(i)?.optJSONArray("bodyBatteryValuesArray") ?: continue
        for (j in values.length() - 1 downTo 0) {
            val point = values.optJSONArray(j) ?: continue
            if (point.length() >= 3 && !point.isNull(2)) return point.optInt(2)
        }
    }
    return null
}

// Der neueste VO2max-Wert der letzten 30 Tage
private fun latestVo2max(days: JSONArray): Double? {
    for (i in days.length() - 1 downTo 0) {
        val generic = days.optJSONObject(i)?.optJSONObject("generic") ?: continue
        val value = generic.doubleOrNull("vo2MaxPreciseValue") ?: generic.doubleOrNull("vo2MaxValue")
        if (value != null && !value.isNaN()) return value
    }
    return null
}

// Tagesform: Garmin liefert je nach Antwort eine Liste oder ein einzelnes Objekt
private fun readinessObject(text: String): JSONObject? {
    val trimmed = text.trim()
    return if (trimmed.startsWith("[")) {
        val list = JSONArray(trimmed)
        list.optJSONObject(list.length() - 1)
    } else {
        JSONObject(trimmed)
    }
}

private fun hrvStatusText(raw: String): String = when (raw.uppercase()) {
    "BALANCED" -> "ausgeglichen"
    "UNBALANCED" -> "unausgeglichen"
    "LOW" -> "niedrig"
    "POOR" -> "schwach"
    else -> raw.lowercase()
}

private fun readinessText(raw: String): String = when (raw.uppercase()) {
    "POOR", "LOW" -> "niedrig"
    "MODERATE" -> "moderat"
    "HIGH" -> "hoch"
    "PRIME" -> "Top-Form"
    else -> raw.lowercase()
}

// Holt die Tageswerte von heute von Garmin
fun fetchDailyStats(apiKey: String): DailyStats {
    val token = obtainToken(apiKey)
    val today = LocalDate.now().toString()
    val monthAgo = LocalDate.now().minusDays(30).toString()

    val sleep = attempt {
        JSONObject(garminGet(token, "/sleep-service/sleep/dailySleep?date=$today"))
            .optJSONObject("dailySleepDTO")
    }
    val battery = attempt {
        latestBodyBattery(
            JSONArray(
                garminGet(
                    token,
                    "/wellness-service/wellness/bodyBattery/reports/daily?startDate=$today&endDate=$today"
                )
            )
        )
    }
    val hrvSummary = attempt {
        JSONObject(garminGet(token, "/hrv-service/hrv/$today")).optJSONObject("hrvSummary")
    }
    val restingHr = attempt {
        JSONObject(garminGet(token, "/wellness-service/wellness/dailyHeartRate?date=$today"))
            .intOrNull("restingHeartRate")
    }
    val vo2max = attempt {
        latestVo2max(JSONArray(garminGet(token, "/metrics-service/metrics/maxmet/daily/$monthAgo/$today")))
    }
    val readiness = attempt {
        readinessObject(garminGet(token, "/metrics-service/metrics/trainingreadiness/$today"))
    }

    val stats = DailyStats(
        sleepMinutes = sleep?.intOrNull("sleepTimeSeconds")?.let { it / 60 },
        sleepScore = sleep?.optJSONObject("sleepScores")?.optJSONObject("overall")?.intOrNull("value"),
        bodyBattery = battery,
        hrv = hrvSummary?.intOrNull("lastNightAvg"),
        hrvStatus = hrvSummary?.optString("status")?.takeIf { it.isNotBlank() }?.let { hrvStatusText(it) },
        restingHr = restingHr,
        vo2max = vo2max,
        readiness = readiness?.intOrNull("score"),
        readinessLevel = readiness?.optString("level")?.takeIf { it.isNotBlank() }?.let { readinessText(it) }
    )

    val nothingFound = listOf(
        stats.sleepMinutes, stats.bodyBattery, stats.hrv, stats.restingHr, stats.vo2max, stats.readiness
    ).all { it == null }
    if (nothingFound) {
        throw GarminException("Garmin hat heute keine Werte geliefert. Ist die Uhr synchronisiert?")
    }
    return stats
}

// Karte mit den Tageswerten, zeigt Ladezustand und Fehler
@Composable
fun DailyOverview() {
    val context = LocalContext.current
    val apiKey = remember { loadApiKey(context) }
    var stats by remember { mutableStateOf<DailyStats?>(null) }
    var status by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var refreshCount by remember { mutableStateOf(0) }

    LaunchedEffect(refreshCount) {
        if (apiKey.isBlank()) {
            status = "Noch kein Garmin verbunden. Den Schlüssel trägst du unter Einstellungen ein."
        } else {
            loading = true
            val result = withContext(Dispatchers.IO) { runCatching { fetchDailyStats(apiKey) } }
            loading = false
            result
                .onSuccess { stats = it; status = "" }
                .onFailure { status = "Fehler: ${it.message ?: "Unbekannt"}" }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Heute · ${LocalDate.now().format(dateFormatter)}",
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold
        )

        OutlinedButton(
            onClick = { refreshCount++ },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text(if (loading) "Lade Garmin-Daten ..." else "Aktualisieren", fontSize = 18.sp)
        }

        if (status.isNotEmpty()) {
            Text(status, fontSize = 16.sp)
        }

        stats?.let { s ->
            TrainingHint(s)

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                ValueTile(
                    symbol = "😴",
                    label = "Schlaf",
                    value = s.sleepMinutes?.let { "%d h %02d min".format(it / 60, it % 60) } ?: "–",
                    detail = s.sleepScore?.let { "Schlafwert $it" } ?: "",
                    modifier = Modifier.weight(1f)
                )
                ValueTile(
                    symbol = "⚡",
                    label = "Tagesform",
                    value = s.readiness?.toString() ?: "–",
                    detail = s.readinessLevel ?: "",
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                ValueTile(
                    symbol = "🔋",
                    label = "Body Battery",
                    value = s.bodyBattery?.toString() ?: "–",
                    detail = if (s.bodyBattery != null) "von 100" else "",
                    modifier = Modifier.weight(1f)
                )
                ValueTile(
                    symbol = "💓",
                    label = "HRV",
                    value = s.hrv?.let { "$it ms" } ?: "–",
                    detail = s.hrvStatus ?: "",
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                ValueTile(
                    symbol = "❤️",
                    label = "Ruhepuls",
                    value = s.restingHr?.let { "$it bpm" } ?: "–",
                    detail = "",
                    modifier = Modifier.weight(1f)
                )
                ValueTile(
                    symbol = "🫁",
                    label = "VO2max",
                    value = s.vo2max?.let { "%.1f".format(it) } ?: "–",
                    detail = "ml/kg/min",
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// Einfache Faustregel für die Trainingsplanung des Tages
@Composable
fun TrainingHint(s: DailyStats) {
    val score = s.readiness ?: s.bodyBattery ?: return
    val text = when {
        score >= 70 -> "Gute Voraussetzungen: Heute darf es eine anspruchsvollere Einheit sein."
        score >= 40 -> "Solide Basis: Eine moderate Einheit passt gut. Hör auf dein Gefühl."
        else -> "Dein Körper braucht Erholung: Heute lieber locker, z. B. Yoga oder Spazieren."
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Einschätzung für heute", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(text, fontSize = 18.sp)
            Text("Einfache Faustregel aus Tagesform bzw. Body Battery, keine medizinische Aussage.", fontSize = 13.sp)
        }
    }
}

// Eine Kachel mit einem Wert
@Composable
fun ValueTile(symbol: String, label: String, value: String, detail: String, modifier: Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("$symbol $label", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            if (detail.isNotEmpty()) {
                Text(detail, fontSize = 14.sp)
            }
        }
    }
}
