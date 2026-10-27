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

// Ein Bereich der Körper-Seite, z. B. "Schlaf"
data class BodySection(val title: String, val rows: List<BodyRow>)

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

// Ein einzelner Bereich darf fehlen, ohne dass die ganze Seite ausfällt
private fun <T> attempt(block: () -> T?): T? = try {
    block()
} catch (e: Exception) {
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

private fun phaseText(raw: String): String = when (raw.uppercase()) {
    "MENSTRUATION", "MENSTRUAL" -> "Menstruation"
    "FOLLICULAR" -> "Follikelphase"
    "OVULATION" -> "Eisprung"
    "LUTEAL" -> "Lutealphase"
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

// Herzfrequenzzonen aus der Garmin-Antwort lesen
private fun parseZones(text: String): List<BodyRow> {
    val trimmed = text.trim()
    val array = if (trimmed.startsWith("[")) {
        JSONArray(trimmed)
    } else {
        JSONObject(trimmed).optJSONArray("heartRateZones") ?: JSONArray()
    }
    val rows = mutableListOf<BodyRow>()
    for (i in 0 until array.length()) {
        val zone = array.optJSONObject(i) ?: continue
        val number = zone.firstInt("zoneNumber", "zone") ?: continue
        val min = zone.firstInt("minHeartRate", "zoneMinBpm") ?: continue
        val max = zone.firstInt("maxHeartRate", "zoneMaxBpm")
        rows.add(BodyRow("Zone $number", if (max != null) "$min bis $max bpm" else "ab $min bpm"))
    }
    return rows
}

// Tageszusammenfassung: Liste oder Objekt mit Liste, es wird der erste Tag genommen
private fun parseSummary(text: String): JSONObject? {
    val trimmed = text.trim()
    return if (trimmed.startsWith("[")) {
        JSONArray(trimmed).optJSONObject(0)
    } else {
        val root = JSONObject(trimmed)
        root.optJSONArray("dailySummaries")?.optJSONObject(0) ?: root
    }
}

// Holt alle Körperdaten von heute von Garmin. Fehlende Bereiche bleiben leer.
fun fetchBody(apiKey: String): List<BodySection> {
    val token = obtainToken(apiKey)
    val today = LocalDate.now().toString()

    val hrv = attempt { JSONObject(garminGet(token, "/hrv-service/hrv/$today")).optJSONObject("hrvSummary") }
    val heart = attempt { JSONObject(garminGet(token, "/wellness-service/wellness/dailyHeartRate?date=$today")) }
    val zones = attempt { parseZones(garminGet(token, "/biometric-service/heartRateZones/v2")) }
    val stress = attempt {
        val arr = JSONArray(garminGet(token, "/usersummary-service/stats/stress/daily/$today/$today"))
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .firstOrNull { it.optString("calendarDate") == today } ?: arr.optJSONObject(0)
    }
    val spo2 = attempt { JSONObject(garminGet(token, "/wellness-service/wellness/daily/spo2acclimation/$today")) }
    val fitness = attempt { JSONObject(garminGet(token, "/fitnessage-service/fitnessage/v2/$today")) }
    val sleep = attempt { JSONObject(garminGet(token, "/sleep-service/sleep/dailySleep?date=$today")) }
    val battery = attempt {
        JSONArray(garminGet(token, "/wellness-service/wellness/bodyBattery/reports/daily?startDate=$today&endDate=$today"))
    }
    val summary = attempt {
        parseSummary(garminGet(token, "/usersummary-service/v2/dailysummaries?startDate=$today&endDate=$today"))
    }
    val cycle = attempt {
        JSONObject(garminGet(token, "/periodichealth-service/menstrualcycle/dayview/$today?todayDate=$today"))
    }

    val hrvStatus = hrv?.firstText("status")
    val sleepDto = sleep?.optJSONObject("dailySleepDTO")
    val sleepMinutes = sleepDto?.intOrNull("sleepTimeSeconds")?.let { it / 60 }
    val sleepScore = sleepDto?.optJSONObject("sleepScores")?.optJSONObject("overall")?.intOrNull("value")
    val batteryNow = battery?.let { latestBodyBattery(it) }
    val todayReport = battery?.let { it.optJSONObject(it.length() - 1) }
    val intensity = summary?.let { s ->
        val moderate = s.intOrNull("moderateIntensityMinutes")
        val vigorous = s.intOrNull("vigorousIntensityMinutes")
        if (moderate == null && vigorous == null) null else (moderate ?: 0) + (vigorous ?: 0)
    }
    val phase = cycle?.firstText("currentPhaseType", "phaseType", "currentPhaseName")

    val sections = listOf(
        BodySection(
            "Herzfrequenzvariabilität (HRV)",
            listOfNotNull(
                hrv?.intOrNull("lastNightAvg")?.let { BodyRow("Letzte Nacht", "$it ms") },
                hrvStatus?.let { BodyRow("Status", hrvStatusText(it)) }
            )
        ),
        BodySection(
            "Ruhepuls",
            listOfNotNull(heart?.intOrNull("restingHeartRate")?.let { BodyRow("Ruhepuls", "$it bpm") })
        ),
        BodySection(
            "Herzfrequenz",
            listOfNotNull(
                heart?.intOrNull("minHeartRate")?.let { BodyRow("Tiefster Wert", "$it bpm") },
                heart?.intOrNull("maxHeartRate")?.let { BodyRow("Höchster Wert", "$it bpm") }
            )
        ),
        BodySection("Herzfrequenzzonen", zones ?: emptyList()),
        BodySection(
            "Stresslevel",
            listOfNotNull(stress?.intOrNull("overallStressLevel")?.let { BodyRow("Tagesdurchschnitt (0 bis 100)", "$it") })
        ),
        BodySection(
            "Atmung (Sauerstoff, SpO2)",
            listOfNotNull(
                spo2?.intOrNull("averageSpO2")?.let { BodyRow("Durchschnitt", "$it %") },
                spo2?.intOrNull("lowestSpO2")?.let { BodyRow("Tiefster Wert", "$it %") }
            )
        ),
        BodySection(
            "Fitnessalter",
            listOfNotNull(
                fitness?.firstInt("fitnessAge")?.let { BodyRow("Fitnessalter", "$it Jahre") },
                fitness?.firstInt("chronologicalAge")?.let { BodyRow("Kalenderalter", "$it Jahre") }
            )
        ),
        BodySection(
            "Schlaf",
            listOfNotNull(
                sleepMinutes?.let { BodyRow("Schlafdauer", "%d h %02d min".format(it / 60, it % 60)) },
                sleepScore?.let { BodyRow("Schlafwert", "$it") }
            )
        ),
        BodySection(
            "Body Battery",
            listOfNotNull(
                batteryNow?.let { BodyRow("Aktuell", "$it von 100") },
                todayReport?.intOrNull("charged")?.let { BodyRow("Aufgeladen", "+$it") },
                todayReport?.intOrNull("drained")?.let { BodyRow("Verbraucht", "-$it") }
            )
        ),
        BodySection(
            "Tageszusammenfassung",
            listOfNotNull(
                summary?.intOrNull("totalSteps")?.let { BodyRow("Schritte", "$it") },
                summary?.intOrNull("totalKilocalories")?.let { BodyRow("Kalorien", "$it kcal") },
                intensity?.let { BodyRow("Intensitätsminuten", "$it Min.") }
            )
        ),
        BodySection(
            "Zyklus",
            listOfNotNull(phase?.let { BodyRow("Phase", phaseText(it)) })
        )
    )

    if (sections.all { it.rows.isEmpty() }) {
        throw GarminException("Garmin hat heute keine Körperdaten geliefert. Ist die Uhr synchronisiert?")
    }
    return sections
}

// Körper-Seite: alle Körperdaten von heute, ein Bereich pro Karte
@Composable
fun BodyContent(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val apiKey = remember { loadApiKey(context) }
    var sections by remember { mutableStateOf<List<BodySection>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var refreshCount by remember { mutableStateOf(0) }

    LaunchedEffect(refreshCount) {
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Körper", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Heute · ${LocalDate.now().format(dateFormatter)}", fontSize = 16.sp)

        OutlinedButton(
            onClick = { refreshCount++ },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text(if (loading) "Lade Garmin-Daten ..." else "Aktualisieren", fontSize = 18.sp)
        }

        if (loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (status.isNotEmpty()) {
            Text(status, fontSize = 16.sp)
        }

        sections.forEach { section ->
            BodySectionCard(section)
        }
    }
}

// Eine Karte pro Bereich: Titel, Zeilen oder ein Hinweis, wenn keine Daten da sind
@Composable
private fun BodySectionCard(section: BodySection) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(section.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            if (section.rows.isEmpty()) {
                Text("Keine Daten von Garmin", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            section.rows.forEach { row ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(row.label, fontSize = 16.sp)
                    Text(row.value, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
