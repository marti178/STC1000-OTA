package com.marti.stc1000.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marti.stc1000.domain.Format
import com.marti.stc1000.domain.Period
import com.marti.stc1000.domain.Stats
import com.marti.stc1000.mqtt.ConnectionState
import com.marti.stc1000.mqtt.OutputState
import com.marti.stc1000.mqtt.otaPercent
import androidx.compose.material3.LinearProgressIndicator

private const val STALE_AFTER_SEC = 15 * 60L
private const val SPARK_WINDOW_SEC = 3 * 3600L

@Composable
fun HomeScreen(
    state: MainUiState,
    onPeriod: (com.marti.stc1000.domain.Period) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader("Temperatura", "Termostato STC-1000") {
            StatusPills(state)
        }
        ProblemBanner(state.connection, state.loaded && !state.settings.isConfigured, onRetry)
        OtaBanner(state)
        RangeBanner(state)
        HeroCard(state)
        ChartCard(state, onPeriod)
        StatsGrid(state.stats, long = state.period == Period.LAST_7D || state.period == Period.LAST_30D)
    }
}

/** Mensaje largo de la conexión, solo cuando hay algo que explicar. */
@Composable
private fun ProblemBanner(conn: ConnectionState, notConfigured: Boolean, onRetry: () -> Unit) {
    val text = when (conn) {
        ConnectionState.Idle -> if (notConfigured) "Todavía no configuraste el server. Andá a Ajustes." else return
        ConnectionState.WaitingNetwork -> "Sin red. Esperando conexión…"
        is ConnectionState.Error -> conn.message + (conn.retryInSec?.let { " Reintento en $it s." } ?: "")
        else -> return
    }
    val isError = conn is ConnectionState.Error
    val cs = MaterialTheme.colorScheme
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (isError) cs.errorContainer else cs.surfaceVariant,
            contentColor = if (isError) cs.onErrorContainer else cs.onSurfaceVariant,
        ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (isError) TextButton(onClick = onRetry) { Text("Reintentar") }
        }
    }
}

/** Avance de la actualizacion del firmware; se oculta tras 1 min sin mensajes. */
@Composable
private fun OtaBanner(state: MainUiState) {
    val text = state.device.ota?.takeIf { it.isNotBlank() } ?: return
    val at = state.device.otaAtSec ?: return
    if (state.connection != ConnectionState.Connected || state.nowSec - at > OTA_SHOW_SEC) return
    val pct = otaPercent(text)
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .background(cs.secondaryContainer, MaterialTheme.shapes.medium)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            if (pct != null) "Actualizando el equipo… $pct %" else "Equipo: $text",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSecondaryContainer,
        )
        if (pct != null) LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
    }
}

private const val OTA_SHOW_SEC = 60L

