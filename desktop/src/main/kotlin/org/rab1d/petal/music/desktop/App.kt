/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.rab1d.petal.music.desktop.audio.BridgeFXWin
import org.rab1d.petal.music.desktop.ui.AppRoot
import org.rab1d.petal.music.desktop.ui.state.DesktopController
import org.rab1d.petal.music.desktop.ui.theme.PetalPalette
import org.rab1d.petal.music.desktop.link.DeviceLinkServer

/** The Android design canvas, in dp. Everything is laid out against this. */
private const val PHONE_W = 412f
private const val PHONE_H = 892f

@Composable
fun App(engine: BridgeFXWin) {
    val scope = rememberCoroutineScope()
    val controller = remember { DesktopController(engine, scope) }
    val link = remember { DeviceLinkServer() }
    link.libraryDir = { controller.libraryPath }
    link.onQueueDrained = { if (controller.libraryPath.isNotBlank()) controller.scanLibrary(controller.libraryPath) }
    DisposableEffect(Unit) { onDispose { link.stop() } }

    LaunchedEffect(Unit) {
        runCatching { engine.start() }.onFailure { controller.reportStatus("No audio output (${it.message})") }
        engine.fadeMs = controller.fadeMs
        engine.style = controller.style
        engine.automix = controller.automix
        engine.volume = controller.volume
        // Device Link is on by default so the phone can connect immediately;
        // it can be stopped from Settings.
        if (!link.isRunning) link.start(DeviceLinkServer.DEFAULT_PORT)
    }

    // Render the phone-sized UI 1:1 and scale it to fit the window.
    PhoneCanvas {
        AppRoot(controller, link)
    }
}

@Composable
private fun PhoneCanvas(content: @Composable () -> Unit) {
    val outer = LocalDensity.current
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(Color(0xFF050509)),
        contentAlignment = Alignment.Center,
    ) {
        val wDp = maxWidth.value
        val hDp = maxHeight.value
        val scale = minOf(wDp / PHONE_W, hDp / PHONE_H).coerceIn(0.35f, 1.8f)
        val density = Density(outer.density * scale, outer.fontScale)
        CompositionLocalProvider(LocalDensity provides density) {
            Box(
                Modifier
                    .size(PHONE_W.dp, PHONE_H.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(PetalPalette.Petal.wash),
            ) {
                content()
            }
        }
    }
}
