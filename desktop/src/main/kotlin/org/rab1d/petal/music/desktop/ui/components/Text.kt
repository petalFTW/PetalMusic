/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.desktop.ui.components

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

/** Shrinks until it fits [maxLines], then marquees. Android parity. */
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
        modifier = if (scrolling) modifier.basicMarquee(Int.MAX_VALUE) else modifier,
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
                scrolling = true
            }
        },
    )
}
