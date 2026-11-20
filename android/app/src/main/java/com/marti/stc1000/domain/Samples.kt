package com.marti.stc1000.domain

import com.marti.stc1000.data.Sample

object Samples {
    /** Epoch mínimo creíble (sep. 2020): un ESP32 sin NTP publicaría fechas de 1970. */
    const val MIN_VALID_EPOCH = 1_600_000_000L
    const val MAX_FUTURE_SKEW_SEC = 600L
    const val MIN_TEMP = -50.0
    const val MAX_TEMP = 150.0
    const val DEFAULT_STEP_SEC = 300

    /** Redondea [ts] al múltiplo más cercano de [step] (así lecturas en vivo e historial coinciden). */
    fun bucket(ts: Long, step: Int): Long {
        val s = step.toLong()
        return Math.floorDiv(ts + s / 2, s) * s
    }

    /**
     * Cierre del intervalo que contiene [ts]. El ESP32 etiqueta cada muestra del historial con
     * el FIN de su intervalo de [step] s (el promedio de [fin - step, fin)), así que una lectura
     * en vivo de las 12:01 va al casillero de las 12:05, no al de las 12:00.
     */
    fun intervalEnd(ts: Long, step: Int): Long {
        val s = step.toLong()
        return Math.floorDiv(ts, s) * s + s
    }

    fun isPlausible(temp: Double): Boolean = temp in MIN_TEMP..MAX_TEMP

    /**
     * Lectura en vivo -> muestra en el casillero de su intervalo. Null si el valor no es creíble.
     * Cuando llega el historial, el promedio del ESP32 reemplaza a la lectura suelta.
     */
    fun fromLive(temp: Double, nowSec: Long, step: Int = DEFAULT_STEP_SEC): Sample? =
        if (isPlausible(temp)) Sample(intervalEnd(nowSec, step), temp) else null

    /**
     * Convierte un historial en muestras (sin nulos, sin duplicados por bucket).
     * Si el reloj del ESP32 es inválido (`fin` muy viejo o en el futuro) devuelve vacío.
     */
    fun fromHistory(h: History, nowSec: Long): List<Sample> {
        if (h.endEpoch < MIN_VALID_EPOCH || h.endEpoch > nowSec + MAX_FUTURE_SKEW_SEC) return emptyList()
        val out = ArrayList<Sample>(h.values.size)
        h.values.forEachIndexed { i, v ->
            if (v != null && isPlausible(v)) out.add(Sample(bucket(h.timestampOf(i), h.stepSec), v))
        }
        return mergeLatestWins(emptyList(), out)
    }

    /**
     * Fusión: une [existing] e [incoming]; ante el mismo ts gana [incoming] (lo último que llegó).
     * Es la misma semántica que `INSERT OR REPLACE` en Room. Resultado ordenado por ts.
     */
    fun mergeLatestWins(existing: List<Sample>, incoming: List<Sample>): List<Sample> {
        val map = LinkedHashMap<Long, Sample>()
        existing.forEach { map[it.ts] = it }
        incoming.forEach { map[it.ts] = it }
        return map.values.sortedBy { it.ts }
    }
}
