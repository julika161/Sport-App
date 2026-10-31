package com.julia.sportapp

import android.content.Context
import com.julia.sportapp.data.Activity
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.roundToInt

internal data class CoachPlannedSession(
    val title: String,
    val durationMinutes: Int,
    val description: String,
    val isRunning: Boolean,
    val date: LocalDate?
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

// Gemeinsame Vorschläge für Coach-Seite und Kalender, damit beide dieselben Einheiten zeigen.
internal fun buildCoachSessions(
    recentRuns: List<Activity>,
    availableDays: Set<String>,
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

    val dates = findUpcomingCoachDates(availableDays, workouts.size, fromDate)
    return workouts.mapIndexed { index, workout ->
        CoachPlannedSession(
            title = workout.title,
            durationMinutes = workout.durationMinutes,
            description = workout.description,
            isRunning = workout.isRunning,
            date = dates.getOrNull(index)
        )
    }
}

private fun findUpcomingCoachDates(
    availableDays: Set<String>,
    count: Int,
    fromDate: LocalDate
): List<LocalDate> {
    if (availableDays.isEmpty()) return emptyList()
    val dates = mutableListOf<LocalDate>()
    var offset = 1L
    while (dates.size < count && offset <= 28L) {
        val date = fromDate.plusDays(offset)
        val weekdayCode = coachWeekdayCodes[date.dayOfWeek.value - 1]
        if (weekdayCode in availableDays) dates.add(date)
        offset++
    }
    return dates
}

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
                date = date
            )
        )
    }
    return sessions.sortedBy { it.date }
}

private fun roundToFive(minutes: Int): Int = ((minutes + 2) / 5) * 5
