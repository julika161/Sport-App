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
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

// Die Ziele. Jedes Ziel hat eine eigene Farbe.
private enum class Goal(val label: String, val symbol: String, val color: Color, val weekly: Boolean) {
    YOGA_MORGENS("Yoga morgens", "🧘", Color(0xFF7E57C2), false),
    YOGA_ABENDS("Yoga abends", "🧘", Color(0xFFAB47BC), false),
    SCHRITTE("10.000 Schritte", "👣", Color(0xFF8D6E63), false),
    HIIT("HIIT (1× Woche)", "🔥", Color(0xFF1E88E5), true),
    LAUFEN("Laufen (1× Woche)", "🏃", Color(0xFF43A047), true)
}

private enum class GoalPeriod(val label: String) {
    WEEK("Woche"), MONTH("Monat"), QUARTER("Quartal"), YEAR("Jahr")
}

// Yoga vor 12 Uhr zählt als morgens, ab 12 Uhr als abends
private val NOON: LocalTime = LocalTime.of(12, 0)
private const val CHUNK_DAYS = 28L
private val dayNames = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
private val shortDate: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.")
private val monthFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.GERMAN)
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

private data class PeriodStat(val goal: Goal, val done: Int, val elapsed: Int)

private fun startOfQuarter(day: LocalDate): LocalDate {
    val firstMonth = ((day.monthValue - 1) / 3) * 3 + 1
    return day.withMonth(firstMonth).withDayOfMonth(1)
}

private fun monthLabel(date: LocalDate): String =
    date.format(monthFormatter).replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(Locale.GERMAN) else it.toString()
    }

// Prüft Tagesziele pro vergangenem Tag und Wochenziele pro Kalenderwoche im gewählten Zeitraum.
private fun statsForRange(
    goal: Goal,
    start: LocalDate,
    requestedEnd: LocalDate,
    today: LocalDate,
    activities: List<Activity>,
    steps: Map<LocalDate, Int>
): PeriodStat {
    val end = if (requestedEnd.isAfter(today)) today else requestedEnd
    if (end.isBefore(start)) return PeriodStat(goal, 0, 0)
    val values = WeekValues(steps)

    if (goal.weekly) {
        var weekStart = start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        var elapsedWeeks = 0
        var completedWeeks = 0
        while (!weekStart.isAfter(end)) {
            val rangeStart = if (weekStart.isBefore(start)) start else weekStart
            val weekEnd = weekStart.plusDays(6)
            val rangeEnd = if (weekEnd.isAfter(end)) end else weekEnd
            var day = rangeStart
            var completed = false
            while (!day.isAfter(rangeEnd)) {
                if (achieved(goal, day, activities, values) == true) completed = true
                day = day.plusDays(1)
            }
            elapsedWeeks++
            if (completed) completedWeeks++
            weekStart = weekStart.plusWeeks(1)
        }
        return PeriodStat(goal, completedWeeks, elapsedWeeks)
    }

    val elapsedDays = ChronoUnit.DAYS.between(start, end).toInt() + 1
    var completedDays = 0
    for (offset in 0 until elapsedDays) {
        if (achieved(goal, start.plusDays(offset.toLong()), activities, values) == true) completedDays++
    }
    return PeriodStat(goal, completedDays, elapsedDays)
}

