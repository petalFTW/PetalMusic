/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.data.downloads.upscale

import java.io.Closeable
import java.io.File

/**
 * Apollo restoration on already-decoded PCM. Only the rom flavor ships an
 * implementation (ONNX Runtime + QNN); see OnDeviceApollo.
 */
interface RestoreEngine : Closeable {
    val backendDescription: String

    fun upscaleDecoded(
        pcm: File,
        frames: Long,
        output: File,
        profile: AudioProfile,
        shouldCancel: () -> Boolean = { false },
        masteringWidth: Float = 0.5f,
        onProgress: (Float) -> Unit,
    ): Long
}
