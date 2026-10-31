package com.julia.sportapp

import android.content.Context
import com.julia.sportapp.data.Activity
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

internal data class CoachPlannedSession(
    val title: String,
    val durationMinutes: Int,
    val description: String,
    val isRunning: Boolean,
    val date: LocalDate?,
    val heartRateTarget: String? = null
)

private data class CoachWorkoutTemplate(
    val title: String,
    val durationMinutes: Int,
    val description: String,
    val isRunning: Boolean = true
)

private const val COACH_SCHEDULE_PREFS = "running_coach_local"
private const val COACH_SCHEDULE_KEY = "planned_calendar_sessions"
private val coachWeekdayCodes = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")

internal fun parseCoachTargetDate(text: String): LocalDate? {
    val value = text.trim()
    if (value.isBlank()) return null
    return runCatching {
        LocalDate.parse(value, DateTimeFormatter.ofPattern("d.M.uuuu", Locale.GERMAN))
    }.getOrNull() ?: runCatching {
        LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE)
    }.getOrNull()
}

internal fun loadCoachTargetDate(context: Context): LocalDate? {
    val prefs = context.getSharedPreferences(COACH_SCHEDULE_PREFS, Context.MODE_PRIVATE)
    return parseCoachTargetDate(prefs.getString("target_date", "").orEmpty())
}

