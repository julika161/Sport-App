package com.julia.sportapp.data

import java.time.LocalDate
import java.time.LocalTime

// Die Sportarten, die zur Auswahl stehen
enum class ActivityType(val label: String, val symbol: String) {
    LAUFEN("Laufen", "🏃"),
    RADFAHREN("Radfahren", "🚴"),
    KRAFTTRAINING("Krafttraining", "🏋️"),
    YOGA("Yoga/Mobility", "🧘"),
    WALKEN("Walken/Spazieren", "🚶"),
    HIIT("HIIT", "🔥")
}

// Eine gespeicherte Trainingseinheit
data class Activity(
    val type: ActivityType,
    val date: LocalDate,
    val time: LocalTime,
    val durationMinutes: Int,
    val distanceKm: Double?
)

