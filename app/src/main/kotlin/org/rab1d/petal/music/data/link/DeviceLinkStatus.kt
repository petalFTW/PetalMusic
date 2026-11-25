/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.data.link

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the Device Link is reachable. `null` = unknown (never probed).
 *
 * Once a link operation fails we latch `false` and stop attempting, so a phone
 * with the desktop switched off never burns battery retrying. Tapping **Link**
 * (a deliberate user action) is the only thing that probes again.
 */
object DeviceLinkStatus {
    private val _connected = MutableStateFlow<Boolean?>(null)
    val connected: StateFlow<Boolean?> = _connected

    fun set(value: Boolean?) {
        _connected.value = value
    }
}
