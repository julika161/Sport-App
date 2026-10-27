package com.julia.sportapp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.julia.sportapp.data.Activity
import com.julia.sportapp.data.ActivityType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

// Die Ziele der Woche. Jedes Ziel hat eine eigene Farbe.
private enum class Goal(val label: String, val symbol: String, val color: Color, val weekly: Boolean) {
    YOGA_MORGENS("Yoga morgens", "🧘", Color(0xFF7E57C2), false),
    YOGA_ABENDS("Yoga abends", "🧘", Color(0xFFAB47BC), false),
    SCHRITTE("10.000 Schritte", "👣", Color(0xFF8D6E63), false),
    HIIT("HIIT (1× Woche)", "🔥", Color(0xFFE53935), true),
    LAUFEN("Laufen (1× Woche)", "🏃", Color(0xFF43A047), true)
}

// Yoga vor 12 Uhr zählt als morgens, ab 12 Uhr als abends
private val NOON: LocalTime = LocalTime.of(12, 0)

private val dayNames = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
private val shortDate: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.")
private val labelWidth = 140.dp

// Ist das Ziel an diesem Tag erreicht? null = keine Daten (nur bei Schritten)
private fun achieved(goal: Goal, day: LocalDate, activities: List<Activity>, values: WeekValues): Boolean? =
    when (goal) {
        Goal.YOGA_MORGENS -> activities.any {
            it.type == ActivityType.YOGA && it.date == day && it.time.isBefore(NOON)
        }
        Goal.YOGA_ABENDS -> activities.any {
            it.type == ActivityType.YOGA && it.date == day && !it.time.isBefore(NOON)
        }
        Goal.SCHRITTE -> values.steps[day]?.let { it >= 10000 }
        Goal.HIIT -> activities.any { it.type == ActivityType.HIIT && it.date == day }
        Goal.LAUFEN -> activities.any { it.type == ActivityType.LAUFEN && it.date == day }
    }

// Startseite: Wochenziel mit Navigation zu vergangenen Wochen (vorwärts bis zur aktuellen Woche)
@Composable
fun WeeklyGoalContent(activities: List<Activity>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val apiKey = remember { loadApiKey(context) }
    val today = LocalDate.now()
    val thisMonday = today.with(DayOfWeek.MONDAY)

    // 0 = aktuelle Woche, -1 = letzte Woche, usw.
    var weekOffset by remember { mutableStateOf(0) }
    val monday = thisMonday.plusWeeks(weekOffset.toLong())
    val sunday = monday.plusDays(6)
    val days = (0..6).map { monday.plusDays(it.toLong()) }
    val isCurrentWeek = weekOffset == 0

    var values by remember { mutableStateOf(WeekValues(emptyMap())) }
    var status by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var refreshCount by remember { mutableStateOf(0) }

    LaunchedEffect(monday, refreshCount) {
        if (apiKey.isBlank()) {
            status = "Schritte kommen von Garmin. Verbinde Garmin unter Einstellungen."
        } else {
            loading = true
            val result = withContext(Dispatchers.IO) {
                runCatching { fetchWeekValues(apiKey, monday, sunday) }
            }
            loading = false
            result
                .onSuccess { values = it; status = "" }
                .onFailure { status = "Schritte konnten nicht geladen werden: ${it.message ?: "Unbekannt"}" }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Mein Wochenziel", fontSize = 28.sp, fontWeight = FontWeight.Bold)

        // Wochen-Navigation
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            OutlinedButton(onClick = { weekOffset-- }) {
                Text("‹ Zurück", fontSize = 16.sp)
            }
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "${monday.format(shortDate)} – ${sunday.format(dateFormatter)}",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(if (isCurrentWeek) "Diese Woche" else "Rückblick", fontSize = 14.sp)
            }
            OutlinedButton(onClick = { weekOffset++ }, enabled = !isCurrentWeek) {
                Text("Weiter ›", fontSize = 16.sp)
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Kopfzeile mit Wochentag und Datum, heute ist hervorgehoben
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(modifier = Modifier.width(labelWidth))
                    days.forEachIndexed { index, day ->
                        val isToday = day == today
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                dayNames[index],
                                fontSize = 14.sp,
                                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                day.dayOfMonth.toString(),
                                fontSize = 14.sp,
                                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                Goal.entries.forEach { goal ->
                    GoalRow(goal, days, today, activities, values)
                }

                if (loading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (status.isNotEmpty()) {
                    Text(status, fontSize = 16.sp)
                }

                OutlinedButton(
                    onClick = { refreshCount++ },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Text("Aktualisieren", fontSize = 16.sp)
                }
            }
        }
    }
}

// Eine Zeile pro Ziel: Name, Zusammenfassung und ein Kreis pro Tag
@Composable
private fun GoalRow(
    goal: Goal,
    days: List<LocalDate>,
    today: LocalDate,
    activities: List<Activity>,
    values: WeekValues
) {
    val pastDays = days.filter { !it.isAfter(today) }
    val done = pastDays.count { achieved(goal, it, activities, values) == true }
    val weekOver = days.last().isBefore(today)
    val summary = when {
        !goal.weekly -> "$done von ${pastDays.size} Tagen"
        done > 0 -> "✓ geschafft"
        weekOver -> "nicht geschafft"
        else -> "noch offen"
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.width(labelWidth)) {
            Text("${goal.symbol} ${goal.label}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(summary, fontSize = 14.sp)
        }
        days.forEach { day ->
            DayCircle(
                value = if (day.isAfter(today)) null else achieved(goal, day, activities, values),
                future = day.isAfter(today),
                isToday = day == today,
                color = goal.color,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// Ein Kreis pro Tag: farbig mit ✓ = geschafft, grau mit ✗ = verpasst, leer = noch offen
@Composable
private fun DayCircle(
    value: Boolean?,
    future: Boolean,
    isToday: Boolean,
    color: Color,
    modifier: Modifier
) {
    val done = value == true
    val text = when {
        done -> "✓"
        future -> ""
        value == null -> "?"
        isToday -> ""
        else -> "✗"
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .padding(2.dp)
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(if (done) color else Color.Transparent)
            .border(
                width = if (isToday) 2.dp else 1.dp,
                color = if (isToday) MaterialTheme.colorScheme.primary else Color.LightGray,
                shape = CircleShape
            )
    ) {
        Text(
            text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = if (done) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