internal fun loadCoachGoalSummary(context: Context): String? {
    val prefs = context.getSharedPreferences(COACH_SCHEDULE_PREFS, Context.MODE_PRIVATE)
    val distance = prefs.getString("goal_distance_km", "").orEmpty().trim()
    val time = prefs.getString("goal_time_minutes", "").orEmpty().trim()
    val parts = listOfNotNull(
        distance.takeIf { it.isNotBlank() }?.let { "$it km" },
        time.takeIf { it.isNotBlank() }?.let { "$it Min." }
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

internal fun coachZoneTwoTarget(zones: List<HeartRateZone>): String {
    val zone = zones.firstOrNull { it.number == 2 }
        ?: return "Zone 2 · lockeres Gesprächstempo; Garmin-Bereich nicht verfügbar"
    return when {
        zone.minBpm != null && zone.maxBpm != null -> "Zone 2 · ${zone.minBpm}–${zone.maxBpm} bpm"
        zone.minBpm != null -> "Zone 2 · ab ${zone.minBpm} bpm"
        zone.maxBpm != null -> "Zone 2 · bis ${zone.maxBpm} bpm"
        else -> "Zone 2 · lockeres Gesprächstempo; Garmin-Bereich nicht verfügbar"
    }
}

// Gemeinsame Vorschläge für Coach-Seite und Kalender, damit beide dieselben Einheiten zeigen.
internal fun buildCoachSessions(
    recentRuns: List<Activity>,
    availableDays: Set<String>,
    targetDate: LocalDate?,
    heartRateTarget: String,
    fromDate: LocalDate = LocalDate.now()
): List<CoachPlannedSession> {
    val recent = recentRuns.filter {
        !it.date.isBefore(fromDate.minusDays(28)) && !it.date.isAfter(fromDate)
    }.take(3)
    val averageMinutes = recent.map { run ->
        run.durationSeconds?.toDouble()?.div(60.0) ?: run.durationMinutes.toDouble()
    }.average().takeIf { it.isFinite() }?.roundToInt()

    val workouts = if (recent.size >= 2 && averageMinutes != null) {
        val longestRecent = recent.maxOf { it.durationMinutes }
        listOf(
            CoachWorkoutTemplate(
                "Locker laufen",
                roundToFive((averageMinutes * 0.75).roundToInt()).coerceIn(15, 40),
                "Bequem bleiben und das Zieltempo nicht erzwingen."
            ),
            CoachWorkoutTemplate(
                "Ruhiger Dauerlauf",
                roundToFive(averageMinutes).coerceIn(20, 45),
                "Gleichmäßig laufen; bei Müdigkeit kürzer machen."
            ),
            CoachWorkoutTemplate(
                "Etwas länger, weiterhin locker",
                (longestRecent + 5).coerceIn(15, 60),
                "Nur steigern, wenn sich die vorherigen Läufe gut angefühlt haben."
            )
        )
    } else {
        listOf(
            CoachWorkoutTemplate(
                "Locker starten", 20,
                "Lauf-/Geh-Wechsel: 1 Min. locker traben, 2 Min. gehen."
            ),
            CoachWorkoutTemplate(
                "Erholung & Bewegung", 15,
                "Spaziergang oder sanfte Mobility; ein Ruhetag ist auch Training.",
                isRunning = false
            ),
            CoachWorkoutTemplate(
                "Lauf-/Geh-Wechsel wiederholen", 20,
                "Nur wiederholen, wenn sich der erste Lauf gut angefühlt hat."
            )
        )
    }

    val dates = findUpcomingCoachDates(availableDays, targetDate, fromDate)
    val scheduled = dates.mapIndexed { index, date ->
        val workout = workouts[index % workouts.size]
        workout.toSession(date, heartRateTarget)
    }
    val displayedSuggestions = workouts.drop(scheduled.size.coerceAtMost(workouts.size))
        .map { workout -> workout.toSession(null, heartRateTarget) }
    return scheduled + displayedSuggestions
}

private fun findUpcomingCoachDates(
    availableDays: Set<String>,
    targetDate: LocalDate?,
    fromDate: LocalDate
): List<LocalDate> {
    if (availableDays.isEmpty()) return emptyList()
    val lastTrainingDate = if (targetDate != null) {
        targetDate.minusDays(1) // Zieltag bleibt für den Wettkampf oder das persönliche Ziel frei.
    } else {
        fromDate.plusDays(28)
    }
    if (lastTrainingDate.isBefore(fromDate.plusDays(1))) return emptyList()

    val dates = mutableListOf<LocalDate>()
    var date = fromDate.plusDays(1)
    while (!date.isAfter(lastTrainingDate)) {
        val weekdayCode = coachWeekdayCodes[date.dayOfWeek.value - 1]
        if (weekdayCode in availableDays) dates.add(date)
        date = date.plusDays(1)
    }
    return dates
}

private fun CoachWorkoutTemplate.toSession(
    date: LocalDate?,
    heartRateTarget: String
): CoachPlannedSession = CoachPlannedSession(
    title = title,
    durationMinutes = durationMinutes,
    description = description,
    isRunning = isRunning,
    date = date,
    heartRateTarget = heartRateTarget
)

internal fun saveCoachSchedule(context: Context, sessions: List<CoachPlannedSession>) {
    val json = JSONArray()
    sessions.filter { it.date != null }.forEach { session ->
        json.put(
            JSONObject()
                .put("date", session.date.toString())
                .put("title", session.title)
                .put("durationMinutes", session.durationMinutes)
                .put("description", session.description)
                .put("isRunning", session.isRunning)
                .put("heartRateTarget", session.heartRateTarget)
        )
    }
    context.getSharedPreferences(COACH_SCHEDULE_PREFS, Context.MODE_PRIVATE)
        .edit().putString(COACH_SCHEDULE_KEY, json.toString()).apply()
}

internal fun loadCoachSchedule(context: Context): List<CoachPlannedSession> {
    val raw = context.getSharedPreferences(COACH_SCHEDULE_PREFS, Context.MODE_PRIVATE)
        .getString(COACH_SCHEDULE_KEY, "[]") ?: "[]"
    val json = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
    val sessions = mutableListOf<CoachPlannedSession>()
    for (index in 0 until json.length()) {
        val item = json.optJSONObject(index) ?: continue
        val date = runCatching { LocalDate.parse(item.optString("date")) }.getOrNull() ?: continue
        sessions.add(
            CoachPlannedSession(
                title = item.optString("title", "Coach-Training"),
                durationMinutes = item.optInt("durationMinutes", 0),
                description = item.optString("description", ""),
                isRunning = item.optBoolean("isRunning", true),
                date = date,
                heartRateTarget = item.optString("heartRateTarget").takeIf { it.isNotBlank() }
            )
        )
    }
    return sessions.filter { session -> session.date?.isBefore(LocalDate.now()) != true }
        .sortedBy { it.date }
}

private fun roundToFive(minutes: Int): Int = ((minutes + 2) / 5) * 5