// Garmin-Schritte in handlichen 28-Tage-Blöcken laden.
private fun fetchStepsForRange(apiKey: String, start: LocalDate, end: LocalDate): Map<LocalDate, Int> {
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

// Ziele-Seite: Wochen-, Monats-, Quartals- und Jahresansicht
@Composable
fun WeeklyGoalContent(activities: List<Activity>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val apiKey = remember { loadApiKey(context) }
    val today = LocalDate.now()
    val thisMonday = today.with(DayOfWeek.MONDAY)

    // Beim Öffnen immer mit der Wochenansicht starten.
    var period by remember { mutableStateOf(GoalPeriod.WEEK) }

    var weekOffset by remember { mutableStateOf(0) }
    val monday = thisMonday.plusWeeks(weekOffset.toLong())
    val sunday = monday.plusDays(6)
    val days = (0..6).map { monday.plusDays(it.toLong()) }
    val isCurrentWeek = weekOffset == 0
    var weekValues by remember { mutableStateOf(WeekValues(emptyMap())) }
    var weekLoading by remember { mutableStateOf(false) }
    var weekStatus by remember { mutableStateOf("") }
    var weekRefresh by remember { mutableStateOf(0) }

    var selectedMonth by remember { mutableStateOf(today.withDayOfMonth(1)) }
    var monthSteps by remember { mutableStateOf<Map<LocalDate, Int>>(emptyMap()) }
    var monthLoading by remember { mutableStateOf(false) }
    var monthStatus by remember { mutableStateOf("") }
    var monthRefresh by remember { mutableStateOf(0) }

    var selectedQuarter by remember { mutableStateOf(startOfQuarter(today)) }
    var quarterSteps by remember { mutableStateOf<Map<LocalDate, Int>>(emptyMap()) }
    var quarterLoading by remember { mutableStateOf(false) }
    var quarterStatus by remember { mutableStateOf("") }
    var quarterRefresh by remember { mutableStateOf(0) }

    var year by remember { mutableStateOf(today.year) }
    var yearSteps by remember { mutableStateOf<Map<LocalDate, Int>>(emptyMap()) }
    var yearLoading by remember { mutableStateOf(false) }
    var yearStatus by remember { mutableStateOf("") }
    var yearRefresh by remember { mutableStateOf(0) }

    LaunchedEffect(monday, weekRefresh, period) {
        if (period == GoalPeriod.WEEK) {
            if (apiKey.isBlank()) {
                weekStatus = "Schritte kommen von Garmin. Verbinde Garmin unter Einstellungen."
            } else {
                weekValues = WeekValues(emptyMap())
                weekLoading = true
                val result = withContext(Dispatchers.IO) {
                    runCatching { fetchWeekValues(apiKey, monday, sunday) }
                }
                weekLoading = false
                result.onSuccess { weekValues = it; weekStatus = "" }
                    .onFailure { weekStatus = "Schritte konnten nicht geladen werden: ${it.message ?: "Unbekannt"}" }
            }
        }
    }

    LaunchedEffect(selectedMonth, monthRefresh, period) {
        if (period == GoalPeriod.MONTH) {
            val end = selectedMonth.plusMonths(1).minusDays(1).let { if (it.isAfter(today)) today else it }
            if (apiKey.isBlank()) {
                monthSteps = emptyMap()
                monthStatus = "Schritte kommen von Garmin. Verbinde Garmin unter Einstellungen."
            } else {
                monthSteps = emptyMap()
                monthLoading = true
                val result = withContext(Dispatchers.IO) {
                    runCatching { fetchStepsForRange(apiKey, selectedMonth, end) }
                }
                monthLoading = false
                result.onSuccess { monthSteps = it; monthStatus = "" }
                    .onFailure { monthStatus = "Schritte konnten nicht geladen werden: ${it.message ?: "Unbekannt"}" }
            }
        }
    }

    LaunchedEffect(selectedQuarter, quarterRefresh, period) {
        if (period == GoalPeriod.QUARTER) {
            val end = selectedQuarter.plusMonths(3).minusDays(1).let { if (it.isAfter(today)) today else it }
            if (apiKey.isBlank()) {
                quarterSteps = emptyMap()
                quarterStatus = "Schritte kommen von Garmin. Verbinde Garmin unter Einstellungen."
            } else {
                quarterSteps = emptyMap()
                quarterLoading = true
                val result = withContext(Dispatchers.IO) {
                    runCatching { fetchStepsForRange(apiKey, selectedQuarter, end) }
                }
                quarterLoading = false
                result.onSuccess { quarterSteps = it; quarterStatus = "" }
                    .onFailure { quarterStatus = "Schritte konnten nicht geladen werden: ${it.message ?: "Unbekannt"}" }
            }
        }
    }

    LaunchedEffect(year, yearRefresh, period) {
        if (period == GoalPeriod.YEAR) {
            val start = LocalDate.of(year, 1, 1)
            val end = if (year == today.year) today else LocalDate.of(year, 12, 31)
            if (apiKey.isBlank()) {
                yearSteps = emptyMap()
                yearStatus = "Schritte kommen von Garmin. Verbinde Garmin unter Einstellungen."
            } else {
                yearSteps = emptyMap()
                yearLoading = true
                val result = withContext(Dispatchers.IO) {
                    runCatching { fetchStepsForRange(apiKey, start, end) }
                }
                yearLoading = false
                result.onSuccess { yearSteps = it; yearStatus = "" }
                    .onFailure { yearStatus = "Schritte fürs Jahr konnten nicht geladen werden: ${it.message ?: "Unbekannt"}" }
            }
        }
    }

    val dayCounts: List<Int?> = days.map { day ->
        if (day.isAfter(today)) null
        else Goal.entries.count { goal -> achieved(goal, day, activities, weekValues) == true }
    }
    val weekTotal = dayCounts.filterNotNull().sum()

    val monthStats = Goal.entries.map {
        statsForRange(it, selectedMonth, selectedMonth.plusMonths(1).minusDays(1), today, activities, monthSteps)
    }
    val quarterStats = Goal.entries.map {
        statsForRange(it, selectedQuarter, selectedQuarter.plusMonths(3).minusDays(1), today, activities, quarterSteps)
    }
    val yearStats = Goal.entries.map {
        statsForRange(it, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31), today, activities, yearSteps)
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Ziele", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Dein Fortschritt auf einen Blick", fontSize = 16.sp)
        PeriodSelector(period) { period = it }

        when (period) {
            GoalPeriod.WEEK -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    OutlinedButton(onClick = { weekOffset-- }) { Text("‹ Zurück", fontSize = 16.sp) }
                    Column(
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("${monday.format(shortDate)} – ${sunday.format(dateFormatter)}", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(if (isCurrentWeek) "Diese Woche" else "Rückblick", fontSize = 14.sp)
                    }
                    OutlinedButton(onClick = { weekOffset++ }, enabled = !isCurrentWeek) { Text("Weiter ›", fontSize = 16.sp) }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Spacer(modifier = Modifier.width(labelWidth))
                            days.forEachIndexed { index, day ->
                                val isToday = day == today
                                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(dayNames[index], fontSize = 14.sp, fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
                                    Text(day.dayOfMonth.toString(), fontSize = 14.sp, fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
                                }
                            }
                        }
                        Goal.entries.forEach { GoalRow(it, days, today, activities, weekValues) }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("✓ Haken pro Tag", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(labelWidth))
                            dayCounts.forEach { count ->
                                Text(count?.toString() ?: "", fontSize = 16.sp, fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                            }
                        }
                        Text("Summe der Woche: $weekTotal Haken", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        if (weekLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        if (weekStatus.isNotEmpty()) Text(weekStatus, fontSize = 14.sp)
                    }
                }
                RefreshButton(weekLoading) { weekRefresh++ }
            }
            GoalPeriod.MONTH -> {
                SummaryPeriodContent(
                    title = monthLabel(selectedMonth), stats = monthStats, loading = monthLoading, status = monthStatus,
                    onPrevious = { selectedMonth = selectedMonth.minusMonths(1) },
                    onNext = { selectedMonth = selectedMonth.plusMonths(1) },
                    canGoNext = selectedMonth.isBefore(today.withDayOfMonth(1)),
                    onRefresh = { monthRefresh++ }
                )
            }
            GoalPeriod.QUARTER -> {
                val quarterNumber = (selectedQuarter.monthValue - 1) / 3 + 1
                SummaryPeriodContent(
                    title = "$quarterNumber. Quartal ${selectedQuarter.year}", stats = quarterStats, loading = quarterLoading, status = quarterStatus,
                    onPrevious = { selectedQuarter = selectedQuarter.minusMonths(3) },
                    onNext = { selectedQuarter = selectedQuarter.plusMonths(3) },
                    canGoNext = selectedQuarter.isBefore(startOfQuarter(today)),
                    onRefresh = { quarterRefresh++ }
                )
            }
            GoalPeriod.YEAR -> {
                SummaryPeriodContent(
                    title = year.toString(), stats = yearStats, loading = yearLoading, status = yearStatus,
                    onPrevious = { year-- }, onNext = { year++ }, canGoNext = year < today.year,
                    onRefresh = { yearRefresh++ }
                )
            }
        }
    }
}

