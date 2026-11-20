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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marti.stc1000.data.Sample
import com.marti.stc1000.domain.DaySummary
import com.marti.stc1000.domain.Format
import com.marti.stc1000.domain.TempUnit
import kotlin.math.abs

/** Lecturas separadas por más que esto se dibujan con un corte (no se inventan datos en los huecos). */
private const val GAP_SEC = 900L

private fun axisNumber(celsius: Double, unit: TempUnit): String = Format.number(celsius, unit)

/** Partes continuas de la serie: se corta donde faltan más de [GAP_SEC] de datos. */
private fun segmentsOf(samples: List<Sample>): List<List<Sample>> {
    val out = ArrayList<MutableList<Sample>>()
    for (s in samples) {
        val last = out.lastOrNull()?.lastOrNull()
        if (last == null || s.ts - last.ts > GAP_SEC) out.add(mutableListOf(s)) else out.last().add(s)
    }
    return out
}

/**
 * Gráfico de línea con área degradada, hecho con Canvas (sin dependencias): curva suave opcional,
 * banda de rango deseado, línea de promedio, marcas de mínima y máxima, huecos reales y selección táctil.
 * El eje X cubre [startSec, endSec]; el eje Y se ajusta a los datos (y a la banda, si hay).
 */
@Composable
fun TemperatureChart(
    samples: List<Sample>,
    startSec: Long,
    endSec: Long,
    unit: TempUnit,
    modifier: Modifier = Modifier,
    smooth: Boolean = true,
    showAverage: Boolean = true,
    showMinMax: Boolean = true,
    range: ClosedFloatingPointRange<Double>? = null,
    chartHeight: Dp = 220.dp,
) {
    val cs = MaterialTheme.colorScheme
    val lineColor = cs.primary
    val gridColor = cs.outlineVariant
    val labelColor = cs.onSurfaceVariant
    val markerColor = cs.tertiary
    val bandColor = MaterialTheme.status.ok
    val surface = cs.surfaceContainerLow
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = labelColor, fontSize = 11.sp)

    var selected by remember(samples) { mutableStateOf<Sample?>(null) }

    Column(modifier) {
        // Lectura de la muestra seleccionada (o ayuda), con altura fija para que no salte el layout.
        Box(Modifier.height(32.dp), contentAlignment = Alignment.CenterStart) {
            val s = selected
            if (s != null) {
                Text(
                    "${Format.dayHour(s.ts)}  ·  ${Format.temp(s.temp, unit)}",
                    Modifier
                        .background(cs.surfaceContainerHigh, RoundedCornerShape(50))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.onSurface,
                )
            } else if (samples.isNotEmpty()) {
                Text("Tocá o arrastrá para ver cada lectura", style = MaterialTheme.typography.bodySmall, color = labelColor)
            }
        }
        if (samples.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(chartHeight - 40.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Todavía no hay datos en este período.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = labelColor,
                )
            }
            return@Column
        }

        val minS = samples.minBy { it.temp }
        val maxS = samples.maxBy { it.temp }
        val avg = samples.sumOf { it.temp } / samples.size
        var lo = minS.temp
        var hi = maxS.temp
        if (range != null) {
            lo = minOf(lo, range.start)
            hi = maxOf(hi, range.endInclusive)
        }
        val span = (hi - lo).coerceAtLeast(2.0)
        val mid = (lo + hi) / 2
        val yMin = mid - span / 2 - span * 0.12
        val yMax = mid + span / 2 + span * 0.12
        val xSpan = (endSec - startSec).coerceAtLeast(1).toDouble()
        val segments = remember(samples) { segmentsOf(samples) }

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(chartHeight)
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
            val top = 14.dp.toPx()
            val bottom = 22.dp.toPx()
            val plotW = size.width - left - right
            val plotH = size.height - top - bottom
            val baseY = top + plotH

            fun xOf(ts: Long) = left + ((ts - startSec) / xSpan).toFloat().coerceIn(0f, 1f) * plotW
            fun yOf(v: Double) = top + (1f - ((v - yMin) / (yMax - yMin)).toFloat()) * plotH

            // Banda de rango deseado.
            if (range != null) {
                val yTop = yOf(range.endInclusive).coerceIn(top, baseY)
                val yBot = yOf(range.start).coerceIn(top, baseY)
                drawRect(bandColor.copy(alpha = 0.10f), Offset(left, yTop), Size(plotW, yBot - yTop))
                val dashBand = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                drawLine(bandColor.copy(alpha = 0.6f), Offset(left, yTop), Offset(left + plotW, yTop), 1.dp.toPx(), pathEffect = dashBand)
                drawLine(bandColor.copy(alpha = 0.6f), Offset(left, yBot), Offset(left + plotW, yBot), 1.dp.toPx(), pathEffect = dashBand)
            }

            // Grilla punteada + etiquetas de temperatura.
            val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))
            for (i in 0..3) {
                val v = yMin + (yMax - yMin) * i / 3
                val y = yOf(v)
                drawLine(gridColor, Offset(left, y), Offset(size.width - right, y), strokeWidth = 1f, pathEffect = dash)
                val label = measurer.measure(axisNumber(v, unit), labelStyle)
                drawText(label, topLeft = Offset(left - label.size.width - 6.dp.toPx(), y - label.size.height / 2f))
            }
            // Etiquetas de tiempo.
            val longRange = endSec - startSec > 26 * 3600
            for (i in 0..4) {
                val ts = startSec + ((endSec - startSec) * i / 4)
                val text = if (longRange) Format.day(ts) else Format.hour(ts)
                val label = measurer.measure(text, labelStyle)
                val x = (xOf(ts) - label.size.width / 2f).coerceIn(0f, size.width - label.size.width)
                drawText(label, topLeft = Offset(x, size.height - label.size.height))
            }

            // Línea de promedio.
            if (showAverage && samples.size > 1) {
                val y = yOf(avg)
                drawLine(markerColor.copy(alpha = 0.55f), Offset(left, y), Offset(left + plotW, y), 1.2.dp.toPx(), pathEffect = dash)
                val label = measurer.measure("prom. " + axisNumber(avg, unit), labelStyle.copy(color = markerColor))
                drawText(label, topLeft = Offset(left + plotW - label.size.width - 2.dp.toPx(), y - label.size.height - 1.dp.toPx()))
            }

            // Área + línea por segmento.
            val fill = Brush.verticalGradient(
                colors = listOf(lineColor.copy(alpha = 0.30f), lineColor.copy(alpha = 0f)),
                startY = top,
                endY = baseY,
            )
            for (seg in segments) {
                if (seg.size < 2) {
                    drawCircle(lineColor, 3.5.dp.toPx(), Offset(xOf(seg[0].ts), yOf(seg[0].temp)))
                    continue
                }
                val line = Path()
                var px = 0f
                var py = 0f
                seg.forEachIndexed { i, s ->
                    val x = xOf(s.ts)
                    val y = yOf(s.temp)
                    when {
                        i == 0 -> line.moveTo(x, y)
                        smooth -> {
                            val cx = (px + x) / 2f
                            line.cubicTo(cx, py, cx, y, x, y) // tangentes horizontales: curva suave sin sobrepasos
                        }
                        else -> line.lineTo(x, y)
                    }
                    px = x
                    py = y
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

            // Marcas de mínima y máxima.
            if (showMinMax && samples.size > 1 && maxS.temp - minS.temp > 0.05) {
                markExtreme(maxS, true, ::xOf, ::yOf, measurer, labelStyle, unit, lineColor, surface, top, size.width)
                markExtreme(minS, false, ::xOf, ::yOf, measurer, labelStyle, unit, lineColor, surface, top, size.width)
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

private fun DrawScope.markExtreme(
    s: Sample,
    isMax: Boolean,
    xOf: (Long) -> Float,
    yOf: (Double) -> Float,
    measurer: TextMeasurer,
    style: TextStyle,
    unit: TempUnit,
    color: Color,
    ring: Color,
    top: Float,
    width: Float,
) {
    val x = xOf(s.ts)
    val y = yOf(s.temp)
    drawCircle(ring, 5.dp.toPx(), Offset(x, y))
    drawCircle(color, 3.5.dp.toPx(), Offset(x, y))
    val label = measurer.measure(axisNumber(s.temp, unit), style.copy(color = color))
    val lx = (x - label.size.width / 2f).coerceIn(0f, width - label.size.width)
    val ly = if (isMax) (y - label.size.height - 6.dp.toPx()).coerceAtLeast(0f) else y + 6.dp.toPx()
    drawText(label, topLeft = Offset(lx, ly))
}

/**
 * Barras por día: de la mínima a la máxima, con un punto en el promedio.
 * Sirve para ver de un vistazo cómo se comportó cada día de la semana o del mes.
 */
@Composable
fun DailyRangeChart(
    days: List<DaySummary>,
    unit: TempUnit,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Double>? = null,
    chartHeight: Dp = 200.dp,
) {
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = cs.onSurfaceVariant, fontSize = 11.sp)
    val bandColor = MaterialTheme.status.ok
    val barTop = cs.tertiary
    val barBottom = cs.primary
    val gridColor = cs.outlineVariant
    val ring = cs.surfaceContainerLow

    if (days.isEmpty()) {
        Box(modifier.fillMaxWidth().height(chartHeight - 60.dp), contentAlignment = Alignment.Center) {
            Text("Sin datos para resumir.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        }
        return
    }
    var lo = days.minOf { it.min }
    var hi = days.maxOf { it.max }
    if (range != null) {
        lo = minOf(lo, range.start)
        hi = maxOf(hi, range.endInclusive)
    }
    val span = (hi - lo).coerceAtLeast(2.0)
    val yMin = lo - span * 0.12
    val yMax = hi + span * 0.12

    Canvas(modifier.fillMaxWidth().height(chartHeight)) {
        val left = 44.dp.toPx()
        val right = 8.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = 22.dp.toPx()
        val plotW = size.width - left - right
        val plotH = size.height - top - bottom
        fun yOf(v: Double) = top + (1f - ((v - yMin) / (yMax - yMin)).toFloat()) * plotH

        if (range != null) {
            val yT = yOf(range.endInclusive).coerceIn(top, top + plotH)
            val yB = yOf(range.start).coerceIn(top, top + plotH)
            drawRect(bandColor.copy(alpha = 0.10f), Offset(left, yT), Size(plotW, yB - yT))
        }
        val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))
        for (i in 0..3) {
            val v = yMin + (yMax - yMin) * i / 3
            val y = yOf(v)
            drawLine(gridColor, Offset(left, y), Offset(size.width - right, y), strokeWidth = 1f, pathEffect = dash)
            val label = measurer.measure(axisNumber(v, unit), labelStyle)
            drawText(label, topLeft = Offset(left - label.size.width - 6.dp.toPx(), y - label.size.height / 2f))
        }

        val slot = plotW / days.size
        val barW = (slot * 0.55f).coerceAtMost(26.dp.toPx())
        val every = if (days.size <= 8) 1 else if (days.size <= 16) 2 else 5
        days.forEachIndexed { i, d ->
            val cx = left + slot * (i + 0.5f)
            val yTop = yOf(d.max)
            val yBot = yOf(d.min)
            val h = (yBot - yTop).coerceAtLeast(4.dp.toPx())
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(barTop, barBottom), startY = yTop, endY = yTop + h),
                topLeft = Offset(cx - barW / 2, yTop),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2, barW / 2),
            )
            drawCircle(ring, 4.5.dp.toPx(), Offset(cx, yOf(d.avg)))
            drawCircle(cs.onSurface, 2.8.dp.toPx(), Offset(cx, yOf(d.avg)))
            if (i % every == 0) {
                val label = measurer.measure(Format.day(d.date.atStartOfDay(java.time.ZoneId.systemDefault()).toEpochSecond()), labelStyle)
                drawText(label, topLeft = Offset((cx - label.size.width / 2f).coerceIn(0f, size.width - label.size.width), size.height - label.size.height))
            }
        }
    }
}

