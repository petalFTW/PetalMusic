/* SPDX-License-Identifier: Apache-2.0 */
package org.rab1d.petal.music.ui.screens

import org.rab1d.petal.music.ui.components.sleeveLight

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.rab1d.petal.music.data.AutomixAlgorithm
import org.rab1d.petal.music.data.AutomixStyle
import org.rab1d.petal.music.BuildConfig
import org.rab1d.petal.music.data.UpscaleMode
import org.rab1d.petal.music.data.downloads.upscale.OnDeviceApollo
import org.rab1d.petal.music.ui.PetalViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: PetalViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsState()
    val state by viewModel.playbackState.collectAsStateWithLifecycle()
    var spotifyDialog by remember { mutableStateOf(false) }
    var ytMusicDialog by remember { mutableStateOf(false) }
    var tidalDialog by remember { mutableStateOf(false) }
    var discordDialog by remember { mutableStateOf(false) }
    // account status is plain getters, compose never saw sign-outs. poke this to re-read
    var accountsTick by remember { mutableIntStateOf(0) }
    val discordUser by viewModel.discordUser.collectAsState()
    val discordEnabled by viewModel.discordEnabled.collectAsState()
    val scope = rememberCoroutineScope()
    val deviceLink by viewModel.deviceLink.collectAsState()
    val upscaleSettings by viewModel.upscaleSettings.collectAsState()
    val unlockTaps by viewModel.upscaleUnlockTaps.collectAsState()
    val showUnlockWarning by viewModel.showUpscaleWarning.collectAsState()
    val context = LocalContext.current
    val linkConnected by viewModel.linkConnected.collectAsState()
    var linkHost by remember(deviceLink.host) { mutableStateOf(deviceLink.host) }
    var linkPort by remember(deviceLink.port) { mutableStateOf(deviceLink.port.toString()) }
    var probeResult by remember { mutableStateOf<String?>(null) }
    var probing by remember { mutableStateOf(false) }
    var linkFailed by remember { mutableStateOf(false) }

    val scroll = rememberScrollState()
    val pageColor = org.rab1d.petal.music.ui.theme.Petal.colors.bg
    val statusPx = androidx.compose.foundation.layout.WindowInsets.statusBars.getTop(androidx.compose.ui.platform.LocalDensity.current)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(pageColor)
            // Before verticalScroll, so it stays put: a strip under the status bar that fills in
            // once the lit header has scrolled away, so text never runs into the clock.
            .drawWithContent {
                drawContent()
                val a = (scroll.value / (statusPx * 3f).coerceAtLeast(1f)).coerceIn(0f, .96f)
                drawRect(pageColor.copy(alpha = a), size = androidx.compose.ui.geometry.Size(size.width, statusPx.toFloat()))
            }
            .verticalScroll(scroll)
            .padding(bottom = org.rab1d.petal.music.ui.components.LocalChromeInset.current + 24.dp),
    ) {
        SettingsHeader(viewModel, onBack)

        // ---- Accounts ----------------------------------------------------------
        // spotify restores its session in the background, so re-check on resume too
        LifecycleResumeEffect(Unit) {
            accountsTick++
            onPauseOrDispose { }
        }
        val spotifyUser = remember(accountsTick, spotifyDialog) { viewModel.spotifyUsername() }
        val ytSignedIn = remember(accountsTick, ytMusicDialog) { viewModel.ytMusicLoggedIn() }
        val tidalSignedIn = remember(accountsTick, tidalDialog) { viewModel.tidalLoggedIn() }
        SectionHeader("Accounts")
        AccountCard(
            name = "Spotify",
            status = spotifyUser?.let { "Signed in as $it" } ?: "Not signed in",
            onLogin = { spotifyDialog = true },
            signedIn = spotifyUser != null,
            onLogout = {
                scope.launch {
                    // closing the session hits the network, keep it off the main thread
                    withContext(Dispatchers.IO) { viewModel.spotifyLogout() }
                    accountsTick++
                }
            },
        )
        AccountCard(
            name = "YT Music",
            status = if (ytSignedIn) "Signed in (Google)" else "Not signed in",
            onLogin = { ytMusicDialog = true },
            signedIn = ytSignedIn,
            onLogout = {
                viewModel.ytMusicLogout()
                accountsTick++
            },
        )
        AccountCard(
            name = "Tidal",
            status = if (tidalSignedIn) "Signed in (web login)" else "Not signed in",
            onLogin = { tidalDialog = true },
            signedIn = tidalSignedIn,
            onLogout = {
                viewModel.tidalLogout()
                accountsTick++
            },
        )
        AccountCard(
            name = "Discord",
            status = discordUser?.let { "Rich Presence · signed in as $it" } ?: "Not signed in",
            onLogin = { discordDialog = true },
        )
        if (discordUser != null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SettingSwitch(
                        title = "Rich Presence",
                        subtitle = "Show what you're listening to on your Discord profile",
                        checked = discordEnabled,
                        onChecked = { viewModel.setDiscordEnabled(it) },
                    )
                    TextButton(onClick = { viewModel.discordLogout() }) { Text("Sign out") }
                }
            }
        }

        // ---- Appearance --------------------------------------------------------
        // ---- AutoMix -----------------------------------------------------------
        SectionHeader("AutoMix")
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Enable AutoMix", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Crossfade transitions with full control of the queue",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.automixEnabled,
                        onCheckedChange = { viewModel.setAutomixEnabled(it) },
                    )
                }
                Text("Transition engine", style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    AutomixAlgorithm.entries.forEach { algorithm ->
                        FilterChip(
                            selected = settings.algorithm == algorithm,
                            onClick = { viewModel.setAlgorithm(algorithm) },
                            enabled = settings.automixEnabled,
                            label = { Text(algorithm.label) },
                        )
                    }
                }
                Text(
                    settings.algorithm.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (settings.algorithm == AutomixAlgorithm.V2_DJ) {
                    Text("Mix style", style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 12.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 6.dp),
                    ) {
                        FilterChip(
                            selected = settings.cleanMix,
                            onClick = { viewModel.setCleanMix(true) },
                            enabled = settings.automixEnabled,
                            label = { Text("Clean") },
                        )
                        FilterChip(
                            selected = !settings.cleanMix,
                            onClick = { viewModel.setCleanMix(false) },
                            enabled = settings.automixEnabled,
                            label = { Text("Creative") },
                        )
                    }
                    Text(
                        if (settings.cleanMix) "Overlays, handoffs and context mixes on the phrase. No effects."
                        else "Adds mashups, double drops, fills, builds and hook previews. Never cuts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    SettingSwitch(
                        title = "Match the next song to this one",
                        subtitle = "The song playing keeps its speed; the next one is stretched to it",
                        checked = settings.conformIncoming,
                        onChecked = { viewModel.setConformIncoming(it) },
                    )
                    if (settings.conformIncoming) SettingSwitch(
                        title = "Return to original tempo",
                        subtitle = "Slowly, over 32 bars after the mix",
                        checked = settings.glideBack,
                        onChecked = { viewModel.setGlideBack(it) },
                    )
                    if (!settings.djPacing) SettingSwitch(
                        title = "Let outros play out",
                        subtitle = "Drop the next song where this one ends",
                        checked = settings.letOutrosPlay,
                        onChecked = { viewModel.setLetOutrosPlay(it) },
                    )
                    if (settings.conformIncoming) SettingSwitch(
                        title = "Ride the tempo",
                        subtitle = "Blend songs up to 8 % apart: this one eases halfway there before the mix",
                        checked = settings.tempoRide,
                        onChecked = { viewModel.setTempoRide(it) },
                    )
                    SettingSwitch(
                        title = "Stem moves",
                        subtitle = "For files on this device: bring in only the next song's drums, drop to the vocal, preview its hook",
                        checked = settings.stemMoves,
                        onChecked = { viewModel.setStemMoves(it) },
                    )
                    SettingSwitch(
                        title = "DJ pacing",
                        subtitle = "Mix out after a song's second chorus instead of waiting for its outro (files on this device)",
                        checked = settings.djPacing,
                        onChecked = { viewModel.setDjPacing(it) },
                    )
                    SettingSwitch(
                        title = "Smooth shuffle",
                        subtitle = "When shuffling, play songs with close tempos and keys next to each other",
                        checked = settings.smoothShuffle,
                        onChecked = { viewModel.setSmoothShuffle(it) },
                    )
                }
                SettingSwitch(
                    title = "Automatic duration",
                    subtitle = "Blend the incoming intro across the detected ending, with longer musical overlaps when no ending is detected",
                    checked = settings.autoFade,
                    onChecked = { viewModel.setAutoFade(it) },
                )
                if (!settings.autoFade) {
                    var fade by remember(settings.fadeMs) { mutableFloatStateOf(settings.fadeMs.toFloat()) }
                    Text("Maximum overlap · ${"%.1f".format(fade / 1000f)} s",
                        style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = fade,
                        onValueChange = { fade = it },
                        onValueChangeFinished = { viewModel.setFadeMs(fade.toInt()) },
                        valueRange = 0f..12000f,
                        steps = 23,
                        enabled = settings.automixEnabled,
                    )
                }
            }
        }

        SectionHeader("Appearance")
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Theme", style = MaterialTheme.typography.titleMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    for (mode in org.rab1d.petal.music.ui.theme.ThemeMode.entries) {
                        FilterChip(
                            selected = settings.themeMode == mode.name,
                            onClick = { viewModel.setThemeMode(mode.name) },
                            label = { Text(mode.label) },
                        )
                    }
                }
                Text(
                    "Paper is the light theme. The player stays dark either way.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text("Dye", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                ) {
                    val paper = org.rab1d.petal.music.ui.theme.Petal.colors.dark.not()
                    for (dye in org.rab1d.petal.music.ui.theme.Dye.entries) {
                        FilterChip(
                            selected = settings.dye == dye.name,
                            onClick = { viewModel.setDye(dye.name) },
                            label = { Text(dye.label) },
                            leadingIcon = {
                                val now = org.rab1d.petal.music.ui.theme.LocalNowPlayingTheme.current
                                androidx.compose.foundation.layout.Box(
                                    Modifier
                                        .size(14.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(
                                            when (dye) {
                                                org.rab1d.petal.music.ui.theme.Dye.SYSTEM -> MaterialTheme.colorScheme.onSurfaceVariant
                                                org.rab1d.petal.music.ui.theme.Dye.SLEEVE -> now.accentOnArt
                                                else -> if (paper) dye.deep else dye.light
                                            },
                                        ),
                                )
                            },
                        )
                    }
                }
                Text(
                    "The accent for buttons and selection. Sleeve takes it from the cover that's playing; System takes the Material You colour.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        // ---- Lyrics ------------------------------------------------------------
        SectionHeader("Lyrics")
        Column(Modifier.padding(horizontal = 20.dp)) {
            SettingSwitch(
                title = "Look up lyrics on LRCLIB",
                subtitle = "When a song's own service has no lyrics, ask lrclib.net, a free lyrics database. " +
                    "It's sent the title, artist, album and length, only when you open the lyrics.",
                checked = settings.lrclibLyrics,
                onChecked = { viewModel.setLrclibLyrics(it) },
            )
        }

        // ---- Upscaling ---------------------------------------------------------
        SectionHeader("Upscaling")
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("On-device upscaling", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (!OnDeviceApollo.AVAILABLE) {
                                "Apollo restoration isn't included in this build — Restore runs over " +
                                    "Device Link. On-device jobs use the DSP Enhance path."
                            } else if (upscaleSettings.onDeviceUnlocked) {
                                "Enabled — Apollo restoration and Hi-Fi mastering run on this phone"
                            } else {
                                "Locked — tap the button 10 times to enable. Device Link processing is always allowed."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (OnDeviceApollo.AVAILABLE && !upscaleSettings.onDeviceUnlocked) {
                    FilledTonalButton(
                        onClick = {
                            val taps = viewModel.tapOnDeviceUpscale()
                            if (taps < PetalViewModel.ON_DEVICE_UNLOCK_TAPS) {
                                Toast.makeText(
                                    context,
                                    "Tap ${PetalViewModel.ON_DEVICE_UNLOCK_TAPS - taps} more times to unlock on-device upscaling",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    ) {
                        Text("Upscale on device · $unlockTaps/${PetalViewModel.ON_DEVICE_UNLOCK_TAPS}")
                    }
                }
                SettingSwitch(
                    title = "Album art 2x",
                    subtitle = "Fetch the largest cover each service offers and upscale it 2x for the player, " +
                        "media notification, lock screen and island",
                    checked = upscaleSettings.art2x,
                    onChecked = { viewModel.setArt2x(it) },
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    UpscaleMode.entries.forEach { mode ->
                        FilterChip(
                            selected = upscaleSettings.mode == mode,
                            onClick = { viewModel.setUpscaleMode(mode) },
                            label = { Text(mode.label) },
                        )
                    }
                }
                Text(
                    upscaleSettings.mode.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "Auto and Enhance inspect the actual spectrum for on-device jobs, so a transcode " +
                        "wrapped in FLAC is still routed to Restore. Device Link follows the desktop " +
                        "engine; choose Enhance there to force its DSP path. Reference Master " +
                        "(Matchering) remains a desktop-only optional stage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                var width by remember(upscaleSettings.width) {
                    mutableFloatStateOf(upscaleSettings.width)
                }
                Text(
                    "Stereo width · ${"%.2f".format(width)}",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Slider(
                    value = width,
                    onValueChange = { width = it },
                    onValueChangeFinished = { viewModel.setUpscaleWidth(width) },
                    valueRange = 0f..1.5f,
                    steps = 5,
                )
                Text(
                    "Adaptive M/S widening for new Hi-Fi upscales. Already-wide material is left " +
                        "alone and bass stays centred; 0 is off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---- Device Link -------------------------------------------------------
        SectionHeader("Device Link")
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                SettingSwitch(
                    title = "Link to a desktop",
                    subtitle = "Send your playlists to PetalMusic on a PC or laptop. The desktop " +
                        "imports the playlist metadata; downloading the audio there is optional. " +
                        "It also upscales downloads using its own GPU/CPU when you tap the U button.",
                    checked = deviceLink.enabled,
                    onChecked = { viewModel.setDeviceLinkEnabled(it) },
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    OutlinedTextField(
                        value = linkHost,
                        onValueChange = { linkHost = it },
                        label = { Text("Desktop IP / host") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = linkPort,
                        onValueChange = { linkPort = it.filter { c -> c.isDigit() } },
                        label = { Text("Port") },
                        singleLine = true,
                        modifier = Modifier.width(110.dp),
                    )
                }
                // One button: save the address and link in a single step.
                FilledTonalButton(
                    enabled = linkHost.isNotBlank() && !probing,
                    onClick = {
                        probing = true
                        linkFailed = false
                        probeResult = null
                        val port = linkPort.toIntOrNull() ?: 9478
                        viewModel.setDeviceLinkEndpoint(linkHost, port)
                        scope.launch {
                            val name = viewModel.probeDeviceLink(linkHost, port)
                            probeResult = name
                            if (name != null) {
                                viewModel.setDeviceLinkEnabled(true)
                            } else {
                                linkFailed = true
                            }
                            probing = false
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                ) { Text(if (probing) "Linking…" else "Link") }
                FilledTonalButton(
                    onClick = { viewModel.sendPlaylistsToDevice() },
                    enabled = deviceLink.configured,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) { Text("Send playlists to desktop") }
                val disconnected = linkFailed || linkConnected == false
                Text(
                    when {
                        probing -> "Reaching the desktop…"
                        probeResult != null -> "Linked · $probeResult"
                        disconnected -> "Device Link disconnected — start Device Link on the PC, " +
                            "then tap Link to reconnect."
                        deviceLink.configured -> "Linked to ${deviceLink.host}:${deviceLink.port}"
                        else -> "Enter your PC's IP and tap Link. Start Device Link in PetalMusic on the PC first."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (disconnected) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        SectionHeader("About")
        Text(
            "Petal Music 2.0 — the bridgefx² player. Local-first playback with " +
                "unofficial Spotify / YT Music / Tidal clients and universal AutoMix" +
                if (BuildConfig.PETAL_ROM) ", on petalOS driven by the system BridgeFX effect." else ".",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(32.dp))
    }

    if (spotifyDialog) {
        SpotifyWebLoginDialog(
            viewModel = viewModel,
            onDone = { spotifyDialog = false },
            onDismiss = { spotifyDialog = false },
        )
    }
    if (ytMusicDialog) {
        YtMusicWebLoginDialog(
            viewModel = viewModel,
            onDone = { ytMusicDialog = false },
            onDismiss = { ytMusicDialog = false },
        )
    }
    if (discordDialog) {
        LoginDialog(
            title = "Discord Rich Presence",
            hint = "Paste your Discord account token. PetalMusic connects to the gateway as Discord Android and sets a LISTENING activity — an unofficial method, use a spare account if unsure.",
            onConfirm = { token, _ ->
                scope.launch {
                    val ok = viewModel.discordLogin(token)
                    if (!ok) {
                        org.rab1d.petal.music.playback.PlaybackBus.publishError(
                            "Discord login failed — check the token",
                        )
                    }
                    discordDialog = false
                }
            },
            onDismiss = { discordDialog = false },
        )
    }
    if (tidalDialog) {
        TidalWebLoginDialog(
            viewModel = viewModel,
            onDone = { tidalDialog = false },
            onDismiss = { tidalDialog = false },
        )
    }
    if (showUnlockWarning) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissUpscaleWarning() },
            title = { Text("Enable on-device upscaling?") },
            text = {
                Text(
                    "This runs the Apollo restoration model and the Hi-Fi mastering chain " +
                        "on your phone's CPU. It can take several minutes per track, warms " +
                        "the device and uses battery. Device Link (a linked PC) is faster. " +
                        "Audio never leaves the device when it is processed here.",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmOnDeviceUpscale() }) {
                    Text("Enable on-device")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissUpscaleWarning() }) { Text("Not now") }
            },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        color = org.rab1d.petal.music.ui.theme.Petal.colors.ink,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 32.dp, bottom = 12.dp),
    )
}

/**
 * Settings opens lit by what's playing, like every other page, with the few facts
 * Petal knows about your music: what's on the phone and what's downloaded.
 */
@Composable
private fun SettingsHeader(viewModel: PetalViewModel, onBack: () -> Unit) {
    val now = org.rab1d.petal.music.ui.theme.LocalNowPlayingTheme.current
    val downloads by viewModel.downloads.collectAsState()
    val songs = viewModel.allTracks().size
    val facts = listOfNotNull(
        songs.takeIf { it > 0 }?.let { "${"%,d".format(it)} songs on this phone" },
        downloads.size.takeIf { it > 0 }?.let { n ->
            "$n downloaded · ${"%.1f".format(downloads.sumOf { it.bytes } / 1e9)} GB"
        },
    ).joinToString("\n")
    org.rab1d.petal.music.ui.theme.NightSurface {
        Column(
            Modifier
                .fillMaxWidth()
                .then(Modifier.sleeveLight(now))
                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.statusBars)
                .padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = now.ink)
            }
            Text("Settings", style = MaterialTheme.typography.displayLarge, color = now.ink,
                modifier = Modifier.padding(start = 12.dp, top = 24.dp))
            if (facts.isNotEmpty()) {
                Text(facts, style = org.rab1d.petal.music.ui.theme.PetalType.time.copy(fontSize = 12.sp, lineHeight = 18.sp),
                    color = now.text2, modifier = Modifier.padding(start = 12.dp, top = 8.dp))
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun AccountCard(
    name: String,
    status: String,
    onLogin: (() -> Unit)?,
    signedIn: Boolean = false,
    onLogout: (() -> Unit)? = null,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(
                    status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                signedIn && onLogout != null -> TextButton(onClick = onLogout) { Text("Sign out") }
                onLogin != null -> Button(onClick = onLogin) { Text("Sign in") }
            }
        }
    }
}

@Composable
private fun LoginDialog(
    title: String,
    hint: String,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var field1 by remember { mutableStateOf("") }
    var field2 by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(hint, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = field1,
                    onValueChange = { field1 = it },
                    label = { Text("Username / ARL / token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = field2,
                    onValueChange = { field2 = it },
                    label = { Text("Password (if needed)") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(field1, field2) }, enabled = field1.isNotBlank()) {
                Text("Sign in")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Tidal device pairing: show the code, poll while the user approves at
 * link.tidal.com, close when approved/expired/failed.
 */
@Composable
private fun TidalPairingDialog(
    viewModel: PetalViewModel,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pairing by remember { mutableStateOf<org.rab1d.petal.music.sources.tidal.TidalSource.TidalPairing?>(null) }
    var result by remember {
        mutableStateOf<org.rab1d.petal.music.sources.tidal.TidalSource.TidalPairResult?>(
            null,
        )
    }
    var starting by remember { mutableStateOf(true) }
    var showCustom by remember { mutableStateOf(false) }
    var customId by remember { mutableStateOf("") }
    var customSecret by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun beginPairing() {
        starting = true
        scope.launch {
            pairing = viewModel.tidalStartPairing()
            starting = false
        }
    }

    LaunchedEffect(Unit) { beginPairing() }

    LaunchedEffect(pairing) {
        val p = pairing ?: return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(p.intervalSec * 1000L)
            when (val r = viewModel.tidalPollPairing(p)) {
                is org.rab1d.petal.music.sources.tidal.TidalSource.TidalPairResult.Pending -> { /* keep polling */ }
                is org.rab1d.petal.music.sources.tidal.TidalSource.TidalPairResult.Approved -> {
                    result = r
                    break
                }
                is org.rab1d.petal.music.sources.tidal.TidalSource.TidalPairResult.Expired -> {
                    result = r
                    break
                }
                is org.rab1d.petal.music.sources.tidal.TidalSource.TidalPairResult.Error -> {
                    result = r
                    break
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pair Tidal") },
        text = {
            Column {
                if (starting) {
                    Text("Starting pairing…", style = MaterialTheme.typography.bodyMedium)
                } else {
                    val p = pairing
                    val r = result
                    when {
                        r is org.rab1d.petal.music.sources.tidal.TidalSource.TidalPairResult.Approved -> {
                            Text(
                                "Paired!" + (r.displayName?.let { " Signed in as $it." } ?: ""),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        r is org.rab1d.petal.music.sources.tidal.TidalSource.TidalPairResult.Expired -> {
                            Text(
                                "The code expired. Reopen pairing to get a new one.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        r is org.rab1d.petal.music.sources.tidal.TidalSource.TidalPairResult.Error -> {
                            Text(
                                "Pairing failed: ${r.message}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        p != null -> {
                            Text(
                                "On any device, open",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                p.verificationUrl,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                "and enter this code:",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                p.userCode,
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Waiting for approval…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        showCustom -> {
                            Text(
                                "Custom OAuth client (if the built-in one stops working, " +
                                    "extract client id/secret from a Tidal TV app login):",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = customId,
                                onValueChange = { customId = it },
                                label = { Text("Client id") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = customSecret,
                                onValueChange = { customSecret = it },
                                label = { Text("Client secret") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        else -> Text(
                            "Could not reach Tidal. Try again.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            when {
                result is org.rab1d.petal.music.sources.tidal.TidalSource.TidalPairResult.Approved ->
                    TextButton(onClick = onDone) { Text("Done") }
                result is org.rab1d.petal.music.sources.tidal.TidalSource.TidalPairResult.Expired ||
                    result is org.rab1d.petal.music.sources.tidal.TidalSource.TidalPairResult.Error -> {
                    TextButton(onClick = {
                        result = null
                        scope.launch {
                            starting = true
                            pairing = viewModel.tidalStartPairing()
                            starting = false
                        }
                    }) { Text("Retry") }
                }
                showCustom ->
                    TextButton(
                        onClick = {
                            viewModel.tidalSetCredentials(customId, customSecret)
                            showCustom = false
                            result = null
                            beginPairing()
                        },
                        enabled = customId.isNotBlank(),
                    ) { Text("Save & pair") }
                starting -> {}
                else -> TextButton(onClick = { showCustom = true }) { Text("Custom client…") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Tidal sign-in with the phone app client through an in-app browser:
 * login.tidal.com (authorization_code + PKCE). The popupbridge redirect is
 * intercepted inside the WebViewClient — no OS scheme registration needed.
 * Supports the real login page: password, social, 2FA.
 */
@Composable
private fun TidalWebLoginDialog(
    viewModel: PetalViewModel,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var request by remember {
        mutableStateOf<org.rab1d.petal.music.sources.tidal.TidalSource.WebLoginRequest?>(null)
    }
    var status by remember { mutableStateOf("Loading TIDAL login…") }
    var done by remember { mutableStateOf(false) }
    var exchanging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { request = viewModel.tidalStartWebLogin() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sign in to TIDAL") },
        text = {
            Column {
                if (done) {
                    Text(
                        "Signed in!",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    val req = request
                    if (req != null) {
                        androidx.compose.ui.viewinterop.AndroidView(
                            factory = { ctx ->
                                fun configure(w: android.webkit.WebView) {
                                    w.settings.javaScriptEnabled = true
                                    w.settings.domStorageEnabled = true
                                    w.settings.databaseEnabled = true
                                    w.settings.setSupportMultipleWindows(true)
                                    w.settings.javaScriptCanOpenWindowsAutomatically = true
                                    w.settings.userAgentString =
                                        "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                                    android.webkit.CookieManager.getInstance().apply {
                                        setAcceptCookie(true)
                                        setAcceptThirdPartyCookies(w, true)
                                    }
                                }
                                android.webkit.WebView(ctx).apply {
                                    configure(this)
                                    fun handle(view: android.webkit.WebView, url: String): Boolean {
                                        android.util.Log.i("TidalLogin", "nav: $url")
                                        when (val r = viewModel.tidalParseRedirect(url)) {
                                            is org.rab1d.petal.music.sources.tidal.TidalSource.RedirectParse.GotCode -> {
                                                // The callback fires twice per URL
                                                // (shouldOverrideUrlLoading +
                                                // doUpdateVisitedHistory) — exchange once.
                                                if (!exchanging && !done) {
                                                    exchanging = true
                                                    status = "Finishing sign-in…"
                                                    scope.launch {
                                                        done = viewModel.tidalCompleteWebLogin(req, r.code)
                                                        exchanging = false
                                                        if (!done) status = "Sign-in failed — please retry."
                                                    }
                                                }
                                                return true
                                            }
                                            is org.rab1d.petal.music.sources.tidal.TidalSource.RedirectParse.Failed -> {
                                                status = "Sign-in failed: ${r.message}"
                                                return true
                                            }
                                            org.rab1d.petal.music.sources.tidal.TidalSource.RedirectParse.Ignore -> {
                                                if (url.startsWith(req.redirectPrefix)) {
                                                    status = "Sign-in failed — please retry."
                                                    return true
                                                }
                                            }
                                        }
                                        return false
                                    }
                                    webViewClient = object : android.webkit.WebViewClient() {
                                        override fun shouldOverrideUrlLoading(
                                            view: android.webkit.WebView,
                                            url: String,
                                        ): Boolean = handle(view, url)

                                        override fun doUpdateVisitedHistory(
                                            view: android.webkit.WebView,
                                            url: String?,
                                            isReload: Boolean,
                                        ) {
                                            // loadUrl()-initiated chains skip
                                            // shouldOverrideUrlLoading — catch the
                                            // callback here too.
                                            url?.let { handle(view, it) }
                                            super.doUpdateVisitedHistory(view, url, isReload)
                                        }
                                    }
                                    // Google blocks OAuth in WebViews and signs in via
                                    // window.open popups: capture the popup target and
                                    // continue it in this webview instead.
                                    webChromeClient = object : android.webkit.WebChromeClient() {
                                        override fun onCreateWindow(
                                            view: android.webkit.WebView,
                                            isDialog: Boolean,
                                            isUserGesture: Boolean,
                                            resultMsg: android.os.Message,
                                        ): Boolean {
                                            val popup = android.webkit.WebView(view.context).apply {
                                                configure(this)
                                                webViewClient = object : android.webkit.WebViewClient() {
                                                    override fun shouldOverrideUrlLoading(
                                                        v: android.webkit.WebView,
                                                        url: String,
                                                    ): Boolean {
                                                        android.util.Log.i("TidalLogin", "popup->main: $url")
                                                        view.loadUrl(url)
                                                        return true
                                                    }
                                                }
                                            }
                                            (resultMsg.obj as android.webkit.WebView.WebViewTransport)
                                                .webView = popup
                                            resultMsg.sendToTarget()
                                            return true
                                        }
                                    }
                                    loadUrl(req.url)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(420.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (done) TextButton(onClick = onDone) { Text("Done") }
        },
        dismissButton = {
            if (!done && status.startsWith("Sign-in failed")) {
                TextButton(onClick = {
                    request = viewModel.tidalStartWebLogin()
                    status = "Loading TIDAL login…"
                }) { Text("Retry") }
            } else {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/**
 * Spotify sign-in in the real browser (Custom Tab): Google refuses to sign in
 * inside a WebView, and the browser also has the user's saved logins. The app
 * answers the loopback redirect itself, see LibrespotBridge.awaitWebLogin.
 */
@Composable
private fun SpotifyWebLoginDialog(
    viewModel: PetalViewModel,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var request by remember {
        mutableStateOf<org.rab1d.petal.music.sources.spotify.LibrespotBridge.WebLoginRequest?>(null)
    }
    var status by remember { mutableStateOf("Opening the browser…") }
    var failed by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var exchanging by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }

    fun fail(message: String) {
        failed = true
        status = "Sign-in failed: $message"
    }
    fun openBrowser(url: String): Boolean = runCatching {
        androidx.browser.customtabs.CustomTabsIntent.Builder().setShowTitle(true).build()
            .launchUrl(context, android.net.Uri.parse(url))
    }.onFailure { fail("no browser found") }.isSuccess

    LaunchedEffect(attempt) {
        val req = viewModel.spotifyStartWebLogin()
        request = req
        failed = false
        status = "Finish signing in in your browser. Google, Apple, Facebook and passwords all work there."
        val appContext = context.applicationContext
        org.rab1d.petal.music.sources.spotify.WebLoginKeepAliveService.start(appContext)
        try {
            if (!openBrowser(req.url)) return@LaunchedEffect
            when (val r = viewModel.spotifyAwaitWebLogin(req)) {
                is org.rab1d.petal.music.sources.spotify.LibrespotBridge.RedirectParse.GotCode -> {
                    exchanging = true
                    status = "Finishing sign-in…"
                    done = viewModel.spotifyCompleteWebLogin(req, r.code)
                    exchanging = false
                    if (!done) fail(viewModel.spotifyLoginError())
                }
                is org.rab1d.petal.music.sources.spotify.LibrespotBridge.RedirectParse.Failed -> fail(r.message)
                org.rab1d.petal.music.sources.spotify.LibrespotBridge.RedirectParse.Ignore -> Unit
            }
        } finally {
            org.rab1d.petal.music.sources.spotify.WebLoginKeepAliveService.stop(appContext)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sign in to Spotify") },
        text = {
            if (done) {
                Text(
                    "Signed in!",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(status, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            when {
                done -> TextButton(onClick = onDone) { Text("Done") }
                failed -> TextButton(onClick = { attempt++ }) { Text("Retry") }
                !exchanging -> TextButton(onClick = { request?.let { openBrowser(it.url) } }) { Text("Open browser") }
            }
        },
        dismissButton = {
            if (!done) TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// google login in a webview, Connect nicks the yt cookies once youre in
@Composable
private fun YtMusicWebLoginDialog(
    viewModel: PetalViewModel,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    var status by remember { mutableStateOf("Sign in and complete any YouTube verification, then tap Connect.") }
    var done by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val cookieManager = remember { android.webkit.CookieManager.getInstance() }

    fun harvest(url: String) {
        if (done) return
        val host = runCatching { android.net.Uri.parse(url).host }.getOrNull() ?: return
        if (host == "youtube.com" || host.endsWith(".youtube.com")) {
            val cookie = cookieManager.getCookie("https://music.youtube.com").orEmpty()
            if (org.rab1d.petal.music.sources.ytmusic.hasYouTubeSession(cookie)) {
                cookieManager.flush()
                scope.launch {
                    if (viewModel.ytMusicLogin(cookie)) {
                        done = true
                        status = "Signed in!"
                    } else {
                        status = "Sign-in failed — please retry."
                    }
                }
            } else status = "Finish signing in to YouTube Music before connecting."
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sign in to YT Music") },
        text = {
            Column {
                if (done) {
                    Text(
                        "Signed in!",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { ctx ->
                            fun configure(w: android.webkit.WebView) {
                                w.settings.javaScriptEnabled = true
                                w.settings.domStorageEnabled = true
                                w.settings.databaseEnabled = true
                                w.settings.setSupportMultipleWindows(true)
                                w.settings.javaScriptCanOpenWindowsAutomatically = true
                                // NO fake desktop UA. webview still sends X-Requested-With + android
                                // client hints, so "linux chrome" screamed bot -> "browser not secure"
                                android.webkit.CookieManager.getInstance().apply {
                                    setAcceptCookie(true)
                                    setAcceptThirdPartyCookies(w, true)
                                }
                            }
                            android.webkit.WebView(ctx).apply {
                                configure(this)
                                webViewClient = object : android.webkit.WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: android.webkit.WebView,
                                        url: String,
                                    ): Boolean {
                                        return false
                                    }

                                    override fun onPageFinished(
                                        view: android.webkit.WebView,
                                        url: String?,
                                    ) {
                                        super.onPageFinished(view, url)
                                    }
                                }
                                    webChromeClient = object : android.webkit.WebChromeClient() {
                                        override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
                                            android.util.Log.i(
                                                "YtMusicLogin",
                                                "console: ${message?.message()} @${message?.sourceId()}:${message?.lineNumber()}",
                                            )
                                            return true
                                        }

                                        override fun onCreateWindow(
                                        view: android.webkit.WebView,
                                        isDialog: Boolean,
                                        isUserGesture: Boolean,
                                        resultMsg: android.os.Message,
                                    ): Boolean {
                                        val popup = android.webkit.WebView(view.context).apply {
                                            configure(this)
                                            webViewClient = object : android.webkit.WebViewClient() {
                                                override fun shouldOverrideUrlLoading(
                                                    v: android.webkit.WebView,
                                                    url: String,
                                                ): Boolean {
                                                    view.loadUrl(url)
                                                    return true
                                                }
                                            }
                                        }
                                        (resultMsg.obj as android.webkit.WebView.WebViewTransport)
                                            .webView = popup
                                        resultMsg.sendToTarget()
                                        return true
                                    }
                                }
                                // youtube's own signin bridge, same door the real yt apps use
                                loadUrl(
                                    "https://accounts.google.com/ServiceLogin" +
                                        "?ytbridge=1&service=youtube&uilel=3&passive=true" +
                                        "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin" +
                                        "%3Faction_handle_signin%3Dtrue%26app%3Ddesktop%26hl%3Den" +
                                        "%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F" +
                                        "&hl=en",
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(420.dp),
                    )
                }
            }
        },
        confirmButton = {
            if (done) TextButton(onClick = onDone) { Text("Done") }
            else TextButton(onClick = { harvest("https://music.youtube.com/") }) { Text("Connect") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
