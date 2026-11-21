package com.marti.stc1000.mqtt

import org.json.JSONException
import org.json.JSONObject

sealed interface ConnectionState {
    /** Sin configurar o app en segundo plano. */
    data object Idle : ConnectionState
    data object WaitingNetwork : ConnectionState
    data class Connecting(val attempt: Int) : ConnectionState
    data object Connected : ConnectionState
    data class Error(val message: String, val fatal: Boolean, val retryInSec: Int?) : ConnectionState
}

data class DeviceInfo(
    val ip: String?,
    val rssi: Int?,
    val uptimeSec: Long?,
    val version: String?,
    val wifiReconnects: Int?,
    val mqttReconnects: Int?,
    val unknown: String?,
    val txDbm: Double? = null,
) {
    companion object {
        fun parse(text: String): DeviceInfo? = try {
            val o = JSONObject(text.trim())
            DeviceInfo(
                ip = o.optString("ip").ifEmpty { null },
                rssi = if (o.has("rssi")) o.optInt("rssi") else null,
                uptimeSec = if (o.has("uptime")) o.optLong("uptime") else null,
                version = o.optString("version").ifEmpty { null },
                wifiReconnects = if (o.has("reconexiones_wifi")) o.optInt("reconexiones_wifi") else null,
                mqttReconnects = if (o.has("reconexiones_mqtt")) o.optInt("reconexiones_mqtt") else null,
                unknown = o.optString("desconocidos").ifEmpty { null },
                txDbm = if (o.has("tx_dbm") && !o.isNull("tx_dbm")) o.optDouble("tx_dbm") else null,
            )
        } catch (e: JSONException) {
            null
        }
    }
}

/** Estado de una salida del STC-1000 (LED del display). */
enum class OutputState { ON, BLINK, OFF }

/** Qué hace el termostato: `frio` es null mientras el firmware no identifica el LED de frío. */
data class Outputs(val heat: OutputState?, val cool: OutputState?) {
    companion object {
        private fun state(v: String?): OutputState? = when (v?.lowercase()) {
            "on" -> OutputState.ON
            "parpadea" -> OutputState.BLINK
            "off" -> OutputState.OFF
            else -> null
        }

        fun parse(text: String): Outputs? = try {
            val o = JSONObject(text.trim())
            Outputs(
                heat = state(if (o.isNull("calor")) null else o.optString("calor")),
                cool = state(if (o.isNull("frio")) null else o.optString("frio")),
            )
        } catch (e: JSONException) {
            null
        }
    }
}

/** Avance de una actualización OTA: porcentaje (0..100) si el texto es "NN%". */
fun otaPercent(text: String?): Int? =
    text?.trim()?.let { Regex("""^(\d{1,3})\s*%$""").find(it)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100) }

/** Porcentaje que queda tras un mensaje de OTA: el del mensaje, 100 si terminó, ninguno si no hay actualización. */
fun nextOtaPct(previous: Int?, text: String): Int? {
    otaPercent(text)?.let { return it }
    val t = text.trim().lowercase()
    return when {
        t.startsWith("listo") -> 100
        t.startsWith("al dia") || t.startsWith("al día") || t.startsWith("actualizando") -> null
        else -> previous
    }
}

/** Último estado conocido del equipo, tal como llegó por MQTT. */
data class DeviceState(
    val online: Boolean? = null,
    val temperature: Double? = null,
    val display: String? = null,
    val firmware: String? = null,
    val info: DeviceInfo? = null,
    val ota: String? = null,
    /** Cuándo (epoch s) llegó el último mensaje de OTA en vivo; sirve para ocultar el avance tras un rato. */
    val otaAtSec: Long? = null,
    /** Último porcentaje de OTA conocido: se mantiene mientras llegan avisos de corte o reanudación. */
    val otaPct: Int? = null,
    val outputs: Outputs? = null,
    /** Cuándo (epoch s) llegó algo EN VIVO del equipo (temperatura, info o salidas, no retenido). */
    val lastSignalAtSec: Long? = null,
    /** Desde cuándo (epoch s) la app está conectada y escuchando: arranca el plazo de verificación. */
    val watchSinceSec: Long? = null,
    /** Cuándo (epoch s) llegó un `estado` EN VIVO (no retenido); un "offline" en vivo más nuevo que la última señal manda. */
    val estadoLiveAtSec: Long? = null,
    /** El equipo publicó `temperatura` vacía: el display no muestra número (LLL, HHH, sin lectura). */
    val noReading: Boolean = false,
    /** Cuándo (epoch s) llegó por última vez una temperatura EN VIVO (no retenida). */
    val lastLiveAtSec: Long? = null,
)

/** Segundos sin datos en vivo tras los cuales el equipo se da por caido (publica cada 60 s; keepalive 30 s). */
const val SIGNAL_FRESH_SEC = 75L

/** Con latido de 20 s (firmware 1.1.8+) alcanza una ventana corta; con el de 60 s hace falta más margen. */
fun signalWindowSec(version: String?): Long = if (supportsCommandExpiry(version)) 60L else SIGNAL_FRESH_SEC

/**
 * ¿Está el equipo en línea? Criterio estricto: solo cuenta lo recibido EN VIVO (los retenidos pueden ser viejos).
 * true = llegó algo en los últimos [SIGNAL_FRESH_SEC] s; false = no llegó nada o el broker avisó que cayó;
 * null = todavía verificando (recién se conectó la app y no pasó el plazo).
 */
fun DeviceState.isOnline(nowSec: Long): Boolean? {
    val signal = lastSignalAtSec
    val liveOffline = online == false && estadoLiveAtSec != null && (signal == null || estadoLiveAtSec >= signal)
    if (liveOffline) return false
    val window = signalWindowSec(firmware ?: info?.version)
    if (signal != null && nowSec - signal <= window) return true
    if (online == false) return false
    val since = watchSinceSec ?: return false
    return if (nowSec - since <= window) null else false
}

/** ¿El firmware entiende comandos con vencimiento (`REBOOT@<epoch>`)? Desde la 1.1.8. */
fun supportsCommandExpiry(version: String?): Boolean {
    val p = version?.trim()?.split('.')?.mapNotNull { it.toIntOrNull() } ?: return false
    if (p.size < 3) return false
    return (p[0] * 1_000_000 + p[1] * 1_000 + p[2]) >= 1_001_008
}

/** Comando con la hora en que se manda, para que el equipo lo ignore si llega tarde (más de 5 min). */
fun stampCommand(command: String, version: String?, nowSec: Long): String =
    if (supportsCommandExpiry(version)) "$command@$nowSec" else command

/** Una línea del registro de conexión (solo en memoria, para diagnóstico). */
data class LogEntry(val ts: Long, val text: String, val isError: Boolean = false)
