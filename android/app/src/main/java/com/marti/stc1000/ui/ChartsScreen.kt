package com.marti.stc1000.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.marti.stc1000.domain.Format
import com.marti.stc1000.domain.Period

@Composable
fun ChartsScreen(
    state: MainUiState,
    onPeriod: (Period) -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unit = LocalTempUnit.current
    val p = state.prefs
    val range = if (p.rangeEnabled) p.rangeMin..p.rangeMax else null
    val stats = state.stats

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader("Gráficos", "Historial y estadísticas")
        PeriodSelector(state.period, onPeriod)

        SurfaceCard {
            Column(Modifier.padding(16.dp)) {
                TemperatureChart(
                    samples = state.samples,
                    startSec = state.period.startEpoch(state.nowSec),
                    endSec = maxOf(state.nowSec, state.samples.lastOrNull()?.ts ?: 0L),
                    unit = unit,
                    smooth = p.smooth,
                    showAverage = p.showAverage,
                    showMinMax = p.showMinMax,
                    range = range,
                    chartHeight = 280.dp,
                )
            }
        }

        if (stats != null) {
            Text("Resumen", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Mínima", Format.temp(stats.min, unit), Format.dayHour(stats.minTs), "↓", Color(0xFF2A7FD6), Modifier.weight(1f))
                StatTile("Máxima", Format.temp(stats.max, unit), Format.dayHour(stats.maxTs), "↑", Color(0xFFE0612B), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Promedio", Format.temp(stats.avg, unit), "mediana ${Format.temp(stats.median, unit)}", "≈", Color(0xFF1B8A5A), Modifier.weight(1f))
                StatTile(
                    "Variación",
                    "± " + String.format(java.util.Locale.US, "%.2f", unit.convertDelta(stats.stdDev)).replace('.', ',') + "°",
                    "desvío estándar",
                    "σ",
                    Color(0xFF7E57C2),
                    Modifier.weight(1f),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Muestras", stats.count.toString(), coverageText(state), "#", Color(0xFF00897B), Modifier.weight(1f))
                if (state.inRange != null) {
                    StatTile(
                        "En rango",
                        Format.percent(state.inRange),
                        "${Format.number(p.rangeMin, unit)} a ${Format.number(p.rangeMax, unit)} ${unit.symbol}",
                        "✓",
                        MaterialTheme.status.ok,
                        Modifier.weight(1f),
                    )
                } else {
                    StatTile("Rango", String.format(java.util.Locale.US, "%.1f", unit.convertDelta(stats.max - stats.min)).replace('.', ',') + "°", "entre mín. y máx.", "↕", Color(0xFFF08A24), Modifier.weight(1f))
                }
            }
        }

        if (state.period == Period.LAST_7D || state.period == Period.LAST_30D) {
            SurfaceCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Resumen por día", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Cada barra va de la mínima a la máxima del día; el punto es el promedio.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    DailyRangeChart(state.days, unit, range = range)
                }
            }
            SurfaceCard {
                Column(Modifier.padding(16.dp)) {
                    Text("Detalle diario", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
                        HeaderCell("Día", 1.6f, TextAlign.Start)
                        HeaderCell("Mín.", 1f)
                        HeaderCell("Prom.", 1f)
                        HeaderCell("Máx.", 1f)
                    }
                    state.days.asReversed().forEach { d ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(Format.dayName(d.date), Modifier.weight(1.6f), style = MaterialTheme.typography.bodyMedium)
                            Cell(Format.number(d.min, unit), Modifier.weight(1f))
                            Cell(Format.number(d.avg, unit), Modifier.weight(1f))
                            Cell(Format.number(d.max, unit), Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        OutlinedButton(
            onClick = onExport,
            enabled = state.allSamples.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Exportar lecturas a CSV") }
    }
}

private fun coverageText(state: MainUiState): String {
    val start = state.period.startEpoch(state.nowSec)
    val expected = ((state.nowSec - start) / 300).coerceAtLeast(1)
    val pct = (state.samples.size * 100.0 / expected).coerceAtMost(100.0)
    return String.format(java.util.Locale.US, "%.0f %% del período", pct)
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.HeaderCell(text: String, weight: Float, align: TextAlign = TextAlign.End) {
    Text(
        text,
        Modifier.weight(weight),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = align,
    )
}

@Composable
private fun Cell(text: String, modifier: Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, fontWeight = FontWeight.Medium)
}
