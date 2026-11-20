package com.marti.stc1000.domain

import com.marti.stc1000.data.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class StatsTest {
    @Test
    fun sinMuestrasNoHayEstadisticas() {
        assertNull(Stats.compute(emptyList()))
    }

    @Test
    fun calculaMinMaxPromedioYCantidad() {
        val st = Stats.compute(
            listOf(Sample(100, 20.0), Sample(200, 25.0), Sample(300, 15.0), Sample(400, 20.0)),
        )!!
        assertEquals(15.0, st.min, 0.0)
        assertEquals(300L, st.minTs)
        assertEquals(25.0, st.max, 0.0)
        assertEquals(200L, st.maxTs)
        assertEquals(20.0, st.avg, 1e-9)
        assertEquals(4, st.count)
    }

    @Test
    fun unaSolaMuestra() {
        val st = Stats.compute(listOf(Sample(100, 21.3)))!!
        assertEquals(21.3, st.min, 0.0)
        assertEquals(21.3, st.max, 0.0)
        assertEquals(21.3, st.avg, 0.0)
        assertEquals(1, st.count)
    }

    @Test
    fun empateDevuelveLaPrimeraOcurrencia() {
        val st = Stats.compute(listOf(Sample(1, 10.0), Sample(2, 10.0)))!!
        assertEquals(1L, st.minTs)
        assertEquals(1L, st.maxTs)
    }

    @Test
    fun periodoHoyEmpiezaALaMedianocheLocal() {
        val zone = ZoneId.of("America/Argentina/Buenos_Aires")
        val now = ZonedDateTime.of(2026, 11, 20, 15, 30, 0, 0, zone).toEpochSecond()
        val midnight = ZonedDateTime.of(2026, 11, 20, 0, 0, 0, 0, zone).toEpochSecond()
        assertEquals(midnight, Period.TODAY.startEpoch(now, zone))
        assertEquals(now - 86400, Period.LAST_24H.startEpoch(now, zone))
        assertEquals(now - 7 * 86400, Period.LAST_7D.startEpoch(now, zone))
    }

    @Test
    fun formatoDeEdadYTemperatura() {
        assertEquals("hace instantes", Format.age(10))
        assertEquals("hace 5 min", Format.age(300))
        assertEquals("hace 2 h", Format.age(7200))
        assertEquals("hace 3 d", Format.age(3 * 86400L))
        assertEquals("27,3 °C", Format.temp(27.3))
    }
}
