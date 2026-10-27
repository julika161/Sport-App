package com.julia.sportapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.julia.sportapp.data.ActivityDatabase
import com.julia.sportapp.data.ActivityType
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

// Ansicht der Auswertung: Anzahl der Einheiten oder Strecke bzw. Minuten
private enum class Metric { COUNT, VALUE }

// Diese Sportarten werden in Kilometern gezählt, alle anderen in Minuten
private val distanceTypes = setOf(ActivityType.LAUFEN, ActivityType.RADFAHREN, ActivityType.WALKEN)

// Auswertung für eine Sportart
private data class TypeStat(
    val type: ActivityType,
    val count: Int,
    val days: Int,
    val km: Double,
    val minutes: Int
)

// Zahl für Sortierung und Balken, je nach Ansicht
private fun statValue(s: TypeStat, metric: Metric): Double = when (metric) {
    Metric.COUNT -> s.count.toDouble()
    Metric.VALUE -> if (s.type in distanceTypes) s.km else s.minutes.toDouble()
}

// Text für die Zeile, je nach Ansicht
private fun statLabel(s: TypeStat, metric: Metric): String = when (metric) {
    Metric.COUNT -> "${s.count}×"
    Metric.VALUE -> if (s.type in distanceTypes) "%.1f km".format(s.km) else formatMinutes(s.minutes)
}

private fun formatMinutes(total: Int): String =
    if (total >= 60) "${total / 60} Std. ${total % 60} Min." else "$total Min."

// Der Auswertungs-Bereich: Monat oder Jahr, mit Zusammenfassung und Sportarten
@Composable
fun StatsContent(db: ActivityDatabase, modifier: Modifier = Modifier) {
    val activities = remember { db.getAll() }

    var byMonth by remember { mutableStateOf(true) }
    var period by remember { mutableStateOf(YearMonth.now()) }
    var metric by remember { mutableStateOf(Metric.COUNT) }

    val title = if (byMonth) {
        period.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.GERMAN))
    } else {
        period.year.toString()
    }

    // Nur die Einheiten des gewählten Zeitraums
    val filtered = activities.filter {
        if (byMonth) YearMonth.from(it.date) == period else it.date.year == period.year
    }

    val totalMinutes = filtered.sumOf { it.durationMinutes }
    val totalKm = filtered.mapNotNull { it.distanceKm }.sum()

    val stats = ActivityType.entries
        .map { type ->
            val list = filtered.filter { it.type == type }
            TypeStat(
                type = type,
                count = list.size,
                days = list.map { it.date }.distinct().size,
                km = list.mapNotNull { it.distanceKm }.sum(),
                minutes = list.sumOf { it.durationMinutes }
            )
        }
        .sortedByDescending { statValue(it, metric) }
    val maxValue = stats.maxOfOrNull { statValue(it, metric) } ?: 0.0

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Seitentitel: 28 sp, fett (einheitlich auf allen Seiten)
        Text("Auswertung", fontSize = 28.sp, fontWeight = FontWeight.Bold)

        // Umschalten: Monat oder Jahr
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            ChoiceButton(
                text = "Monat",
                selected = byMonth,
                onClick = { byMonth = true },
                modifier = Modifier.weight(1f)
            )
            ChoiceButton(
                text = "Jahr",
                selected = !byMonth,
                onClick = { byMonth = false },
                modifier = Modifier.weight(1f)
            )
        }

        // Zeitraum wechseln
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedButton(onClick = {
                period = if (byMonth) period.minusMonths(1) else period.minusYears(1)
            }) {
                Text("‹", fontSize = 16.sp)
            }
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = {
                period = if (byMonth) period.plusMonths(1) else period.plusYears(1)
            }) {
                Text("›", fontSize = 16.sp)
            }
        }

        // Zusammenfassung
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Gesamt", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("${filtered.size} Einheiten", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("${formatMinutes(totalMinutes)} Trainingszeit", fontSize = 16.sp)
                Text("%.1f km Strecke".format(totalKm), fontSize = 16.sp)
            }
        }

        // Umschalten: Anzahl oder Kilometer/Minuten
        Text("Anzeigen als", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            ChoiceButton(
                text = "Anzahl",
                selected = metric == Metric.COUNT,
                onClick = { metric = Metric.COUNT },
                modifier = Modifier.weight(1f)
            )
            ChoiceButton(
                text = "Km / Minuten",
                selected = metric == Metric.VALUE,
                onClick = { metric = Metric.VALUE },
                modifier = Modifier.weight(1f)
            )
        }
        if (metric == Metric.VALUE) {
            Text(
                "Laufen, Radfahren und Walken in Kilometern, die anderen Sportarten in Minuten.",
                fontSize = 14.sp
            )
        }

        // Pro Sportart
        Text("Nach Sportart", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        if (filtered.isEmpty()) {
            Text("Keine Einheiten in diesem Zeitraum.", fontSize = 16.sp)
        }

        stats.forEach { s ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("${s.type.symbol} ${s.type.label}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(statLabel(s, metric), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    "an ${s.days} ${if (s.days == 1) "Tag" else "Tagen"}",
                    fontSize = 14.sp
                )
                if (metric == Metric.VALUE && s.type in distanceTypes && s.count > 0 && s.km == 0.0) {
                    Text("Keine Strecke erfasst.", fontSize = 14.sp)
                }
                CountBar(
                    fraction = if (maxValue == 0.0) 0f else (statValue(s, metric) / maxValue).toFloat()
                )
            }
        }
    }
}

// Umschalt-Knopf: ausgefüllt, wenn ausgewählt, sonst umrandet
@Composable
fun ChoiceButton(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier.height(52.dp)) {
            Text(text, fontSize = 16.sp)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier.height(52.dp)) {
            Text(text, fontSize = 16.sp)
        }
    }
}

// Balken, der den Anteil einer Sportart zeigt
@Composable
fun CountBar(fraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}
