/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Loads and caches one [ArtTheme] per cover. Extraction runs off the main thread. */
object ArtThemes {
    private const val SAMPLE = 40
    private val cache = LruCache<String, ArtTheme>(160)

    fun cached(uri: String?): ArtTheme? = uri?.let { cache.get(it) }

    suspend fun load(context: Context, uri: String): ArtTheme? {
        cache.get(uri)?.let { return it }
        return try {
            val result = context.imageLoader.execute(
                ImageRequest.Builder(context).data(uri).allowHardware(false).size(SAMPLE).build(),
            )
            val bmp = (result.drawable as? BitmapDrawable)?.bitmap ?: return null
            val theme = withContext(Dispatchers.Default) {
                val small = if (bmp.width > SAMPLE * 2 || bmp.height > SAMPLE * 2) {
                    Bitmap.createScaledBitmap(bmp, SAMPLE, SAMPLE, true)
                } else bmp
                val px = IntArray(small.width * small.height)
                small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
                extractArtTheme(px, small.width)
            } ?: return null
            cache.put(uri, theme)
            theme
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * The theme for one item: from its cover when there is one, otherwise from a hash of
 * [seed] (title + artist), otherwise [fallback]. Returns the cached or seeded theme at
 * once and swaps in the cover's when it has been read.
 */
@Composable
fun rememberArtTheme(uri: String?, seed: String? = null, fallback: ArtTheme? = null): ArtTheme {
    val context = LocalContext.current
    val placeholder = remember(uri, seed, fallback) {
        ArtThemes.cached(uri) ?: seed?.takeIf { it.isNotBlank() }?.let { seedArtTheme(it) }
            ?: fallback ?: ArtTheme.neutral()
    }
    var theme by remember(uri, seed) { mutableStateOf(placeholder) }
    LaunchedEffect(uri) {
        if (uri != null && !theme.fromArt) ArtThemes.load(context, uri)?.let { theme = it }
    }
    return theme
}

/**
 * Cross-fades to [target] over [Motion.THEME] ms in OKLCH. An interrupted fade
 * continues from the colour on screen, never jumps back.
 */
@Composable
fun animateArtTheme(target: ArtTheme): ArtTheme {
    val reduced = LocalReducedMotion.current
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    val t = remember { Animatable(1f) }
    LaunchedEffect(target) {
        if (target == to) return@LaunchedEffect
        from = ArtTheme.lerp(from, to, t.value)
        to = target
        if (reduced) { t.snapTo(1f); return@LaunchedEffect }
        t.snapTo(0f)
        t.animateTo(1f, tween(Motion.THEME, easing = Motion.Enter))
    }
    val v = t.value
    return if (v >= 1f) to else ArtTheme.lerp(from, to, v)
}

/** The theme of what's playing, animated: the app chrome (mini-player, tab bar) takes it. */
val LocalNowPlayingTheme = compositionLocalOf { ArtTheme.neutral() }
