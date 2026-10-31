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
    val referencePaceSeconds = recent.mapNotNull { run ->
        val distance = run.distanceKm?.takeIf { it > 0.0 } ?: return@mapNotNull null
        val duration = run.durationSeconds ?: run.durationMinutes * 60
        if (duration <= 0) null else duration / distance
    }.average().takeIf { it.isFinite() && it > 0.0 }?.roundToInt()
    val referenceSpeedKmh = referencePaceSeconds?.let { 3600.0 / it }
    val sessions = remember(recentRuns, availableDays) {
        buildCoachSessions(recentRuns, availableDays)
    }
    val zoneTwo = heartRateZones.firstOrNull { it.number == 2 }
    val zoneTwoTarget = zoneTwo?.let { zone ->
        val min = zone.minBpm
        val max = zone.maxBpm
        if (min != null && max != null) "$min–$max bpm" else null
    }
    val zoneTwoLabel = zoneTwo?.let { zone ->
        if (zone.sport.equals("DEFAULT", ignoreCase = true)) "Garmin-Standardzone 2" else "Garmin-Laufzone 2"
    }

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
                    val dateLabel = workout.date?.format(
                        DateTimeFormatter.ofPattern("EEE dd.MM.", Locale.GERMAN)
                    )
                    Text(
                        buildString {
                            append("${index + 1}. ${workout.title}")
                            if (dateLabel != null) append(" · $dateLabel")
                            else if (availableDays.isEmpty()) append(" · Tag offen")
                            else append(" · Termin noch offen")
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
            if (availableDays.isEmpty()) {
                Text(
                    "Wähle mögliche Trainingstage und speichere dein Ziel, damit konkrete Coach-Termine im Kalender erscheinen.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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

private fun formatRecommendationPace(minutesPerKm: Double): String {
    val totalSeconds = (minutesPerKm * 60.0).roundToInt()
    val minutes = totalSeconds / 60
    val seconds = (totalSeconds % 60).toString().padStart(2, '0')
    return "$minutes:$seconds"
}
