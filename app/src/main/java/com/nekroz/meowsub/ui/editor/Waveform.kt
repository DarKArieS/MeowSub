package com.nekroz.meowsub.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nekroz.meowsub.data.audio.WaveformPeaks
import com.nekroz.meowsub.data.subtitle.SubtitleLine
import com.nekroz.meowsub.data.subtitle.TimeFormat
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

private val TICK_STEPS = longArrayOf(
    10, 20, 50, 100, 200, 500, 1_000, 2_000, 5_000, 10_000, 15_000, 30_000, 60_000, 120_000, 300_000, 600_000,
)

private class WaveColors(
    val background: Color,
    val ruler: Color,
    val rulerText: Color,
    val grid: Color,
    val wave: Color,
    val waveSelected: Color,
    val lineFill: Color,
    val lineEdge: Color,
    val neighborFill: Color,
    val neighborEdge: Color,
    val neighborText: Color,
    val snapGuide: Color,
    val selectedFill: Color,
    val startMarker: Color,
    val endMarker: Color,
    val playhead: Color,
    val cursor: Color,
)

/** Reusable float buffers for Canvas.drawLines, so drawing a frame does not allocate. */
private class WaveBuffers {
    var peak = FloatArray(0)
    var peakSel = FloatArray(0)
    var avg = FloatArray(0)
    var avgSel = FloatArray(0)

    fun ensure(n: Int) {
        if (peak.size < n) {
            peak = FloatArray(n); peakSel = FloatArray(n); avg = FloatArray(n); avgSel = FloatArray(n)
        }
    }
}

/**
 * Audio waveform with subtitle regions, after Aegisub's audio display.
 * - drag a marker handle of the selected line to retime it (snaps to the previous/next line)
 * - one-finger drag scrolls, pinch zooms the time axis
 * - tap sets the play cursor and selects the line under it
 */
@Composable
fun Waveform(
    peaks: WaveformPeaks?,
    lines: List<SubtitleLine>,
    selected: SubtitleLine?,
    viewport: ViewportState,
    playback: PlaybackState,
    snapGuideMs: Long?,
    previousLabel: String,
    nextLabel: String,
    onWidthChanged: (Int) -> Unit,
    onTransform: (zoom: Float, anchorX: Float, panX: Float) -> Unit,
    onTap: (ms: Long) -> Unit,
    onMarkerDragStart: () -> Unit,
    onMarkerDrag: (marker: Marker, ms: Long, snapRadiusMs: Double) -> Unit,
    onMarkerDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val colors = remember(cs) {
        WaveColors(
            background = cs.surfaceContainerLow,
            ruler = cs.surfaceContainerHighest,
            rulerText = cs.onSurfaceVariant,
            grid = cs.outlineVariant.copy(alpha = 0.5f),
            wave = cs.onSurfaceVariant,
            waveSelected = cs.primary,
            lineFill = cs.onSurface.copy(alpha = 0.05f),
            lineEdge = cs.outlineVariant,
            neighborFill = cs.secondaryContainer.copy(alpha = 0.85f),
            neighborEdge = cs.secondary,
            neighborText = cs.onSecondaryContainer,
            snapGuide = cs.onSurface.copy(alpha = 0.3f),
            selectedFill = cs.primaryContainer.copy(alpha = 0.75f),
            startMarker = cs.primary,
            endMarker = cs.tertiary,
            playhead = cs.error,
            cursor = cs.onSurface,
        )
    }
    val linePaint = remember { android.graphics.Paint().apply { strokeWidth = 1f; isAntiAlias = false } }
    val textPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    val buffers = remember { WaveBuffers() }

    val latestViewport by rememberUpdatedState(viewport)
    val latestSelected by rememberUpdatedState(selected)
    val latestOnTransform by rememberUpdatedState(onTransform)
    val latestOnTap by rememberUpdatedState(onTap)
    val latestOnDragStart by rememberUpdatedState(onMarkerDragStart)
    val latestOnDrag by rememberUpdatedState(onMarkerDrag)
    val latestOnDragEnd by rememberUpdatedState(onMarkerDragEnd)

    Canvas(
        modifier
            .clipToBounds()
            .onSizeChanged { onWidthChanged(it.width) }
            .pointerInput(Unit) {
                val hitRadius = 24.dp.toPx()
                val snapRadius = 12.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val v = latestViewport
                    val marker = latestSelected?.let { hitMarker(down.position.x, it, v, hitRadius) }

                    if (marker != null) {
                        down.consume()
                        latestOnDragStart()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (change.positionChanged()) {
                                val vv = latestViewport
                                latestOnDrag(
                                    marker,
                                    (vv.startMs + change.position.x * vv.msPerPx).roundToLong(),
                                    snapRadius * vv.msPerPx,
                                )
                            }
                            change.consume()
                        }
                        latestOnDragEnd()
                        return@awaitEachGesture
                    }

                    val slop = viewConfiguration.touchSlop
                    var accumulated = 0f
                    var panning = false
                    var multiTouch = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            multiTouch = true
                            val zoom = event.calculateZoom()
                            val centroid = event.calculateCentroid(useCurrent = true)
                            val pan = event.calculatePan()
                            if (zoom != 1f || pan.x != 0f) latestOnTransform(zoom, centroid.x, pan.x)
                            event.changes.forEach { it.consume() }
                        } else if (!multiTouch) {
                            val dx = pressed[0].positionChange().x
                            if (panning) {
                                latestOnTransform(1f, 0f, dx)
                            } else {
                                accumulated += dx
                                if (abs(accumulated) > slop) {
                                    panning = true
                                    latestOnTransform(1f, 0f, accumulated)
                                }
                            }
                            if (panning) event.changes.forEach { it.consume() }
                        }
                    }
                    if (!panning && !multiTouch) {
                        val vv = latestViewport
                        latestOnTap((vv.startMs + down.position.x * vv.msPerPx).roundToLong())
                    }
                }
            }
    ) {
        drawWaveform(
            peaks, lines, selected, viewport, playback, snapGuideMs,
            previousLabel, nextLabel, colors, linePaint, textPaint, buffers,
        )
    }
}

