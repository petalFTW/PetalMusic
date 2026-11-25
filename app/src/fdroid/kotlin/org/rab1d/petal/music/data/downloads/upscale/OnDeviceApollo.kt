/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.data.downloads.upscale

import android.content.Context

/**
 * F-Droid build: no on-device Apollo (the QNN runtime is proprietary). Restore
 * runs over Device Link only; local passes use the DSP Enhance path.
 */
internal object OnDeviceApollo {
    const val AVAILABLE = false

    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context, repository: UpscaleModelRepository, config: UpscaleConfig): RestoreEngine =
        throw UnsupportedOperationException("on-device Apollo is not part of this build")
}
