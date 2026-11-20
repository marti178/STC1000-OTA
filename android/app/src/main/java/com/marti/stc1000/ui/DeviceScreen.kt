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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Estado del equipo", style = MaterialTheme.typography.titleMedium)
                val info: DeviceInfo? = d.info
                Line("Estado", when (d.online) { true -> "en línea"; false -> "desconectado"; null -> "desconocido" })
                Line("Firmware", d.firmware ?: info?.version ?: "—")
                Line("Dirección IP", info?.ip ?: "—")
                Line("Señal Wi-Fi", info?.rssi?.let { "$it dBm" } ?: "—")
                Line("Encendido hace", info?.uptimeSec?.let { uptime(it) } ?: "—")
                Line("Reconexiones Wi-Fi", info?.wifiReconnects?.toString() ?: "—")
                Line("Reconexiones MQTT", info?.mqttReconnects?.toString() ?: "—")
                info?.unknown?.takeIf { it.isNotEmpty() }?.let { Line("Desconocidos", it) }
                if (!d.ota.isNullOrEmpty()) Line("Actualización", d.ota ?: "")
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Acciones", style = MaterialTheme.typography.titleMedium)
                if (!connected) {
                    Text(
                        "Hace falta estar conectado al server para enviar comandos.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = { pending = DeviceCommand.CHECK }, enabled = connected, modifier = Modifier.fillMaxWidth()) {
                    Text(DeviceCommand.CHECK.title)
                }
                OutlinedButton(onClick = { pending = DeviceCommand.OTA }, enabled = connected, modifier = Modifier.fillMaxWidth()) {
                    Text(DeviceCommand.OTA.title)
                }
                OutlinedButton(onClick = { pending = DeviceCommand.REBOOT }, enabled = connected, modifier = Modifier.fillMaxWidth()) {
                    Text(DeviceCommand.REBOOT.title)
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
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
