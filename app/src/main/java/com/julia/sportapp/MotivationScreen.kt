package com.julia.sportapp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import java.time.Year
import java.time.format.DateTimeFormatter

// Die Ziele. Jedes Ziel hat eine eigene Farbe.
private enum class Goal(val label: String, val symbol: String, val color: Color, val weekly: Boolean) {
    YOGA_MORGENS("Yoga morgens", "🧘", Color(0xFF7E57C2), false),
    YOGA_ABENDS("Yoga abends", "🧘", Color(0xFFAB47BC), false),
    SCHRITTE("10.000 Schritte", "👣", Color(0xFF8D6E63), false),
    HIIT("HIIT (1× Woche)", "🔥", Color(0xFF1E88E5), true),
    LAUFEN("Laufen (1× Woche)", "🏃", Color(0xFF43A047), true)
}

private enum class GoalPeriod { WEEK, YEAR }

// Yoga vor 12 Uhr zählt als morgens, ab 12 Uhr als abends
private val NOON: LocalTime = LocalTime.of(12, 0)

// Schritte für ein Jahr werden in Stücken von 28 Tagen geholt
private const val CHUNK_DAYS = 28L

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

// Ergebnis eines Ziels für ein Jahr
private data class YearStat(val goal: Goal, val done: Int, val elapsed: Int)

// Alle Tage eines Jahres
private fun weekDaysOfYear(year: Int): List<LocalDate> {
    val first = LocalDate.of(year, 1, 1)
    return (0 until Year.of(year).length()).map { first.plusDays(it.toLong()) }
}

// Jahresauswertung: Erfolg bisher geteilt durch vergangene Tage oder Wochen
private fun yearStat(
    goal: Goal,
    year: Int,
    today: LocalDate,
    activities: List<Activity>,
    steps: Map<LocalDate, Int>
): YearStat {
    val values = WeekValues(steps)
    val allDays = weekDaysOfYear(year)
    return if (goal.weekly) {
        val mondays = allDays.filter { it.dayOfWeek == DayOfWeek.MONDAY }
        val done = mondays.count { monday ->
            (0..6).any { achieved(goal, monday.plusDays(it.toLong()), activities, values) == true }
        }
        YearStat(goal, done, mondays.count { !it.isAfter(today) })
    } else {
        val past = allDays.filter { !it.isAfter(today) }
        val done = past.count { achieved(goal, it, activities, values) == true }
        YearStat(goal, done, past.size)
    }
}

// Schritte für ein ganzes Jahr (bis heute) in Stücken holen
private fun fetchYearSteps(apiKey: String, start: LocalDate, end: LocalDate): Map<LocalDate, Int> {
    val result = mutableMapOf<LocalDate, Int>()
    var from = start
    while (!from.isAfter(end)) {
        val candidate = from.plusDays(CHUNK_DAYS - 1)
        val to = if (candidate.isAfter(end)) end else candidate
        result.putAll(fetchWeekValues(apiKey, from, to).steps)
        from = to.plusDays(1)
    }
    return result
}

