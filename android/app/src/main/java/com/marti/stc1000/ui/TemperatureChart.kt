package com.marti.stc1000.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marti.stc1000.data.Sample
import com.marti.stc1000.domain.Format
import kotlin.math.abs

/** Lecturas separadas por más que esto se dibujan con un corte (no se inventan datos en los huecos). */
private const val GAP_SEC = 900L

/**
 * Gráfico de línea con área degradada, hecho con Canvas (sin dependencias), con huecos reales
 * y selección táctil. El eje X cubre [startSec, endSec]; el eje Y se ajusta al rango de los datos.
 */
@Composable
fun TemperatureChart(
    samples: List<Sample>,
    startSec: Long,
    endSec: Long,
    modifier: Modifier = Modifier,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val markerColor = MaterialTheme.colorScheme.tertiary
    val surface = MaterialTheme.colorScheme.surfaceContainerLow
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = labelColor, fontSize = 11.sp)

    var selected by remember(samples) { mutableStateOf<Sample?>(null) }

    Column(modifier) {
        // Lectura de la muestra seleccionada (o ayuda), con altura fija para que no salte el layout.
        Box(Modifier.height(32.dp), contentAlignment = Alignment.CenterStart) {
            val s = selected
            if (s != null) {
                Text(
                    "${Format.dayHour(s.ts)}  ·  ${Format.temp(s.temp)}",
                    Modifier
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(50))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else if (samples.isNotEmpty()) {
                Text("Tocá o arrastrá para ver cada lectura", style = MaterialTheme.typography.bodySmall, color = labelColor)
            }
        }
        if (samples.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Todavía no hay datos en este período.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = labelColor,
                )
            }
            return@Column
        }

        val minT = samples.minOf { it.temp }
        val maxT = samples.maxOf { it.temp }
        val span = (maxT - minT).coerceAtLeast(2.0)
        val mid = (minT + maxT) / 2
        val yMin = mid - span / 2 - span * 0.1
        val yMax = mid + span / 2 + span * 0.1
        val xSpan = (endSec - startSec).coerceAtLeast(1).toDouble()

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .pointerInput(samples, startSec, endSec) {
                    val left = 44.dp.toPx()
                    val right = 8.dp.toPx()
                    val plotW = size.width - left - right
                    fun pick(x: Float) {
                        val t = startSec + ((x - left) / plotW).coerceIn(0f, 1f) * xSpan
                        selected = samples.minByOrNull { abs(it.ts - t) }
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        pick(down.position.x)
                        drag(down.id) {
                            pick(it.position.x)
                            it.consume()
                        }
                    }
                },
        ) {
            val left = 44.dp.toPx()
            val right = 8.dp.toPx()
            val top = 8.dp.toPx()
            val bottom = 22.dp.toPx()
            val plotW = size.width - left - right
            val plotH = size.height - top - bottom

            fun xOf(ts: Long) = left + ((ts - startSec) / xSpan).toFloat().coerceIn(0f, 1f) * plotW
            fun yOf(v: Double) = top + (1f - ((v - yMin) / (yMax - yMin)).toFloat()) * plotH

            // Grilla punteada + etiquetas de temperatura.
            val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))
            for (i in 0..3) {
                val v = yMin + (yMax - yMin) * i / 3
                val y = yOf(v)
                drawLine(gridColor, Offset(left, y), Offset(size.width - right, y), strokeWidth = 1f, pathEffect = dash)
                val label = measurer.measure(String.format(java.util.Locale.US, "%.1f", v).replace('.', ','), labelStyle)
                drawText(label, topLeft = Offset(left - label.size.width - 6.dp.toPx(), y - label.size.height / 2f))
            }
            // Etiquetas de tiempo.
            val longRange = endSec - startSec > 26 * 3600
            for (i in 0..4) {
                val ts = startSec + ((endSec - startSec) * i / 4)
                val text = if (longRange) Format.dayHour(ts).substringBefore(' ') else Format.hour(ts)
                val label = measurer.measure(text, labelStyle)
                val x = (xOf(ts) - label.size.width / 2f).coerceIn(0f, size.width - label.size.width)
                drawText(label, topLeft = Offset(x, size.height - label.size.height))
            }

            // Segmentos continuos (cortados donde hay huecos): área degradada + línea.
            val segments = ArrayList<MutableList<Sample>>()
            for (s in samples) {
                val last = segments.lastOrNull()?.lastOrNull()
                if (last == null || s.ts - last.ts > GAP_SEC) segments.add(mutableListOf(s)) else segments.last().add(s)
            }
            val baseY = top + plotH
            val fill = Brush.verticalGradient(
                colors = listOf(lineColor.copy(alpha = 0.28f), lineColor.copy(alpha = 0f)),
                startY = top,
                endY = baseY,
            )
            for (seg in segments) {
                if (seg.size < 2) {
                    drawCircle(lineColor, 3.5.dp.toPx(), Offset(xOf(seg[0].ts), yOf(seg[0].temp)))
                    continue
                }
                val line = Path()
                seg.forEachIndexed { i, s ->
                    val x = xOf(s.ts)
                    val y = yOf(s.temp)
                    if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
                }
                val area = Path().apply {
                    addPath(line)
                    lineTo(xOf(seg.last().ts), baseY)
                    lineTo(xOf(seg.first().ts), baseY)
                    close()
                }
                drawPath(area, fill)
                drawPath(line, lineColor, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }

            selected?.let { s ->
                val x = xOf(s.ts)
                val y = yOf(s.temp)
                drawLine(markerColor.copy(alpha = 0.5f), Offset(x, top), Offset(x, baseY), strokeWidth = 1.dp.toPx())
                drawCircle(surface, 7.dp.toPx(), Offset(x, y))
                drawCircle(markerColor, 5.dp.toPx(), Offset(x, y))
            }
        }
    }
}
