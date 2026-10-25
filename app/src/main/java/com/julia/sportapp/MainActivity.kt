package com.julia.sportapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.julia.sportapp.ui.theme.SportAppTheme
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
    // Merkt sich, welcher Bereich gerade offen ist
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
            Screen.Start -> StartContent(Modifier.padding(innerPadding))
            else -> PlaceholderContent(current.label, Modifier.padding(innerPadding))
        }
    }
}

@Composable
fun StartContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Sport App",
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Hallo Julia! Bereit für deine nächste Einheit?",
            fontSize = 20.sp
        )
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