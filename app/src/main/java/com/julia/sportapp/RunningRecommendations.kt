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
    val description: String
)

// Kleine, nachvollziehbare Vorschläge aus den lokalen Laufdaten; kein KI-Dienst wird verwendet.
@Composable
fun RunningRecommendations(
    goalDistanceKm: Double,
    goalPaceMinutesPerKm: Double,
    targetDateText: String,
    recentRuns: List<Activity>,
    availableDays: Set<String>,
    hasLocalConstraints: Boolean
) {
    val today = LocalDate.now()
    val recent = remember(recentRuns) {
        recentRuns.filter { !it.date.isBefore(today.minusDays(28)) && !it.date.isAfter(today) }
            .take(3)
    }
    val averageMinutes = recent.map { run ->
        (run.durationSeconds?.toDouble()?.div(60.0) ?: run.durationMinutes.toDouble())
    }.average().takeIf { it.isFinite() }?.roundToInt()
    val sessions = remember(recent) {
        if (recent.size >= 2 && averageMinutes != null) {
            val longestRecent = recent.maxOf { it.durationMinutes }
            listOf(
                SuggestedWorkout(
                    "Locker laufen",
                    roundToFive((averageMinutes * 0.75).roundToInt()).coerceIn(15, 40),
                    "Im Gesprächstempo bleiben. Das Zieltempo heute nicht erzwingen."
                ),
                SuggestedWorkout(
                    "Ruhiger Dauerlauf",
                    roundToFive(averageMinutes).coerceIn(20, 45),
                    "Gleichmäßig und bequem laufen; bei Müdigkeit kürzer machen."
                ),
                SuggestedWorkout(
                    "Etwas länger, weiterhin locker",
                    (longestRecent + 5).coerceAtMost(60),
                    "Nur wenn sich die vorherigen Läufe gut angefühlt haben; sonst die gewohnte Dauer wiederholen."
                )
            )
        } else {
            listOf(
                SuggestedWorkout(
                    "Locker starten",
                    20,
                    "20 Min. Lauf-/Geh-Wechsel: 1 Min. sehr locker traben, 2 Min. gehen."
                ),
                SuggestedWorkout(
                    "Erholung & Bewegung",
                    15,
                    "15 Min. Spaziergang oder sanfte Mobility. Ein Erholungstag gehört zum Training."
                ),
                SuggestedWorkout(
                    "Lauf-/Geh-Wechsel wiederholen",
                    20,
                    "Nur wiederholen, wenn sich der erste Lauf gut angefühlt hat; sonst spazieren gehen."
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
            Text("Deine nächsten Trainingsvorschläge", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)

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
                    Text("Etwa ${workout.durationMinutes} Minuten · ${workout.description}", fontSize = 14.sp)
                }
                if (index < sessions.lastIndex) HorizontalDivider()
            }

            Text(
                if (recent.size >= 2) {
                    "Vorschläge orientieren sich an deinen Läufen der letzten 4 Wochen."
                } else {
                    "Da nur wenige Läufe der letzten 4 Wochen vorliegen, startet der Vorschlag bewusst sanft."
                },
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Das Zieltempo ist ein Zielwert, nicht das Tempo für jede Einheit. " +
                    if (hasLocalConstraints) {
                        "Deine lokalen Hinweise werden nicht automatisch medizinisch bewertet; passe die Vorschläge daran an."
                    } else {
                        "Passe die Vorschläge an dein Wohlbefinden an und pausiere bei Schmerzen."
                    },
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
