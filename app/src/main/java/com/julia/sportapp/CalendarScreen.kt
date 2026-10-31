package com.julia.sportapp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.julia.sportapp.data.ActivityDatabase
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

// Monatsansicht mit Markierungen für absolvierte, geplante und Zieltermine.
@Composable
fun CalendarContent(db: ActivityDatabase, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    val goalGreen = if (darkTheme) Color(0xFFA5D6A7) else Color(0xFF2E7D32)
    val goalGreenContainer = if (darkTheme) Color(0xFF1B3A24) else Color(0xFFE8F5E9)
    val onGoalGreenContainer = if (darkTheme) Color(0xFFE8F5E9) else Color(0xFF1B5E20)

    val activities = remember(db) { db.getAll() }
    val byDate = activities.groupBy { it.date }
    val coachSessions = remember { loadCoachSchedule(context) }
    val coachByDate = coachSessions.filter { it.date != null }.groupBy { it.date!! }
    val goalDate = remember { loadCoachTargetDate(context) }
    val goalSummary = remember { loadCoachGoalSummary(context) }

    var month by remember { mutableStateOf(YearMonth.now()) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(LocalDate.now()) }

    val today = LocalDate.now()
    val monthTitle = month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.GERMAN))
    val weekdays = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")

    // Leere Felder vor dem Monatsersten halten die Wochentage korrekt ausgerichtet.
    val offset = month.atDay(1).dayOfWeek.value - 1
    val days: List<LocalDate?> = List<LocalDate?>(offset) { null } +
        (1..month.lengthOfMonth()).map { month.atDay(it) }
    val weeks: List<List<LocalDate?>> = days.chunked(7).map { week ->
        week + List<LocalDate?>(7 - week.size) { null }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Kalender", fontSize = 28.sp, fontWeight = FontWeight.Bold)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedButton(onClick = { month = month.minusMonths(1) }) { Text("‹", fontSize = 16.sp) }
            Text(monthTitle, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = { month = month.plusMonths(1) }) { Text("›", fontSize = 16.sp) }
        }

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

        weeks.forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    val dayActivities = date?.let { byDate[it] }.orEmpty()
                    val hasCoachPlan = date?.let { coachByDate[it].orEmpty().isNotEmpty() } ?: false
                    DayCell(
                        date = date,
                        isToday = date == today,
                        isSelected = date != null && date == selectedDate,
                        activityCount = dayActivities.size,
                        hasCoachPlan = hasCoachPlan,
                        hasGoalDate = date != null && date == goalDate,
                        onClick = { selectedDate = date }
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CalendarLegendItem(MaterialTheme.colorScheme.secondary, "Absolviert")
            CalendarLegendItem(MaterialTheme.colorScheme.tertiary, "Coach-Plan")
            CalendarLegendItem(goalGreen, "Zieltermin")
        }

        if (coachSessions.isEmpty()) {
            Text(
                "Noch keine Coach-Termine geplant. Wähle Trainingstage im Laufcoach und tippe auf „Ziel speichern“.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        val chosen = selectedDate
        if (chosen != null) {
            Spacer(Modifier.height(4.dp))
            Text("Tagesdetails · ${chosen.format(dateFormatter)}", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)

            if (chosen == goalDate) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = goalGreenContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Dein Zieltermin", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = onGoalGreenContainer)
                        Text(
                            goalSummary ?: "Dein gespeichertes Laufziel",
                            fontSize = 15.sp,
                            color = onGoalGreenContainer
                        )
                    }
                }
            }

            val planned = coachByDate[chosen].orEmpty()
            if (planned.isNotEmpty()) {
                Text("Coach-Plan", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.tertiary)
                planned.forEach { session -> CoachPlanCard(session) }
            }

            val dayActivities = byDate[chosen].orEmpty().sortedBy { it.time }
            if (dayActivities.isNotEmpty()) {
                Text("Absolvierte Einheiten", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                dayActivities.forEach { ActivityRow(it) }
            }

            if (chosen != goalDate && planned.isEmpty() && dayActivities.isEmpty()) {
                Text("Keine geplante oder absolvierte Einheit an diesem Tag.", fontSize = 16.sp)
            }
        }
    }
}

@Composable
fun RowScope.DayCell(
    date: LocalDate?,
    isToday: Boolean,
    isSelected: Boolean,
    activityCount: Int,
    hasCoachPlan: Boolean,
    hasGoalDate: Boolean,
    onClick: () -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    val goalGreen = if (darkTheme) Color(0xFFA5D6A7) else Color(0xFF2E7D32)
    val goalGreenContainer = if (darkTheme) Color(0xFF1B3A24) else Color(0xFFE8F5E9)
    val cellBackground = when {
        hasGoalDate -> goalGreenContainer
        isSelected -> MaterialTheme.colorScheme.primary
        hasCoachPlan -> MaterialTheme.colorScheme.tertiaryContainer
        activityCount > 0 -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val selectedWithoutGoal = isSelected && !hasGoalDate
    val textColor = if (selectedWithoutGoal) MaterialTheme.colorScheme.onPrimary
        else if (hasGoalDate && darkTheme) Color(0xFFE8F5E9)
        else if (hasGoalDate) Color(0xFF1B5E20)
        else MaterialTheme.colorScheme.onSurface
    val activityMarker = if (selectedWithoutGoal) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.secondary
    val coachMarker = if (selectedWithoutGoal) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.tertiary

    Column(
        modifier = Modifier
            .weight(1f)
            .padding(2.dp)
            .height(64.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(cellBackground)
            .border(
                width = if (isSelected) 2.dp else 0.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
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
            Row(
                modifier = Modifier.padding(top = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                repeat(activityCount) { MarkerDot(activityMarker, 5.dp) }
                if (hasCoachPlan) MarkerDot(coachMarker)
                if (hasGoalDate) MarkerDot(goalGreen)
            }
        }
    }
}

@Composable
private fun MarkerDot(color: Color, size: Dp = 7.dp) {
    Box(modifier = Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
private fun CalendarLegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MarkerDot(color)
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CoachPlanCard(session: CoachPlannedSession) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(session.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            val activityLabel = if (session.isRunning) "Lauftraining" else "Erholung / Mobility"
            Text("$activityLabel · ${session.durationMinutes} Min.", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (session.isRunning) {
                Text(
                    "Pulsziel · ${session.heartRateTarget ?: "Zone 2 · lockeres Gesprächstempo"}",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            } else {
                Text("Ohne Lauf-Pulsziel; sanfte Bewegung.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            Text(session.description, fontSize = 14.sp, color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}
