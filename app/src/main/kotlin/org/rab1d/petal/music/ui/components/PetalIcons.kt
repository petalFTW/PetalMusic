/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.rab1d.petal.music.ui.theme.LocalReducedMotion
import org.rab1d.petal.music.ui.theme.Motion

/**
 * Petal's own glyphs for the controls people touch most. One stroke (1.75 on a 24 grid,
 * round caps and joins), matching Bricolage's soft terminals. Transport glyphs are filled
 * with rounded corners for weight. Everything else uses Material Rounded.
 */
object PetalIcons {
    private const val STROKE = 1.75f

    private fun icon(name: String, block: ImageVector.Builder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    private fun ImageVector.Builder.line(alpha: Float = 1f, d: PathBuilder.() -> Unit) = path(
        stroke = SolidColor(Color.Black),
        strokeAlpha = alpha,
        strokeLineWidth = STROKE,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = d,
    )

    private fun ImageVector.Builder.solid(d: PathBuilder.() -> Unit) = path(
        fill = SolidColor(Color.Black),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2.2f,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = d,
    )

    /** Two lanes that cross over and under, like a patch cable: the over lane is unbroken. */
    val Shuffle = icon("Shuffle") {
        line { moveTo(3f, 7f); horizontalLineToRelative(3f); curveTo(11f, 7f, 12f, 17f, 17.5f, 17f); horizontalLineTo(21f) }
        line { moveTo(3f, 17f); horizontalLineToRelative(3f); curveTo(7.8f, 17f, 9f, 15.6f, 10f, 14.1f) }
        line { moveTo(13.9f, 9.7f); curveTo(14.8f, 8.2f, 15.9f, 7f, 17.5f, 7f); horizontalLineTo(21f) }
        line { moveTo(18.5f, 4.5f); lineTo(21f, 7f); lineTo(18.5f, 9.5f) }
        line { moveTo(18.5f, 14.5f); lineTo(21f, 17f); lineTo(18.5f, 19.5f) }
    }

    val Repeat = icon("Repeat") {
        line { moveTo(4f, 11.5f); verticalLineTo(10f); arcToRelative(3f, 3f, 0f, false, true, 3f, -3f); horizontalLineTo(20f) }
        line { moveTo(17f, 4f); lineTo(20f, 7f); lineTo(17f, 10f) }
        line { moveTo(20f, 12.5f); verticalLineTo(14f); arcToRelative(3f, 3f, 0f, false, true, -3f, 3f); horizontalLineTo(4f) }
        line { moveTo(7f, 20f); lineTo(4f, 17f); lineTo(7f, 14f) }
    }

    val RepeatOne = icon("RepeatOne") {
        line { moveTo(4f, 11.5f); verticalLineTo(10f); arcToRelative(3f, 3f, 0f, false, true, 3f, -3f); horizontalLineTo(20f) }
        line { moveTo(17f, 4f); lineTo(20f, 7f); lineTo(17f, 10f) }
        line { moveTo(20f, 12.5f); verticalLineTo(14f); arcToRelative(3f, 3f, 0f, false, true, -3f, 3f); horizontalLineTo(4f) }
        line { moveTo(7f, 20f); lineTo(4f, 17f); lineTo(7f, 14f) }
        line { moveTo(11f, 10.9f); lineTo(12.4f, 10.2f); verticalLineTo(14f) }
    }

    val Next = icon("Next") {
        solid { moveTo(5f, 6f); lineTo(14.5f, 12f); lineTo(5f, 18f); close() }
        line { moveTo(18.5f, 6f); verticalLineTo(18f) }
    }

    val Previous = icon("Previous") {
        solid { moveTo(19f, 6f); lineTo(9.5f, 12f); lineTo(19f, 18f); close() }
        line { moveTo(5.5f, 6f); verticalLineTo(18f) }
    }

    /** Lines of songs, with the playing one marked by a small solid triangle. */
    val Queue = icon("Queue") {
        line { moveTo(4f, 6.5f); horizontalLineTo(20f) }
        line { moveTo(4f, 12f); horizontalLineTo(13f) }
        line { moveTo(4f, 17.5f); horizontalLineTo(11f) }
        solid { moveTo(16f, 14.2f); lineTo(20.5f, 17f); lineTo(16f, 19.8f); close() }
    }

    /** AutoMix: two fade curves crossing, the seam where one song becomes the next. */
    val Seam = icon("Seam") {
        line { moveTo(3f, 18f); curveTo(10f, 18f, 12f, 6f, 21f, 6f) }
        line(alpha = .5f) { moveTo(3f, 6f); curveTo(10f, 6f, 12f, 18f, 21f, 18f) }
    }

    val Share = icon("Share") {
        line { moveTo(12f, 3.5f); verticalLineTo(14.5f) }
        line { moveTo(8f, 7.5f); lineTo(12f, 3.5f); lineTo(16f, 7.5f) }
        line { moveTo(8.5f, 10.5f); horizontalLineTo(7f); arcToRelative(2f, 2f, 0f, false, false, -2f, 2f); verticalLineTo(18.5f); arcToRelative(2f, 2f, 0f, false, false, 2f, 2f); horizontalLineTo(17f); arcToRelative(2f, 2f, 0f, false, false, 2f, -2f); verticalLineTo(12.5f); arcToRelative(2f, 2f, 0f, false, false, -2f, -2f); horizontalLineTo(15.5f) }
    }

    /** Where the sound goes: a speaker cone with one wave. */
    val Output = icon("Output") {
        line { moveTo(4f, 9.5f); horizontalLineTo(7f); lineTo(11.5f, 5.5f); verticalLineTo(18.5f); lineTo(7f, 14.5f); horizontalLineTo(4f); close() }
        line { moveTo(15f, 9f); curveTo(16.4f, 10.6f, 16.4f, 13.4f, 15f, 15f) }
        line(alpha = .55f) { moveTo(18f, 6.5f); curveTo(20.8f, 9.8f, 20.8f, 14.2f, 18f, 17.5f) }
    }

    /** A song jumping the line: an arrow curling into the top row. */
    val PlayNext = icon("PlayNext") {
        line { moveTo(11f, 6.5f); horizontalLineTo(20f) }
        line { moveTo(13f, 12f); horizontalLineTo(20f) }
        line { moveTo(4f, 17.5f); horizontalLineTo(20f) }
        line { moveTo(4f, 13f); verticalLineTo(9.5f); arcToRelative(3f, 3f, 0f, false, true, 3f, -3f) }
        line { moveTo(5f, 4.5f); lineTo(7.2f, 6.5f); lineTo(5f, 8.5f) }
    }

    /** Lyrics: a speech mark with two lines of words. */
    val Lyrics = icon("Lyrics") {
        line { moveTo(5f, 4.5f); horizontalLineTo(19f); arcToRelative(1.5f, 1.5f, 0f, false, true, 1.5f, 1.5f); verticalLineTo(14.5f); arcToRelative(1.5f, 1.5f, 0f, false, true, -1.5f, 1.5f); horizontalLineTo(10f); lineTo(5.5f, 19.5f); verticalLineTo(16f); horizontalLineTo(5f); arcToRelative(1.5f, 1.5f, 0f, false, true, -1.5f, -1.5f); verticalLineTo(6f); arcToRelative(1.5f, 1.5f, 0f, false, true, 1.5f, -1.5f); close() }
        line { moveTo(7.5f, 8.8f); horizontalLineTo(16.5f) }
        line(alpha = .55f) { moveTo(7.5f, 11.8f); horizontalLineTo(13.5f) }
    }

    /** The sleep timer: a crescent. */
    val Sleep = icon("Sleep") {
        line { moveTo(19.5f, 14.5f); arcToRelative(8f, 8f, 0f, true, true, -10f, -10f); arcToRelative(6.5f, 6.5f, 0f, false, false, 10f, 10f); close() }
    }

    /** Credits: a person with a line of text beside them. */
    val Credits = icon("Credits") {
        line { moveTo(12f, 8f); moveToRelative(-3f, 0f); arcToRelative(3f, 3f, 0f, true, false, 6f, 0f); arcToRelative(3f, 3f, 0f, true, false, -6f, 0f) }
        line { moveTo(4.5f, 19.5f); curveTo(5.5f, 15.5f, 8.5f, 14f, 12f, 14f); curveTo(15.5f, 14f, 18.5f, 15.5f, 19.5f, 19.5f) }
    }

    val More = icon("More") {
        solid { moveTo(6f, 12f); moveToRelative(-1.4f, 0f); arcToRelative(1.4f, 1.4f, 0f, true, false, 2.8f, 0f); arcToRelative(1.4f, 1.4f, 0f, true, false, -2.8f, 0f) }
        solid { moveTo(12f, 12f); moveToRelative(-1.4f, 0f); arcToRelative(1.4f, 1.4f, 0f, true, false, 2.8f, 0f); arcToRelative(1.4f, 1.4f, 0f, true, false, -2.8f, 0f) }
        solid { moveTo(18f, 12f); moveToRelative(-1.4f, 0f); arcToRelative(1.4f, 1.4f, 0f, true, false, 2.8f, 0f); arcToRelative(1.4f, 1.4f, 0f, true, false, -2.8f, 0f) }
    }

    val AddTo = icon("AddTo") {
        line { moveTo(4f, 6.5f); horizontalLineTo(16f) }
        line { moveTo(4f, 12f); horizontalLineTo(12f) }
        line { moveTo(4f, 17.5f); horizontalLineTo(10f) }
        line { moveTo(17f, 12f); verticalLineTo(20f) }
        line { moveTo(13f, 16f); horizontalLineTo(21f) }
    }

    val Down = icon("Down") {
        line { moveTo(6f, 9.5f); lineTo(12f, 15f); lineTo(18f, 9.5f) }
    }

    internal fun heartPath(s: Float) = Path().apply {
        fun x(v: Float) = v / 24f * s
        moveTo(x(12f), x(20.3f))
        cubicTo(x(12f), x(20.3f), x(3.2f), x(15.1f), x(3.2f), x(9f))
        cubicTo(x(3.2f), x(6.2f), x(5.3f), x(4.2f), x(7.8f), x(4.2f))
        cubicTo(x(9.6f), x(4.2f), x(11.1f), x(5.2f), x(12f), x(6.8f))
        cubicTo(x(12.9f), x(5.2f), x(14.4f), x(4.2f), x(16.2f), x(4.2f))
        cubicTo(x(18.7f), x(4.2f), x(20.8f), x(6.2f), x(20.8f), x(9f))
        cubicTo(x(20.8f), x(15.1f), x(12f), x(20.3f), x(12f), x(20.3f))
        close()
    }
}

/**
 * Play and pause are one glyph: the triangle's two halves slide apart into the bars.
 * Corners are rounded so the morph never shows a sharp spike mid-way.
 */
@Composable
fun PlayPauseGlyph(playing: Boolean, color: Color, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    val reduced = LocalReducedMotion.current
    val t by animateFloatAsState(
        if (playing) 1f else 0f,
        Motion.orSnap(reduced, Motion.press(Motion.BASE)),
        label = "playPause",
    )
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val ox = (this.size.width - s) / 2f
        val oy = (this.size.height - s) / 2f
        fun p(px: Float, py: Float, qx: Float, qy: Float) =
            Offset(ox + (px + (qx - px) * t) * s, oy + (py + (qy - py) * t) * s)
        // play: optically centred triangle (x .22..0.86); pause: bars .2-.42 and .58-.8
        val left = Path().apply {
            p(.22f, .14f, .20f, .14f).let { moveTo(it.x, it.y) }
            p(.54f, .33f, .42f, .14f).let { lineTo(it.x, it.y) }
            p(.54f, .67f, .42f, .86f).let { lineTo(it.x, it.y) }
            p(.22f, .86f, .20f, .86f).let { lineTo(it.x, it.y) }
            close()
        }
        val right = Path().apply {
            p(.54f, .33f, .58f, .14f).let { moveTo(it.x, it.y) }
            p(.86f, .50f, .80f, .14f).let { lineTo(it.x, it.y) }
            p(.86f, .50f, .80f, .86f).let { lineTo(it.x, it.y) }
            p(.54f, .67f, .58f, .86f).let { lineTo(it.x, it.y) }
            close()
        }
        val round = PathEffect.cornerPathEffect(s * .07f)
        for (path in listOf(left, right)) {
            drawPath(path, color, style = Fill)
            drawPath(path, color, style = Stroke(width = s * .05f, join = StrokeJoin.Round, pathEffect = round))
        }
    }
}

