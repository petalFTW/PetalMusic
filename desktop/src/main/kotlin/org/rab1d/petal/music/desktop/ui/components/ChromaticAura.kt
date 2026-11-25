/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.desktop.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.sin

private val NEON = listOf(
    Color(0xFFFF2E88), Color(0xFF00F5D4), Color(0xFF7A5CFF),
    Color(0xFFFFE066), Color(0xFF00D1FF), Color(0xFFFF5E3A),
)

private fun hash(x: Float): Float {
    val s = sin(x * 12.9898f) * 43758.5453f
    return s - floor(s)
}

@Composable
fun ChromaticAura(
    modifier: Modifier = Modifier,
    cornerPx: Float = 0f,
    intensity: Float = 1f,
    blurRadius: Dp = 34.dp,
) {
    val a = intensity.coerceIn(0f, 2f)
    if (a <= 0.01f) return
    val transition = rememberInfiniteTransition(label = "chroma")
    val pulse by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(540, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val crackle by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
        label = "crackle",
    )
    Canvas(modifier.blur(blurRadius, BlurredEdgeTreatment.Unbounded)) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val breathe = 0.5f + 0.5f * pulse
        val stroke = (size.minDimension * 0.05f).coerceIn(18f, 110f) * (0.8f + 0.6f * pulse)
        val inset = stroke / 2f
        val rect = RoundRect(inset, inset, w - inset, h - inset, CornerRadius(cornerPx))
        val path = Path().apply { addRoundRect(rect) }
        val measure = PathMeasure().apply { setPath(path, false) }
        val length = measure.length
        if (length <= 0f) return@Canvas
        val glow = (0.6f * a * breathe).coerceIn(0f, 1f)
        val brush = Brush.sweepGradient(
            listOf(
                NEON[0].copy(alpha = glow), NEON[1].copy(alpha = glow), NEON[4].copy(alpha = glow),
                NEON[2].copy(alpha = glow), NEON[0].copy(alpha = glow),
            ),
            center = Offset(w / 2f, h / 2f),
        )
        drawRoundRect(
            brush = brush, topLeft = Offset(inset, inset), size = Size(w - 2f * inset, h - 2f * inset),
            cornerRadius = CornerRadius(cornerPx), style = Stroke(width = stroke),
        )
        val step = floor(crackle * 24f)
        val count = (length / 6f).toInt().coerceIn(90, 340)
        val grain = stroke * 0.42f
        for (i in 0 until count) {
            val seed = i * 0.618f
            val jitter = hash(seed)
            val flick = hash(seed + step * 1.37f)
            if (flick < 0.30f + 0.25f * (1f - breathe)) continue
            val along = ((i + (jitter - 0.5f)) / count).mod(1f)
            val d = along * length
            val pos = measure.getPosition(d)
            val tan = measure.getTangent(d)
            val color = NEON[(hash(seed + 7.13f) * NEON.size).toInt().coerceIn(0, NEON.size - 1)]
            val alpha = (a * (0.35f + 0.65f * flick) * (0.6f + 0.4f * pulse)).coerceIn(0f, 1f)
            val half = grain * (0.6f + 0.8f * jitter)
            drawLine(
                color = color.copy(alpha = alpha),
                start = Offset(pos.x - tan.x * half, pos.y - tan.y * half),
                end = Offset(pos.x + tan.x * half, pos.y + tan.y * half),
                strokeWidth = grain * (0.6f + 0.6f * flick),
                cap = StrokeCap.Round,
            )
        }
    }
}
