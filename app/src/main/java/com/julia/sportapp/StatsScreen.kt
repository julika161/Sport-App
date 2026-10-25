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

// Auswertung für eine Sportart: Anzahl Einheiten und Anzahl verschiedener Tage
private data class TypeStat(val type: ActivityType, val count: Int, val days: Int)

// Der Auswertungs-Bereich: Monat oder Jahr, mit Zusammenfassung und Sportarten
@Composable
fun StatsContent(db: ActivityDatabase, modifier: Modifier = Modifier) {
    val activities = remember { db.getAll() }

    var byMonth by remember { mutableStateOf(true) }
    var period by remember { mutableStateOf(YearMonth.now()) }

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
            TypeStat(type, list.size, list.map { it.date }.distinct().size)
        }
        .sortedByDescending { it.count }
    val maxCount = stats.maxOfOrNull { it.count } ?: 0

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Auswertung", fontSize = 30.sp, fontWeight = FontWeight.Bold)

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
                Text("‹", fontSize = 20.sp)
            }
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = {
                period = if (byMonth) period.plusMonths(1) else period.plusYears(1)
            }) {
                Text("›", fontSize = 20.sp)
            }
        }

        // Zusammenfassung
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Gesamt", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("${filtered.size} Einheiten", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${totalMinutes / 60} Std. ${totalMinutes % 60} Min. Trainingszeit",
                    fontSize = 16.sp
                )
                Text("%.1f km Strecke".format(totalKm), fontSize = 16.sp)
            }
        }

        // Pro Sportart
        Text("Nach Sportart", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        if (filtered.isEmpty()) {
            Text("Keine Einheiten in diesem Zeitraum.", fontSize = 18.sp)
        }

        stats.forEach { s ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("${s.type.symbol} ${s.type.label}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("${s.count}×", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    "an ${s.days} ${if (s.days == 1) "Tag" else "Tagen"}",
                    fontSize = 14.sp
                )
                CountBar(fraction = if (maxCount == 0) 0f else s.count.toFloat() / maxCount)
            }
        }
    }
}

// Umschalt-Knopf: ausgefüllt, wenn ausgewählt, sonst umrandet
@Composable
fun ChoiceButton(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier.height(52.dp)) {
            Text(text, fontSize = 18.sp)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier.height(52.dp)) {
            Text(text, fontSize = 18.sp)
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
