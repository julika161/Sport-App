package com.julia.sportapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import kotlin.math.roundToInt

private const val GARMIN_API = "https://personal.connectapi.garmin.com"

private data class GarminHealthSnapshot(
    val sleepSeconds: Int?,
    val sleepScore: Int?,
    val sleepQuality: String?,
    val hrvAverage: Int?,
    val hrvStatus: String?,
    val restingHeartRate: Int?,
    val minHeartRate: Int?,
    val maxHeartRate: Int?,
    val bodyBattery: Int?,
    val batteryCharged: Int?,
    val batteryDrained: Int?,
    val stressAverage: Int?,
    val steps: Int?,
    val averageSpO2: Int?,
    val lowestSpO2: Int?,
    val fitnessAge: Int?,
    val chronologicalAge: Int?,
    val errors: Map<String, String>
)

// Holt tägliche Werte direkt von Garmin. Die Antwort wird nur im Arbeitsspeicher verarbeitet.
private fun fetchBody(apiKey: String): GarminHealthSnapshot {
    val token = obtainToken(apiKey)
    val today = LocalDate.now().toString()
    val errors = mutableMapOf<String, String>()

    val hrv = safeFetchObject(errors, "HRV") {
        JSONObject(garminGet(token, "/hrv-service/hrv/$today")).optJSONObject("hrvSummary")
    }
    val heart = safeFetchObject(errors, "Herzfrequenz") {
        JSONObject(garminGet(token, "/wellness-service/wellness/dailyHeartRate?date=$today"))
    }
    val sleep = safeFetchJson(errors, "Schlaf") {
        parseGarminJson(garminGet(token, "/sleep-service/sleep/dailySleep?date=$today"))
    }
    val battery = safeFetchArray(errors, "Body Battery") {
        JSONArray(garminGet(token, "/wellness-service/wellness/bodyBattery/reports/daily?startDate=$today&endDate=$today"))
    }
    val stress = safeFetchJson(errors, "Stress") {
        parseGarminJson(garminGet(token, "/usersummary-service/stats/stress/daily/$today/$today"))
    }
    val summaries = safeFetchJson(errors, "Schritte") {
        parseGarminJson(garminGet(token, "/usersummary-service/v2/dailysummaries?startDate=$today&endDate=$today"))
    }
    val spo2 = safeFetchObject(errors, "SpO₂") {
        JSONObject(garminGet(token, "/wellness-service/wellness/daily/spo2acclimation/$today"))
    }
    val fitness = safeFetchObject(errors, "Fitnessalter") {
        JSONObject(garminGet(token, "/fitnessage-service/fitnessage/v2/$today"))
    }

    val sleepObject = sleep?.let { findSleepObject(it) }
    val scoreBlock = sleepObject?.optJSONObject("sleepScores")?.optJSONObject("overall")
    val sleepScore = scoreBlock?.firstInt("value", "score")
        ?: sleepObject?.firstInt("overallSleepScore", "sleepScore")
    val sleepQuality = scoreBlock?.firstText("qualifierKey", "qualifier")
        ?: sleepObject?.firstText("sleepQuality", "qualifierKey")

    val hrvStatus = hrv?.firstText("status")?.let(::hrvStatusText)
    val batteryNow = battery?.let(::latestBodyBattery)
    val todayReport = battery?.let { if (it.length() > 0) it.optJSONObject(it.length() - 1) else null }

    return GarminHealthSnapshot(
        sleepSeconds = findIntRec(sleepObject, "sleepTimeSeconds", "sleepDurationSeconds", "totalSleepSeconds"),
        sleepScore = sleepScore,
        sleepQuality = sleepQuality?.let(::sleepQualityText),
        hrvAverage = hrv?.intOrNull("lastNightAvg"),
        hrvStatus = hrvStatus,
        restingHeartRate = heart?.firstInt("restingHeartRate"),
        minHeartRate = heart?.firstInt("minHeartRate"),
        maxHeartRate = heart?.firstInt("maxHeartRate"),
        bodyBattery = batteryNow,
        batteryCharged = todayReport?.firstInt("charged"),
        batteryDrained = todayReport?.firstInt("drained"),
        stressAverage = findIntRec(stress, "overallStressLevel", "averageStressLevel", "averageStress", "avgStress"),
        steps = findIntRec(summaries, "totalSteps", "steps"),
        averageSpO2 = spo2?.firstInt("averageSpO2"),
        lowestSpO2 = spo2?.firstInt("lowestSpO2"),
        fitnessAge = fitness?.firstInt("fitnessAge"),
        chronologicalAge = fitness?.firstInt("chronologicalAge"),
        errors = errors.toMap()
    )
}

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

