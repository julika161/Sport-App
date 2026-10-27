package com.julia.sportapp

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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

private const val GARMIN_API = "https://personal.connectapi.garmin.com"

// Die Tageswerte für den Startbildschirm. Fehlende Werte sind null.
data class DailyStats(
    val hrv: Int?,
    val hrvStatus: String?,
    val restingHr: Int?
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

private fun hrvStatusText(raw: String): String = when (raw.uppercase()) {
    "BALANCED" -> "ausgeglichen"
    "UNBALANCED" -> "unausgeglichen"
    "LOW" -> "niedrig"
    "POOR" -> "schwach"
    else -> raw.lowercase()
}

// Holt HRV und Ruhepuls von heute von Garmin
fun fetchDailyStats(apiKey: String): DailyStats {
    val token = obtainToken(apiKey)
    val today = LocalDate.now().toString()

    val hrvSummary = attempt {
        JSONObject(garminGet(token, "/hrv-service/hrv/$today")).optJSONObject("hrvSummary")
    }
    val restingHr = attempt {
        JSONObject(garminGet(token, "/wellness-service/wellness/dailyHeartRate?date=$today"))
            .intOrNull("restingHeartRate")
    }

    val stats = DailyStats(
        hrv = hrvSummary?.intOrNull("lastNightAvg"),
        hrvStatus = hrvSummary?.optString("status")?.takeIf { it.isNotBlank() }?.let { hrvStatusText(it) },
        restingHr = restingHr
    )
    if (stats.hrv == null && stats.restingHr == null) {
        throw GarminException("Garmin hat heute keine Werte geliefert. Ist die Uhr synchronisiert?")
    }
    return stats
}

// Karte mit HRV und Ruhepuls, zeigt Ladezustand und Fehler
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
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                ValueTile(
                    symbol = "💓",
                    label = "HRV",
                    value = s.hrv?.let { "$it ms" } ?: "–",
                    detail = s.hrvStatus ?: "",
                    modifier = Modifier.weight(1f)
                )
                ValueTile(
                    symbol = "❤️",
                    label = "Ruhepuls",
                    value = s.restingHr?.let { "$it bpm" } ?: "–",
                    detail = "",
                    modifier = Modifier.weight(1f)
                )
            }
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
