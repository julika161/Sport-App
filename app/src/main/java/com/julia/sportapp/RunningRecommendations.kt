package com.julia.sportapp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.julia.sportapp.data.Activity
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private data class SuggestedWorkout(
    val title: String,
    val durationMinutes: Int,
    val description: String,
    val isRunning: Boolean = true
)

// Kleine, nachvollziehbare Vorschläge aus lokalen Laufdaten; kein KI-Dienst wird verwendet.
@Composable
fun RunningRecommendations(
    goalDistanceKm: Double,
    goalPaceMinutesPerKm: Double,
    targetDateText: String,
    recentRuns: List<Activity>,
    availableDays: Set<String>,
    heartRateZones: List<HeartRateZone>,
    zonesLoading: Boolean,
    zonesMessage: String
) {
    val today = LocalDate.now()
    val recent = remember(recentRuns) {
        recentRuns.filter { !it.date.isBefore(today.minusDays(28)) && !it.date.isAfter(today) }
            .take(3)
    }
    val averageMinutes = recent.map { run ->
        (run.durationSeconds?.toDouble()?.div(60.0) ?: run.durationMinutes.toDouble())
    }.average().takeIf { it.isFinite() }?.roundToInt()
    val referencePaceSeconds = recent.mapNotNull { run ->
        val distance = run.distanceKm?.takeIf { it > 0.0 } ?: return@mapNotNull null
        val duration = run.durationSeconds ?: run.durationMinutes * 60
        if (duration <= 0) null else duration / distance
    }.average().takeIf { it.isFinite() && it > 0.0 }?.roundToInt()
    val referenceSpeedKmh = referencePaceSeconds?.let { 3600.0 / it }
    val zoneTwo = heartRateZones.firstOrNull { it.number == 2 }
    val zoneTwoTarget = zoneTwo?.let { zone ->
        val min = zone.minBpm
        val max = zone.maxBpm
        if (min != null && max != null) "$min–$max bpm" else null
    }
    val zoneTwoLabel = zoneTwo?.let { zone ->
        if (zone.sport.equals("DEFAULT", ignoreCase = true)) "Garmin-Standardzone 2" else "Garmin-Laufzone 2"
    }

    val sessions = remember(recent, averageMinutes) {
        if (recent.size >= 2 && averageMinutes != null) {
            val longestRecent = recent.maxOf { it.durationMinutes }
            listOf(
                SuggestedWorkout(
                    "Locker laufen",
                    roundToFive((averageMinutes * 0.75).roundToInt()).coerceIn(15, 40),
                    "Bequem bleiben und das Zieltempo nicht erzwingen."
                ),
                SuggestedWorkout(
                    "Ruhiger Dauerlauf",
                    roundToFive(averageMinutes).coerceIn(20, 45),
                    "Gleichmäßig laufen; bei Müdigkeit kürzer machen."
                ),
                SuggestedWorkout(
                    "Etwas länger, weiterhin locker",
                    (longestRecent + 5).coerceIn(15, 60),
                    "Nur steigern, wenn sich die vorherigen Läufe gut angefühlt haben."
                )
            )
        } else {
            listOf(
                SuggestedWorkout(
                    "Locker starten",
                    20,
                    "Lauf-/Geh-Wechsel: 1 Min. locker traben, 2 Min. gehen."
                ),
                SuggestedWorkout(
                    "Erholung & Bewegung",
                    15,
                    "Spaziergang oder sanfte Mobility; ein Ruhetag ist auch Training.",
                    isRunning = false
                ),
                SuggestedWorkout(
                    "Lauf-/Geh-Wechsel wiederholen",
                    20,
                    "Nur wiederholen, wenn sich der erste Lauf gut angefühlt hat."
                )
            )
        }
    }
    val dayLabels = remember(availableDays) { nextAvailableDayLabels(availableDays, sessions.size) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Dein gespeichertes Laufziel", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "%.1f km · Zieltempo ${formatRecommendationPace(goalPaceMinutesPerKm)} min/km".format(
                    Locale.GERMAN,
                    goalDistanceKm
                ),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            if (targetDateText.isNotBlank()) {
                Text("Wunschdatum: $targetDateText", fontSize = 14.sp)
            }
            Text("Deine nächsten Trainings", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)

            sessions.forEachIndexed { index, workout ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val day = dayLabels.getOrNull(index)
                    Text(
                        buildString {
                            append("${index + 1}. ${workout.title}")
                            if (day != null) append(" · $day")
                            else if (availableDays.isEmpty()) append(" · Tag offen")
                        },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (workout.isRunning) {
                        val paceText = if (referencePaceSeconds != null && referenceSpeedKmh != null) {
                            "${formatRecommendationPace(referencePaceSeconds / 60.0)} min/km · " +
                                "${String.format(Locale.GERMAN, "%.1f", referenceSpeedKmh)} km/h"
                        } else {
                            "Noch kein persönlicher Pace-/km/h-Richtwert"
                        }
                        Text("${workout.durationMinutes} Min. · $paceText", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            when {
                                zonesLoading -> "Pulsziel: Garmin-Laufzone wird geladen …"
                                zoneTwoTarget != null && zoneTwoLabel != null -> "Pulsziel: $zoneTwoLabel · $zoneTwoTarget"
                                else -> "Pulsziel: lockeres Gesprächstempo; kein bpm-Wert geraten."
                            },
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text("${workout.durationMinutes} Min. · ohne Pace- oder Pulsziel", fontSize = 14.sp)
                    }
                    Text(workout.description, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (index < sessions.lastIndex) HorizontalDivider()
            }

            Text(
                when {
                    recent.size < 2 -> "Wenig aktuelle Laufdaten: Der Einstieg bleibt bewusst sanft."
                    referencePaceSeconds != null -> "Pace/km/h sind der Durchschnitt deiner letzten Läufe – ein Richtwert, kein Muss."
                    else -> "Für einen Pace-Richtwert fehlen Distanz oder Dauer bei den letzten Läufen."
                },
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (zoneTwoTarget == null && !zonesLoading) {
                Text(
                    zonesMessage.ifBlank { "Keine persönliche Garmin-Laufzone 2 verfügbar; bis dahin gilt das Gesprächstempo." },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "Wenn du Schmerzen oder ungewöhnliche Beschwerden hast, pausiere.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun roundToFive(minutes: Int): Int = ((minutes + 2) / 5) * 5

private fun nextAvailableDayLabels(availableDays: Set<String>, count: Int): List<String> {
    if (availableDays.isEmpty()) return emptyList()
    val labels = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
    val formatter = DateTimeFormatter.ofPattern("EEE dd.MM.", Locale.GERMAN)
    val result = mutableListOf<String>()
    var offset = 1L
    while (result.size < count && offset <= 28L) {
        val date = LocalDate.now().plusDays(offset)
        val code = labels[date.dayOfWeek.value - 1]
        if (code in availableDays) result.add(date.format(formatter))
        offset++
    }
    return result
}

private fun formatRecommendationPace(minutesPerKm: Double): String {
    val totalSeconds = (minutesPerKm * 60.0).roundToInt()
    val minutes = totalSeconds / 60
    val seconds = (totalSeconds % 60).toString().padStart(2, '0')
    return "$minutes:$seconds"
}