private fun safeFetchObject(
    errors: MutableMap<String, String>,
    label: String,
    block: () -> JSONObject?
): JSONObject? = try {
    block()
} catch (e: Exception) {
    errors[label] = e.message ?: "Wert konnte nicht geladen werden"
    null
}

private fun safeFetchArray(
    errors: MutableMap<String, String>,
    label: String,
    block: () -> JSONArray
): JSONArray? = try {
    block()
} catch (e: Exception) {
    errors[label] = e.message ?: "Wert konnte nicht geladen werden"
    null
}

private fun safeFetchJson(
    errors: MutableMap<String, String>,
    label: String,
    block: () -> Any?
): Any? = try {
    block()
} catch (e: Exception) {
    errors[label] = e.message ?: "Wert konnte nicht geladen werden"
    null
}

private fun parseGarminJson(raw: String): Any? {
    val text = raw.trim()
    return when {
        text.startsWith("{") -> JSONObject(text)
        text.startsWith("[") -> JSONArray(text)
        else -> null
    }
}

private fun findSleepObject(value: Any): JSONObject? = when (value) {
    is JSONObject -> value.optJSONObject("dailySleepDTO") ?: value.optJSONObject("sleep") ?: value
    is JSONArray -> value.optJSONObject(0)
    else -> null
}

private fun findIntRec(value: Any?, vararg keys: String): Int? {
    return when (value) {
        is JSONObject -> {
            keys.forEach { key ->
                if (value.has(key) && !value.isNull(key)) {
                    val found = value.optDouble(key, Double.NaN)
                    if (found.isFinite()) return found.roundToInt()
                }
            }
            val names = value.keys()
            while (names.hasNext()) {
                val found = findIntRec(value.opt(names.next()), *keys)
                if (found != null) return found
            }
            null
        }
        is JSONArray -> {
            for (index in 0 until value.length()) {
                val found = findIntRec(value.opt(index), *keys)
                if (found != null) return found
            }
            null
        }
        else -> null
    }
}

private fun hrvStatusText(raw: String): String = when (raw.uppercase()) {
    "BALANCED" -> "Ausgeglichen"
    "UNBALANCED" -> "Unausgeglichen"
    "LOW" -> "Niedrig"
    "POOR" -> "Schwach"
    else -> raw.lowercase().replaceFirstChar { it.uppercase() }
}

private fun sleepQualityText(raw: String): String = when (raw.uppercase()) {
    "EXCELLENT" -> "Sehr gut"
    "GOOD" -> "Gut"
    "FAIR" -> "Mittel"
    "POOR" -> "Niedrig"
    else -> raw.lowercase().replaceFirstChar { it.uppercase() }
}

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

private fun JSONObject.intOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optDouble(key, Double.NaN).takeIf { it.isFinite() }?.roundToInt() else null

private fun JSONObject.firstInt(vararg keys: String): Int? =
    keys.firstNotNullOfOrNull { key -> intOrNull(key) }

private fun JSONObject.firstText(vararg keys: String): String? =
    keys.asSequence().map { optString(it) }.firstOrNull { it.isNotBlank() && it != "null" }

