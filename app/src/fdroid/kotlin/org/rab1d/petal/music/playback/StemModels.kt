/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.playback

import android.content.Context

/** fdroid: no ONNX Runtime, so stems come from the model-free separator. */
internal object StemModels {
    fun separator(context: Context): StemSeparator = HpssStemSeparator()
}
