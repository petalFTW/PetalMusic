/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import android.graphics.Paint
import androidx.compose.ui.unit.dp
import org.rab1d.petal.music.playback.EqTransition
import kotlin.math.ceil

/**
 * The seek bar's transition form: the outgoing waveform up top and the incoming
 * mirrored below, with bar gridlines, a playhead, and colour-coded curves for
 * each EQ band's handover (sub/bass/mid/high), so you can see the two records
 * line up and how the mix trades bands.
 */
@Composable
fun MixWaveform(
    modifier: Modifier = Modifier,
    progress: Float,
    bpm: Float,
    accent: Color,
    eq: EqTransition,
    durationMs: Int,
) {
    val p = progress.coerceIn(0f, 1f)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val mid = h / 2f

        // ---- bar gridlines -------------------------------------------------
        val bars = if (bpm > 0f && durationMs > 0) durationMs * bpm / 240000f else 0f
        for (b in 0..ceil(bars.toDouble()).toInt().coerceAtMost(128)) {
            if (bars <= 0f || b > bars) break
            val x = w * b / bars
            val major = b % 4 == 0
            drawLine(
                color = Color.White.copy(alpha = if (major) 0.22f else 0.09f),
                start = Offset(x, 0f),
                end = Offset(x, h),
                strokeWidth = if (major) 1.4.dp.toPx() else 1.dp.toPx(),
            )
        }

        // ---- waveforms -----------------------------------------------------
        drawWave(eq.points, top = true, mid = mid, h = h, w = w, color = Color.White.copy(alpha = 0.75f))
        drawWave(eq.points, top = false, mid = mid, h = h, w = w, color = accent.copy(alpha = 0.9f))

        // ---- EQ band handover curves (colour-coded) ------------------------
        // Outgoing solid, incoming dashed: applied gain history up to the playhead.
        // Future space stays empty because the controller is still listening.
        val colors = listOf(Color(0xFFB06BFF), Color(0xFFFF5050), Color(0xFF50FF9B), Color(0xFF5AA0FF))
        val labels = listOf("SUB", "BASS", "MIDS", "HIGHS")
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 9.dp.toPx()
            this.color = android.graphics.Color.WHITE
            alpha = 190
            // Right-aligned in each lane: the future side stays empty until the mix ends,
            // so the labels never sit on the curves.
            textAlign = Paint.Align.RIGHT
        }
        colors.forEachIndexed { index, color ->
            val laneTop = index * h / 4f
            drawLine(Color.White.copy(alpha = .12f), Offset(0f, laneTop), Offset(w, laneTop))
            drawContext.canvas.nativeCanvas.drawText(labels[index], w - 3.dp.toPx(),
                laneTop + h / 8f + labelPaint.textSize * .35f, labelPaint)
            drawBandCurve(w, h, eq.points, index, out = true, color)
            drawBandCurve(w, h, eq.points, index, out = false, color)
        }

        // ---- v2 effects: filter sweeps, echo send and loop rolls ------------
        if (eq.points.any { it.fx != EqTransition.Fx.NONE }) {
            val amber = Color(0xFFFFC14D)
            drawFxCurve(w, h, eq.points, amber, dashed = false) { it.fx.outFilter }
            drawFxCurve(w, h, eq.points, amber, dashed = true) { it.fx.inFilter }
            drawFxCurve(w, h, eq.points, Color(0xFF4DE8FF), dashed = false) { it.fx.outEcho }
            for (i in 1 until eq.points.size) {
                val point = eq.points[i]
                if (point.fx.outRoll <= 0f) continue
                val x0 = w * eq.points[i - 1].progress
                drawLine(Color(0xFFFF6BD5), Offset(x0, 1.dp.toPx()), Offset(w * point.progress, 1.dp.toPx()),
                    strokeWidth = 3.dp.toPx())
            }
            drawContext.canvas.nativeCanvas.drawText("FX", w - 40.dp.toPx(), h / 8f + labelPaint.textSize * .35f, labelPaint)
        }

        // ---- playhead ------------------------------------------------------
        val px = w * p
        drawLine(
            color = Color.White.copy(alpha = 0.9f),
            start = Offset(px, 0f),
            end = Offset(px, h),
            strokeWidth = 2.dp.toPx(),
        )
    }
}

private fun DrawScope.drawWave(
    points: List<EqTransition.Point>,
    top: Boolean,
    mid: Float,
    h: Float,
    w: Float,
    color: Color,
) {
    if (points.size < 2) return
    for (i in 1 until points.size) {
        val point = points[i]
        val rms = if (top) point.outgoingRms else point.incomingRms
        val a = (rms * 2.5f).coerceIn(0f, 1f) * (h / 2f - 2.dp.toPx())
        if (a <= 0.5f) continue
        val x = w * point.progress
        val barW = (w * (point.progress - points[i - 1].progress)).coerceAtLeast(1.dp.toPx())
        val y1 = if (top) mid - a else mid + a
        drawLine(color = color, start = Offset(x, mid), end = Offset(x, y1), strokeWidth = barW * .7f)
    }
}

/** One band's gain curve across the transition (1 → 0 for outgoing). */
private fun DrawScope.drawBandCurve(
    w: Float,
    h: Float,
    points: List<EqTransition.Point>,
    band: Int,
    out: Boolean,
    color: Color,
) {
    if (points.size < 2) return
    val path = Path()
    val laneHeight = h / 4f
    val bottom = (band + 1) * laneHeight - 2.dp.toPx()
    for ((s, point) in points.withIndex()) {
        val g = if (out) point.outgoing[band] else point.incoming[band]
        val x = w * point.progress
        val y = bottom - g * (laneHeight - 5.dp.toPx())
        if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(
        path = path,
        color = color.copy(alpha = if (out) 0.9f else 0.5f),
        style = Stroke(
            width = 1.6.dp.toPx(),
            pathEffect = if (out) null else PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
        ),
    )
}

/** A v2 effect level (0..1) across the full height: solid outgoing, dashed incoming. */
private fun DrawScope.drawFxCurve(
    w: Float,
    h: Float,
    points: List<EqTransition.Point>,
    color: Color,
    dashed: Boolean,
    value: (EqTransition.Point) -> Float,
) {
    if (points.size < 2 || points.none { value(it) > 0f }) return
    val path = Path()
    for ((s, point) in points.withIndex()) {
        val x = w * point.progress
        val y = h - 2.dp.toPx() - value(point).coerceIn(0f, 1f) * (h - 4.dp.toPx())
        if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(
        path = path,
        color = color.copy(alpha = .75f),
        style = Stroke(
            width = 1.4.dp.toPx(),
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6f, 6f)) else null,
        ),
    )
}
