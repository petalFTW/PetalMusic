/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.rab1d.petal.music.ui.theme.LocalNowPlayingTheme
import org.rab1d.petal.music.ui.theme.LocalReducedMotion
import org.rab1d.petal.music.ui.theme.Petal
import org.rab1d.petal.music.ui.theme.Radius
import org.rab1d.petal.music.ui.theme.Space

/**
 * An empty or error state: a small drawing, a plain sentence, and the one thing to do
 * next. The drawing is a record half out of its sleeve, in the playing cover's colours.
 * No card behind it: it sits on the page.
 */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    drawing: @Composable () -> Unit = { SleeveDrawing() },
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Space.gutter, vertical = Space.xxl),
    ) {
        drawing()
        Text(title, style = MaterialTheme.typography.headlineMedium, color = Petal.colors.ink,
            modifier = Modifier.padding(top = Space.xl))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = Petal.colors.text2,
            modifier = Modifier.padding(top = Space.s))
        if (action != null && onAction != null) {
            Spacer(Modifier.height(Space.xl))
            PillButton(action, onAction)
        }
    }
}

/** A record sliding out of its sleeve: the empty-state mark. Static. */
@Composable
fun SleeveDrawing(size: Dp = 112.dp) {
    val theme = LocalNowPlayingTheme.current
    val ink = Petal.colors.ink
    Canvas(Modifier.width(size * 1.5f).height(size)) {
        val s = this.size.height
        // the record, peeking out to the right
        val center = Offset(s * .95f, s / 2f)
        drawCircle(Color(0xFF111014), s * .46f, center)
        for (i in 1..4) drawCircle(ink.copy(alpha = .07f), s * (.18f + i * .065f), center, style = Stroke(1.dp.toPx()))
        drawCircle(theme.accentOnArt, s * .13f, center)
        drawCircle(Color(0xFF111014), s * .025f, center)
        // the sleeve, lit by the playing cover; its light stays on the sleeve
        clipRect(right = s, bottom = s) {
            drawRect(theme.base, size = androidx.compose.ui.geometry.Size(s, s))
            theme.stops.take(3).forEach { st ->
                drawCircle(
                    Brush.radialGradient(listOf(st.color, st.color.copy(alpha = 0f)), Offset(st.x * s, st.y * s), s * .7f),
                    s * .7f, Offset(st.x * s, st.y * s),
                )
            }
            drawGrain(.08f)
        }
        drawLine(ink.copy(alpha = .14f), Offset(s, 0f), Offset(s, s), 1.dp.toPx(), StrokeCap.Butt)
    }
}

/** A drawing for "no connection": the sleeve with a gap in its groove line. */
@Composable
fun OfflineDrawing() {
    val ink = Petal.colors.ink
    Canvas(Modifier.size(112.dp)) {
        val r = size.minDimension / 2f
        drawArc(ink.copy(alpha = .5f), 200f, 290f, false, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round),
            topLeft = Offset(r * .2f, r * .2f), size = androidx.compose.ui.geometry.Size(r * 1.6f, r * 1.6f))
        drawCircle(ink.copy(alpha = .5f), 4.dp.toPx(), Offset(r, r))
    }
}

/** The one solid pill button outside the player: accent fill, label in the UI face. */
@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, prominent: Boolean = true) {
    val c = Petal.colors
    Box(
        modifier
            .height(48.dp)
            .clip(Radius.pill)
            .background(if (prominent) c.accent else c.surface2)
            .pressable(onClick = onClick, role = androidx.compose.ui.semantics.Role.Button)
            .padding(horizontal = Space.xl),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (prominent) c.onAccent else c.ink)
    }
}

// ---- skeletons ---------------------------------------------------------------------------

/**
 * A loading shimmer in the playing cover's colours. It only runs while something is
 * loading, and is a still tint when motion is reduced.
 */
@Composable
fun Modifier.shimmer(): Modifier {
    val reduced = LocalReducedMotion.current
    val theme = LocalNowPlayingTheme.current
    val base = Petal.colors.surface1
    val glint = theme.stops.firstOrNull()?.color ?: Petal.colors.surface2
    if (reduced) return this.background(base)
    val t by rememberInfiniteTransition(label = "shimmer").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "shimmerT",
    )
    return drawWithContent {
        val w = size.width
        val x = -w + 3f * w * t
        drawRect(base)
        drawRect(Brush.linearGradient(listOf(base, glint.copy(alpha = .55f), base), Offset(x, 0f), Offset(x + w, size.height)))
    }
}

/** Skeleton for a track list: shaped like real rows (cover, two lines, duration). */
@Composable
fun TrackRowSkeletons(count: Int = 6) {
    Column {
        repeat(count) { i ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 8.dp),
            ) {
                Box(Modifier.size(48.dp).clip(Radius.sleeve).shimmer())
                Column(Modifier.weight(1f).padding(start = Space.m), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.fillMaxWidth(listOf(.62f, .48f, .7f, .55f)[i % 4]).height(13.dp).clip(Radius.tile).shimmer())
                    Box(Modifier.fillMaxWidth(listOf(.34f, .4f, .28f, .44f)[i % 4]).height(11.dp).clip(Radius.tile).shimmer())
                }
                Box(Modifier.width(28.dp).height(11.dp).clip(Radius.tile).shimmer())
            }
        }
    }
}

/** Skeleton for a shelf of covers. */
@Composable
fun ShelfSkeleton(cover: Dp = 148.dp) {
    Row(Modifier.padding(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.l)) {
        repeat(3) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(cover).clip(Radius.sleeve).shimmer())
                Box(Modifier.width(cover * .7f).height(12.dp).clip(Radius.tile).shimmer())
                Box(Modifier.width(cover * .45f).height(10.dp).clip(Radius.tile).shimmer())
            }
        }
    }
}
