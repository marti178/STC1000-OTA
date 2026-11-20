package com.marti.stc1000.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.marti.stc1000.mqtt.ConnectionState
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
        ScreenHeader("Equipo", "ESP32 espía del STC-1000") { ConnectionPill(state.connection) }

        SurfaceCard {
            Column(Modifier.padding(16.dp)) {
                Text("Estado", style = MaterialTheme.typography.titleMedium)
                val rows = buildList {
                    add("Estado" to when (d.online) { true -> "En línea"; false -> "Desconectado"; null -> "Desconocido" })
                    add("Firmware" to (d.firmware ?: info?.version ?: "—"))
                    add("Dirección IP" to (info?.ip ?: "—"))
                    add("Señal Wi-Fi" to (info?.rssi?.let { "$it dBm · ${signalQuality(it)}" } ?: "—"))
                    add("Encendido hace" to (info?.uptimeSec?.let { uptime(it) } ?: "—"))
                    add("Reconexiones Wi-Fi" to (info?.wifiReconnects?.toString() ?: "—"))
                    add("Reconexiones MQTT" to (info?.mqttReconnects?.toString() ?: "—"))
                    info?.unknown?.takeIf { it.isNotEmpty() }?.let { add("Desconocidos" to it) }
                    d.ota?.takeIf { it.isNotEmpty() }?.let { add("Actualización" to it) }
                }
                rows.forEachIndexed { i, (k, v) ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Line(k, v)
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
