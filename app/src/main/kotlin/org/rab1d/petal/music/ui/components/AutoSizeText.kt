/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Text that shrinks (down to [minSize]) until it fits [maxLines] without
 * clipping. If it still doesn't fit at the floor it stops shrinking and
 * scrolls through instead, pause between each pass.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AutoSizeText(
    text: String,
    modifier: Modifier = Modifier,
    baseSize: TextUnit,
    minSize: TextUnit,
    maxLines: Int = 1,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
) {
    var size by remember(text, baseSize) { mutableStateOf(baseSize) }
    var scrolling by remember(text) { mutableStateOf(false) }
    Text(
        text = text,
        modifier = if (scrolling) {
            // A few passes, then rest: an endless marquee asks for a frame every vsync.
            modifier.basicMarquee(iterations = 3)
        } else {
            modifier
        },
        fontSize = size,
        lineHeight = size * 1.15f,
        maxLines = maxLines,
        softWrap = !scrolling,
        overflow = TextOverflow.Clip,
        color = color,
        fontWeight = fontWeight,
        textAlign = textAlign,
        onTextLayout = { result ->
            if (result.hasVisualOverflow && size.value > minSize.value + 0.1f) {
                size = (size.value - 0.5f).coerceAtLeast(minSize.value).sp
            } else if (result.hasVisualOverflow) {
                // already at the floor and still too big, let it scroll
                scrolling = true
            }
        },
    )
}
