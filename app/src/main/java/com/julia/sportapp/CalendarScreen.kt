package com.julia.sportapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.julia.sportapp.data.ActivityDatabase
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

// Der Kalender: Monatsansicht mit Sportsymbolen, darunter die Einheiten des gewählten Tages
@Composable
fun CalendarContent(db: ActivityDatabase, modifier: Modifier = Modifier) {
    val activities = remember { db.getAll() }
    val byDate = activities.groupBy { it.date }

    var month by remember { mutableStateOf(YearMonth.now()) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(LocalDate.now()) }

    val today = LocalDate.now()
    val monthTitle = month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.GERMAN))
    val weekdays = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")

    // Leere Felder vor dem 1. des Monats, damit die Wochentage stimmen
    val offset = month.atDay(1).dayOfWeek.value - 1
    val days: List<LocalDate?> = List<LocalDate?>(offset) { null } +
            (1..month.lengthOfMonth()).map { month.atDay(it) }
    val weeks: List<List<LocalDate?>> = days.chunked(7).map { week ->
        week + List<LocalDate?>(7 - week.size) { null }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Seitentitel: 28 sp, fett (einheitlich auf allen Seiten)
        Text("Kalender", fontSize = 28.sp, fontWeight = FontWeight.Bold)

        // Monat wechseln
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedButton(onClick = { month = month.minusMonths(1) }) {
                Text("‹", fontSize = 16.sp)
            }
            Text(monthTitle, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = { month = month.plusMonths(1) }) {
                Text("›", fontSize = 16.sp)
            }
        }

        // Wochentage
        Row(modifier = Modifier.fillMaxWidth()) {
            weekdays.forEach { day ->
                Text(
                    text = day,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Die Tage des Monats
        weeks.forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    val entriesOfDay = date?.let { byDate[it] }.orEmpty()
                    DayCell(
                        date = date,
                        isToday = date == today,
                        isSelected = date != null && date == selectedDate,
                        symbols = entriesOfDay.map { it.type.symbol }.distinct().take(3).joinToString(""),
                        onClick = { selectedDate = date }
                    )
                }
            }
        }

        // Details zum gewählten Tag
        val chosen = selectedDate
        if (chosen != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Einheiten am ${chosen.format(dateFormatter)}",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold
            )
            val dayEntries = byDate[chosen].orEmpty().sortedBy { it.time }
            if (dayEntries.isEmpty()) {
                Text("Keine Einheit an diesem Tag.", fontSize = 16.sp)
            } else {
                dayEntries.forEach { ActivityRow(it) }
            }
        }
    }
}

// Ein Tag im Kalender
@Composable
fun RowScope.DayCell(
    date: LocalDate?,
    isToday: Boolean,
    isSelected: Boolean,
    symbols: String,
    onClick: () -> Unit
) {
    val background = when {
        isSelected -> MaterialTheme.colorScheme.primary
        symbols.isNotEmpty() -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val textColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Column(
        modifier = Modifier
            .weight(1f)
            .padding(2.dp)
            .height(64.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .clickable(enabled = date != null) { onClick() }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (date != null) {
            Text(
                text = date.dayOfMonth.toString(),
                fontSize = 16.sp,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                color = textColor
            )
            Text(
                text = symbols,
                fontSize = 14.sp,
                maxLines = 1,
                color = textColor
            )
        }
    }
}
