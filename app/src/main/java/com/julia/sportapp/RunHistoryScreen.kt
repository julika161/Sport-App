package com.julia.sportapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.julia.sportapp.data.Activity
import com.julia.sportapp.data.ActivityDatabase
import com.julia.sportapp.data.RunDetail
import com.julia.sportapp.data.RunLap
import java.util.Locale

// Liste und antippbare Detailseite vergangener Läufe.
@Composable
fun RunHistoryContent(db: ActivityDatabase, modifier: Modifier = Modifier) {
    val runs = remember(db) { db.getAll().filter { it.type == com.julia.sportapp.data.ActivityType.LAUFEN } }
    val details = remember(db) { db.getAllRunDetails() }
    var selectedGarminId by remember { mutableStateOf<String?>(null) }
    val selectedRun = runs.firstOrNull { it.garminId == selectedGarminId }

    if (selectedRun != null) {
        RunDetailContent(
            activity = selectedRun,
            detail = selectedRun.garminId?.let { details[it] },
            onBack = { selectedGarminId = null },
            modifier = modifier
        )
    } else {
        RunListContent(
            runs = runs,
            details = details,
            onRunClick = { run -> selectedGarminId = run.garminId },
            modifier = modifier
        )
    }
}

@Composable
private fun RunListContent(
    runs: List<Activity>,
    details: Map<String, RunDetail>,
    onRunClick: (Activity) -> Unit,
    modifier: Modifier
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Läufe", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Deine Laufhistorie · Kennzahlen bleiben auf diesem Gerät.", fontSize = 15.sp)

        if (runs.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Noch keine Läufe gespeichert", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("Synchronisiere deine Garmin-Aktivitäten unter Einstellungen, um Läufe hier zu sehen.", fontSize = 16.sp)
                }
            }
        } else {
            Text("${runs.size} ${if (runs.size == 1) "Lauf" else "Läufe"}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            runs.forEach { run ->
                RunListCard(run, run.garminId?.let { details[it] }, onClick = { onRunClick(run) })
            }
        }
    }
}

@Composable
private fun RunListCard(run: Activity, detail: RunDetail?, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${run.type.symbol} ${run.date.format(dateFormatter)}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(run.time.format(timeFormatter), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(run.distanceKm?.let { "%.2f km".format(Locale.GERMAN, it) } ?: "Strecke n/a", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("${run.durationMinutes} Min.", fontSize = 15.sp)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        detail?.averagePaceSecondsPerKm?.let { formatRunPace(it) } ?: "Tempo n/a",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text("Ø-Tempo", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("Tippen für Laufdetails  ›", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun RunDetailContent(
    activity: Activity,
    detail: RunDetail?,
    onBack: () -> Unit,
    modifier: Modifier
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
            Text("‹ Zurück zu Läufen", fontSize = 16.sp)
        }
        Text("Laufdetails", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("${activity.date.format(dateFormatter)} · ${activity.time.format(timeFormatter)}", fontSize = 16.sp)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Übersicht", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                RunMetricRow("Distanz", activity.distanceKm?.let { "%.2f km".format(Locale.GERMAN, it) } ?: null)
                RunMetricRow("Dauer", detail?.durationSeconds?.let { formatRunDuration(it) } ?: "${activity.durationMinutes} Min.")
                RunMetricRow("Ø-Tempo", detail?.averagePaceSecondsPerKm?.let { "${formatRunPace(it)} min/km" })
                RunMetricRow("Geschwindigkeit", detail?.averageSpeedKmh?.let { "%.1f km/h".format(Locale.GERMAN, it) })
            }
        }

        if (detail == null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Für diesen Lauf sind noch keine Garmin-FIT-Details gespeichert. Öffne Einstellungen und synchronisiere Garmin erneut.",
                    modifier = Modifier.padding(16.dp),
                    fontSize = 15.sp
                )
            }
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Herzfrequenz & Laufwerte", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    RunMetricRow("Durchschnittspuls", detail.averageHeartRate?.let { "$it bpm" })
                    RunMetricRow("Maximalpuls", detail.maximumHeartRate?.let { "$it bpm" })
                    RunMetricRow("Höhenmeter", detail.ascentMeters?.let { "$it m" })
                    RunMetricRow("Ø-Kadenz", detail.averageCadenceSpm?.let { "$it Schritte/Min." })
                    if (detail.averageHeartRate == null && detail.maximumHeartRate == null &&
                        detail.ascentMeters == null && detail.averageCadenceSpm == null
                    ) {
                        Text("Garmin hat für diese Aktivität keine dieser Zusatzmessungen geliefert.", fontSize = 14.sp)
                    }
                }
            }

            if (detail.laps.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Runden / Splits", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        detail.laps.forEach { lap -> RunLapRow(lap) }
                    }
                }
            }
        }

        Text("Es werden keine GPS-Punkte oder Routenkarten angezeigt.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RunMetricRow(label: String, value: String?) {
    if (value != null) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 16.sp)
            Text(value, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun RunLapRow(lap: RunLap) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Runde ${lap.number}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(lap.distanceMeters?.let { "%.2f km".format(Locale.GERMAN, it / 1000.0) } ?: "Strecke n/a", fontSize = 15.sp)
        }
        val details = listOfNotNull(
            lap.durationSeconds?.let { formatRunDuration(it) },
            lap.averagePaceSecondsPerKm?.let { "${formatRunPace(it)} min/km" },
            lap.averageHeartRate?.let { "$it bpm" }
        )
        if (details.isNotEmpty()) Text(details.joinToString(" · "), fontSize = 14.sp)
        HorizontalDivider()
    }
}

private fun formatRunPace(secondsPerKm: Int): String {
    val minutes = secondsPerKm.coerceAtLeast(0) / 60
    val seconds = (secondsPerKm.coerceAtLeast(0) % 60).toString().padStart(2, '0')
    return "$minutes:$seconds"
}

private fun formatRunDuration(seconds: Int): String {
    val safeSeconds = seconds.coerceAtLeast(0)
    val hours = safeSeconds / 3600
    val minutes = (safeSeconds % 3600) / 60
    val remainingSeconds = safeSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remainingSeconds)
    else "%d:%02d Min.".format(minutes, remainingSeconds)
}
