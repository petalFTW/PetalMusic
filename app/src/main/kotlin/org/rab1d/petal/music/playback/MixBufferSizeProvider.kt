/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import androidx.media3.exoplayer.audio.DefaultAudioSink
import kotlin.math.ceil

/**
 * Keep live gain updates responsive. Media3's default expands its entire music
 * buffer by the maximum platform speed (8x). Reserve against the device minimum
 * instead, with two minimum buffers at that speed and a 180 ms normal-play floor.
 */
internal object MixBufferSizeProvider : DefaultAudioSink.AudioTrackBufferSizeProvider {
    override fun getBufferSizeInBytes(
        minBufferSizeInBytes: Int,
        encoding: Int,
        outputMode: Int,
        pcmFrameSize: Int,
        sampleRate: Int,
        bitrate: Int,
        maxAudioTrackPlaybackSpeed: Double,
    ): Int {
        if (outputMode != DefaultAudioSink.OUTPUT_MODE_PCM) {
            return DefaultAudioSink.AudioTrackBufferSizeProvider.DEFAULT.getBufferSizeInBytes(
                minBufferSizeInBytes, encoding, outputMode, pcmFrameSize, sampleRate, bitrate, maxAudioTrackPlaybackSpeed,
            )
        }
        val frame = pcmFrameSize.coerceAtLeast(1).toLong()
        val speedReserve = ceil(minBufferSizeInBytes * maxAudioTrackPlaybackSpeed.coerceAtLeast(1.0) * 2).toLong()
        val normalReserve = sampleRate.toLong() * frame * 180 / 1000
        val bytes = maxOf(minBufferSizeInBytes.toLong(), speedReserve, normalReserve)
        return (((bytes + frame - 1) / frame) * frame).coerceAtMost(Int.MAX_VALUE / frame * frame).toInt()
    }
}
