package com.marti.stc1000.domain

import java.time.Instant
import java.time.LocalDate
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

    /** Número con coma decimal y la cantidad de decimales pedida, ya convertido a [unit]. */
    fun number(celsius: Double, unit: TempUnit = TempUnit.C, decimals: Int = 1): String =
        String.format(Locale.US, "%.${decimals}f", unit.convert(celsius))
            .replace('.', ',')
            .let { if (it.startsWith("-") && it.none { c -> c in '1'..'9' }) it.drop(1) else it } // sin "-0,0"

    fun temp(celsius: Double, unit: TempUnit = TempUnit.C): String = number(celsius, unit) + " " + unit.symbol

    /** "+0,4°" / "−0,2°" / "0,0°" (diferencia, no temperatura absoluta). */
    fun delta(celsiusDelta: Double, unit: TempUnit = TempUnit.C): String {
        val v = unit.convertDelta(celsiusDelta)
        val body = String.format(Locale.US, "%.1f", kotlin.math.abs(v)).replace('.', ',')
        return when {
            v > 0.049 -> "+$body°"
            v < -0.049 -> "−$body°"
            else -> "$body°"
        }
    }

    fun percent(fraction: Double): String = String.format(Locale.US, "%.0f", fraction * 100) + " %"

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
    private val dayFmt = DateTimeFormatter.ofPattern("dd/MM")
    private val dayNameFmt = DateTimeFormatter.ofPattern("EEE dd/MM", Locale("es", "AR"))
    private val fileFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun hour(epochSec: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        hourFmt.format(Instant.ofEpochSecond(epochSec).atZone(zone))

    fun dayHour(epochSec: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        dayHourFmt.format(Instant.ofEpochSecond(epochSec).atZone(zone))

    fun day(epochSec: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        dayFmt.format(Instant.ofEpochSecond(epochSec).atZone(zone))

    fun dayName(date: LocalDate): String = dayNameFmt.format(date).replaceFirstChar { it.uppercase() }

    fun dateTimeFull(epochSec: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        fileFmt.format(Instant.ofEpochSecond(epochSec).atZone(zone))
}
