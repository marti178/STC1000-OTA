package com.marti.stc1000.domain

import com.marti.stc1000.data.Sample
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.sqrt

data class Stats(
    val min: Double,
    val minTs: Long,
    val max: Double,
    val maxTs: Long,
    val avg: Double,
    val count: Int,
    val median: Double = avg,
    val stdDev: Double = 0.0,
) {
    companion object {
        /** Null si no hay muestras. En empates se queda con la primera ocurrencia. */
        fun compute(samples: List<Sample>): Stats? {
            if (samples.isEmpty()) return null
            var min = samples[0]
            var max = samples[0]
            var sum = 0.0
            for (s in samples) {
                if (s.temp < min.temp) min = s
                if (s.temp > max.temp) max = s
                sum += s.temp
            }
            val avg = sum / samples.size
            val sorted = samples.map { it.temp }.sorted()
            val mid = sorted.size / 2
            val median = if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
            val variance = samples.sumOf { (it.temp - avg) * (it.temp - avg) } / samples.size
            return Stats(min.temp, min.ts, max.temp, max.ts, avg, samples.size, median, sqrt(variance))
        }

        /** Fracción (0..1) de las muestras dentro de [lo]..[hi] inclusive. Null si no hay muestras. */
        fun inRangeFraction(samples: List<Sample>, lo: Double, hi: Double): Double? {
            if (samples.isEmpty()) return null
            return samples.count { it.temp in lo..hi }.toDouble() / samples.size
        }
    }
}

/** Resumen de un día calendario (en la zona horaria dada). */
data class DaySummary(
    val date: LocalDate,
    val min: Double,
    val avg: Double,
    val max: Double,
    val count: Int,
)

object Summaries {
    /** Agrupa por día local, de más viejo a más nuevo. */
    fun byDay(samples: List<Sample>, zone: ZoneId = ZoneId.systemDefault()): List<DaySummary> =
        samples
            .groupBy { Instant.ofEpochSecond(it.ts).atZone(zone).toLocalDate() }
            .toSortedMap()
            .map { (date, list) ->
                DaySummary(
                    date = date,
                    min = list.minOf { it.temp },
                    avg = list.sumOf { it.temp } / list.size,
                    max = list.maxOf { it.temp },
                    count = list.size,
                )
            }

    /**
     * Cambio de temperatura de la última muestra respecto de la de hace ~[windowSec] segundos.
     * Null si no hay una muestra razonablemente cercana a ese momento.
     */
    fun trend(samples: List<Sample>, windowSec: Long = 1800, toleranceSec: Long = 600): Double? {
        val last = samples.lastOrNull() ?: return null
        val target = last.ts - windowSec
        val ref = samples.minByOrNull { kotlin.math.abs(it.ts - target) } ?: return null
        if (ref === last || kotlin.math.abs(ref.ts - target) > toleranceSec) return null
        return last.temp - ref.temp
    }
}
