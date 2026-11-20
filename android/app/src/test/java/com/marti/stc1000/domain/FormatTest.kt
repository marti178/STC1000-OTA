package com.marti.stc1000.domain

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatTest {
    @Test
    fun displayNumericoNoEsProblema() {
        assertNull(Format.displayProblem("27.3 C"))
        assertNull(Format.displayProblem("-5.0 C"))
        assertNull(Format.displayProblem(null))
    }

    @Test
    fun displayConErrorSeExplica() {
        assertTrue(Format.displayProblem("LLL")!!.contains("sonda"))
        assertTrue(Format.displayProblem("HHH")!!.contains("fuera de rango"))
        assertTrue(Format.displayProblem("sin lectura")!!.startsWith("Sin lectura"))
        assertTrue(Format.displayProblem("E1")!! == "E1")
    }
}
