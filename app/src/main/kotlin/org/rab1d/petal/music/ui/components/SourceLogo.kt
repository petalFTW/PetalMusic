/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import org.rab1d.petal.music.R
import org.rab1d.petal.music.model.Source

/** Brand marks for each service, drawn as vectors so they tint anywhere. */
object SourceBrand {
    fun drawable(source: Source): Int = when (source) {
        Source.TIDAL -> R.drawable.ic_source_tidal
        Source.SPOTIFY -> R.drawable.ic_source_spotify
        Source.YT_MUSIC -> R.drawable.ic_source_ytmusic
        Source.DEEZER -> R.drawable.ic_source_deezer
        Source.LOCAL -> R.drawable.ic_source_device
    }

    fun accent(source: Source): Color = when (source) {
        Source.TIDAL -> Color(0xFFE8E8F2)
        Source.SPOTIFY -> Color(0xFF4CE87A)
        Source.YT_MUSIC -> Color(0xFFFF5C74)
        Source.DEEZER -> Color(0xFFC77DFF)
        Source.LOCAL -> Color(0xFFB9A7E8)
    }
}

@Composable
fun SourceLogo(
    source: Source,
    modifier: Modifier = Modifier,
    size: Int = 18,
    tint: Color = Color.White,
) {
    Icon(
        painter = painterResource(SourceBrand.drawable(source)),
        contentDescription = source.label,
        tint = tint,
        modifier = modifier.size(size.dp),
    )
}
