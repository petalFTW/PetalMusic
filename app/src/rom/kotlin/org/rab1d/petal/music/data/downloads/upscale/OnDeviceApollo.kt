/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.data.downloads.upscale

import android.content.Context

/** rom flavor: Apollo runs on device through ONNX Runtime (QNN when it helps). */
internal object OnDeviceApollo {
    const val AVAILABLE = true

    fun create(context: Context, repository: UpscaleModelRepository, config: UpscaleConfig): RestoreEngine =
        OnnxAudioUpscaler(
            repository.modelFile, config, repository.pipelineFiles(),
            context.applicationInfo.nativeLibraryDir, context,
        )
}
