/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.data.downloads.upscale

import ai.onnxruntime.OrtSession
import android.system.Os
import java.io.File

internal object QnnSessionOptions {
    fun configure(options: OrtSession.SessionOptions, nativeDir: String, outputCache: File? = null) {
        Os.setenv("ADSP_LIBRARY_PATH", "$nativeDir;/odm/lib/rfsa/adsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp", true)
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
        options.addConfigEntry("session.disable_cpu_ep_fallback", "1")
        if (outputCache != null) {
            options.addConfigEntry("ep.context_enable", "1")
            options.addConfigEntry("ep.context_embed_mode", "1")
            options.addConfigEntry("ep.context_file_path", outputCache.absolutePath)
        }
        options.addQnn(
            mapOf(
                "backend_path" to File(nativeDir, "libQnnHtp.so").absolutePath,
                "enable_htp_fp16_precision" to "1",
                "htp_performance_mode" to "sustained_high_performance",
                "htp_graph_finalization_optimization_mode" to "1",
            ),
        )
    }
}
