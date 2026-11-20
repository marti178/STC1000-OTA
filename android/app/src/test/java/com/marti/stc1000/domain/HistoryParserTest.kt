package com.marti.stc1000.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryParserTest {
    @Test
    fun parseaHistorialNormalConNulos() {
        val h = HistoryParser.parse("""{"paso_s":300,"fin":1795142400,"t":[26.8,27.0,null,27.5]}""")
        assertNotNull(h)
        h!!
        assertEquals(300, h.stepSec)
        assertEquals(1795142400L, h.endEpoch)
        assertEquals(listOf(26.8, 27.0, null, 27.5), h.values)
    }

    @Test
    fun timestampDeCadaMuestra() {
        val h = HistoryParser.parse("""{"paso_s":300,"fin":10000,"t":[1,2,3]}""")!!
        assertEquals(10000L - 600, h.timestampOf(0))
        assertEquals(10000L - 300, h.timestampOf(1))
        assertEquals(10000L, h.timestampOf(2))
    }

    @Test
    fun arrayCortoYVacioSonValidos() {
        assertEquals(1, HistoryParser.parse("""{"paso_s":300,"fin":5,"t":[20.0]}""")!!.values.size)
        assertEquals(0, HistoryParser.parse("""{"paso_s":300,"fin":5,"t":[]}""")!!.values.size)
    }

    @Test
    fun invalidosDevuelvenNull() {
        assertNull(HistoryParser.parse(""))
        assertNull(HistoryParser.parse("no es json"))
        assertNull(HistoryParser.parse("""{"fin":5,"t":[1]}"""))
        assertNull(HistoryParser.parse("""{"paso_s":0,"fin":5,"t":[1]}"""))
        assertNull(HistoryParser.parse("""{"paso_s":300,"t":[1]}"""))
        assertNull(HistoryParser.parse("""{"paso_s":300,"fin":5}"""))
    }

    @Test
    fun elementosRarosSeTratanComoSinLectura() {
        val h = HistoryParser.parse("""{"paso_s":300,"fin":5,"t":["x",null,12.5]}""")!!
        assertEquals(listOf(null, null, 12.5), h.values)
    }

    @Test
    fun toleraEspaciosYSaltosDeLinea() {
        val h = HistoryParser.parse("  {\"paso_s\": 300,\n\"fin\": 5, \"t\": [1.5]}\n")
        assertNotNull(h)
    }
}
