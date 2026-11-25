/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.ui.components

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.rab1d.petal.music.ui.theme.ArtTheme
import org.rab1d.petal.music.ui.theme.LocalReducedMotion
import org.rab1d.petal.music.ui.theme.LocalReducedTransparency
import org.rab1d.petal.music.ui.theme.Motion
import kotlin.math.sqrt

/** The three glass elevations. Values are documented in docs/ui-redesign.md → Glass levels. */
enum class GlassLevel(val blur: Dp, val saturation: Float, val tint: Float, val highlight: Float) {
    /** Buttons and chips laid on art. */
    Control(24.dp, 1.4f, .22f, .16f),
    /** Mini-player, tab bar, the player's control deck. */
    Bar(32.dp, 1.6f, .46f, .12f),
    /** Up Next, menus, sheets, toasts. */
    Sheet(40.dp, 1.8f, .62f, .10f),
}

/**
 * Whatever is drawn inside [backdropSource] can be frosted by a [glass] surface that
 * floats over it. The surface draws the same RenderNode, so the blur follows scrolling
 * and animation with no extra invalidation.
 */
@Stable
class Backdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var origin by mutableStateOf(Offset.Zero)
}

@Composable
fun rememberBackdrop(): Backdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { Backdrop(layer) }
}

fun Modifier.backdropSource(backdrop: Backdrop): Modifier = this
    .onGloballyPositioned { backdrop.origin = it.positionInRoot() }
    .drawWithContent {
        backdrop.layer.record { this@drawWithContent.drawContent() }
        drawLayer(backdrop.layer)
    }

private fun blurSaturate(radiusPx: Float, saturation: Float): androidx.compose.ui.graphics.RenderEffect {
    val blur = RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP)
    val sat = RenderEffect.createColorFilterEffect(
        ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(saturation) }),
    )
    // Inner (blur) runs first, then the saturation lift: the glass is richer than its backdrop.
    return RenderEffect.createChainEffect(sat, blur).asComposeRenderEffect()
}

/**
 * Frosted glass over [backdrop], tinted with [tint] (a colour from the palette, never
 * plain white or black). With reduced transparency, or no backdrop, it is an opaque
 * tint instead.
 */
@Composable
fun Modifier.glass(
    backdrop: Backdrop?,
    level: GlassLevel,
    tint: Color,
    shape: Shape = RectangleShape,
): Modifier {
    val solid = LocalReducedTransparency.current || backdrop == null
    val layer = rememberGraphicsLayer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Built once per level, not per frame: every frame would churn native effect objects.
    val effect = remember(level, density) { with(density) { blurSaturate(level.blur.toPx(), level.saturation) } }
    var position by remember { mutableStateOf(Offset.Zero) }
    return this
        .onGloballyPositioned { position = it.positionInRoot() }
        .drawWithContent {
            val outline = shape.createOutline(size, layoutDirection, this)
            val path = Path().apply { addOutline(outline) }
            clipPath(path) {
                if (!solid && backdrop != null) {
                    layer.record {
                        translate(backdrop.origin.x - position.x, backdrop.origin.y - position.y) {
                            drawLayer(backdrop.layer)
                        }
                    }
                    layer.renderEffect = effect
                    drawLayer(layer)
                }
                // Solid means nothing is blurred underneath, so it must be opaque: at .94 the
                // player's white title and waveform ghosted through the Up Next drawer.
                drawRect(tint.copy(alpha = if (solid) 1f else level.tint))
                drawGrain()
                // The 1dp light edge along the top: where the paper catches the light.
                drawPath(
                    path,
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = level.highlight),
                        (12.dp.toPx() / size.height).coerceIn(.05f, 1f) to Color.Transparent,
                    ),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
            drawContent()
        }
}

// ---- grain ---------------------------------------------------------------------------

private object Grain {
    val bitmap: ImageBitmap by lazy {
        val s = 128
        val rnd = java.util.Random(0x5EED)
        val px = IntArray(s * s) {
            val a = rnd.nextInt(256)
            (a shl 24) or (if (rnd.nextBoolean()) 0xFFFFFF else 0x000000)
        }
        Bitmap.createBitmap(px, s, s, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    val brush: ShaderBrush by lazy { ShaderBrush(ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated)) }
}

/** Monochrome film grain, about 3 % on average: breaks up banding and gives the paper a tooth. */
fun DrawScope.drawGrain(alpha: Float = .06f) {
    drawRect(Grain.brush, alpha = alpha)
}

// ---- sleeve light --------------------------------------------------------------------

/**
 * The lit page behind a cover: the theme's base, then one radial light per colour in the
 * artwork, placed where that colour sits on the sleeve, then grain. [glow] (0..1, read
 * at draw time) swells the lights with the music; it is ignored when motion is reduced.
 */
@Composable
fun Modifier.sleeveLight(
    theme: ArtTheme,
    glow: () -> Float = { 0f },
    alpha: Float = 1f,
): Modifier {
    val reduced = LocalReducedMotion.current
    return drawBehind { drawSleeveLight(theme, if (reduced) 0f else glow(), alpha) }
}

fun DrawScope.drawSleeveLight(theme: ArtTheme, glow: Float = 0f, alpha: Float = 1f) {
    // The glows are far larger than the box; without the clip they spill onto whatever is below.
    clipRect {
        drawRect(theme.base, alpha = alpha)
        val span = maxOf(size.width, size.height)
        // Largest first, so small vivid lights sit on top of the wash.
        for (stop in theme.stops) {
            if (stop.color.alpha <= 0f || stop.weight <= 0f) continue
            val r = span * (.38f + .62f * sqrt(stop.weight)) * (1f + .08f * glow)
            val center = Offset(stop.x * size.width, stop.y * size.height)
            drawCircle(
                Brush.radialGradient(
                    0f to stop.color.copy(alpha = stop.color.alpha * .92f * alpha),
                    .55f to stop.color.copy(alpha = stop.color.alpha * .42f * alpha),
                    1f to stop.color.copy(alpha = 0f),
                    center = center,
                    radius = r,
                ),
                radius = r,
                center = center,
            )
        }
        drawGrain(.05f * alpha)
    }
}

// ---- press ---------------------------------------------------------------------------

/**
 * The press state every tappable thing shares: scale 0.97 over [Motion.FAST] on the
 * snappy curve. No ripple: the scale is the feedback.
 */
@Composable
fun Modifier.pressable(
    onClick: () -> Unit,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: androidx.compose.ui.semantics.Role? = null,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduced) .97f else 1f,
        animationSpec = Motion.press(),
        label = "press",
    )
    val dim by animateFloatAsState(if (pressed && reduced) .7f else 1f, Motion.press(), label = "pressDim")
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale; alpha = dim }
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            onClick = onClick,
        )
}
