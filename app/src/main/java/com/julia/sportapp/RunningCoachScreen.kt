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
import java.util.Locale
import kotlin.math.roundToInt

private const val COACH_PREFS = "running_coach_local"
private val runDays = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")

// Ziel und Verfügbarkeit bleiben lokal auf dem Gerät.
@Composable
fun RunningCoachContent(db: ActivityDatabase, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(COACH_PREFS, android.content.Context.MODE_PRIVATE) }

    var distanceText by remember { mutableStateOf(prefs.getString("goal_distance_km", "") ?: "") }
    var timeText by remember { mutableStateOf(prefs.getString("goal_time_minutes", "") ?: "") }
    var targetDate by remember { mutableStateOf(prefs.getString("target_date", "") ?: "") }
    var constraints by remember { mutableStateOf(prefs.getString("constraints", "") ?: "") }
    var selectedDays by remember {
        mutableStateOf(prefs.getStringSet("available_days", emptySet())?.toSet() ?: emptySet())
    }
    var savedMessage by remember { mutableStateOf("") }
    var goalSaved by remember {
        mutableStateOf(distanceText.isNotBlank() && timeText.isNotBlank())
    }

    val recentRuns = remember(db) { db.getAll().filter { it.type == ActivityType.LAUFEN } }
    val distanceKm = distanceText.replace(',', '.').toDoubleOrNull()
    val targetMinutes = timeText.replace(',', '.').toDoubleOrNull()
    val validGoal = distanceKm != null && distanceKm > 0.0 && targetMinutes != null && targetMinutes > 0.0
    val pace = if (validGoal) targetMinutes!! / distanceKm!! else null
    val speed = if (validGoal) distanceKm!! / (targetMinutes!! / 60.0) else null

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Laufcoach", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Dein Ziel, dein Rhythmus, dein nächster Schritt.", fontSize = 16.sp)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("1. Dein Laufziel", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
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
                        "Daraus ergibt sich ein Zieltempo von ${formatCoachPace(pace)} min/km " +
                            "und etwa ${String.format(Locale.GERMAN, "%.1f", speed)} km/h.",
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text("Trage Distanz und Zielzeit ein. Tempo und Geschwindigkeit berechnet die App.", fontSize = 14.sp)
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
                Text("2. Deine verfügbaren Lauftage", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("Wähle die Tage, an denen ein Lauf grundsätzlich möglich ist.", fontSize = 14.sp)
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
                    Text("Noch keine Tage gewählt. Die Vorschläge erhalten dann zunächst keinen festen Wochentag.", fontSize = 14.sp)
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("3. Hinweise für den Coach (optional)", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("Zum Beispiel eine Trainings-Einschränkung. Bitte keine Notfalldaten eintragen.", fontSize = 14.sp)
                OutlinedTextField(
                    value = constraints,
                    onValueChange = {
                        constraints = it.take(500)
                        goalSaved = false
                    },
                    placeholder = { Text("Nur wenn du möchtest") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Diese Angaben bleiben lokal auf dem Gerät und werden nicht an einen KI-Dienst gesendet.", fontSize = 13.sp)
            }
        }

        Button(
            onClick = {
                prefs.edit()
                    .putString("goal_distance_km", distanceText)
                    .putString("goal_time_minutes", timeText)
                    .putString("target_date", targetDate)
                    .putString("constraints", constraints)
                    .putStringSet("available_days", selectedDays)
                    .apply()
                goalSaved = true
                savedMessage = "Dein Ziel wurde gespeichert. Die Trainingsvorschläge findest du direkt darunter."
            },
            enabled = validGoal,
            modifier = Modifier.fillMaxWidth().height(54.dp)
        ) {
            Text("Ziel lokal speichern", fontSize = 16.sp)
        }
        if (savedMessage.isNotEmpty()) Text(savedMessage, fontSize = 14.sp)

        if (goalSaved && validGoal && pace != null) {
            RunningRecommendations(
                goalDistanceKm = distanceKm!!,
                goalPaceMinutesPerKm = pace,
                targetDateText = targetDate,
                recentRuns = recentRuns,
                availableDays = selectedDays,
                hasLocalConstraints = constraints.isNotBlank()
            )
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Garmin-Daten für den Coach", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("In der App sind derzeit ${recentRuns.size} Laufaktivitäten mit Datum, Dauer und Distanz gespeichert.", fontSize = 15.sp)
                Text(
                    "Im Reiter Läufe wird das Ø-Tempo schon aus Distanz und Dauer berechnet. " +
                        "Garmin-FIT-Zusatzwerte werden nach der Synchronisierung ergänzt; unter Einstellungen " +
                        "zeigt der Status nun auch, wenn der Detailabruf fehlschlägt.",
                    fontSize = 14.sp
                )
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
