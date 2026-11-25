/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.desktop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.rab1d.petal.music.desktop.library.DesktopTrack
import org.rab1d.petal.music.desktop.ui.theme.petalPalette

/** Mirrors the Android Source enum so the UI reads identically. */
enum class DesktopSource(val id: String, val label: String, val tint: Color) {
    LOCAL("local", "Device", Color(0xFF8E4957)),
    SPOTIFY("spotify", "Spotify", Color(0xFF1DB954)),
    YT_MUSIC("ytmusic", "YT Music", Color(0xFFFF0033)),
    DEEZER("deezer", "Deezer", Color(0xFFA238FF)),
    TIDAL("tidal", "Tidal", Color(0xFF00B3E3)),
}

/** Simple vector brand marks so the desktop needs no Android drawables. */
@Composable
fun SourceLogo(source: DesktopSource, modifier: Modifier = Modifier, size: Int = 18, tint: Color = Color.White) {
    Canvas(modifier.size(size.dp)) {
        val w = this.size.width
        val h = this.size.height
        val s = minOf(w, h)
        when (source) {
            DesktopSource.LOCAL -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.30f, h * 0.14f),
                    size = Size(w * 0.40f, h * 0.72f),
                    cornerRadius = CornerRadius(w * 0.10f),
                    style = Stroke(width = s * 0.09f),
                )
                drawCircle(tint, radius = s * 0.05f, center = Offset(w * 0.50f, h * 0.74f))
            }
            DesktopSource.SPOTIFY -> {
                val stroke = Stroke(width = s * 0.10f)
                for (i in 0..2) {
                    val r = w * (0.18f + i * 0.16f)
                    drawArc(
                        color = tint, startAngle = 200f, sweepAngle = 140f, useCenter = false,
                        topLeft = Offset(w / 2 - r, h / 2 - r + s * 0.10f),
                        size = Size(r * 2, r * 2), style = stroke,
                    )
                }
            }
            DesktopSource.YT_MUSIC -> {
                drawCircle(tint, radius = s * 0.44f, center = Offset(w / 2, h / 2))
                val p = Path().apply {
                    moveTo(w * 0.42f, h * 0.34f); lineTo(w * 0.70f, h * 0.50f); lineTo(w * 0.42f, h * 0.66f); close()
                }
                drawPath(p, Color(0xFF0A0B10))
            }
            DesktopSource.DEEZER -> {
                val barW = w * 0.14f
                for (i in 0..3) {
                    val hh = h * (0.30f + i * 0.16f)
                    drawRoundRect(
                        color = tint,
                        topLeft = Offset(w * (0.14f + i * 0.20f), h - hh - h * 0.14f),
                        size = Size(barW, hh),
                        cornerRadius = CornerRadius(barW * 0.4f),
                    )
                }
            }
            DesktopSource.TIDAL -> {
                val p = Path().apply {
                    moveTo(w * 0.50f, h * 0.12f); lineTo(w * 0.88f, h * 0.50f)
                    lineTo(w * 0.50f, h * 0.88f); lineTo(w * 0.12f, h * 0.50f); close()
                }
                drawPath(p, tint, style = Stroke(width = s * 0.10f))
            }
        }
    }
}

@Composable
fun SourceBadge(source: DesktopSource, modifier: Modifier = Modifier, size: Int = 26) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size * 0.35f).toInt().dp))
            .background(source.tint.copy(alpha = 0.22f)),
    ) {
        SourceLogo(source, size = (size * 0.58f).toInt().coerceAtLeast(10), tint = source.tint)
    }
}

@Composable
fun SourceChip(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    source: DesktopSource? = null,
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .clip(shape)
            .background(if (selected) Color(0x30FFFFFF) else Color(0x14FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        source?.let { SourceLogo(it, size = 16, tint = if (selected) accent else Color(0xFFAAA6AD)) }
        Text(
            label,
            color = if (selected) accent else Color.White.copy(alpha = 0.7f),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            fontSize = 14.sp,
        )
    }
}

/** Consistent artwork placeholder (desktop has no embedded art yet). */
@Composable
fun CoverArt(uri: String?, modifier: Modifier = Modifier, corner: Int = 12) {
    Box(
        modifier.clip(RoundedCornerShape(corner.dp)).background(
            Brush.linearGradient(listOf(Color(0xFF45323F), Color(0xFF262530))),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.MusicNote, null, tint = Color(0xFFB99EAD), modifier = Modifier.size(32.dp))
    }
}

@Composable
fun TrackRow(
    track: DesktopTrack,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    playing: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        CoverArt(null, Modifier.size(52.dp), corner = 8)
        Column(
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        ) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                SourceBadge(DesktopSource.LOCAL, size = 16)
                Text(
                    track.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun NumberedTrackRow(
    number: Int,
    track: DesktopTrack,
    playing: Boolean,
    accent: Color,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        Text(
            "$number",
            style = MaterialTheme.typography.bodyLarge,
            color = if (playing) accent else Color.White.copy(alpha = 0.45f),
            modifier = Modifier.width(32.dp),
        )
        Column(verticalArrangement = Arrangement.Center, modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (playing) accent else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                SourceBadge(DesktopSource.LOCAL, size = 15)
                Text(
                    track.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
        trailing?.invoke()
        Text(
            formatDuration(track.durationMs),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.45f),
            modifier = Modifier.padding(start = 2.dp),
        )
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.4.sp, fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

internal fun formatDuration(ms: Long): String {
    if (ms <= 0) return "--:--"
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