/** Mini gráfico sin ejes para la tarjeta principal: la tendencia de las últimas horas. */
@Composable
fun Sparkline(samples: List<Sample>, color: Color, modifier: Modifier = Modifier) {
    if (samples.size < 2) return
    val minT = samples.minOf { it.temp }
    val maxT = samples.maxOf { it.temp }
    val span = (maxT - minT).coerceAtLeast(0.6)
    val t0 = samples.first().ts
    val t1 = samples.last().ts
    val segments = remember(samples) { segmentsOf(samples) }
    Canvas(modifier) {
        val pad = 3.dp.toPx()
        fun xOf(ts: Long) = pad + ((ts - t0).toFloat() / (t1 - t0).coerceAtLeast(1)) * (size.width - 2 * pad)
        fun yOf(v: Double) = pad + (1f - ((v - minT) / span).toFloat()) * (size.height - 2 * pad)
        for (seg in segments) {
            if (seg.size < 2) continue
            val p = Path()
            seg.forEachIndexed { i, s ->
                if (i == 0) p.moveTo(xOf(s.ts), yOf(s.temp)) else p.lineTo(xOf(s.ts), yOf(s.temp))
            }
            drawPath(p, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        val last = samples.last()
        drawCircle(color, 3.5.dp.toPx(), Offset(xOf(last.ts), yOf(last.temp)))
    }
}