/** Aviso cuando la temperatura actual está fuera del rango deseado (si está activado en Preferencias). */
@Composable
private fun RangeBanner(state: MainUiState) {
    val p = state.prefs
    val t = state.device.temperature ?: state.lastSample?.temp
    // No se juzga el rango con un dato viejo, un display con error o sin conexion al server.
    val ageSec = liveAgeSec(state)
    val fresh = state.connection == ConnectionState.Connected && ageSec != null && ageSec <= STALE_AFTER_SEC
    if (!p.rangeEnabled || t == null || !fresh || Format.displayProblem(state.device.display) != null) return
    val unit = LocalTempUnit.current
    val (text, bad) = when {
        t < p.rangeMin -> "Por debajo del rango deseado (mínimo ${Format.temp(p.rangeMin, unit)})" to true
        t > p.rangeMax -> "Por encima del rango deseado (máximo ${Format.temp(p.rangeMax, unit)})" to true
        else -> "Dentro del rango deseado (${Format.number(p.rangeMin, unit)} a ${Format.temp(p.rangeMax, unit)})" to false
    }
    val cs = MaterialTheme.colorScheme
    val st = MaterialTheme.status
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (bad) cs.errorContainer else st.okContainer, MaterialTheme.shapes.medium)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(if (bad) cs.error else st.ok, 10)
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (bad) cs.onErrorContainer else cs.onSurface,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroCard(state: MainUiState) {
    val unit = LocalTempUnit.current
    val device = state.device
    val problem = Format.displayProblem(device.display)
    val temp = device.temperature ?: state.lastSample?.temp
    val ageSec = liveAgeSec(state)
    val stale = ageSec != null && ageSec > STALE_AFTER_SEC
    val connected = state.connection == ConnectionState.Connected
    val outOfRange = state.prefs.rangeEnabled && temp != null &&
        (temp < state.prefs.rangeMin || temp > state.prefs.rangeMax)

    val (c1, c2) = when {
        problem != null || device.online == false || stale || !connected -> heroColors(null)
        outOfRange -> Color(0xFFE53935) to Color(0xFF8E1B1B)
        else -> heroColors(temp)
    }
    val top by animateColorAsState(c1, label = "heroTop")
    val bottom by animateColorAsState(c2, label = "heroBottom")
    val spark = state.allSamples.filter { it.ts >= state.nowSec - SPARK_WINDOW_SEC }

    Box(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(Brush.linearGradient(listOf(top, bottom))),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "TEMPERATURA ACTUAL",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.92f),
            )
            when {
                problem != null -> Text(
                    problem,
                    modifier = Modifier.padding(vertical = 12.dp),
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                )
                temp != null -> Row(verticalAlignment = Alignment.Top) {
                    Text(
                        Format.number(temp, unit),
                        fontSize = 76.sp,
                        lineHeight = 84.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Text(
                        unit.symbol,
                        Modifier.padding(top = 12.dp, start = 4.dp),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }
                else -> Text(
                    "— —",
                    fontSize = 76.sp,
                    lineHeight = 84.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
            if (spark.size >= 2 && problem == null) {
                Sparkline(
                    spark,
                    Color.White.copy(alpha = 0.9f),
                    Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                )
                Text(
                    "últimas 3 h",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Sin conexion al server, el "en linea" que quedo en memoria es un dato viejo.
                HeroChip(
                    when {
                        !connected -> "Sin conexión al server"
                        device.online == true -> "En línea"
                        device.online == false -> "Desconectado"
                        else -> "Estado desconocido"
                    },
                    dot = when {
                        !connected -> Color(0xFFFFD27A)
                        device.online == true -> Color(0xFF7CF2B4)
                        device.online == false -> Color(0xFFFF9C94)
                        else -> Color.White.copy(alpha = 0.6f)
                    },
                )
                HeroChip(
                    if (ageSec != null) Format.age(ageSec) else "Sin datos",
                    dot = if (stale) Color(0xFFFFD27A) else null,
                )
                // Que hace el termostato: solo con datos al dia y el equipo en linea.
                val outs = device.outputs
                if (connected && device.online == true && outs != null) {
                    val text = outputsText(outs, withIdle = false)
                    if (text.isNotEmpty()) {
                        HeroChip(
                            text,
                            dot = when {
                                outs.heat == OutputState.ON -> Color(0xFFFFB066)
                                outs.cool == OutputState.ON -> Color(0xFF8CC8FF)
                                outs.heat == OutputState.BLINK || outs.cool == OutputState.BLINK -> Color(0xFFFFD27A)
                                else -> null
                            },
                        )
                    }
                }
                state.trend?.let { d ->
                    val arrow = when {
                        d > 0.2 -> "↑"
                        d < -0.2 -> "↓"
                        else -> "→"
                    }
                    HeroChip("$arrow ${Format.delta(d, unit)} / 30 min", dot = null)
                }
            }
        }
    }
}

@Composable
private fun HeroChip(text: String, dot: Color?) {
    Row(
        Modifier
            .background(Color.Black.copy(alpha = 0.18f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Dot(dot)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = Color.White)
    }
}

/** Selector de período compartido por Inicio y Gráficos. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeriodSelector(selected: Period, onSelect: (Period) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        Period.entries.forEachIndexed { i, p ->
            SegmentedButton(
                selected = selected == p,
                onClick = { onSelect(p) },
                shape = SegmentedButtonDefaults.itemShape(i, Period.entries.size),
                icon = {},
            ) { Text(p.label, maxLines = 1) }
        }
    }
}

@Composable
private fun ChartCard(state: MainUiState, onPeriod: (Period) -> Unit) {
    val p = state.prefs
    SurfaceCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Evolución", style = MaterialTheme.typography.titleMedium)
            PeriodSelector(state.period, onPeriod)
            TemperatureChart(
                samples = state.samples,
                startSec = state.period.startEpoch(state.nowSec),
                // Las lecturas en vivo se guardan al cierre de su intervalo de 5 min (puede ser unos
                // minutos a futuro); el eje X tiene que llegar hasta ahí o el último punto se aplasta.
                endSec = maxOf(state.nowSec, state.samples.lastOrNull()?.ts ?: 0L),
                unit = LocalTempUnit.current,
                smooth = p.smooth,
                showAverage = p.showAverage,
                showMinMax = p.showMinMax,
                range = if (p.rangeEnabled) p.rangeMin..p.rangeMax else null,
                zoomKey = state.period,
            )
        }
    }
}

@Composable
private fun StatsGrid(stats: Stats?, long: Boolean) {
    val unit = LocalTempUnit.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Estadísticas del período", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 4.dp))
        if (stats == null) {
            SurfaceCard {
                Text(
                    "Sin datos en este período.",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("Mínima", Format.temp(stats.min, unit), whenText(stats.minTs, long), "↓", Color(0xFF2A7FD6), Modifier.weight(1f))
            StatTile("Máxima", Format.temp(stats.max, unit), whenText(stats.maxTs, long), "↑", Color(0xFFE0612B), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("Promedio", Format.temp(stats.avg, unit), "del período", "≈", Color(0xFF1B8A5A), Modifier.weight(1f))
            StatTile("Muestras", stats.count.toString(), "lecturas", "#", Color(0xFF7E57C2), Modifier.weight(1f))
        }
    }
}

@Composable
fun StatTile(label: String, value: String, sub: String, glyph: String, accent: Color, modifier: Modifier = Modifier) {
    SurfaceCard(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(28.dp)
                        .background(accent.copy(alpha = 0.16f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(glyph, color = accent, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Dice "a las 14:05" dentro de un dia y "20/11 14:05" cuando el periodo abarca varios. */
private fun whenText(ts: Long, long: Boolean): String =
    if (long) Format.dayHour(ts) else "a las ${Format.hour(ts)}"

/**
 * Hace cuantos segundos llego el ultimo dato. Usa la lectura en vivo si la hay; si no, la ultima muestra del
 * historial (recortada a "ahora": las lecturas en vivo se rotulan al cierre de su intervalo, a futuro).
 */
fun liveAgeSec(state: MainUiState): Long? {
    val at = state.device.lastLiveAtSec ?: state.lastSample?.ts?.coerceAtMost(state.nowSec)
    return at?.let { (state.nowSec - it).coerceAtLeast(0) }
}
