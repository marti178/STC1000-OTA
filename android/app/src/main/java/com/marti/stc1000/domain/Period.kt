package com.marti.stc1000.domain

import java.time.Instant
import java.time.ZoneId

enum class Period(val label: String) {
    TODAY("Hoy"),
    LAST_3H("3 h"),
    LAST_24H("24 h"),
    LAST_7D("7 d"),
    LAST_30D("30 d");

    /** Inicio de la ventana, en epoch segundos. */
    fun startEpoch(nowSec: Long, zone: ZoneId = ZoneId.systemDefault()): Long = when (this) {
        TODAY -> Instant.ofEpochSecond(nowSec).atZone(zone).toLocalDate()
            .atStartOfDay(zone).toEpochSecond()
        LAST_3H -> nowSec - 3 * 3600L
        LAST_24H -> nowSec - 24 * 3600L
        LAST_7D -> nowSec - 7 * 24 * 3600L
        LAST_30D -> nowSec - 30 * 24 * 3600L
    }
}
