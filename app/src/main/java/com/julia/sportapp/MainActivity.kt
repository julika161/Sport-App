package com.julia.sportapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.julia.sportapp.data.Activity
import com.julia.sportapp.data.ActivityDatabase
import com.julia.sportapp.data.ActivityType
import com.julia.sportapp.ui.theme.SportAppTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// Datums- und Zeitformate für die Anzeige (deutsch)
val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SportAppTheme {
                SportAppScreen()
            }
        }
    }
}

// Die vier Bereiche der App (unten in der Leiste)
enum class Screen(val label: String, val symbol: String) {
    Start("Start", "🏠"),
    Kalender("Kalender", "📅"),
    Auswertung("Auswertung", "📊"),
    Abzeichen("Abzeichen", "🏅")
}

@Composable
fun SportAppScreen() {
    val context = LocalContext.current
    val db = remember { ActivityDatabase(context) }
    var current by remember { mutableStateOf(Screen.Start) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Screen.entries.forEach { screen ->
                    NavigationBarItem(
                        selected = current == screen,
                        onClick = { current = screen },
                        icon = { Text(screen.symbol, fontSize = 22.sp) },
                        label = { Text(screen.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        when (current) {
            Screen.Start -> StartContent(db, Modifier.padding(innerPadding))
            Screen.Kalender -> CalendarContent(db, Modifier.padding(innerPadding))
            Screen.Auswertung -> StatsContent(db, Modifier.padding(innerPadding))
            Screen.Abzeichen -> BadgesContent(db, Modifier.padding(innerPadding))
            else -> PlaceholderContent(current.label, Modifier.padding(innerPadding))
        }
    }
}

// Startbildschirm: Begrüßung, Button und letzte Einheiten
@Composable
fun StartContent(db: ActivityDatabase, modifier: Modifier = Modifier) {
    var showForm by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf(db.getAll()) }

    if (showForm) {
        ActivityForm(
            db = db,
            modifier = modifier,
            onSaved = {
                entries = db.getAll()
                showForm = false
            },
            onCancel = { showForm = false }
        )
    } else {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Sport App", fontSize = 36.sp, fontWeight = FontWeight.Bold)
            Text("Hallo Julia! Bereit für deine nächste Einheit?", fontSize = 20.sp)

            Button(
                onClick = { showForm = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            ) {
                Text("+ Neue Einheit erfassen", fontSize = 20.sp)
            }
            StartMotivation(entries)
            Text("Letzte Einheiten", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            if (entries.isEmpty()) {
                Text("Noch keine Einheiten. Erfasse deine erste!", fontSize = 18.sp)
            }
            entries.take(5).forEach { ActivityRow(it) }
        }
    }
}

// Formular zum Erfassen einer Einheit
@Composable
fun ActivityForm(
    db: ActivityDatabase,
    modifier: Modifier,
    onSaved: () -> Unit,
    onCancel: () -> Unit
) {
    var type by remember { mutableStateOf(ActivityType.LAUFEN) }
    var date by remember { mutableStateOf(LocalDate.now().format(dateFormatter)) }
    var time by remember {
        mutableStateOf(LocalTime.now().truncatedTo(ChronoUnit.MINUTES).format(timeFormatter))
    }
    var duration by remember { mutableStateOf("") }
    var distance by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Neue Einheit", fontSize = 30.sp, fontWeight = FontWeight.Bold)

        Text("Sportart", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        ActivityType.entries.chunked(2).forEach { pair ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                pair.forEach { t ->
                    TypeButton(
                        activityType = t,
                        selected = type == t,
                        onClick = { type = t },
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                    )
                }
            }
        }

        OutlinedTextField(
            value = date,
            onValueChange = { date = it },
            label = { Text("Datum (TT.MM.JJJJ)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = time,
            onValueChange = { time = it },
            label = { Text("Uhrzeit (HH:MM)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = duration,
            onValueChange = { duration = it },
            label = { Text("Dauer in Minuten") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = distance,
            onValueChange = { distance = it },
            label = { Text("Distanz in km (optional)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )

        if (error.isNotEmpty()) {
            Text(error, color = MaterialTheme.colorScheme.error, fontSize = 16.sp)
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                onClick = {
                    val parsedDate = runCatching { LocalDate.parse(date, dateFormatter) }.getOrNull()
                    val parsedTime = runCatching { LocalTime.parse(time, timeFormatter) }.getOrNull()
                    val minutes = duration.toIntOrNull()
                    val km = distance.trim().replace(',', '.').toDoubleOrNull()

                    if (parsedDate == null) {
                        error = "Bitte das Datum als TT.MM.JJJJ eingeben."
                        return@Button
                    }
                    if (parsedTime == null) {
                        error = "Bitte die Uhrzeit als HH:MM eingeben."
                        return@Button
                    }
                    if (minutes == null || minutes <= 0) {
                        error = "Bitte eine Dauer in Minuten eingeben."
                        return@Button
                    }
                    if (distance.isNotBlank() && km == null) {
                        error = "Distanz bitte als Zahl eingeben, z. B. 5,2."
                        return@Button
                    }

                    db.insert(
                        Activity(
                            type = type,
                            date = parsedDate,
                            time = parsedTime,
                            durationMinutes = minutes,
                            distanceKm = if (distance.isBlank()) null else km
                        )
                    )
                    onSaved()
                },
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
            ) {
                Text("Speichern", fontSize = 18.sp)
            }

            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
            ) {
                Text("Abbrechen", fontSize = 18.sp)
            }
        }
    }
}

// Ein Sportart-Knopf: ausgefüllt, wenn ausgewählt, sonst nur umrandet
@Composable
fun TypeButton(
    activityType: ActivityType,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) {
            Text("${activityType.symbol} ${activityType.label}", fontSize = 16.sp)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) {
            Text("${activityType.symbol} ${activityType.label}", fontSize = 16.sp)
        }
    }
}

// Eine Zeile in der Liste der letzten Einheiten
@Composable
fun ActivityRow(a: Activity) {
    val km = a.distanceKm?.let { " · %.1f km".format(it) } ?: ""
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "${a.type.symbol}  ${a.type.label}",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "${a.date.format(dateFormatter)} um ${a.time.format(timeFormatter)} · ${a.durationMinutes} Min.$km",
                fontSize = 16.sp
            )
        }
    }
}

@Composable
fun PlaceholderContent(title: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "$title kommt in einem späteren Block",
            fontSize = 18.sp
        )
    }
}