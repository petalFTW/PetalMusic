/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.rab1d.petal.music.ui.AppRoot
import org.rab1d.petal.music.ui.PetalViewModel
import org.rab1d.petal.music.ui.theme.Dye
import org.rab1d.petal.music.ui.theme.PetalTheme
import org.rab1d.petal.music.ui.theme.ThemeMode
import org.rab1d.petal.music.ui.theme.isPaper
import androidx.compose.runtime.LaunchedEffect

class MainActivity : ComponentActivity() {

    private val viewModel by lazy { PetalViewModel(this, (application as PetalApp).container.settings, (application as PetalApp).container.library) }

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        lifecycleScope.launch {
            (application as PetalApp).container.settings.settings.collect {
                themeMode = runCatching { ThemeMode.valueOf(it.themeMode) }.getOrDefault(ThemeMode.SYSTEM)
                dye = runCatching { Dye.valueOf(it.dye) }.getOrDefault(Dye.SLEEVE)
            }
        }
        setContent {
            PetalTheme(mode = themeMode, dye = dye) {
                // Bar icons follow the theme: light icons on Night, dark on Paper.
                val paper = themeMode.isPaper()
                LaunchedEffect(paper) {
                    val style = if (paper)
                        androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    else androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }
                AppRoot(viewModel)
            }
        }
    }

    private var themeMode by mutableStateOf(ThemeMode.SYSTEM)
    private var dye by mutableStateOf(Dye.SLEEVE)
}
