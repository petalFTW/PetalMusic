/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.desktop

import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.rab1d.petal.music.desktop.audio.BridgeFXWin

fun main() = application {
    val engine = BridgeFXWin()
    Window(
        onCloseRequest = {
            engine.close()
            exitApplication()
        },
        state = rememberWindowState(width = 460.dp, height = 940.dp),
        title = "Petal Music — ${BridgeFXWin.NAME}",
        icon = painterResource("icons/petal_music.png"),
    ) {
        App(engine)
    }
}
