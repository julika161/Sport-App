package com.julia.sportapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.julia.sportapp.data.Activity
import com.julia.sportapp.data.ActivityDatabase
import com.julia.sportapp.ui.theme.SportAppTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.format.DateTimeFormatter

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
    Einstellungen("Einstellungen", "⚙️")
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
            Screen.Einstellungen -> SettingsContent(db, Modifier.padding(innerPadding))
        }
    }
}

// Startbildschirm: Tageswerte von Garmin, darunter das Wochenziel
@Composable
fun StartContent(db: ActivityDatabase, modifier: Modifier = Modifier) {
    val entries = remember { db.getAll() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Sport App", fontSize = 36.sp, fontWeight = FontWeight.Bold)

        Text("Hallo Julia! Bereit für deine nächste Einheit?", fontSize = 20.sp)

        DailyOverview()

        WeeklyGoalCard(entries)
    }
}

// Eine Zeile in einer Liste von Einheiten (z. B. im Kalender)
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
