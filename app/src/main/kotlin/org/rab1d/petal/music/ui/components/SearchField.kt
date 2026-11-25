/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.rab1d.petal.music.ui.theme.Motion
import org.rab1d.petal.music.ui.theme.Petal
import org.rab1d.petal.music.ui.theme.Radius
import org.rab1d.petal.music.ui.theme.Space

/**
 * The search field. On glass when [backdrop] is given (the Search tab's header floats
 * over results), a plain surface otherwise. Focus grows it and lights its edge.
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
    tint: Color = Petal.colors.surface1,
    height: Dp = 48.dp,
) {
    val c = Petal.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val h by animateDpAsState(if (focused) height + 8.dp else height, Motion.enter(Motion.BASE), label = "searchH")
    val edge by animateColorAsState(if (focused) c.accent else Color.Transparent, Motion.press(Motion.BASE), label = "searchEdge")
    val surface = if (backdrop != null) Modifier.glass(backdrop, GlassLevel.Control, tint, Radius.control)
        else Modifier.clip(Radius.control).background(tint)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(h)
            .then(surface)
            .border(1.5.dp, edge, Radius.control)
            .padding(horizontal = Space.m),
    ) {
        Icon(Icons.Rounded.Search, null, tint = if (focused) c.accent else c.text2, modifier = Modifier.size(22.dp))
        Box(Modifier.weight(1f).padding(horizontal = Space.m)) {
            if (value.isEmpty()) Text(placeholder, color = c.text3, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                interactionSource = interaction,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.ink),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            GlyphButton(onClick = { onValueChange("") }, label = "Clear search", size = 40.dp) {
                Icon(Icons.Rounded.Close, null, tint = c.text2, modifier = Modifier.size(20.dp))
            }
        }
    }
}
