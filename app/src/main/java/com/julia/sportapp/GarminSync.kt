package com.julia.sportapp

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.julia.sportapp.data.Activity
import com.julia.sportapp.data.ActivityDatabase
import com.julia.sportapp.data.ActivityType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

private const val GARMIN_BASE = "https://personal.connectapi.garmin.com"
private const val PREFS_GARMIN = "sportapp_garmin"
private const val KEY_API = "api_key"

class GarminException(message: String) : Exception(message)

private data class GarminActivityBatch(
    val fetchedCount: Int,
    val activities: List<Activity>,
    val accessToken: String
)

// Schlüssel nur lokal auf dem Handy speichern (wird nicht gesichert).
fun loadApiKey(context: Context): String =
    context.getSharedPreferences(PREFS_GARMIN, Context.MODE_PRIVATE).getString(KEY_API, "") ?: ""

fun saveApiKey(context: Context, key: String) {
    context.getSharedPreferences(PREFS_GARMIN, Context.MODE_PRIVATE)
        .edit().putString(KEY_API, key).apply()
}

// Ein HTTP-Aufruf an Garmin.
private fun httpRequest(url: String, method: String, headers: Map<String, String>): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.requestMethod = method
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        val code = conn.responseCode
        when {
            code == 401 || code == 403 ->
                throw GarminException("Zugriff verweigert ($code). Schlüssel prüfen oder neu erstellen.")
            code !in 200..299 ->
                throw GarminException("Garmin antwortet mit Fehler $code.")
        }
        return conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
        conn.disconnect()
    }
}

// Garmin-Sportarten auf unsere Sportarten abbilden.
private fun mapType(typeKey: String): ActivityType? = when (typeKey) {
    "running", "treadmill_running", "trail_running" -> ActivityType.LAUFEN
    "cycling", "road_biking", "indoor_cycling", "gravel_cycling", "mountain_biking" -> ActivityType.RADFAHREN
    "strength_training" -> ActivityType.KRAFTTRAINING
    "yoga" -> ActivityType.YOGA
    "walking" -> ActivityType.WALKEN
    "hiit" -> ActivityType.HIIT
    else -> null
}

// Liest die Einheiten der letzten 60 Tage. Die FIT-Datei wird hier nicht gespeichert.
private fun fetchGarminActivities(apiKey: String): GarminActivityBatch {
    val token = httpRequest(
        "$GARMIN_BASE/personalapitoken-service/personal-api/personal-api-key/token",
        "POST",
        mapOf("Accept" to "*/*", "Authorization" to "ApiKey $apiKey")
    ).trim()

    val end = LocalDate.now()
    val start = end.minusDays(60)
    val json = httpRequest(
        "$GARMIN_BASE/activitylist-service/activities/search/activities?start=0&limit=100&startDate=$start&endDate=$end",
        "GET",
        mapOf("Accept" to "application/json", "Authorization" to "Bearer $token")
    )

    val array = JSONArray(json)
    val mapped = mutableListOf<Activity>()
    for (i in 0 until array.length()) {
        val item = array.getJSONObject(i)
        val typeKey = item.optJSONObject("activityType")?.optString("typeKey") ?: continue
        val type = mapType(typeKey) ?: continue
        val startLocal = LocalDateTime.parse(item.getString("startTimeLocal").replace(' ', 'T'))
        val minutes = (item.optDouble("duration", 0.0) / 60.0).roundToInt().coerceAtLeast(1)
        val meters = item.optDouble("distance", 0.0)
        mapped.add(
            Activity(
                type = type,
                date = startLocal.toLocalDate(),
                time = startLocal.toLocalTime().truncatedTo(ChronoUnit.MINUTES),
                durationMinutes = minutes,
                distanceKm = if (meters > 0) meters / 1000.0 else null,
                garminId = item.optLong("activityId").toString()
            )
        )
    }
    return GarminActivityBatch(array.length(), mapped, token)
}

// Übernimmt neue Einheiten. Doppelte werden übersprungen.
private fun importGarmin(db: ActivityDatabase, apiKey: String): String {
    val batch = fetchGarminActivities(apiKey)
    val known = db.getAll().toMutableList()
    var added = 0
    for (activity in batch.activities) {
        val isDuplicate = known.any { existing ->
            (activity.garminId != null && existing.garminId == activity.garminId) ||
                (existing.type == activity.type && existing.date == activity.date &&
                    abs(existing.time.toSecondOfDay() - activity.time.toSecondOfDay()) <= 30 * 60)
        }
        if (!isDuplicate) {
            db.insert(activity)
            known.add(activity)
            added++
        }
    }

    val details = syncRunDetails(db, batch.accessToken)
    val detailsMessage = buildString {
        append("${details.saved} Laufdetails ergänzt")
        if (details.failed > 0) append("; ${details.failed} FIT-Dateien waren nicht verfügbar")
        append(".")
    }
    return "Garmin: ${batch.fetchedCount} Einträge gelesen, ${batch.activities.size} passende Sportarten, " +
        "$added neu übernommen. $detailsMessage"
}

// Karte auf dem Startbildschirm: Schlüssel eintragen und synchronisieren.
@Composable
fun GarminSyncCard(db: ActivityDatabase, onImported: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apiKey by remember { mutableStateOf(loadApiKey(context)) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Garmin verbinden", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("Der Schlüssel wird nur auf diesem Handy gespeichert.", fontSize = 14.sp)

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it.trim() },
                label = { Text("Personal-API-Schlüssel") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrect = false
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    saveApiKey(context, apiKey)
                    busy = true
                    status = "Synchronisiere Aktivitäten und Laufdetails …"
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { importGarmin(db, apiKey) }
                        }
                        busy = false
                        result.fold(
                            onSuccess = { text ->
                                status = text
                                onImported()
                            },
                            onFailure = { e ->
                                status = "Fehler: ${e.message ?: "Unbekannt"}"
                            }
                        )
                    }
                },
                enabled = !busy && apiKey.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(if (busy) "Synchronisiere …" else "Jetzt synchronisieren", fontSize = 18.sp)
            }

            if (status.isNotEmpty()) {
                Text(status, fontSize = 15.sp)
            }
        }
    }
}
