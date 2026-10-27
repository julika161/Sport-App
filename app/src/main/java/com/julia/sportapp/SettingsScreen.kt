package com.julia.sportapp

import android.content.Context
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
import com.julia.sportapp.data.ActivityDatabase

// Einstellungen: Garmin-Verbindung und Löschen aller Daten
@Composable
fun SettingsContent(db: ActivityDatabase, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var resetCount by remember { mutableStateOf(0) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Seitentitel: 28 sp, fett (einheitlich auf allen Seiten)
        Text("Einstellungen", fontSize = 28.sp, fontWeight = FontWeight.Bold)

        // key() baut die Karte neu auf, damit der gelöschte Schlüssel nicht mehr angezeigt wird
        key(resetCount) {
            GarminSyncCard(db) { }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Daten löschen", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Entfernt alle gespeicherten Einheiten und den Garmin-Schlüssel von diesem Handy. Dein Garmin-Konto bleibt unverändert.",
                    fontSize = 16.sp
                )
                Button(
                    onClick = { showDialog = true },
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text("Alle Daten löschen", fontSize = 16.sp)
                }
                if (message.isNotEmpty()) {
                    Text(message, fontSize = 16.sp)
                }
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Wirklich alle Daten löschen?") },
            text = {
                Text("Das kann nicht rückgängig gemacht werden. Danach musst du Garmin erneut synchronisieren, um Einheiten zu sehen.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteAllData(context, db)
                        showDialog = false
                        resetCount++
                        message = "Alle Daten wurden gelöscht."
                    }
                ) {
                    Text("Ja, löschen")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Abbrechen")
                }
            }
        )
    }
}

// Löscht alle Einheiten, den Garmin-Schlüssel und die gemerkten Abzeichen
private fun deleteAllData(context: Context, db: ActivityDatabase) {
    db.deleteAll()
    context.getSharedPreferences("sportapp_garmin", Context.MODE_PRIVATE).edit().clear().apply()
    context.getSharedPreferences("sportapp_prefs", Context.MODE_PRIVATE).edit().clear().apply()
}
