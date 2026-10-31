package com.julia.sportapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.julia.sportapp.data.Activity
import com.julia.sportapp.data.ActivityDatabase
import com.julia.sportapp.ui.theme.SportAppTheme
import java.time.format.DateTimeFormatter

// Datums- und Zeitformate für die Anzeige (deutsch).
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

// Die Bereiche der App. Labels dienen der Barrierefreiheit, werden aber nicht unter den Icons gezeigt.
enum class Screen(val label: String, val icon: ImageVector) {
    Start("Ziele", Icons.Filled.Flag),
    Laufcoach("Laufcoach", Icons.Filled.DirectionsRun),
    Laeufe("Läufe", Icons.Filled.History),
    Koerper("Gesundheit", Icons.Filled.Favorite),
    Kalender("Kalender", Icons.Filled.DateRange),
    Einstellungen("Einstellungen", Icons.Filled.Settings)
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
                        icon = {
                            Icon(
                                imageVector = screen.icon,
                                contentDescription = screen.label,
                                modifier = Modifier.size(24.dp)
                            )
                        },
                        label = null
                    )
                }
            }
        }
    ) { innerPadding ->
        when (current) {
            Screen.Start -> StartContent(db, Modifier.padding(innerPadding))
            Screen.Laufcoach -> RunningCoachContent(db, Modifier.padding(innerPadding))
            Screen.Laeufe -> RunHistoryContent(db, Modifier.padding(innerPadding))
            Screen.Koerper -> BodyContent(Modifier.padding(innerPadding))
            Screen.Kalender -> CalendarContent(db, Modifier.padding(innerPadding))
            Screen.Einstellungen -> SettingsContent(db, Modifier.padding(innerPadding))
        }
    }
}

// Ziele-Startseite.
@Composable
fun StartContent(db: ActivityDatabase, modifier: Modifier = Modifier) {
    val entries = remember { db.getAll() }
    WeeklyGoalContent(entries, modifier)
}

// Eine Zeile in einer Liste von Einheiten (z. B. im Kalender).
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
