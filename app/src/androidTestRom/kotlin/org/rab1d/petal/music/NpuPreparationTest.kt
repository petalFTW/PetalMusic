/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music

import android.os.Process
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.rab1d.petal.music.data.downloads.upscale.NpuPreparationClient
import org.rab1d.petal.music.data.downloads.upscale.UpscaleModelRepository
import org.rab1d.petal.music.data.downloads.upscale.OnnxAudioUpscaler
import kotlin.math.sin
import kotlin.math.sqrt

/** Exercises the actual app-sandbox DSP access and compiler-process boundary. */
class NpuPreparationTest {
    @Test
    fun prepareSmallNpuBlocks() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = UpscaleModelRepository(context)
        repository.ensure()
        val files = requireNotNull(repository.pipelineFiles())
        val pid = Process.myPid()
        NpuPreparationClient.prepare(
            context, files, { false }, { Log.i("PetalMusic/NpuTest", it) },
            blockTimeoutMs = 180_000, totalTimeoutMs = 600_000,
        )
        assertTrue("Parent process survived preparation", pid == Process.myPid())
        assertTrue("All six contexts were published", files.npuBlocks.all { it.cache.length() > 0 })
        val input = Array(2) { channel ->
            FloatArray(88_200) { sample ->
                (0.15 * sin(2.0 * Math.PI * (440 + channel * 220) * sample / 44_100) +
                    0.05 * sin(2.0 * Math.PI * 3_300 * sample / 44_100)).toFloat()
            }
        }
        val config = repository.readConfig()
        val reference = OnnxAudioUpscaler(repository.modelFile, config, files).use { it.runSegment(input) }
        OnnxAudioUpscaler(repository.modelFile, config, files, context.applicationInfo.nativeLibraryDir, context).use {
            val candidate = it.runSegment(input)
            var error = 0.0
            for (channel in reference.indices) for (sample in reference[channel].indices) {
                val difference = (reference[channel][sample] - candidate[channel][sample]).toDouble()
                assertTrue(candidate[channel][sample].isFinite())
                error += difference * difference
            }
            val rms = sqrt(error / (2 * 88_200))
            Log.i("PetalMusic/NpuTest", "Selected backend: ${it.backendDescription}; waveform RMS difference=$rms")
            assertTrue("Accelerated waveform agrees with CPU", rms < 0.005)
        }
    }
}
