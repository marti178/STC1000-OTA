package com.marti.stc1000.domain

import com.marti.stc1000.data.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AnalyticsTest {
    private val zone = ZoneId.of("America/Argentina/Buenos_Aires")
    private fun ts(d: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 11, d, h, m, 0, 0, zone).toEpochSecond()

    @Test
    fun conversionDeUnidades() {
        assertEquals(32.0, TempUnit.F.convert(0.0), 1e-9)
        assertEquals(212.0, TempUnit.F.convert(100.0), 1e-9)
        assertEquals(1.8, TempUnit.F.convertDelta(1.0), 1e-9)
        assertEquals(25.0, TempUnit.F.toCelsius(77.0), 1e-9)
        assertEquals(26.3, TempUnit.C.convert(26.3), 0.0)
    }

    @Test
    fun formatoDeTemperaturaYDiferencia() {
        assertEquals("27,3 °C", Format.temp(27.3))
        assertEquals("81,1 °F", Format.temp(27.3, TempUnit.F))
        assertEquals("+0,4°", Format.delta(0.4))
        assertEquals("−0,2°", Format.delta(-0.2))
        assertEquals("0,0°", Format.delta(0.01))
        assertEquals("+0,7°", Format.delta(0.4, TempUnit.F))
        assertEquals("50 %", Format.percent(0.5))
    }

    @Test
    fun medianaYDesvioEstandar() {
        val impar = Stats.compute(listOf(Sample(1, 10.0), Sample(2, 30.0), Sample(3, 20.0)))!!
        assertEquals(20.0, impar.median, 0.0)
        val par = Stats.compute(listOf(Sample(1, 10.0), Sample(2, 20.0), Sample(3, 30.0), Sample(4, 40.0)))!!
        assertEquals(25.0, par.median, 0.0)
        val iguales = Stats.compute(listOf(Sample(1, 5.0), Sample(2, 5.0)))!!
        assertEquals(0.0, iguales.stdDev, 0.0)
        val dos = Stats.compute(listOf(Sample(1, 10.0), Sample(2, 20.0)))!!
        assertEquals(5.0, dos.stdDev, 1e-9) // desvío poblacional
    }

    @Test
    fun fraccionDentroDeRango() {
        val s = listOf(Sample(1, 19.0), Sample(2, 20.0), Sample(3, 25.0), Sample(4, 28.0), Sample(5, 29.0))
        assertEquals(0.6, Stats.inRangeFraction(s, 20.0, 28.0)!!, 1e-9) // límites incluidos
        assertNull(Stats.inRangeFraction(emptyList(), 20.0, 28.0))
    }

    @Test
    fun resumenPorDiaAgrupaPorFechaLocal() {
        val samples = listOf(
            Sample(ts(19, 23, 55), 20.0), // 19/11 local
            Sample(ts(20, 0, 5), 24.0),   // 20/11 local (aunque en UTC sea el mismo día)
            Sample(ts(20, 12), 28.0),
            Sample(ts(20, 18), 26.0),
        )
        val days = Summaries.byDay(samples, zone)
        assertEquals(2, days.size)
        assertEquals(19, days[0].date.dayOfMonth)
        assertEquals(1, days[0].count)
        assertEquals(24.0, days[1].min, 0.0)
        assertEquals(28.0, days[1].max, 0.0)
        assertEquals(26.0, days[1].avg, 1e-9)
        assertEquals(3, days[1].count)
    }

    @Test
    fun tendenciaContraHaceMediaHora() {
        val base = ts(20, 12)
        val samples = listOf(Sample(base, 25.0), Sample(base + 900, 25.2), Sample(base + 1800, 25.6))
        assertEquals(0.6, Summaries.trend(samples)!!, 1e-9)
    }

    @Test
    fun tendenciaSinReferenciaCercanaEsNula() {
        val base = ts(20, 12)
        assertNull(Summaries.trend(emptyList()))
        assertNull(Summaries.trend(listOf(Sample(base, 25.0))))
        // la única otra muestra está a 3 h: no sirve de referencia
        assertNull(Summaries.trend(listOf(Sample(base - 10800, 20.0), Sample(base, 25.0))))
        assertNotNull(Summaries.trend(listOf(Sample(base - 1700, 20.0), Sample(base, 25.0))))
    }

    @Test
    fun periodoDe30Dias() {
        val now = ts(20, 15, 30)
        assertEquals(now - 30 * 86400L, Period.LAST_30D.startEpoch(now, zone))
    }
}
