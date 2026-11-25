/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import org.rab1d.petal.music.ui.theme.DisplayWidth
import org.rab1d.petal.music.ui.theme.HeroBreath
import org.rab1d.petal.music.ui.theme.bricolage

/**
 * Display text that fits by stepping its size down (at [DisplayWidth.steps] widths).
 * Never breaks a word.
 * [weightStep] (0..3) picks one of the [HeroBreath] weights.
 */
@Composable
fun HeroText(
    text: String,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
    weightStep: Int = 2,
    maxLines: Int = 2,
    minSize: TextUnit = 34.sp,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val measurer = rememberTextMeasurer()
        val width = constraints.maxWidth
        val density = androidx.compose.ui.platform.LocalDensity.current
        val fitted = remember(text, width, style, maxLines, minSize, density) {
            val base = style.fontSize.value
            val sizes = generateSequence(base) { it / 1.12f }.takeWhile { it >= minSize.value }.toList()
                .ifEmpty { listOf(minSize.value) }
            fun styled(w: Float, size: Float) = style.copy(
                fontFamily = bricolage(w, size),
                fontSize = size.sp,
                lineHeight = (size * style.lineHeight.value / base).coerceAtLeast(size * 1.08f).sp,
                letterSpacing = (-.015).em,
            )
            for (size in sizes) for (w in DisplayWidth.steps) {
                val candidate = styled(w, size)
                val r = measurer.measure(
                    text, candidate.copy(fontWeight = FontWeight(HeroBreath.last())),
                    maxLines = maxLines, constraints = Constraints(maxWidth = width),
                )
                val splitsWord = (0 until r.lineCount - 1).any { line ->
                    val end = r.getLineEnd(line)
                    end in 1 until text.length && !text[end - 1].isWhitespace() && !text[end].isWhitespace()
                }
                if (!r.hasVisualOverflow && !splitsWord) return@remember candidate
            }
            styled(DisplayWidth.NORMAL, sizes.last())
        }
        Text(
            text,
            color = color,
            style = fitted.copy(fontWeight = FontWeight(HeroBreath[weightStep.coerceIn(0, HeroBreath.lastIndex)])),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Draws the content [by] higher and gives that space back to the layout, so a title can
 * overlap the sleeve above it without leaving a hole below (an offset would).
 */
fun Modifier.pullUp(by: Dp): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    val px = by.roundToPx()
    layout(p.width, (p.height - px).coerceAtLeast(0)) { p.place(0, -px) }
}