// Ziele-Seite: zuerst die Wochenansicht, per Umschalter zur Jahresübersicht
@Composable
fun WeeklyGoalContent(activities: List<Activity>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val apiKey = remember { loadApiKey(context) }
    val today = LocalDate.now()
    val thisMonday = today.with(DayOfWeek.MONDAY)

    // Beim Öffnen immer mit der Wochenansicht starten
    var period by remember { mutableStateOf(GoalPeriod.WEEK) }

    // 0 = aktuelle Woche, -1 = letzte Woche, usw.
    var weekOffset by remember { mutableStateOf(0) }
    val monday = thisMonday.plusWeeks(weekOffset.toLong())
    val sunday = monday.plusDays(6)
    val days = (0..6).map { monday.plusDays(it.toLong()) }
    val isCurrentWeek = weekOffset == 0

    var values by remember { mutableStateOf(WeekValues(emptyMap())) }
    var status by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var weekRefresh by remember { mutableStateOf(0) }

    // Jahresauswertung: aktuelles Jahr, mit Navigation zu früheren Jahren
    var year by remember { mutableStateOf(today.year) }
    var yearSteps by remember { mutableStateOf<Map<LocalDate, Int>>(emptyMap()) }
    var yearLoading by remember { mutableStateOf(false) }
    var yearStatus by remember { mutableStateOf("") }
    var yearRefresh by remember { mutableStateOf(0) }

    LaunchedEffect(monday, weekRefresh, period) {
        if (period == GoalPeriod.WEEK) {
            if (apiKey.isBlank()) {
                status = "Schritte kommen von Garmin. Verbinde Garmin unter Einstellungen."
            } else {
                values = WeekValues(emptyMap())
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
    }

    LaunchedEffect(year, yearRefresh, period) {
        if (period == GoalPeriod.YEAR) {
            if (apiKey.isBlank()) {
                yearSteps = emptyMap()
                yearStatus = "Schritte fürs Jahr kommen von Garmin. Verbinde Garmin unter Einstellungen."
            } else {
                yearSteps = emptyMap()
                yearLoading = true
                val end = if (year == today.year) today else LocalDate.of(year, 12, 31)
                val result = withContext(Dispatchers.IO) {
                    runCatching { fetchYearSteps(apiKey, LocalDate.of(year, 1, 1), end) }
                }
                yearLoading = false
                result
                    .onSuccess { yearSteps = it; yearStatus = "" }
                    .onFailure { yearStatus = "Schritte fürs Jahr konnten nicht geladen werden: ${it.message ?: "Unbekannt"}" }
            }
        }
    }

    // Haken pro Tag (nur vergangene und heutige Tage)
    val dayCounts: List<Int?> = days.map { day ->
        if (day.isAfter(today)) null
        else Goal.entries.count { goal -> achieved(goal, day, activities, values) == true }
    }
    val weekTotal = dayCounts.filterNotNull().sum()

    val yearStats = Goal.entries.map { goal ->
        yearStat(goal, year, today, activities, yearSteps)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Ziele", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Dein Fortschritt auf einen Blick", fontSize = 16.sp)

        // Zwei klare Ansichten statt langer Wochen- und Jahresliste
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (period == GoalPeriod.WEEK) {
                Button(onClick = {}, modifier = Modifier.weight(1f)) {
                    Text("Woche", fontSize = 16.sp)
                }
            } else {
                OutlinedButton(onClick = { period = GoalPeriod.WEEK }, modifier = Modifier.weight(1f)) {
                    Text("Woche", fontSize = 16.sp)
                }
            }
            if (period == GoalPeriod.YEAR) {
                Button(onClick = {}, modifier = Modifier.weight(1f)) {
                    Text("Jahr", fontSize = 16.sp)
                }
            } else {
                OutlinedButton(onClick = { period = GoalPeriod.YEAR }, modifier = Modifier.weight(1f)) {
                    Text("Jahr", fontSize = 16.sp)
                }
            }
        }

        if (period == GoalPeriod.WEEK) {
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

            // Wochenübersicht
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

                    // Anzahl der Haken pro Tag und Summe der Woche
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "✓ Haken pro Tag",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.width(labelWidth)
                        )
                        dayCounts.forEach { count ->
                            Text(
                                count?.toString() ?: "",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Text("Summe der Woche: $weekTotal Haken", fontSize = 16.sp, fontWeight = FontWeight.Bold)

                    if (loading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    if (status.isNotEmpty()) {
                        Text(status, fontSize = 14.sp)
                    }
                }
            }

            // Aktualisieren steht am Ende der Wochenansicht
            Button(
                onClick = { weekRefresh++ },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (loading) "Wird aktualisiert …" else "↻ Aktualisieren", fontSize = 16.sp)
            }
        } else {
            // Jahresübersicht: eine Ansicht mit Jahr-Navigation und Fortschritt je Ziel
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Jahresziele", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { year-- }) {
                                Text("‹", fontSize = 16.sp)
                            }
                            Text(
                                "$year",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                            OutlinedButton(onClick = { year++ }, enabled = year < today.year) {
                                Text("›", fontSize = 16.sp)
                            }
                        }
                    }

                    if (yearLoading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    if (yearStatus.isNotEmpty()) {
                        Text(yearStatus, fontSize = 14.sp)
                    }

                    yearStats.forEach { s ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("${s.goal.symbol} ${s.goal.label}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (s.goal.weekly) "${s.done} von ${s.elapsed} Wochen"
                                    else "${s.done} von ${s.elapsed} Tagen",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                if (s.goal.weekly) "Ziel: mindestens 1× pro Woche"
                                else "Ziel: jeden Tag",
                                fontSize = 14.sp
                            )
                            GoalBar(
                                fraction = if (s.elapsed == 0) 0f else s.done.toFloat() / s.elapsed,
                                color = s.goal.color
                            )
                        }
                    }
                }
            }

            // Aktualisieren steht am Ende der Jahresansicht
            Button(
                onClick = { yearRefresh++ },
                enabled = !yearLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (yearLoading) "Wird aktualisiert …" else "↻ Aktualisieren", fontSize = 16.sp)
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

// Fortschrittsbalken in der Farbe des Ziels
@Composable
private fun GoalBar(fraction: Float, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(color)
        )
    }
}
