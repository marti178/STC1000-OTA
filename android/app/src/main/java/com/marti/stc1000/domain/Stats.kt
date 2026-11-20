package com.marti.stc1000.domain

import com.marti.stc1000.data.Sample

data class Stats(
    val min: Double,
    val minTs: Long,
    val max: Double,
    val maxTs: Long,
    val avg: Double,
    val count: Int,
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
            return Stats(min.temp, min.ts, max.temp, max.ts, sum / samples.size, samples.size)
        }
    }
}
