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
    val outputs: Outputs? = null,
    /** Cuándo (epoch s) llegó por última vez una temperatura EN VIVO (no retenida). */
    val lastLiveAtSec: Long? = null,
)

/** Una línea del registro de conexión (solo en memoria, para diagnóstico). */
data class LogEntry(val ts: Long, val text: String, val isError: Boolean = false)
