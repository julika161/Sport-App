package com.julia.sportapp.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

// Speichert Trainings und ausgewählte Laufwerte dauerhaft und nur lokal auf dem Handy.
class ActivityDatabase(context: Context) : SQLiteOpenHelper(context, "sportapp.db", null, 4) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE activities (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                type TEXT NOT NULL,
                date TEXT NOT NULL,
                time TEXT NOT NULL,
                durationMinutes INTEGER NOT NULL,
                distanceKm REAL,
                garminId TEXT,
                durationSeconds INTEGER
            )"""
        )
        createRunDetailsTable(db)
    }

    // Bestehende Trainings bleiben bei App-Aktualisierungen erhalten.
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE activities ADD COLUMN garminId TEXT")
        }
        if (oldVersion < 3) {
            createRunDetailsTable(db)
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE activities ADD COLUMN durationSeconds INTEGER")
        }
    }

    private fun createRunDetailsTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS run_details (
                garminId TEXT PRIMARY KEY NOT NULL,
                durationSeconds INTEGER,
                averagePaceSecondsPerKm INTEGER,
                averageSpeedKmh REAL,
                averageHeartRate INTEGER,
                maximumHeartRate INTEGER,
                ascentMeters INTEGER,
                averageCadenceSpm INTEGER,
                lapsJson TEXT NOT NULL DEFAULT '[]'
            )"""
        )
    }

    fun insert(activity: Activity) {
        writableDatabase.insert("activities", null, activityValues(activity))
    }

    // Bei erneuter Garmin-Synchronisierung werden vorhandene Garmin-Einträge mit exakten Sekunden ergänzt.
    fun updateImportedActivity(activity: Activity) {
        val garminId = activity.garminId ?: return
        writableDatabase.update(
            "activities", activityValues(activity), "garminId = ?", arrayOf(garminId)
        )
    }

    private fun activityValues(activity: Activity) = ContentValues().apply {
        put("type", activity.type.name)
        put("date", activity.date.toString())
        put("time", activity.time.toString())
        put("durationMinutes", activity.durationMinutes)
        put("distanceKm", activity.distanceKm)
        put("garminId", activity.garminId)
        put("durationSeconds", activity.durationSeconds)
    }

    fun getAll(): List<Activity> {
        val result = mutableListOf<Activity>()
        readableDatabase.rawQuery(
            "SELECT type, date, time, durationMinutes, distanceKm, garminId, durationSeconds FROM activities ORDER BY date DESC, time DESC",
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
                        garminId = if (cursor.isNull(5)) null else cursor.getString(5),
                        durationSeconds = if (cursor.isNull(6)) null else cursor.getInt(6)
                    )
                )
            }
        }
        return result
    }

    // FIT-Dateien werden verworfen; in der Datenbank bleiben nur Kennzahlen und Runden.
    fun saveRunDetail(detail: RunDetail) {
        val laps = JSONArray()
        detail.laps.forEach { lap ->
            laps.put(
                JSONObject()
                    .put("number", lap.number)
                    .putNullable("distanceMeters", lap.distanceMeters)
                    .putNullable("durationSeconds", lap.durationSeconds)
                    .putNullable("averagePaceSecondsPerKm", lap.averagePaceSecondsPerKm)
                    .putNullable("averageHeartRate", lap.averageHeartRate)
                    .putNullable("ascentMeters", lap.ascentMeters)
            )
        }
        val values = ContentValues().apply {
            put("garminId", detail.garminId)
            put("durationSeconds", detail.durationSeconds)
            put("averagePaceSecondsPerKm", detail.averagePaceSecondsPerKm)
            put("averageSpeedKmh", detail.averageSpeedKmh)
            put("averageHeartRate", detail.averageHeartRate)
            put("maximumHeartRate", detail.maximumHeartRate)
            put("ascentMeters", detail.ascentMeters)
            put("averageCadenceSpm", detail.averageCadenceSpm)
            put("lapsJson", laps.toString())
        }
        writableDatabase.insertWithOnConflict(
            "run_details", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun getRunDetail(garminId: String): RunDetail? = readableDatabase.rawQuery(
        "SELECT * FROM run_details WHERE garminId = ? LIMIT 1",
        arrayOf(garminId)
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toRunDetail() else null }

    fun getAllRunDetails(): Map<String, RunDetail> {
        val result = mutableMapOf<String, RunDetail>()
        readableDatabase.rawQuery("SELECT * FROM run_details", null).use { cursor ->
            while (cursor.moveToNext()) {
                val detail = cursor.toRunDetail()
                result[detail.garminId] = detail
            }
        }
        return result
    }

    fun deleteAll() {
        writableDatabase.delete("run_details", null, null)
        writableDatabase.delete("activities", null, null)
    }

    private fun Cursor.toRunDetail(): RunDetail {
        val laps = runCatching { JSONArray(getString(getColumnIndexOrThrow("lapsJson"))) }
            .getOrElse { JSONArray() }
        val parsedLaps = buildList {
            for (i in 0 until laps.length()) {
                val item = laps.optJSONObject(i) ?: continue
                add(
                    RunLap(
                        number = item.optInt("number", i + 1),
                        distanceMeters = item.doubleOrNull("distanceMeters"),
                        durationSeconds = item.intOrNull("durationSeconds"),
                        averagePaceSecondsPerKm = item.intOrNull("averagePaceSecondsPerKm"),
                        averageHeartRate = item.intOrNull("averageHeartRate"),
                        ascentMeters = item.intOrNull("ascentMeters")
                    )
                )
            }
        }
        return RunDetail(
            garminId = getString(getColumnIndexOrThrow("garminId")),
            durationSeconds = intOrNull("durationSeconds"),
            averagePaceSecondsPerKm = intOrNull("averagePaceSecondsPerKm"),
            averageSpeedKmh = doubleOrNull("averageSpeedKmh"),
            averageHeartRate = intOrNull("averageHeartRate"),
            maximumHeartRate = intOrNull("maximumHeartRate"),
            ascentMeters = intOrNull("ascentMeters"),
            averageCadenceSpm = intOrNull("averageCadenceSpm"),
            laps = parsedLaps
        )
    }

    private fun Cursor.intOrNull(column: String): Int? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getInt(index)
    }

    private fun Cursor.doubleOrNull(column: String): Double? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getDouble(index)
    }

    private fun JSONObject.intOrNull(key: String): Int? =
        if (!has(key) || isNull(key)) null else optInt(key)

    private fun JSONObject.doubleOrNull(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key)

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject =
        put(key, value ?: JSONObject.NULL)
}