/**
 * The like glyph: an outline that fills with [fill] from the bottom up when [liked],
 * like ink rising in the paper, and drains when unliked.
 */
@Composable
fun HeartGlyph(liked: Boolean, outline: Color, fill: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val reduced = LocalReducedMotion.current
    val level by animateFloatAsState(
        if (liked) 1f else 0f,
        Motion.orSnap(reduced, if (liked) Motion.enter(Motion.SLOW) else Motion.exit(Motion.BASE)),
        label = "heart",
    )
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val path = PetalIcons.heartPath(s)
        if (level > 0f) {
            val top = s * (.2f + .64f * (1f - level))
            clipRect(top = top) { drawPath(path, fill) }
        }
        drawPath(
            path,
            if (level > .98f) fill else outline,
            style = Stroke(width = s * 1.75f / 24f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/**
 * The "now playing" mark on a row: four bars driven by the deck's bands. When nothing is
 * measured (paused, a stream through the pump, reduced motion) it rests as a static
 * stepped glyph rather than faking movement.
 */
@Composable
fun EqualiserGlyph(level: LiveLevel?, color: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    WatchLevel(level)
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val w = s / 7f
        val rest = floatArrayOf(.45f, .8f, .6f, .35f)
        val live = level?.live ?: 0f
        val bands = if (level != null) floatArrayOf(level.sub, level.bass, level.mid, level.high) else rest
        for (i in 0..3) {
            val h = (rest[i] + (bands[i].coerceIn(.12f, 1f) - rest[i]) * live) * s
            val x = w * (i * 2f) + w * .5f
            drawLine(
                color,
                Offset(x + w / 2f, s - w / 2f),
                Offset(x + w / 2f, s - h + w / 2f),
                strokeWidth = w,
                cap = StrokeCap.Round,
            )
        }
    }
}
