/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.rab1d.petal.music.model.Source
import org.rab1d.petal.music.sources.spotify.SpotifySessionBridge
import org.rab1d.petal.music.sources.spotify.LibrespotBridge
import org.rab1d.petal.music.model.Track

class SpotifyPlaybackTest {
    @Test fun spotifyAccountDetection() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PetalApp
        withTimeout(60_000) { app.container.servicesReady.first { it } }
        val spotify = app.container.spotify
        assumeTrue(spotify.loggedIn)
        // Free accounts cannot stream full tracks; playback then routes through
        // YT Music, so the librespot pump is only asserted on premium.
        if (!spotify.freeTier) {
            val track = app.container.providers.getValue(Source.SPOTIFY).search("Daft Punk Get Lucky").tracks.first()
            val failure = java.util.concurrent.atomic.AtomicReference<Exception>()
            spotify.setListener(object : LibrespotBridge.PumpListener {
                override fun onTrackChanged(track: Track?) {}
                override fun onPlaybackEnded() {}
                override fun onPlayingChanged(playing: Boolean, positionMs: Long) {}
                override fun onSeeked(positionMs: Long) {}
                override fun onFailed(e: Exception) { failure.set(e) }
            })
            spotify.play(track, true)
            try {
                val deadline = System.currentTimeMillis() + 30_000
                while (System.currentTimeMillis() < deadline && failure.get() == null &&
                    (SpotifySessionBridge.activeSink == null || spotify.position() < 500)) delay(100)
                assertTrue("Spotify never started the PCM sink: ${failure.get()}", SpotifySessionBridge.activeSink != null)
                assertTrue("Spotify playhead did not advance: ${spotify.position()}", spotify.position() >= 500)
                assertTrue("Spotify reports paused", spotify.isPlaying())
            } finally { spotify.pause(); spotify.setListener(null) }
        } else {
            // Free tier: verify a search result converts to a YT Music match
            // AND resolves to a playable audio stream.
            val track = app.container.providers.getValue(Source.SPOTIFY).search("Daft Punk Get Lucky").tracks.first()
            val resolved: org.rab1d.petal.music.model.Track = app.container.resolveYtMusicEquivalent(track)
                ?: error("Free-tier fallback resolution failed")
            assertTrue(resolved.source == Source.YT_MUSIC && resolved.originSource == Source.SPOTIFY)
            val handle = app.container.ytMusic.stream(resolved)
            val url = (handle as? org.rab1d.petal.music.sources.StreamHandle.Url)?.url
            assertTrue("YT Music stream resolution failed", !url.isNullOrBlank() && url.startsWith("https"))
        }
    }
}
