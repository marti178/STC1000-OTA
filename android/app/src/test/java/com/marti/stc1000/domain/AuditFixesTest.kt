package com.marti.stc1000.domain

import com.marti.stc1000.data.ConnSettings
import com.marti.stc1000.data.Sample
import com.marti.stc1000.mqtt.MqttRepository
import com.marti.stc1000.mqtt.MqttSupport
import com.marti.stc1000.ui.decimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.ZoneId
import java.time.ZonedDateTime

/** Casos que salieron de la auditoria: horario de verano, medianoche, backoff, errores y reduccion de puntos. */
class AuditFixesTest {
    private val settings = ConnSettings(host = "server.example", port = 8443)

    @Test
    fun resumenPorDiaEnElCambioDeHoraDeEuropa() {
        // En Madrid el 26/10/2025 el reloj se atrasa 1 h (el dia dura 25 h): las 23:55 siguen siendo del dia 26.
        val zone = ZoneId.of("Europe/Madrid")
        fun ts(d: Int, h: Int, m: Int) = ZonedDateTime.of(2025, 10, d, h, m, 0, 0, zone).toEpochSecond()
        val samples = listOf(
            Sample(ts(26, 0, 5), 20.0),
            Sample(ts(26, 12, 0), 22.0),
            Sample(ts(26, 23, 55), 21.0),
            Sample(ts(27, 0, 5), 19.0),
        )
        val days = Summaries.byDay(samples, zone)
        assertEquals(2, days.size)
        assertEquals(26, days[0].date.dayOfMonth)
        assertEquals(3, days[0].count)
        assertEquals(27, days[1].date.dayOfMonth)
    }

    @Test
    fun laMuestraDeMedianocheCuentaParaElDiaQueTermina() {
        val zone = ZoneId.of("America/Argentina/Buenos_Aires")
        val midnight = ZonedDateTime.of(2026, 11, 20, 0, 0, 0, 0, zone).toEpochSecond()
        val days = Summaries.byDay(listOf(Sample(midnight, 24.0), Sample(midnight + 300, 25.0)), zone)
        assertEquals(19, days[0].date.dayOfMonth) // 00:00 = promedio de 23:55-00:00
        assertEquals(20, days[1].date.dayOfMonth)
    }

    @Test
    fun noMuestraMenosCero() {
        assertEquals("0,0 °C", Format.temp(-0.04))
        assertEquals("-0,5 °C", Format.temp(-0.5))
        assertEquals("0,0", Format.number(-0.0))
    }

    @Test
    fun backoffCreceYTieneTope() {
        repeat(20) {
            assertTrue(MqttRepository.backoffSeconds(1) in 2..3)
            assertTrue(MqttRepository.backoffSeconds(3) in 8..9)
            assertTrue(MqttRepository.backoffSeconds(30) in 60..61)
        }
        assertTrue(MqttRepository.backoffSeconds(2) > MqttRepository.backoffSeconds(1) - 1)
    }

    @Test
    fun erroresDeConexionSeExplicanEnEspanol() {
        val refused = MqttSupport.describe(RuntimeException("x", ConnectException("Connection refused")), settings)
        assertTrue(refused.message.contains("rechazó"))
        assertFalse(refused.fatal)

        val unreachable = MqttSupport.describe(RuntimeException(SocketTimeoutException("t")), settings)
        assertTrue(unreachable.message.contains("Tailscale"))

        val noHost = MqttSupport.describe(UnknownHostException("server.example"), settings)
        assertTrue(noHost.message.contains("server.example"))

        val other = MqttSupport.describe(IllegalStateException("algo raro"), settings)
        assertTrue(other.message.contains("algo raro"))
    }

    @Test
    fun reducirPuntosConservaPicosYOrden() {
        val seg = (0 until 8640).map { Sample(it * 300L, 20.0 + (it % 100) * 0.05) }
        val out = decimate(seg, 600)
        assertTrue(out.size <= 700)
        assertEquals(seg.maxOf { it.temp }, out.maxOf { it.temp }, 0.0)
        assertEquals(seg.minOf { it.temp }, out.minOf { it.temp }, 0.0)
        assertEquals(out.sortedBy { it.ts }, out)
    }

    @Test
    fun reducirPuntosNoTocaTramosCortos() {
        val seg = (0 until 50).map { Sample(it * 300L, 20.0 + it) }
        assertEquals(seg, decimate(seg, 600))
    }
}
