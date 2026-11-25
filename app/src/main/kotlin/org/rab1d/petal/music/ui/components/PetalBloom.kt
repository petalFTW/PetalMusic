/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import org.rab1d.petal.music.ui.theme.petalPalette
import kotlin.math.cos
import kotlin.math.sin

/**
 * A soft petal aura: two counter-rotating rings of gradient petals over a wide
 * veil. Everything is drawn with radial gradients so there are no hard edges —
 * it reads as coloured light breathing behind the page rather than a drawing.
 *
 * [energy] (0..1) swells and brightens it; [bpm] locks the motion to the pulse.
 */
@Composable
fun PetalBloom(
    modifier: Modifier = Modifier,
    energy: Float = 0.5f,
    bpm: Float = 120f,
    petals: Int = 7,
    alpha: Float = 0.5f,
) {
    val palette = petalPalette()
    val beatMs = (60_000f / bpm.coerceIn(50f, 200f)).toInt().coerceAtLeast(240)

    val transition = rememberInfiniteTransition(label = "bloom")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween((beatMs * 22).coerceAtLeast(12_000), easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spin",
    )
    val breathe by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween((beatMs * 4).coerceAtLeast(1600), easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathe",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val base = size.minDimension * 0.5f
        val e = energy.coerceIn(0f, 1f)

        // wide veil of light behind everything
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    palette.accent.copy(alpha = alpha * (0.16f + 0.10f * e)),
                    Color.Transparent,
                ),
                center = Offset(cx, cy),
                radius = base * (1.15f + 0.10f * breathe),
            ),
            radius = base * (1.15f + 0.10f * breathe),
            center = Offset(cx, cy),
        )

        // two rings of gradient petals, drifting in opposite directions
        for (layer in 0..1) {
            val count = if (layer == 0) petals else petals + 2
            val dir = if (layer == 0) 1f else -0.62f
            val orbit = base * (if (layer == 0) 0.30f else 0.55f)
            val petalR = base * (if (layer == 0) 0.34f else 0.28f)
            val tint = if (layer == 0) palette.accent else palette.accentAlt
            val layerAlpha = alpha * (if (layer == 0) 0.20f else 0.14f)

            repeat(count) { i ->
                val angle = spin * dir + i * (360f / count) + layer * 24f
                val rad = Math.toRadians(angle.toDouble())
                val dist = orbit * (1f + 0.05f * breathe)
                val px = cx + (cos(rad) * dist).toFloat()
                val py = cy + (sin(rad) * dist).toFloat()
                val r = petalR * (0.85f + 0.30f * e + 0.08f * breathe)

                rotate(degrees = angle, pivot = Offset(px, py)) {
                    drawOval(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                tint.copy(alpha = layerAlpha),
                                Color.Transparent,
                            ),
                            center = Offset(px, py),
                            radius = r * 1.5f,
                        ),
                        topLeft = Offset(px - r * 0.55f, py - r * 1.15f),
                        size = Size(r * 1.1f, r * 2.1f),
                    )
                }
            }
        }

        // warm heart
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = alpha * (0.06f + 0.06f * e)),
                    Color.Transparent,
                ),
                center = Offset(cx, cy),
                radius = base * 0.30f,
            ),
            radius = base * 0.30f,
            center = Offset(cx, cy),
        )
    }
}