@Composable
private fun PeriodSelector(selected: GoalPeriod, onSelect: (GoalPeriod) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        GoalPeriod.entries.forEach { option ->
            val modifier = Modifier.weight(1f)
            if (option == selected) {
                Button(onClick = {}, modifier = modifier, contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp)) {
                    Text(option.label, fontSize = 13.sp, maxLines = 1)
                }
            } else {
                OutlinedButton(onClick = { onSelect(option) }, modifier = modifier,
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp)) {
                    Text(option.label, fontSize = 13.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun SummaryPeriodContent(
    title: String,
    stats: List<PeriodStat>,
    loading: Boolean,
    status: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    canGoNext: Boolean,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        OutlinedButton(onClick = onPrevious) { Text("‹", fontSize = 18.sp) }
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        OutlinedButton(onClick = onNext, enabled = canGoNext) { Text("›", fontSize = 18.sp) }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Fortschritt", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (status.isNotEmpty()) Text(status, fontSize = 14.sp)
            stats.forEach { stat ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${stat.goal.symbol} ${stat.goal.label}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (stat.goal.weekly) "${stat.done} von ${stat.elapsed} Wochen" else "${stat.done} von ${stat.elapsed} Tagen",
                            fontSize = 14.sp, fontWeight = FontWeight.Bold
                        )
                    }
                    Text(if (stat.goal.weekly) "Ziel: mindestens 1× pro Woche" else "Ziel: jeden Tag", fontSize = 14.sp)
                    GoalBar(
                        fraction = if (stat.elapsed == 0) 0f else stat.done.toFloat() / stat.elapsed,
                        color = stat.goal.color
                    )
                }
            }
        }
    }
    RefreshButton(loading, onRefresh)
}

