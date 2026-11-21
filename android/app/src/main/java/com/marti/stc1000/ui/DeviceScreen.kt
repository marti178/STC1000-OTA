package com.marti.stc1000.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.marti.stc1000.domain.Format
import com.marti.stc1000.mqtt.ConnectionState
import com.marti.stc1000.mqtt.isOnline
import com.marti.stc1000.mqtt.DeviceInfo

private enum class DeviceCommand(
    val payload: String,
    val title: String,
    val question: String,
    val done: String,
) {
    CHECK("CHECK", "Buscar actualización", "¿Pedirle al equipo que busque una actualización de firmware?", "Pedido enviado: el equipo buscará actualización."),
    OTA("OTA", "Forzar actualización", "¿Forzar la actualización del firmware ahora? El equipo se va a reiniciar y puede quedar unos minutos sin medir.", "Pedido enviado: el equipo va a actualizar el firmware."),
    REBOOT("REBOOT", "Reiniciar equipo", "¿Reiniciar el ESP32? Se pierden unos segundos de lectura.", "Pedido enviado: el equipo se va a reiniciar."),
}

@Composable
fun DeviceScreen(
    state: MainUiState,
    onCommand: (payload: String, okText: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pending by remember { mutableStateOf<DeviceCommand?>(null) }
    val connected = state.connection == ConnectionState.Connected
    val d = state.device
    val info: DeviceInfo? = d.info

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader("Equipo", "ESP32 espía del STC-1000") { StatusPills(state) }

        SurfaceCard {
            Column(Modifier.padding(16.dp)) {
                Text("Estado", style = MaterialTheme.typography.titleMedium)
                val rows = buildList {
                    add("Estado" to when (d.isOnline(state.nowSec)) { true -> "En línea"; false -> "Sin conexión"; null -> "Desconocido" })
                    add("Firmware" to (d.firmware ?: info?.version ?: "—"))
                    add("Dirección IP" to (info?.ip ?: "—"))
                    add("Encendido hace" to (info?.uptimeSec?.let { uptime(it) } ?: "—"))
                    add("Reconexiones Wi-Fi" to (info?.wifiReconnects?.toString() ?: "—"))
                    add("Reconexiones MQTT" to (info?.mqttReconnects?.toString() ?: "—"))
                    info?.unknown?.takeIf { it.isNotEmpty() }?.let { add("Desconocidos" to it) }
                    d.ota?.takeIf { it.isNotEmpty() }?.let { add("Actualización" to it) }
                    info?.txDbm?.let { add("Potencia de transmisión" to (if (it % 1.0 == 0.0) it.toInt().toString() else it.toString().replace('.', ',')) + " dBm") }
                    d.outputs?.let { add("Termostato" to outputsText(it, withIdle = true)) }
                }
                rows.forEachIndexed { i, (k, v) ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Line(k, v)
                }
            }
        }

        SurfaceCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Señal Wi-Fi del equipo", style = MaterialTheme.typography.titleMedium)
                val rssi = info?.rssi
                if (rssi == null) {
                    Text("Sin datos todavía.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SignalBars(rssi)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(signalQuality(rssi).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("$rssi dBm", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        SurfaceCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Firmware", style = MaterialTheme.typography.titleMedium)
                Text(
                    "El equipo se actualiza solo desde GitHub. Desde acá podés pedirle que busque ya.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { pending = DeviceCommand.CHECK },
                    enabled = connected,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(DeviceCommand.CHECK.title) }
                OutlinedButton(
                    onClick = { pending = DeviceCommand.OTA },
                    enabled = connected,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(DeviceCommand.OTA.title) }
            }
        }

        SurfaceCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Mantenimiento", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(
                    onClick = { pending = DeviceCommand.REBOOT },
                    enabled = connected,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(DeviceCommand.REBOOT.title) }
                if (!connected) {
                    Text(
                        "Para enviar comandos hace falta estar conectado al server.",
                        Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        SurfaceCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Registro de conexión", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Lo que hizo la app con el server desde que se abrió. Útil si algo no conecta.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.log.isEmpty()) {
                    Text("Todavía no hay eventos.", style = MaterialTheme.typography.bodyMedium)
                }
                state.log.asReversed().take(15).forEach { e ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Text(
                            Format.hour(e.ts),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(48.dp),
                        )
                        Text(
                            e.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (e.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }

    pending?.let { cmd ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(cmd.title) },
            text = { Text(cmd.question) },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    onCommand(cmd.payload, cmd.done)
                }) { Text("Confirmar") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/** Cuatro barritas de señal, como la de un celular. */
@Composable
private fun SignalBars(rssi: Int) {
    val level = when {
        rssi >= -55 -> 4
        rssi >= -67 -> 3
        rssi >= -75 -> 2
        rssi >= -85 -> 1
        else -> 0
    }
    val on = if (level >= 3) MaterialTheme.status.ok else if (level == 2) MaterialTheme.status.warn else MaterialTheme.colorScheme.error
    val off = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.size(56.dp, 44.dp)) {
        val w = size.width / 7f
        for (i in 0 until 4) {
            val h = size.height * (0.3f + 0.7f * (i + 1) / 4f)
            drawRoundRect(
                color = if (i < level) on else off,
                topLeft = Offset(i * 2f * w, size.height - h),
                size = Size(w * 1.3f, h),
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
        }
    }
}

private fun signalQuality(rssi: Int): String = when {
    rssi >= -55 -> "excelente"
    rssi >= -67 -> "buena"
    rssi >= -75 -> "regular"
    else -> "débil"
}

private fun uptime(sec: Long): String {
    val d = sec / 86400
    val h = sec % 86400 / 3600
    val m = sec % 3600 / 60
    return when {
        d > 0 -> "${d} d ${h} h"
        h > 0 -> "${h} h ${m} min"
        else -> "${m} min"
    }
}
