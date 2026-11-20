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
            )
        } catch (e: JSONException) {
            null
        }
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
    /** Cuándo (epoch s) llegó por última vez una temperatura EN VIVO (no retenida). */
    val lastLiveAtSec: Long? = null,
)
