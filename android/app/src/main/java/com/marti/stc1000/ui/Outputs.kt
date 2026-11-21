package com.marti.stc1000.ui

import com.marti.stc1000.mqtt.OutputState
import com.marti.stc1000.mqtt.Outputs

/** Lo que hace el termostato, en palabras. El frio se omite mientras el firmware no lo identifica (null). */
fun outputsText(o: Outputs, withIdle: Boolean): String {
    val parts = buildList {
        when (o.heat) {
            OutputState.ON -> add("Calentando")
            OutputState.BLINK -> add("Calor en espera")
            else -> {}
        }
        when (o.cool) {
            OutputState.ON -> add("Enfriando")
            OutputState.BLINK -> add("Frío en espera")
            else -> {}
        }
    }
    if (parts.isNotEmpty()) return parts.joinToString(" · ")
    return if (o.heat == OutputState.OFF || o.cool == OutputState.OFF) "Reposo" else if (withIdle) "Sin datos" else ""
}

/** True si alguna salida esta activa o en espera. */
fun Outputs.isActive(): Boolean =
    heat == OutputState.ON || heat == OutputState.BLINK || cool == OutputState.ON || cool == OutputState.BLINK
