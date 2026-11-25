/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.desktop.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.rab1d.petal.music.desktop.ui.theme.petalPalette

data class PagePalette(
    val accent: Color = Color(0xFFB9A5FF),
    val background: Color = Color(0xFF15121A),
    val alt: Color = Color(0xFF7C5CFF),
)

@Composable
fun rememberPagePalette(artUri: String?): PagePalette {
    val p = petalPalette()
    return PagePalette(accent = p.accent, background = Color(0xFF15121A), alt = p.accentAlt)
}

@Composable
fun ArtHeaderScreen(
    artUri: String?,
    title: String,
    metaLine: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    headerExtras: @Composable () -> Unit = {},
    listContent: LazyListScope.() -> Unit,
) {
    val palette = rememberPagePalette(artUri)
    LazyColumn(
        modifier = modifier.fillMaxSize().background(Color(0xFF0A0B10)),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(palette.background, Color(0xFF0A0B10)))),
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                CoverArt(artUri, Modifier.padding(horizontal = 56.dp).widthIn(max = 340.dp).fillMaxWidth().aspectRatio(1f), corner = 12)
                Text(
                    title, color = Color.White, style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp),
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    metaLine, color = Color(0xFFB9AEB8), style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                headerExtras()
            }
        }
        listContent()
    }
}

@Composable
fun PlayShufflePills(
    onShuffle: () -> Unit,
    onPlay: () -> Unit,
    accent: Color = MaterialTheme.colorScheme.primary,
    trailing: (@Composable () -> Unit)? = null,
) {
    val glass = petalPalette()
    val shape = RoundedCornerShape(12.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
    ) {
        GlassIconButton(Icons.Filled.Shuffle, "Shuffle", onShuffle, size = 48)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .clip(shape)
                .background(glass.accentBrush)
                .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)), shape)
                .springyClickable(onPlay),
        ) {
            Icon(Icons.Filled.PlayArrow, null, tint = glass.onAccent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text("Play", color = glass.onAccent, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
        if (trailing != null) trailing() else Spacer(Modifier.size(48.dp))
    }
}
