package com.marti.stc1000.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
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

/** Maximo de puntos que se dibujan por tramo continuo. */
private const val MAX_POINTS_PER_SEGMENT = 600

/**
 * Reduce un tramo a unos [max] puntos conservando la forma: de cada grupo de muestras queda la minima y la
 * maxima (en el orden en que ocurrieron), asi no se pierden los picos.
 */
internal fun decimate(seg: List<Sample>, max: Int): List<Sample> {
    if (seg.size <= max) return seg
    val groupSize = (seg.size + max / 2 - 1) / (max / 2)
    val out = ArrayList<Sample>(max + 2)
    var i = 0
    while (i < seg.size) {
        val end = minOf(i + groupSize, seg.size)
        var lo = seg[i]
        var hi = seg[i]
        for (j in i until end) {
            if (seg[j].temp < lo.temp) lo = seg[j]
            if (seg[j].temp > hi.temp) hi = seg[j]
        }
        if (lo.ts <= hi.ts) {
            out.add(lo)
            if (hi !== lo) out.add(hi)
        } else {
            out.add(hi)
            out.add(lo)
        }
        i = end
    }
    return out
}

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

/** Ventana visible del eje X, en epoch segundos. */
internal data class ViewWindow(val start: Double, val end: Double)

/** Zoom maximo: no se acerca mas que esto (30 min = 6 lecturas de 5 min). */
private const val MIN_VIEW_SPAN_SEC = 1800.0

/**
 * Calcula la nueva ventana visible despues de un gesto de pellizco/arrastre con dos dedos.
 * [zoom] > 1 acerca; [panPx] es lo que se movieron los dedos (el contenido los acompana); [focusPx] es el punto
 * (relativo al borde izquierdo del grafico) que queda fijo bajo los dedos. Devuelve null si queda toda la serie.
 */
internal fun transformWindow(
    viewStart: Double,
    viewEnd: Double,
    fullStart: Double,
    fullEnd: Double,
    zoom: Float,
    panPx: Float,
    focusPx: Float,
    plotW: Float,
): ViewWindow? {
    val fullSpan = fullEnd - fullStart
    if (fullSpan <= MIN_VIEW_SPAN_SEC || plotW <= 0f) return null
    val span = viewEnd - viewStart
    val newSpan = (span / zoom.coerceIn(0.2f, 5f)).coerceIn(MIN_VIEW_SPAN_SEC, fullSpan)
    val focusFrac = (focusPx / plotW).coerceIn(0f, 1f).toDouble()
    val focusT = viewStart + focusFrac * span
    val start = (focusT - focusFrac * newSpan - (panPx / plotW) * newSpan)
        .coerceIn(fullStart, fullEnd - newSpan)
    return if (newSpan >= fullSpan * 0.995) null else ViewWindow(start, start + newSpan)
}

