package com.marti.stc1000.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay

/** Reloj en epoch segundos que se refresca cada [everyMs]; lo usa la verificacion estricta del equipo. */
@Composable
fun rememberClockSec(everyMs: Long = 5_000): Long {
    val now by produceState(System.currentTimeMillis() / 1000) {
        while (true) {
            delay(everyMs)
            value = System.currentTimeMillis() / 1000
        }
    }
    return now
}
