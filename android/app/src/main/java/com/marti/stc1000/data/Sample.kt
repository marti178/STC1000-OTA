package com.marti.stc1000.data

/** Una lectura de temperatura. [ts] es epoch UTC en segundos, ya redondeado al paso de muestreo. */
data class Sample(
    val ts: Long,
    val temp: Double,
)
