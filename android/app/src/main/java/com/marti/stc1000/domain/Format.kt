package com.marti.stc1000.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Format {
    /** "hace 5 min", "hace 2 h", "hace 3 d". */
    fun age(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return when {
            s < 60 -> "hace instantes"
            s < 3600 -> "hace ${s / 60} min"
            s < 86400 -> "hace ${s / 3600} h"
            else -> "hace ${s / 86400} d"
        }
    }

    fun temp(value: Double): String = String.format(Locale.US, "%.1f", value).replace('.', ',') + " °C"

    private val numericDisplay = Regex("""^\s*-?\d+([.,]\d+)?""")

    /**
     * Si el display NO muestra un número, devuelve el texto explicado para el usuario; si muestra
     * un número (o no hay dato) devuelve null.
     */
    fun displayProblem(display: String?): String? {
        val d = display?.trim().orEmpty()
        if (d.isEmpty() || numericDisplay.containsMatchIn(d)) return null
        return when {
            d.equals("LLL", ignoreCase = true) -> "LLL: error de sonda (desconectada o en corto)"
            d.equals("HHH", ignoreCase = true) -> "HHH: temperatura fuera de rango"
            d.equals("sin lectura", ignoreCase = true) -> "Sin lectura del display"
            else -> d
        }
    }

    private val hourFmt = DateTimeFormatter.ofPattern("HH:mm")
    private val dayHourFmt = DateTimeFormatter.ofPattern("dd/MM HH:mm")

    fun hour(epochSec: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        hourFmt.format(Instant.ofEpochSecond(epochSec).atZone(zone))

    fun dayHour(epochSec: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        dayHourFmt.format(Instant.ofEpochSecond(epochSec).atZone(zone))
}
