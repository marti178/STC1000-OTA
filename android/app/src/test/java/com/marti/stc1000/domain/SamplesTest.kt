package com.marti.stc1000.domain

import com.marti.stc1000.data.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SamplesTest {
    private val now = 1_795_142_400L

    @Test
    fun bucketRedondeaAlMasCercano() {
        assertEquals(300L, Samples.bucket(449, 300))
        assertEquals(600L, Samples.bucket(450, 300))
        assertEquals(300L, Samples.bucket(300, 300))
    }

    @Test
    fun historialSeConvierteSinNulosYConTimestampsRedondeados() {
        val h = History(300, now, listOf(20.0, null, 21.0))
        val s = Samples.fromHistory(h, now)
        assertEquals(2, s.size)
        assertEquals(Samples.bucket(now - 600, 300), s[0].ts)
        assertEquals(Samples.bucket(now, 300), s[1].ts)
        assertEquals(21.0, s[1].temp, 0.0)
    }

    @Test
    fun relojInvalidoDelEspSeRechaza() {
        assertTrue(Samples.fromHistory(History(300, 1000, listOf(20.0)), now).isEmpty())
        assertTrue(Samples.fromHistory(History(300, now + 100_000, listOf(20.0)), now).isEmpty())
    }

    @Test
    fun valoresImplausiblesSeDescartan() {
        val s = Samples.fromHistory(History(300, now, listOf(999.0, 20.0)), now)
        assertEquals(1, s.size)
        assertNull(Samples.fromLive(-80.0, now))
    }

    @Test
    fun fusionDeduplicaYGanaLoNuevo() {
        val existing = listOf(Sample(300, 20.0), Sample(600, 21.0))
        val incoming = listOf(Sample(600, 21.5), Sample(900, 22.0))
        val merged = Samples.mergeLatestWins(existing, incoming)
        assertEquals(listOf(300L, 600L, 900L), merged.map { it.ts })
        assertEquals(21.5, merged[1].temp, 0.0)
    }

    @Test
    fun fusionEsIdempotente() {
        val h = History(300, now, listOf(20.0, 21.0, 22.0))
        val once = Samples.fromHistory(h, now)
        val twice = Samples.mergeLatestWins(once, Samples.fromHistory(h, now))
        assertEquals(once, twice)
    }

    @Test
    fun lecturaEnVivoCaeEnElMismoBucketQueElHistorial() {
        // La muestra del historial con fin = now es el promedio de [now - 300, now).
        val h = History(300, now, listOf(20.0))
        val hist = Samples.fromHistory(h, now)
        val live = Samples.fromLive(20.4, now - 260)!!
        assertEquals(hist[0].ts, live.ts)
        val merged = Samples.mergeLatestWins(hist, listOf(live))
        assertEquals(1, merged.size)
    }

    @Test
    fun lecturaEnVivoVaAlCierreDeSuIntervalo() {
        assertEquals(now + 300, Samples.intervalEnd(now, 300))       // justo al empezar el intervalo
        assertEquals(now + 300, Samples.intervalEnd(now + 40, 300))
        assertEquals(now + 300, Samples.intervalEnd(now + 299, 300))
        assertEquals(now + 600, Samples.fromLive(21.0, now + 300)!!.ts)
    }

    @Test
    fun historialConHuecoMantieneLasDemasMuestras() {
        val h = History(300, now, listOf(20.0, null, null, 23.0))
        val s = Samples.fromHistory(h, now)
        assertEquals(listOf(20.0, 23.0), s.map { it.temp })
        assertEquals(900L, s[1].ts - s[0].ts)
    }
}
