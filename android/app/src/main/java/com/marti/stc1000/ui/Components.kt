package com.marti.stc1000.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.marti.stc1000.mqtt.ConnectionState
import com.marti.stc1000.mqtt.isOnline

@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing()
    }
}

/** Tarjeta de superficie con borde sutil, usada en todas las pantallas. */
@Composable
fun SurfaceCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) { content() }
}

@Composable
fun Dot(color: Color, size: Int = 8) {
    Box(Modifier.size(size.dp).background(color, CircleShape))
}

/** Las dos píldoras del encabezado: conexión del celular con el server y estado del equipo (ESP32). */
@Composable
fun StatusPills(state: MainUiState) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ConnectionPill(state.connection, state.settings.isConfigured, prefix = "Server")
        DevicePill(state)
    }
}

/** Píldora del equipo: lo que publica el ESP32 en `estado`. Sin conexión al server no se sabe. */
@Composable
fun DevicePill(state: MainUiState) {
    val st = MaterialTheme.status
    val cs = MaterialTheme.colorScheme
    val known = state.connection == ConnectionState.Connected
    val online = state.device.isOnline(rememberClockSec())
    val (label, dot, bg) = when {
        !known -> Triple("—", cs.outline, cs.surfaceVariant)
        online == null -> Triple("Verificando…", st.warn, st.warnContainer)
        online == true -> Triple("En línea", st.ok, st.okContainer)
        else -> Triple("Sin conexión", cs.error, cs.errorContainer)
    }
    PillRow("Equipo: $label", dot, bg)
}

@Composable
private fun PillRow(text: String, dot: Color, bg: Color) {
    Row(
        Modifier
            .background(bg, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(dot)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Píldora de estado de la conexión MQTT. */
@Composable
fun ConnectionPill(conn: ConnectionState, configured: Boolean = true, prefix: String? = null) {
    val st = MaterialTheme.status
    val cs = MaterialTheme.colorScheme
    val (label, dot, bg) = when (conn) {
        ConnectionState.Connected -> Triple("Conectado", st.ok, st.okContainer)
        is ConnectionState.Connecting -> Triple(
            if (conn.attempt == 0) "Conectando" else "Reconectando", st.warn, st.warnContainer,
        )
        ConnectionState.WaitingNetwork -> Triple("Sin red", st.warn, st.warnContainer)
        is ConnectionState.Error -> Triple("Sin conexión", cs.error, cs.errorContainer)
        ConnectionState.Idle ->
            if (configured) Triple("Iniciando", cs.outline, cs.surfaceVariant) else Triple("Sin configurar", cs.outline, cs.surfaceVariant)
    }
    PillRow(if (prefix != null) "$prefix: $label" else label, dot, bg)
}
