package com.julia.sportapp.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate
import java.time.LocalTime

// Speichert die Einheiten dauerhaft auf dem Handy
class ActivityDatabase(context: Context) : SQLiteOpenHelper(context, "sportapp.db", null, 2) {

    // Wird beim ersten Start einmal ausgeführt: Tabelle anlegen
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE activities (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                type TEXT NOT NULL,
                date TEXT NOT NULL,
                time TEXT NOT NULL,
                durationMinutes INTEGER NOT NULL,
                distanceKm REAL,
                garminId TEXT
            )"""
        )
    }

    // Version 1 -> 2: nur eine Spalte ergänzen, bestehende Einträge bleiben erhalten
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE activities ADD COLUMN garminId TEXT")
        }
    }

    // Eine Einheit speichern (wird nur vom Garmin-Import genutzt)
    fun insert(activity: Activity) {
        val values = ContentValues().apply {
            put("type", activity.type.name)
            put("date", activity.date.toString())
            put("time", activity.time.toString())
            put("durationMinutes", activity.durationMinutes)
            put("distanceKm", activity.distanceKm)
            put("garminId", activity.garminId)
        }
        writableDatabase.insert("activities", null, values)
    }

    // Alle Einheiten laden, neueste zuerst
    fun getAll(): List<Activity> {
        val result = mutableListOf<Activity>()
        readableDatabase.rawQuery(
            "SELECT type, date, time, durationMinutes, distanceKm, garminId FROM activities ORDER BY date DESC, time DESC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    Activity(
                        type = ActivityType.valueOf(cursor.getString(0)),
                        date = LocalDate.parse(cursor.getString(1)),
                        time = LocalTime.parse(cursor.getString(2)),
                        durationMinutes = cursor.getInt(3),
                        distanceKm = if (cursor.isNull(4)) null else cursor.getDouble(4),
                        garminId = if (cursor.isNull(5)) null else cursor.getString(5)
                    )
                )
            }
        }
        return result
    }

    // Alle Einheiten löschen
    fun deleteAll() {
        writableDatabase.delete("activities", null, null)
    }
}
