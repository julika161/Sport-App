package com.julia.sportapp

import com.garmin.fit.FitDecoder
import com.julia.sportapp.data.ActivityDatabase
import com.julia.sportapp.data.ActivityType
import com.julia.sportapp.data.RunDetail
import com.julia.sportapp.data.RunLap
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

private const val GARMIN_RUN_FILES = "https://personal.connectapi.garmin.com/download-service/files/activity"
private const val MAX_RUN_FILE_BYTES = 25 * 1024 * 1024
private const val MAX_RUN_DETAILS_PER_SYNC = 15

data class RunDetailSyncResult(
    val attempted: Int,
    val saved: Int,
    val failed: Int,
    val lastFailure: String?
)

// Lädt bisher fehlende Laufdetails. Die FIT-Datei wird nur im Arbeitsspeicher verarbeitet.
fun syncRunDetails(db: ActivityDatabase, token: String): RunDetailSyncResult {
    val candidates = db.getAll()
        .filter { it.type == ActivityType.LAUFEN && it.garminId != null }
        .filter { db.getRunDetail(it.garminId!!) == null }
        .take(MAX_RUN_DETAILS_PER_SYNC)

    var saved = 0
    var failed = 0
    var lastFailure: String? = null
    candidates.forEach { activity ->
        val garminId = activity.garminId ?: return@forEach
        val result = runCatching {
            val zippedOrFit = downloadActivityFile(token, garminId)
            val fitBytes = extractFitFile(zippedOrFit)
            decodeRunDetail(garminId, fitBytes)
        }
        result.onSuccess { detail ->
            db.saveRunDetail(detail)
            saved++
        }.onFailure { error ->
            failed++
            lastFailure = safeFailureMessage(error)
        }
    }
    return RunDetailSyncResult(candidates.size, saved, failed, lastFailure)
}

private fun safeFailureMessage(error: Throwable): String = when (error) {
    is GarminException -> error.message ?: "Garmin-Fehler"
    is IOException -> "Netzwerk- oder Dateidownloadfehler"
    else -> "Die FIT-Datei konnte nicht gelesen werden"
}

private fun downloadActivityFile(token: String, garminId: String): ByteArray {
    val connection = URL("$GARMIN_RUN_FILES/$garminId").openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "GET"
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        connection.setRequestProperty("Accept", "*/*")
        connection.setRequestProperty("Authorization", "Bearer $token")
        val code = connection.responseCode
        if (code == 401 || code == 403) {
            throw GarminException("Garmin hat den FIT-Download abgelehnt ($code). Prüfe die Berechtigung des Personal-API-Schlüssels.")
        }
        if (code !in 200..299) {
            throw GarminException("FIT-Download von Garmin fehlgeschlagen ($code).")
        }
        return connection.inputStream.use { readLimited(it) }
    } finally {
        connection.disconnect()
    }
}

private fun readLimited(input: java.io.InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        if (total > MAX_RUN_FILE_BYTES) {
            throw GarminException("Die Garmin-Aktivitätsdatei ist größer als erlaubt.")
        }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun extractFitFile(payload: ByteArray): ByteArray {
    if (payload.isFitFile()) return payload
    ZipInputStream(ByteArrayInputStream(payload)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (!entry.isDirectory && entry.name.endsWith(".fit", ignoreCase = true)) {
                return readLimited(zip)
            }
        }
    }
    throw GarminException("Garmin hat für diesen Lauf keine FIT-Datei geliefert.")
}

private fun ByteArray.isFitFile(): Boolean =
    size >= 12 && this[8] == '.'.code.toByte() &&
        this[9] == 'F'.code.toByte() && this[10] == 'I'.code.toByte() && this[11] == 'T'.code.toByte()

private fun decodeRunDetail(garminId: String, fitBytes: ByteArray): RunDetail {
    val messages = FitDecoder().decode(ByteArrayInputStream(fitBytes))
    val session = messages.sessionMesgs.firstOrNull()
    val timerSeconds = session?.totalTimerTime?.takeIf { it > 0f }?.toDouble()
    val elapsedSeconds = session?.totalElapsedTime?.takeIf { it > 0f }?.toDouble()
    val durationSeconds = (elapsedSeconds ?: timerSeconds)?.roundToInt()
    val distanceMeters = session?.totalDistance?.takeIf { it > 0f }?.toDouble()

    val speedKmh = session?.avgSpeed?.takeIf { it > 0f }?.toDouble()?.times(3.6)
        ?: if (distanceMeters != null && (timerSeconds ?: elapsedSeconds) != null) {
            distanceMeters / (timerSeconds ?: elapsedSeconds!!) * 3.6
        } else null
    val paceSecondsPerKm = speedKmh?.takeIf { it > 0.0 && it.isFinite() }
        ?.let { (3600.0 / it).roundToInt() }

    val laps = messages.lapMesgs.mapIndexedNotNull { index, lap ->
        val lapDistance = lap.totalDistance?.takeIf { it > 0f }?.toDouble()
        val lapTimer = lap.totalTimerTime?.takeIf { it > 0f }?.toDouble()
        val lapElapsed = lap.totalElapsedTime?.takeIf { it > 0f }?.toDouble()
        val lapDuration = (lapTimer ?: lapElapsed)?.roundToInt()
        val lapSpeedKmh = lap.avgSpeed?.takeIf { it > 0f }?.toDouble()?.times(3.6)
            ?: if (lapDistance != null && (lapTimer ?: lapElapsed) != null) {
                lapDistance / (lapTimer ?: lapElapsed!!) * 3.6
            } else null
        RunLap(
            number = index + 1,
            distanceMeters = lapDistance,
            durationSeconds = lapDuration,
            averagePaceSecondsPerKm = lapSpeedKmh?.takeIf { it > 0.0 && it.isFinite() }
                ?.let { (3600.0 / it).roundToInt() },
            averageHeartRate = lap.avgHeartRate?.toInt()?.takeIf { it > 0 },
            ascentMeters = lap.totalAscent?.takeIf { it >= 0 }
        ).takeIf { lapDistance != null || lapDuration != null }
    }

    return RunDetail(
        garminId = garminId,
        durationSeconds = durationSeconds,
        averagePaceSecondsPerKm = paceSecondsPerKm,
        averageSpeedKmh = speedKmh?.takeIf { it > 0.0 && it.isFinite() },
        averageHeartRate = session?.avgHeartRate?.toInt()?.takeIf { it > 0 },
        maximumHeartRate = session?.maxHeartRate?.toInt()?.takeIf { it > 0 },
        ascentMeters = session?.totalAscent?.takeIf { it >= 0 },
        averageCadenceSpm = session?.avgRunningCadence?.toInt()?.takeIf { it > 0 },
        laps = laps
    )
}
