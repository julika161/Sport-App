package com.julia.sportapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

// Eine Zeile in einem Bereich, z. B. "Ruhepuls: 52 bpm"
data class BodyRow(val label: String, val value: String)

// Ein Bereich der Gesundheit-Seite. error zeigt den Grund, wenn Garmin keine Daten liefert.
data class BodySection(val title: String, val rows: List<BodyRow>, val error: String? = null)

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

// Führt einen Abruf aus. Bei Fehler wird der Grund unter dem Schlüssel gemerkt, die Seite bleibt ganz.
private fun <T> check(errors: MutableMap<String, String>, key: String, block: () -> T?): T? =
    try {
        block()
    } catch (e: GarminException) {
        errors[key] = e.message ?: "Garmin-Fehler"
        null
    } catch (e: Exception) {
        errors[key] = "Antwort von Garmin nicht lesbar"
        null
    }

private fun JSONObject.intOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

// Erster vorhandener Zahlenwert aus einer Liste möglicher Schlüssel
private fun JSONObject.firstInt(vararg keys: String): Int? =
    keys.firstNotNullOfOrNull { intOrNull(it) }

// Erster nicht leerer Text aus einer Liste möglicher Schlüssel
private fun JSONObject.firstText(vararg keys: String): String? =
    keys.map { optString(it) }.firstOrNull { it.isNotBlank() }

private fun hrvStatusText(raw: String): String = when (raw.uppercase()) {
    "BALANCED" -> "ausgeglichen"
    "UNBALANCED" -> "unausgeglichen"
    "LOW" -> "niedrig"
    "POOR" -> "schwach"
    else -> raw.lowercase()
}

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

// Holt die Gesundheitsdaten von heute von Garmin. Jeder Bereich ist einzeln abgesichert.
fun fetchBody(apiKey: String): List<BodySection> {
    val token = obtainToken(apiKey)
    val today = LocalDate.now().toString()
    val errors = mutableMapOf<String, String>()

    val hrv = check(errors, "hrv") {
        JSONObject(garminGet(token, "/hrv-service/hrv/$today")).optJSONObject("hrvSummary")
    }
    val heart = check(errors, "heart") {
        JSONObject(garminGet(token, "/wellness-service/wellness/dailyHeartRate?date=$today"))
    }
    val spo2 = check(errors, "spo2") {
        JSONObject(garminGet(token, "/wellness-service/wellness/daily/spo2acclimation/$today"))
    }
    val fitness = check(errors, "fitness") {
        JSONObject(garminGet(token, "/fitnessage-service/fitnessage/v2/$today"))
    }
    val battery = check(errors, "battery") {
        JSONArray(garminGet(token, "/wellness-service/wellness/bodyBattery/reports/daily?startDate=$today&endDate=$today"))
    }

    val hrvStatus = hrv?.firstText("status")
    val batteryNow = battery?.let { latestBodyBattery(it) }
    val todayReport = battery?.let { if (it.length() > 0) it.optJSONObject(it.length() - 1) else null }

    return listOf(
        BodySection(
            "Herzfrequenzvariabilität (HRV)",
            listOfNotNull(
                hrv?.intOrNull("lastNightAvg")?.let { BodyRow("Letzte Nacht", "$it ms") },
                hrvStatus?.let { BodyRow("Status", hrvStatusText(it)) }
            ),
            errors["hrv"]
        ),
        BodySection(
            "Herzfrequenz",
            listOfNotNull(
                heart?.intOrNull("restingHeartRate")?.let { BodyRow("Ruhepuls", "$it bpm") },
                heart?.intOrNull("minHeartRate")?.let { BodyRow("Tiefster Wert", "$it bpm") },
                heart?.intOrNull("maxHeartRate")?.let { BodyRow("Höchster Wert", "$it bpm") }
            ),
            errors["heart"]
        ),
        BodySection(
            "Body Battery",
            listOfNotNull(
                batteryNow?.let { BodyRow("Aktuell", "$it von 100") },
                todayReport?.intOrNull("charged")?.let { BodyRow("Aufgeladen", "+$it") },
                todayReport?.intOrNull("drained")?.let { BodyRow("Verbraucht", "-$it") }
            ),
            errors["battery"]
        ),
        BodySection(
            "Atmung (SpO2)",
            listOfNotNull(
                spo2?.intOrNull("averageSpO2")?.let { BodyRow("Durchschnitt", "$it %") },
                spo2?.intOrNull("lowestSpO2")?.let { BodyRow("Tiefster Wert", "$it %") }
            ),
            errors["spo2"]
        ),
        BodySection(
            "Fitnessalter",
            listOfNotNull(
                fitness?.firstInt("fitnessAge")?.let { BodyRow("Fitnessalter", "$it Jahre") },
                fitness?.firstInt("chronologicalAge")?.let { BodyRow("Kalenderalter", "$it Jahre") }
            ),
            errors["fitness"]
        )
    )
}

// Gesundheit-Seite: die Werte von heute, nur Bereiche mit Garmin-Daten
@Composable
fun BodyContent(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val apiKey = remember { loadApiKey(context) }
    var sections by remember { mutableStateOf<List<BodySection>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    // Die Seite lädt die Daten jedes Mal, wenn man sie öffnet
    LaunchedEffect(Unit) {
        if (apiKey.isBlank()) {
            status = "Noch kein Garmin verbunden. Den Schlüssel trägst du unter Einstellungen ein."
        } else {
            loading = true
            val result = withContext(Dispatchers.IO) { runCatching { fetchBody(apiKey) } }
            loading = false
            result
                .onSuccess { sections = it; status = "" }
                .onFailure { status = "Fehler: ${it.message ?: "Unbekannt"}" }
        }
    }

    val shown = sections.filter { it.rows.isNotEmpty() }
    val missing = sections.filter { it.rows.isEmpty() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Seitentitel: 28 sp, fett (einheitlich auf allen Seiten)
        Text("Gesundheit", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Heute · ${LocalDate.now().format(dateFormatter)}", fontSize = 16.sp)

        if (loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (status.isNotEmpty()) {
            Text(status, fontSize = 14.sp)
        }

        shown.forEach { section ->
            BodySectionCard(section)
        }

        // Hinweis nur, wenn ein Bereich keine Daten liefert (mit dem Grund von Garmin)
        if (missing.isNotEmpty()) {
            Text(
                "Ohne Daten von Garmin:\n" + missing.joinToString("\n") {
                    "${it.title}: ${it.error ?: "keine Werte"}"
                },
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// Eine Karte pro Bereich: Titel und Werte
@Composable
private fun BodySectionCard(section: BodySection) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Abschnittstitel: 20 sp, halbfett
            Text(section.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            section.rows.forEach { row ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(row.label, fontSize = 16.sp)
                    Text(row.value, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
