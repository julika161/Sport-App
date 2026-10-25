package com.julia.sportapp

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.julia.sportapp.data.Activity
import com.julia.sportapp.data.ActivityDatabase
import com.julia.sportapp.data.ActivityType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

// Wochenziel: so viele Einheiten pro Woche
const val WEEKLY_GOAL = 3

// Ein Abzeichen mit Name, Beschreibung und Status
data class Badge(
    val name: String,
    val description: String,
    val symbol: String,
    val unlocked: Boolean
)

// Montag der Woche, in der das Datum liegt
fun weekStart(date: LocalDate): LocalDate =
    date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

// Wie viele Einheiten in der aktuellen Woche?
fun thisWeekCount(activities: List<Activity>): Int {
    val thisWeek = weekStart(LocalDate.now())
    return activities.count { weekStart(it.date) == thisWeek }
}

// Längste Serie von aufeinanderfolgenden Wochen mit mindestens einer Einheit
fun longestWeekStreak(activities: List<Activity>): Int {
    val weeks = activities.map { weekStart(it.date) }.distinct().sorted()
    var best = 0
    var run = 0
    var prev: LocalDate? = null
    for (w in weeks) {
        run = if (prev != null && prev.plusWeeks(1) == w) run + 1 else 1
        best = maxOf(best, run)
        prev = w
    }
    return best
}

// Serie in Tagen: zählt rückwärts ab heute (oder gestern, falls heute noch nichts war)
fun currentDayStreak(activities: List<Activity>): Int {
    val days = activities.map { it.date }.toSet()
    var day = LocalDate.now()
    if (day !in days) day = day.minusDays(1)
    var count = 0
    while (day in days) {
        count++
        day = day.minusDays(1)
    }
    return count
}

// Alle Abzeichen, berechnet aus den gespeicherten Einheiten
fun computeBadges(activities: List<Activity>): List<Badge> {
    val total = activities.size
    val yoga = activities.count { it.type == ActivityType.YOGA }
    val runs = activities.count { it.type == ActivityType.LAUFEN }
    val km = activities.mapNotNull { it.distanceKm }.sum()
    val bestMonth = activities.groupingBy { YearMonth.from(it.date) }.eachCount().values.maxOrNull() ?: 0
    val bestWeek = activities.groupingBy { weekStart(it.date) }.eachCount().values.maxOrNull() ?: 0
    val weeksInRow = longestWeekStreak(activities)

    return listOf(
        Badge("Erste Einheit", "Du hast deine erste Einheit erfasst.", "🥇", total >= 1),
        Badge("Wochenziel geschafft", "$WEEKLY_GOAL Einheiten in einer Woche.", "🎯", bestWeek >= WEEKLY_GOAL),
        Badge("Fleißig im Monat", "10 Einheiten in einem Monat.", "🔥", bestMonth >= 10),
        Badge("Drei Wochen am Stück", "In 3 Wochen hintereinander mindestens eine Einheit.", "📅", weeksInRow >= 3),
        Badge("Yoga-Meister", "10 Yoga-Einheiten insgesamt.", "🧘", yoga >= 10),
        Badge("Läufer", "10 Läufe insgesamt.", "🏃", runs >= 10),
        Badge("100 Kilometer", "100 km Strecke insgesamt.", "🏅", km >= 100.0)
    )
}

// Gespeicherte Liste der Abzeichen, die schon gefeiert wurden
private const val PREFS_NAME = "sportapp_prefs"
private const val KEY_SEEN = "seen_badges"

fun loadSeen(context: Context): Set<String> =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getStringSet(KEY_SEEN, emptySet()) ?: emptySet()

fun saveSeen(context: Context, seen: Set<String>) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putStringSet(KEY_SEEN, seen)
        .apply()
}

// Startbildschirm: Wochenziel, Serie und Feier bei neuem Abzeichen
@Composable
fun StartMotivation(activities: List<Activity>) {
    val context = LocalContext.current
    val badges = computeBadges(activities)
    var seen by remember { mutableStateOf(loadSeen(context)) }

    // Erstes freigeschaltetes Abzeichen, das noch nicht gefeiert wurde
    val pending = badges.firstOrNull { it.unlocked && it.name !in seen }

    fun markSeen(name: String) {
        val updated = seen + name
        seen = updated
        saveSeen(context, updated)
    }

    if (pending != null) {
        AlertDialog(
            onDismissRequest = { markSeen(pending.name) },
            title = { Text("🎉 Neues Abzeichen!", fontSize = 22.sp) },
            text = {
                Text("${pending.symbol} ${pending.name}\n${pending.description}", fontSize = 18.sp)
            },
            confirmButton = {
                Button(onClick = { markSeen(pending.name) }) {
                    Text("Super!", fontSize = 18.sp)
                }
            }
        )
    }

    val done = thisWeekCount(activities)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Wochenziel", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("$done von $WEEKLY_GOAL Einheiten diese Woche", fontSize = 18.sp)
            CountBar(fraction = (done.toFloat() / WEEKLY_GOAL).coerceAtMost(1f))
            Text("🔥 Serie: ${currentDayStreak(activities)} Tage in Folge", fontSize = 18.sp)
        }
    }
}

// Der Abzeichen-Bereich: alle Abzeichen, freigeschaltet oder gesperrt
@Composable
fun BadgesContent(db: ActivityDatabase, modifier: Modifier = Modifier) {
    val activities = remember { db.getAll() }
    val badges = computeBadges(activities)
    val unlockedCount = badges.count { it.unlocked }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Abzeichen", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("$unlockedCount von ${badges.size} freigeschaltet", fontSize = 20.sp)
        CountBar(fraction = unlockedCount.toFloat() / badges.size)
        badges.forEach { BadgeCard(it) }
    }
}

// Eine Karte für ein Abzeichen
@Composable
fun BadgeCard(badge: Badge) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(if (badge.unlocked) badge.symbol else "🔒", fontSize = 32.sp)
            Column {
                Text(badge.name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(badge.description, fontSize = 16.sp)
                Text(
                    if (badge.unlocked) "Freigeschaltet" else "Noch nicht erreicht",
                    fontSize = 14.sp
                )
            }
        }
    }
}
