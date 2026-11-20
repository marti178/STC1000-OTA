package com.marti.stc1000.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marti.stc1000.domain.Format
import com.marti.stc1000.domain.Period
import com.marti.stc1000.domain.Stats
import com.marti.stc1000.mqtt.ConnectionState

private const val STALE_AFTER_SEC = 15 * 60L

@Composable
fun HomeScreen(
    state: MainUiState,
    onPeriod: (Period) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ConnectionBanner(state.connection, onRetry)
        CurrentCard(state)
        ChartCard(state, onPeriod)
        StatsCard(state.stats)
    }
}

@Composable
fun ConnectionBanner(conn: ConnectionState, onRetry: () -> Unit) {
    val (text, isError) = when (conn) {
        ConnectionState.Idle -> "Sin conexión (configurá el server en Ajustes)" to false
        ConnectionState.WaitingNetwork -> "Sin red. Esperando conexión…" to true
        is ConnectionState.Connecting ->
            (if (conn.attempt == 0) "Conectando…" else "Reconectando… (intento ${conn.attempt + 1})") to false
        ConnectionState.Connected -> "Conectado al server" to false
        is ConnectionState.Error ->
            (conn.message + (conn.retryInSec?.let { " Reintento en $it s." } ?: "")) to true
    }
    val container = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val content = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Card(colors = CardDefaults.cardColors(containerColor = container, contentColor = content)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (conn is ConnectionState.Error) TextButton(onClick = onRetry) { Text("Reintentar") }
        }
    }
}

@Composable
private fun CurrentCard(state: MainUiState) {
    val device = state.device
    val problem = Format.displayProblem(device.display)
    val temp = device.temperature ?: state.lastSample?.temp
    val lastDataSec = listOfNotNull(device.lastLiveAtSec, state.lastSample?.ts).maxOrNull()
    val ageSec = lastDataSec?.let { state.nowSec - it }
    val stale = ageSec != null && ageSec > STALE_AFTER_SEC

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        ) {
            when {
                problem != null -> Text(
                    problem,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                )
                temp != null -> Text(
                    Format.temp(temp),
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (stale) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                else -> Text("— —", fontSize = 64.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                when (device.online) {
                    true -> "Equipo en línea"
                    false -> "Equipo desconectado"
                    null -> "Estado del equipo desconocido"
                },
                style = MaterialTheme.typography.titleMedium,
                color = if (device.online == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Text(
                if (ageSec != null) "Último dato: ${Format.age(ageSec)}" else "Todavía no llegó ningún dato",
                style = MaterialTheme.typography.bodyMedium,
                color = if (stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChartCard(state: MainUiState, onPeriod: (Period) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Period.entries.forEachIndexed { i, p ->
                    SegmentedButton(
                        selected = state.period == p,
                        onClick = { onPeriod(p) },
                        shape = SegmentedButtonDefaults.itemShape(i, Period.entries.size),
                    ) { Text(p.label) }
                }
            }
            TemperatureChart(
                samples = state.samples,
                startSec = state.period.startEpoch(state.nowSec),
                endSec = state.nowSec,
            )
        }
    }
}

@Composable
private fun StatsCard(stats: Stats?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Estadísticas del período", style = MaterialTheme.typography.titleMedium)
            if (stats == null) {
                Text("Sin datos en este período.", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            Row(Modifier.fillMaxWidth()) {
                StatItem("Mínima", Format.temp(stats.min), "a las ${Format.hour(stats.minTs)}", Modifier.weight(1f))
                StatItem("Máxima", Format.temp(stats.max), "a las ${Format.hour(stats.maxTs)}", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                StatItem("Promedio", Format.temp(stats.avg), null, Modifier.weight(1f))
                StatItem("Muestras", stats.count.toString(), null, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, sub: String?, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
