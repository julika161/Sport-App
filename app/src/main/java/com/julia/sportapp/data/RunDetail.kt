package com.julia.sportapp.data

// Nur ausgewählte Kennzahlen; GPS-Punkte und die FIT-Datei selbst werden nicht gespeichert.
data class RunDetail(
    val garminId: String,
    val durationSeconds: Int?,
    val averagePaceSecondsPerKm: Int?,
    val averageSpeedKmh: Double?,
    val averageHeartRate: Int?,
    val maximumHeartRate: Int?,
    val ascentMeters: Int?,
    val averageCadenceSpm: Int?,
    val laps: List<RunLap>
)

data class RunLap(
    val number: Int,
    val distanceMeters: Double?,
    val durationSeconds: Int?,
    val averagePaceSecondsPerKm: Int?,
    val averageHeartRate: Int?,
    val ascentMeters: Int?
)