/**
 * Gráfico de línea con área degradada, hecho con Canvas (sin dependencias): curva suave opcional,
 * banda de rango deseado, línea de promedio, marcas de mínima y máxima, huecos reales y selección táctil.
 * Se puede hacer zoom pellizcando con dos dedos y moverse arrastrando con dos dedos. [zoomKey] vuelve a la vista
 * completa cuando cambia (por ejemplo al elegir otro período).
 * El eje X cubre [startSec, endSec] (o la parte con zoom); el eje Y se ajusta a lo que se ve (y a la banda, si hay).
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
    zoomKey: Any = Unit,
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

    // La seleccion se guarda como timestamp y los gestos leen siempre los datos actuales: asi llegar un
    // dato nuevo no la borra ni corta el arrastre.
    var selectedTs by remember { mutableStateOf<Long?>(null) }
    val selected = selectedTs?.let { ts -> samples.firstOrNull { it.ts == ts } }
    var zoom by remember(zoomKey) { mutableStateOf<ViewWindow?>(null) }

    // Ventana visible, siempre dentro del rango completo aunque este se haya movido (llegan datos nuevos).
    val fullStart = startSec.toDouble()
    val fullEnd = endSec.toDouble()
    val z = zoom
    val viewSpanOk = fullEnd - fullStart > MIN_VIEW_SPAN_SEC
    val vs = if (z != null && viewSpanOk) {
        z.start.coerceIn(fullStart, fullEnd - MIN_VIEW_SPAN_SEC)
    } else {
        fullStart
    }
    val ve = if (z != null && viewSpanOk) z.end.coerceIn(vs + MIN_VIEW_SPAN_SEC, fullEnd) else fullEnd
    val zoomed = z != null && viewSpanOk
    val vSpan = (ve - vs).coerceAtLeast(1.0)

    val currentSamples by rememberUpdatedState(samples)
    val currentWindow by rememberUpdatedState(ViewWindow(vs, ve))
    val currentFull by rememberUpdatedState(ViewWindow(fullStart, fullEnd))

    Column(modifier) {
        // Lectura de la muestra seleccionada (o ayuda), con altura fija para que no salte el layout.
        Row(
            Modifier
                .fillMaxWidth()
                .height(36.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
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
                Text(
                    if (zoomed) {
                        "Zoom ×" + String.format(java.util.Locale.US, "%.1f", (fullEnd - fullStart) / vSpan)
                    } else {
                        "Tocá una lectura · pellizcá para hacer zoom"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = labelColor,
                )
            } else {
                Spacer(Modifier)
            }
            if (zoomed) TextButton(onClick = { zoom = null }) { Text("Restablecer") }
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

        // Lo que se ve (con un poco de margen para que la linea entre y salga de la ventana sin cortes).
        val margin = 600.0
        val visible = remember(samples, vs, ve) {
            if (!zoomed) samples else samples.filter { it.ts >= vs - margin && it.ts <= ve + margin }
        }
        val inView = remember(visible, vs, ve) { visible.filter { it.ts >= vs && it.ts <= ve } }
        val stat = inView.ifEmpty { visible.ifEmpty { samples } }
        val minS = stat.minBy { it.temp }
        val maxS = stat.maxBy { it.temp }
        val avg = stat.sumOf { it.temp } / stat.size
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
        // Para dibujar se reducen los puntos (30 dias son 8640): el calculo de estadisticas usa todos.
        val segments = remember(visible) { segmentsOf(visible).map { decimate(it, MAX_POINTS_PER_SEGMENT) } }
        val description = "Gráfico de temperatura. Mínima ${Format.temp(minS.temp, unit)}, máxima " +
            "${Format.temp(maxS.temp, unit)}, promedio ${Format.temp(avg, unit)}, ${stat.size} lecturas."

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(chartHeight)
                .semantics { contentDescription = description }
                .pointerInput(zoomKey) {
                    val left = 44.dp.toPx()
                    val right = 8.dp.toPx()
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val plotW = size.width - left - right
                        fun pick(x: Float) {
                            val w = currentWindow
                            val t = w.start + ((x - left) / plotW).coerceIn(0f, 1f) * (w.end - w.start)
                            selectedTs = currentSamples.minByOrNull { abs(it.ts - t) }?.ts
                        }
                        val down = awaitFirstDown()
                        pick(down.position.x)
                        var multi = false
                        var horizontal = false
                        var dx = 0f
                        var dy = 0f
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size >= 2) {
                                // Dos dedos: pellizco = zoom, arrastre = moverse por el tiempo.
                                multi = true
                                val w = currentWindow
                                val f = currentFull
                                val newWindow = transformWindow(
                                    w.start, w.end, f.start, f.end,
                                    event.calculateZoom(), event.calculatePan().x,
                                    event.calculateCentroid().x - left, plotW,
                                )
                                zoom = newWindow
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            } else if (!multi && pressed.size == 1) {
                                // Un dedo: elegir lectura. Si el movimiento es vertical se deja pasar (scroll de la pantalla).
                                val c = pressed[0]
                                dx += c.positionChange().x
                                dy += c.positionChange().y
                                if (!horizontal && abs(dy) > slop && abs(dy) > abs(dx)) break
                                if (abs(dx) > slop) horizontal = true
                                pick(c.position.x)
                                if (horizontal && c.positionChanged()) c.consume()
                            }
                        } while (event.changes.any { it.pressed })
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

            fun xOf(ts: Long) = left + ((ts - vs) / vSpan).toFloat() * plotW
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
            // Etiquetas de tiempo: se adaptan a lo que se ve.
            val longRange = vSpan > 26 * 3600
            for (i in 0..4) {
                val ts = (vs + vSpan * i / 4).toLong()
                val text = if (longRange) Format.day(ts) else Format.hour(ts)
                val label = measurer.measure(text, labelStyle)
                val x = (xOf(ts) - label.size.width / 2f).coerceIn(0f, size.width - label.size.width)
                drawText(label, topLeft = Offset(x, size.height - label.size.height))
            }

            // Todo lo que sigue se recorta a la zona del grafico (con zoom hay datos fuera de la ventana).
            clipRect(left = left, top = 0f, right = left + plotW, bottom = baseY + 1f) {
                // Línea de promedio.
                if (showAverage && stat.size > 1) {
                    val y = yOf(avg)
                    drawLine(markerColor.copy(alpha = 0.55f), Offset(left, y), Offset(left + plotW, y), 1.2.dp.toPx(), pathEffect = dash)
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
                    // Con zoom, cada lectura se marca con un punto para ver cuantas hay.
                    if (zoomed && vSpan < 6 * 3600) {
                        seg.forEach { s -> drawCircle(lineColor, 2.5.dp.toPx(), Offset(xOf(s.ts), yOf(s.temp))) }
                    }
                }
            }

            // Promedio: la etiqueta va fuera del recorte para que se lea completa.
            if (showAverage && stat.size > 1) {
                val y = yOf(avg)
                val label = measurer.measure("prom. " + axisNumber(avg, unit), labelStyle.copy(color = markerColor))
                drawText(label, topLeft = Offset(left + plotW - label.size.width - 2.dp.toPx(), y - label.size.height - 1.dp.toPx()))
            }

            // Marcas de mínima y máxima (solo de lo que se ve).
            if (showMinMax && stat.size > 1 && maxS.temp - minS.temp > 0.05) {
                markExtreme(maxS, true, ::xOf, ::yOf, measurer, labelStyle, unit, lineColor, surface, top, size.width)
                markExtreme(minS, false, ::xOf, ::yOf, measurer, labelStyle, unit, lineColor, surface, top, size.width)
            }

            selected?.let { s ->
                if (s.ts >= vs && s.ts <= ve) {
                    val x = xOf(s.ts)
                    val y = yOf(s.temp)
                    drawLine(markerColor.copy(alpha = 0.5f), Offset(x, top), Offset(x, baseY), strokeWidth = 1.dp.toPx())
                    drawCircle(surface, 7.dp.toPx(), Offset(x, y))
                    drawCircle(markerColor, 5.dp.toPx(), Offset(x, y))
                }
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
