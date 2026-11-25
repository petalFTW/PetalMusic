/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.sources.spotify

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.spotify.connectstate.Connect
import xyz.gianlu.librespot.core.Session
import xyz.gianlu.librespot.player.Player
import xyz.gianlu.librespot.player.PlayerConfiguration
import xyz.gianlu.librespot.player.mixing.output.OutputAudioFormat
import xyz.gianlu.librespot.player.mixing.output.SinkException
import xyz.gianlu.librespot.player.mixing.output.SinkOutput

/**
 * Shared state for the librespot custom sink: the PCM pump writes into an
 * AudioTrack bound to the main playback audio session, so the whole app —
 * ExoPlayer and Spotify alike — lives in one session that BridgeFX can own.
 */
object SpotifySessionBridge {
    @Volatile var audioSessionId: Int = -1
    @Volatile var activeSink: PetalSink? = null
}

/**
 * librespot SinkOutput: decodes-to-PCM lands here. petalOS detail — the
 * AudioTrack uses the service's audio session id, keeping BridgeFX, the
 * volume ramping and the session-wide mixing coherent.
 */
class PetalSink : SinkOutput {
    private var track: AudioTrack? = null
    private var volume: Float = 1f

    override fun start(format: OutputAudioFormat): Boolean {
        val session = SpotifySessionBridge.audioSessionId
        val encoding = when (format.sampleSizeInBits) {
            16 -> AudioFormat.ENCODING_PCM_16BIT
            24 -> AudioFormat.ENCODING_PCM_FLOAT.takeIf { false } ?: AudioFormat.ENCODING_PCM_8BIT
            else -> AudioFormat.ENCODING_PCM_FLOAT
        }
        val minBuf = AudioTrack.getMinBufferSize(
            format.sampleRate.toInt(),
            channelOut(format.channels),
            encoding,
        )
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(format.sampleRate.toInt())
                    .setChannelMask(channelOut(format.channels))
                    .setEncoding(encoding)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes((minBuf * 4).coerceAtLeast(16384))
            .setSessionId(if (session > 0) session else 0)
            .build()
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            t.release()
            return false
        }
        track = t
        t.play()
        SpotifySessionBridge.activeSink = this
        Log.i("PetalSink", "started rate=${format.sampleRate} ch=${format.channels} bits=${format.sampleSizeInBits} session=$session")
        return true
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        track?.write(b, off, len)
    }

    override fun setVolume(volume: Float): Boolean {
        this.volume = volume
        return track?.setVolume(volume.coerceIn(0f, 1f)) == AudioTrack.SUCCESS
    }

    fun currentVolume(): Float = volume

    override fun drain() {
        track?.let { runCatching { it.stop() } }
    }

    override fun flush() {
        track?.let { runCatching { it.flush() } }
    }

    override fun stop() {
        track?.let { runCatching { it.stop() } }
    }

    override fun release() {
        SpotifySessionBridge.activeSink = null
        track?.release()
        track = null
    }

    override fun close() {
        release()
    }

    private fun channelOut(channels: Int): Int = when (channels) {
        1 -> AudioFormat.CHANNEL_OUT_MONO
        2 -> AudioFormat.CHANNEL_OUT_STEREO
        6 -> AudioFormat.CHANNEL_OUT_5POINT1
        8 -> AudioFormat.CHANNEL_OUT_7POINT1
        else -> AudioFormat.CHANNEL_OUT_DEFAULT
    }
}