@Composable
private fun RefreshButton(loading: Boolean, onRefresh: () -> Unit) {
    Button(onClick = onRefresh, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
        Text(if (loading) "Wird aktualisiert …" else "↻ Aktualisieren", fontSize = 16.sp)
    }
}

// Eine Zeile pro Ziel: Name, Zusammenfassung und ein Kreis pro Tag
@Composable
private fun GoalRow(goal: Goal, days: List<LocalDate>, today: LocalDate, activities: List<Activity>, values: WeekValues) {
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
                future = day.isAfter(today), isToday = day == today, color = goal.color,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// Ein Kreis pro Tag: farbig mit ✓ = geschafft, grau mit ✗ = verpasst, leer = noch offen
@Composable
private fun DayCircle(value: Boolean?, future: Boolean, isToday: Boolean, color: Color, modifier: Modifier) {
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
        modifier = modifier.padding(2.dp).aspectRatio(1f).clip(CircleShape)
            .background(if (done) color else Color.Transparent)
            .border(width = if (isToday) 2.dp else 1.dp,
                color = if (isToday) MaterialTheme.colorScheme.primary else Color.LightGray,
                shape = CircleShape)
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            color = if (done) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// Fortschrittsbalken in der Farbe des Ziels
@Composable
private fun GoalBar(fraction: Float, color: Color) {
    Box(modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)) {
        Box(modifier = Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(color))
    }
}
