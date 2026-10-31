package com.julia.sportapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.julia.sportapp.data.ActivityDatabase
import com.julia.sportapp.data.ActivityType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

private const val COACH_PREFS = "running_coach_local"
private val runDays = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")

// Ziel und Verfügbarkeit bleiben lokal auf dem Gerät.
@Composable
fun RunningCoachContent(db: ActivityDatabase, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(COACH_PREFS, android.content.Context.MODE_PRIVATE) }
    val apiKey = remember { loadApiKey(context) }

    var distanceText by remember { mutableStateOf(prefs.getString("goal_distance_km", "") ?: "") }
    var timeText by remember { mutableStateOf(prefs.getString("goal_time_minutes", "") ?: "") }
    var targetDate by remember { mutableStateOf(prefs.getString("target_date", "") ?: "") }
    var selectedDays by remember {
        mutableStateOf(prefs.getStringSet("available_days", emptySet())?.toSet() ?: emptySet())
    }
    var savedMessage by remember { mutableStateOf("") }
    var goalSaved by remember {
        mutableStateOf(distanceText.isNotBlank() && timeText.isNotBlank())
    }
    var heartRateZones by remember { mutableStateOf<List<HeartRateZone>>(emptyList()) }
    var zonesLoading by remember { mutableStateOf(false) }
    var zonesMessage by remember { mutableStateOf("") }

    val recentRuns = remember(db) { db.getAll().filter { it.type == ActivityType.LAUFEN } }
    val distanceKm = distanceText.replace(',', '.').toDoubleOrNull()
    val targetMinutes = timeText.replace(',', '.').toDoubleOrNull()
    val validGoal = distanceKm != null && distanceKm > 0.0 && targetMinutes != null && targetMinutes > 0.0
    val pace = if (validGoal) targetMinutes!! / distanceKm!! else null
    val speed = if (validGoal) distanceKm!! / (targetMinutes!! / 60.0) else null

    LaunchedEffect(apiKey) {
        if (apiKey.isBlank()) {
            heartRateZones = emptyList()
            zonesMessage = "Garmin nicht verbunden. Verbinde Garmin unter Einstellungen, um deine persönlichen Laufzonen zu laden."
        } else {
            zonesLoading = true
            zonesMessage = ""
            val result = withContext(Dispatchers.IO) {
                runCatching { fetchRunningHeartRateZones(apiKey) }
            }
            zonesLoading = false
            result.fold(
                onSuccess = { zones ->
                    heartRateZones = zones
                    zonesMessage = if (zones.size == 5) "" else "Garmin hat keine fünf auslesbaren Laufzonen geliefert. Prüfe deine Laufzonen in Garmin Connect."
                },
                onFailure = {
                    heartRateZones = emptyList()
                    zonesMessage = "Garmin-Laufzonen konnten nicht geladen werden. Prüfe Verbindung und Garmin-Zugang unter Einstellungen."
                }
            )
        }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Laufcoach", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Ziel, Tempo und nächste Einheit.", fontSize = 16.sp)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Dein Laufziel", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = distanceText,
                    onValueChange = { value ->
                        distanceText = value.filter { it.isDigit() || it == '.' || it == ',' }
                        goalSaved = false
                    },
                    label = { Text("Zieldistanz (km)") },
                    placeholder = { Text("z. B. 10") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = timeText,
                    onValueChange = { value ->
                        timeText = value.filter { it.isDigit() || it == '.' || it == ',' }
                        goalSaved = false
                    },
                    label = { Text("Zielzeit (Minuten)") },
                    placeholder = { Text("z. B. 60") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                if (pace != null && speed != null) {
                    Text(
                        "Zieltempo ${formatCoachPace(pace)} min/km · ${String.format(Locale.GERMAN, "%.1f", speed)} km/h",
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text("Distanz und Zielzeit eintragen; Tempo und km/h werden berechnet.", fontSize = 14.sp)
                }
                OutlinedTextField(
                    value = targetDate,
                    onValueChange = {
                        targetDate = it.take(10)
                        goalSaved = false
                    },
                    label = { Text("Wunschdatum (optional, TT.MM.JJJJ)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Mögliche Lauftage", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    runDays.take(4).forEach { day ->
                        FilterChip(
                            selected = day in selectedDays,
                            onClick = {
                                selectedDays = if (day in selectedDays) selectedDays - day else selectedDays + day
                                goalSaved = false
                            },
                            label = { Text(day, fontSize = 13.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    runDays.drop(4).forEach { day ->
                        FilterChip(
                            selected = day in selectedDays,
                            onClick = {
                                selectedDays = if (day in selectedDays) selectedDays - day else selectedDays + day
                                goalSaved = false
                            },
                            label = { Text(day, fontSize = 13.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                if (selectedDays.isEmpty()) {
                    Text("Ohne Auswahl werden die Vorschläge nicht an feste Wochentage gebunden.", fontSize = 14.sp)
                }
            }
        }

        Button(
            onClick = {
                prefs.edit()
                    .putString("goal_distance_km", distanceText)
                    .putString("goal_time_minutes", timeText)
                    .putString("target_date", targetDate)
                    .putStringSet("available_days", selectedDays)
                    .remove("constraints")
                    .apply()
                goalSaved = true
                savedMessage = "Ziel gespeichert · Trainingsvorschläge direkt darunter."
            },
            enabled = validGoal,
            modifier = Modifier.fillMaxWidth().height(54.dp)
        ) {
            Text("Ziel speichern", fontSize = 16.sp)
        }
        if (savedMessage.isNotEmpty()) Text(savedMessage, fontSize = 14.sp)

        if (goalSaved && validGoal && pace != null) {
            RunningRecommendations(
                goalDistanceKm = distanceKm!!,
                goalPaceMinutesPerKm = pace,
                targetDateText = targetDate,
                recentRuns = recentRuns,
                availableDays = selectedDays,
                heartRateZones = heartRateZones,
                zonesLoading = zonesLoading
            )
            if (!zonesLoading && zonesMessage.isNotBlank()) {
                Text(zonesMessage, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun formatCoachPace(minutesPerKm: Double): String {
    val totalSeconds = (minutesPerKm * 60).roundToInt()
    val minutes = totalSeconds / 60
    val seconds = (totalSeconds % 60).toString().padStart(2, '0')
    return "$minutes:$seconds"
}