private fun hitMarker(x: Float, line: SubtitleLine, v: ViewportState, radius: Float): Marker? {
    val xs = ((line.startMs - v.startMs) / v.msPerPx).toFloat()
    val xe = ((line.endMs - v.startMs) / v.msPerPx).toFloat()
    val ds = abs(x - xs)
    val de = abs(x - xe)
    return when {
        ds > radius && de > radius -> null
        // When both markers are close together, pick by which side of the pair was touched.
        abs(ds - de) < 1f -> if (x <= xs) Marker.Start else Marker.End
        ds < de -> Marker.Start
        else -> Marker.End
    }
}

private fun DrawScope.drawWaveform(
    peaks: WaveformPeaks?,
    lines: List<SubtitleLine>,
    selected: SubtitleLine?,
    v: ViewportState,
    playback: PlaybackState,
    snapGuideMs: Long?,
    previousLabel: String,
    nextLabel: String,
    c: WaveColors,
    linePaint: android.graphics.Paint,
    textPaint: android.graphics.Paint,
    buffers: WaveBuffers,
) {
    val w = size.width
    val h = size.height
    val rulerH = 20.dp.toPx()
    val waveTop = rulerH
    val waveH = h - waveTop
    val mid = waveTop + waveH / 2f
    val startMs = v.startMs
    val mpp = v.msPerPx
    val endMs = startMs + w * mpp
    fun xOf(ms: Long): Float = ((ms - startMs) / mpp).toFloat()
    val canvas = drawContext.canvas.nativeCanvas

    drawRect(c.background)
    drawRect(c.ruler, size = Size(w, rulerH))

    // Subtitle regions: other lines faint, previous/next line emphasised, selected on top.
    textPaint.textSize = 11.sp.toPx()
    val selectedIndex = if (selected == null) -1 else lines.indexOfFirst { it.id == selected.id }
    val labelPad = 4.dp.toPx()
    val labelY = waveTop + 14.dp.toPx()
    lines.forEachIndexed { i, line ->
        if (i == selectedIndex || i == selectedIndex - 1 || i == selectedIndex + 1) return@forEachIndexed
        if (line.endMs < startMs || line.startMs > endMs) return@forEachIndexed
        val x0 = xOf(line.startMs)
        val x1 = max(xOf(line.endMs), x0 + 1f)
        drawRect(c.lineFill, Offset(x0, waveTop), Size(x1 - x0, waveH))
        drawLine(c.lineEdge, Offset(x0, waveTop), Offset(x0, h), 1f)
        drawLine(c.lineEdge, Offset(x1, waveTop), Offset(x1, h), 1f)
        if (x1 - x0 > 24.dp.toPx()) {
            textPaint.color = c.rulerText.toArgb()
            canvas.drawText((i + 1).toString(), x0 + labelPad, labelY, textPaint)
        }
    }
    if (selectedIndex >= 0) {
        for ((i, label) in listOf(selectedIndex - 1 to previousLabel, selectedIndex + 1 to nextLabel)) {
            val line = lines.getOrNull(i) ?: continue
            if (line.endMs < startMs || line.startMs > endMs) continue
            val x0 = xOf(line.startMs)
            val x1 = max(xOf(line.endMs), x0 + 1f)
            val edge = 1.5.dp.toPx()
            drawRect(c.neighborFill, Offset(x0, waveTop), Size(x1 - x0, waveH))
            drawLine(c.neighborEdge, Offset(x0, waveTop), Offset(x0, h), edge)
            drawLine(c.neighborEdge, Offset(x1, waveTop), Offset(x1, h), edge)
            val text = "${i + 1} $label"
            textPaint.color = c.neighborText.toArgb()
            val textW = textPaint.measureText(text)
            // Fall back to just the number when the region is too narrow for the label.
            when {
                x1 - x0 > textW + labelPad * 2 -> canvas.drawText(text, x0 + labelPad, labelY, textPaint)
                x1 - x0 > 24.dp.toPx() -> canvas.drawText((i + 1).toString(), x0 + labelPad, labelY, textPaint)
            }
        }
    }
    var selX0 = Float.NaN
    var selX1 = Float.NaN
    if (selected != null) {
        selX0 = xOf(selected.startMs)
        selX1 = max(xOf(selected.endMs), selX0 + 1f)
        drawRect(c.selectedFill, Offset(selX0, waveTop), Size(selX1 - selX0, waveH))
    }

    // Time grid and ruler labels.
    val step = TICK_STEPS.firstOrNull { it / mpp >= 80.dp.toPx() } ?: TICK_STEPS.last()
    var t = ceil(startMs / step).toLong() * step
    while (t <= endMs) {
        val x = xOf(t)
        drawLine(c.grid, Offset(x, waveTop), Offset(x, h), 1f)
        drawLine(c.rulerText, Offset(x, rulerH - 6.dp.toPx()), Offset(x, rulerH), 1.dp.toPx())
        canvas.drawText(TimeFormat.ruler(t, step), x + 3.dp.toPx(), rulerH - 7.dp.toPx(), textPaint)
        t += step
    }

    // Waveform: one vertical line per pixel column for the peak range, plus the average.
    if (peaks != null && peaks.sizeMs > 0) {
        val width = w.toInt()
        buffers.ensure(width * 4)
        val scale = v.amplitude * (waveH / 2f) / 32768f
        var np = 0
        var ns = 0
        val mins = peaks.mins
        val maxs = peaks.maxs
        val avgs = peaks.avgs
        val n = peaks.sizeMs
        for (x in 0 until width) {
            val t0 = startMs + x * mpp
            var i0 = floor(t0).toInt()
            var i1 = floor(t0 + mpp).toInt()
            if (i1 <= i0) i1 = i0 + 1
            if (i1 <= 0) continue
            if (i0 >= n) break
            i0 = max(i0, 0)
            i1 = min(i1, n)
            var mn = 0
            var mx = 0
            var sum = 0L
            for (i in i0 until i1) {
                val lo = mins[i].toInt()
                val hi = maxs[i].toInt()
                if (lo < mn) mn = lo
                if (hi > mx) mx = hi
                sum += avgs[i]
            }
            val avg = sum.toFloat() / (i1 - i0)
            val px = x + 0.5f
            val yTop = max(mid - mx * scale, waveTop)
            var yBot = min(mid - mn * scale, h)
            if (yBot - yTop < 1f) yBot = yTop + 1f
            val ay = min(avg * scale, waveH / 2f)
            val inSel = px >= selX0 && px <= selX1
            if (inSel) {
                buffers.peakSel.put4(ns, px, yTop, px, yBot)
                buffers.avgSel.put4(ns, px, mid - ay, px, mid + ay)
                ns += 4
            } else {
                buffers.peak.put4(np, px, yTop, px, yBot)
                buffers.avg.put4(np, px, mid - ay, px, mid + ay)
                np += 4
            }
        }
        linePaint.color = c.wave.copy(alpha = 0.45f).toArgb()
        canvas.drawLines(buffers.peak, 0, np, linePaint)
        linePaint.color = c.wave.copy(alpha = 0.9f).toArgb()
        canvas.drawLines(buffers.avg, 0, np, linePaint)
        linePaint.color = c.waveSelected.copy(alpha = 0.5f).toArgb()
        canvas.drawLines(buffers.peakSel, 0, ns, linePaint)
        linePaint.color = c.waveSelected.toArgb()
        canvas.drawLines(buffers.avgSel, 0, ns, linePaint)
    } else {
        drawLine(c.wave.copy(alpha = 0.5f), Offset(0f, mid), Offset(w, mid), 1f)
    }

    // Snap guide: a wide translucent band on the boundary the dragged marker stuck to.
    if (snapGuideMs != null) {
        val xg = xOf(snapGuideMs)
        drawLine(c.snapGuide, Offset(xg, waveTop), Offset(xg, h), 8.dp.toPx())
    }

    // Selected line markers with grab handles at the bottom.
    if (selected != null) {
        val stroke = 2.dp.toPx()
        val hw = 14.dp.toPx()
        val hh = 24.dp.toPx()
        val r = CornerRadius(4.dp.toPx())
        drawLine(c.startMarker, Offset(selX0, waveTop), Offset(selX0, h), stroke)
        drawRoundRect(c.startMarker, Offset(selX0, h - hh), Size(hw, hh), r)
        drawLine(c.endMarker, Offset(selX1, waveTop), Offset(selX1, h), stroke)
        drawRoundRect(c.endMarker, Offset(selX1 - hw, h - hh), Size(hw, hh), r)
    }

    // Play cursor (dashed) and playhead.
    val xc = xOf(playback.cursorMs)
    drawLine(
        c.cursor.copy(alpha = if (playback.playing) 0.35f else 0.8f),
        Offset(xc, 0f), Offset(xc, h), 1.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
    )
    if (playback.playing) {
        val xp = xOf(playback.positionMs)
        drawLine(c.playhead, Offset(xp, 0f), Offset(xp, h), 2.dp.toPx())
    }
}

private fun FloatArray.put4(i: Int, a: Float, b: Float, c: Float, d: Float) {
    this[i] = a; this[i + 1] = b; this[i + 2] = c; this[i + 3] = d
}
