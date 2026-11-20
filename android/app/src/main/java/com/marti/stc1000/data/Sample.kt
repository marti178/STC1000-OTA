package com.marti.stc1000.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Una lectura de temperatura. [ts] es epoch UTC en segundos, ya redondeado al paso de muestreo. */
@Entity(tableName = "samples")
data class Sample(
    @PrimaryKey val ts: Long,
    val temp: Double,
)