@Composable
fun BodyContent(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val apiKey = remember { loadApiKey(context) }
    var snapshot by remember { mutableStateOf<GarminHealthSnapshot?>(null) }
    var zones by remember { mutableStateOf<List<HeartRateZone>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var zoneStatus by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }

    LaunchedEffect(apiKey, refreshKey) {
        if (apiKey.isBlank()) {
            status = "Noch kein Garmin verbunden. Verbinde Garmin unter Einstellungen."
            return@LaunchedEffect
        }
        loading = true
        status = ""
        zoneStatus = ""
        val results = withContext(Dispatchers.IO) {
            val health = runCatching { fetchBody(apiKey) }
            val heartRateZones = runCatching { fetchRunningHeartRateZones(apiKey) }
            health to heartRateZones
        }
        loading = false
        results.first.fold(
            onSuccess = { snapshot = it },
            onFailure = { status = "Gesundheitswerte konnten nicht geladen werden. ${it.message ?: "Bitte Verbindung prüfen."}" }
        )
        results.second.fold(
            onSuccess = {
                zones = it
                zoneStatus = if (it.size == 5) "" else "Garmin hat keine fünf auslesbaren Laufzonen geliefert."
            },
            onFailure = {
                zones = emptyList()
                zoneStatus = "Laufzonen konnten nicht geladen werden. ${it.message ?: "Bitte Verbindung prüfen."}"
            }
        )
    }

    val data = snapshot
    val zoneSource = zones.firstOrNull()?.sport.orEmpty()

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Gesundheit", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("Garmin-Werte · ${LocalDate.now().format(dateFormatter)}", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { refreshKey++ }, enabled = !loading && apiKey.isNotBlank()) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Aktualisieren")
            }
        }

        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (status.isNotBlank()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(status, modifier = Modifier.padding(16.dp), fontSize = 15.sp)
            }
        }

        if (data != null) {
            Text("Erholung auf einen Blick", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                HealthMetricTile(
                    label = "Schlaf",
                    value = data.sleepSeconds?.let(::formatSleepDuration) ?: "—",
                    detail = data.sleepScore?.let { "Score $it/100${data.sleepQuality?.let { quality -> " · $quality" } ?: ""}" } ?: "Letzte Nacht",
                    icon = Icons.Filled.Bedtime,
                    modifier = Modifier.weight(1f)
                )
                HealthMetricTile(
                    label = "HRV",
                    value = data.hrvAverage?.let { "$it ms" } ?: "—",
                    detail = data.hrvStatus ?: "Letzte Nacht",
                    icon = Icons.Filled.FavoriteBorder,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                val batteryDetail = listOfNotNull(
                    data.batteryCharged?.let { "+$it geladen" },
                    data.batteryDrained?.let { "−$it verbraucht" }
                ).joinToString(" · ").ifBlank { "von 100" }
                HealthMetricTile(
                    label = "Body Battery",
                    value = data.bodyBattery?.let { "$it/100" } ?: "—",
                    detail = batteryDetail,
                    icon = Icons.Filled.BatteryChargingFull,
                    modifier = Modifier.weight(1f)
                )
                HealthMetricTile(
                    label = "Ruhepuls",
                    value = data.restingHeartRate?.let { "$it bpm" } ?: "—",
                    detail = "Tageswert",
                    icon = Icons.Filled.Favorite,
                    modifier = Modifier.weight(1f)
                )
            }

            Text("Deine 5 Herzfrequenzzonen beim Laufen", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (zoneSource.equals("DEFAULT", ignoreCase = true)) "Garmin-Standardprofil" else "Garmin-Laufprofil",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (zones.size == 5) {
                        zones.forEach { zone -> HeartRateZoneRow(zone) }
                    } else {
                        Text(zoneStatus.ifBlank { "Noch keine persönlichen Laufzonen verfügbar." }, fontSize = 15.sp)
                    }
                }
            }

            Text("Weitere Tageswerte", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    HealthValueRow("Stress · Garmin-Tageswert", data.stressAverage?.let { "$it/100" })
                    HealthValueRow("Schritte", data.steps?.toString())
                    HealthValueRow(
                        "Tagespuls min.–max.",
                        if (data.minHeartRate != null && data.maxHeartRate != null) "${data.minHeartRate}–${data.maxHeartRate} bpm" else null
                    )
                    HealthValueRow("SpO₂ · Durchschnitt", data.averageSpO2?.let { "$it %" })
                    HealthValueRow("SpO₂ · niedrigster Wert", data.lowestSpO2?.let { "$it %" })
                    HealthValueRow("Fitnessalter", data.fitnessAge?.let { "$it Jahre" })
                    HealthValueRow("Kalenderalter", data.chronologicalAge?.let { "$it Jahre" })
                    if (data.errors.isNotEmpty()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(
                            "Nicht verfügbar: ${data.errors.keys.joinToString(", ")}",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }

            Text(
                "Garmin-Messwerte sind Orientierung, keine medizinische Diagnose. Die App sendet sie nicht an andere Dienste.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HealthMetricTile(
    label: String,
    value: String,
    detail: String,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.heightIn(min = 126.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

@Composable
private fun HeartRateZoneRow(zone: HeartRateZone) {
    val colors = listOf(
        Color(0xFF4F8CC9),
        Color(0xFF369C82),
        Color(0xFFB28A26),
        Color(0xFFD77935),
        Color(0xFFC4515A)
    )
    val descriptions = listOf(
        "Sehr locker · Erholung",
        "Locker · Grundlagenausdauer",
        "Mittel · zügig",
        "Hart · Schwellenbereich",
        "Sehr hart · kurze Belastung"
    )
    val color = colors[(zone.number - 1).coerceIn(colors.indices)]
    val range = when {
        zone.minBpm != null && zone.maxBpm != null -> "${zone.minBpm}–${zone.maxBpm} bpm"
        zone.minBpm != null -> "ab ${zone.minBpm} bpm"
        zone.maxBpm != null -> "bis ${zone.maxBpm} bpm"
        else -> "Keine Grenze"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(color))
        Column(modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Zone ${zone.number}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(range, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Text(descriptions[(zone.number - 1).coerceIn(descriptions.indices)], fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HealthValueRow(label: String, value: String?) {
    if (value != null) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 14.sp)
            Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        HorizontalDivider()
    }
}

private fun formatSleepDuration(seconds: Int): String {
    val hours = seconds.coerceAtLeast(0) / 3600
    val minutes = (seconds.coerceAtLeast(0) % 3600) / 60
    return if (hours > 0) "$hours Std. $minutes Min." else "$minutes Min."
}

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

private fun hrvStatusText(raw: String): String = when (raw.uppercase()) {
    "BALANCED" -> "Ausgeglichen"
    "UNBALANCED" -> "Unausgeglichen"
    "LOW" -> "Niedrig"
    "POOR" -> "Schwach"
    else -> raw.lowercase().replaceFirstChar { it.uppercase() }
}

private fun sleepQualityText(raw: String): String = when (raw.uppercase()) {
    "EXCELLENT" -> "Sehr gut"
    "GOOD" -> "Gut"
    "FAIR" -> "Mittel"
    "POOR" -> "Niedrig"
    else -> raw.lowercase().replaceFirstChar { it.uppercase() }
}

private fun parseGarminJson(raw: String): Any? {
    val text = raw.trim()
    return when {
        text.startsWith("{") -> JSONObject(text)
        text.startsWith("[") -> JSONArray(text)
        else -> null
    }
}

private fun findSleepObject(value: Any): JSONObject? = when (value) {
    is JSONObject -> value.optJSONObject("dailySleepDTO") ?: value.optJSONObject("sleep") ?: value
    is JSONArray -> value.optJSONObject(0)
    else -> null
}

private fun findIntRec(value: Any?, vararg keys: String): Int? {
    return when (value) {
        is JSONObject -> {
            keys.forEach { key ->
                if (value.has(key) && !value.isNull(key)) {
                    val found = value.optDouble(key, Double.NaN)
                    if (found.isFinite()) return found.roundToInt()
                }
            }
            val names = value.keys()
            while (names.hasNext()) {
                val found = findIntRec(value.opt(names.next()), *keys)
                if (found != null) return found
            }
            null
        }
        is JSONArray -> {
            for (index in 0 until value.length()) {
                val found = findIntRec(value.opt(index), *keys)
                if (found != null) return found
            }
            null
        }
        else -> null
    }
}

private fun JSONObject.intOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optDouble(key, Double.NaN).takeIf { it.isFinite() }?.roundToInt() else null

private fun JSONObject.firstInt(vararg keys: String): Int? =
    keys.firstNotNullOfOrNull { key -> intOrNull(key) }

private fun JSONObject.firstText(vararg keys: String): String? =
    keys.asSequence().map { optString(it) }.firstOrNull { it.isNotBlank() && it != "null" }
