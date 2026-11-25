/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.rab1d.petal.music.ui.theme.LocalReducedMotion
import org.rab1d.petal.music.ui.theme.Motion
import org.rab1d.petal.music.ui.theme.Petal
import org.rab1d.petal.music.ui.theme.Radius
import org.rab1d.petal.music.ui.theme.Space

data class Pill<T>(val value: T, val label: String, val leading: (@Composable () -> Unit)? = null)

/**
 * A row of filter pills where the selection is one solid pill that slides to the chosen
 * option (critically damped), rather than each pill blinking on and off.
 */
@Composable
fun <T> FilterPills(
    options: List<Pill<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
) {
    val c = Petal.colors
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    val xs = remember { mutableStateMapOf<Int, Pair<Dp, Dp>>() }
    val index = options.indexOfFirst { it.value == selected }.coerceAtLeast(0)
    val target = xs[index]
    val x by animateDpAsState(target?.first ?: 0.dp, Motion.orSnap(reduced, Motion.drag()), label = "pillX")
    val w by animateDpAsState(target?.second ?: 0.dp, Motion.orSnap(reduced, Motion.drag()), label = "pillW")
    Box(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Space.gutter),
    ) {
        if (target != null) {
            Box(Modifier.offset(x = x).width(w).height(height).clip(Radius.pill).background(c.ink))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            options.forEachIndexed { i, option ->
                val active = i == index
                val ink by animateColorAsState(if (active) c.bg else c.text2, Motion.press(Motion.BASE), label = "pillInk")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .height(height)
                        .onGloballyPositioned { coords ->
                            with(density) {
                                xs[i] = coords.positionInParent().x.toDp() to coords.size.width.toDp()
                            }
                        }
                        .clip(Radius.pill)
                        .then(if (active) Modifier else Modifier.background(c.ink.copy(alpha = .07f)))
                        .pressable(onClick = { onSelect(option.value) }, role = Role.Tab)
                        .semantics { this.selected = active }
                        .padding(horizontal = Space.l),
                ) {
                    option.leading?.let { it(); Box(Modifier.width(Space.s)) }
                    Text(option.label, style = MaterialTheme.typography.labelLarge, color = ink)
                }
            }
        }
    }
}
