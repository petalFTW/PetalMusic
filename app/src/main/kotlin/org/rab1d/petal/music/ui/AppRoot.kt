/* SPDX-License-Identifier: Apache-2.0 */
@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package org.rab1d.petal.music.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import kotlinx.coroutines.launch
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.rab1d.petal.music.ui.components.GlassLevel
import org.rab1d.petal.music.ui.components.GlassNavBar
import org.rab1d.petal.music.ui.components.GlassToast
import org.rab1d.petal.music.ui.components.LocalChromeInset
import org.rab1d.petal.music.ui.components.LocalNavScope
import org.rab1d.petal.music.ui.components.LocalPageBackdrop
import org.rab1d.petal.music.ui.components.LocalSharedTransition
import org.rab1d.petal.music.ui.components.MiniPlayer
import org.rab1d.petal.music.ui.components.NavItem
import org.rab1d.petal.music.ui.components.PetalIcons
import org.rab1d.petal.music.ui.components.backdropSource
import org.rab1d.petal.music.ui.components.glass
import org.rab1d.petal.music.ui.components.rememberBackdrop
import org.rab1d.petal.music.ui.components.sharedArt
import org.rab1d.petal.music.model.key
import org.rab1d.petal.music.ui.screens.findArtist
import org.rab1d.petal.music.ui.screens.HomeScreen
import org.rab1d.petal.music.ui.screens.NowPlayingScreen
import org.rab1d.petal.music.ui.screens.QueueScreen
import org.rab1d.petal.music.ui.screens.SearchScreen
import org.rab1d.petal.music.ui.screens.SettingsScreen
import org.rab1d.petal.music.ui.theme.Dye
import org.rab1d.petal.music.ui.theme.Layer
import org.rab1d.petal.music.ui.theme.LocalNowPlayingTheme
import org.rab1d.petal.music.ui.theme.LocalPaletteSetter
import org.rab1d.petal.music.ui.theme.LocalPetalColors
import org.rab1d.petal.music.ui.theme.LocalReducedMotion
import org.rab1d.petal.music.ui.theme.LocalReducedTransparency
import org.rab1d.petal.music.ui.theme.Motion
import org.rab1d.petal.music.ui.theme.Petal
import org.rab1d.petal.music.ui.theme.PetalEnvironment
import org.rab1d.petal.music.ui.theme.PetalMaterial
import org.rab1d.petal.music.ui.theme.PetalPalette
import org.rab1d.petal.music.ui.theme.Radius
import org.rab1d.petal.music.ui.theme.animateArtTheme
import org.rab1d.petal.music.ui.theme.rememberArtTheme
import org.rab1d.petal.music.ui.theme.rememberSystemReducedMotion
import org.rab1d.petal.music.ui.theme.withArtAccent

object Routes {
    const val HOME = "home"
    const val LIBRARY = "library"
    const val SEARCH = "search"
    const val QUEUE = "queue"
    const val SETTINGS = "settings"
    const val DOWNLOADS = "downloads"
    const val NOW_PLAYING = "now_playing"
    const val ALBUM = "album/{source}/{id}"
    const val ARTIST = "artist/{source}/{id}"
    const val PLAYLIST = "playlist/{source}/{id}"

    fun album(source: String, id: String) = "album/$source/$id"
    fun artist(source: String, id: String) = "artist/$source/$id"
    fun playlist(source: String, id: String) = "playlist/$source/$id"
}

/** Battery saver turns the glass solid: blur is the most expensive thing Petal draws. */
@Composable
private fun rememberPowerSave(): Boolean {
    val context = LocalContext.current
    val pm = remember(context) { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    var saving by remember { mutableStateOf(pm.isPowerSaveMode) }
    DisposableEffect(pm) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { saving = pm.isPowerSaveMode }
        }
        context.registerReceiver(receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        onDispose { context.unregisterReceiver(receiver) }
    }
    return saving
}

@Composable
fun AppRoot(viewModel: PetalViewModel) {
    val nav = rememberNavController()
    // Reopen on whichever bottom-nav tab was last shown.
    val startTab = remember { viewModel.lastTab() ?: Routes.HOME }
    val state by viewModel.playbackState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var showNowPlaying by rememberSaveable { mutableStateOf(false) }
    var showQueueOverlay by rememberSaveable { mutableStateOf(false) }
    var palette by remember { mutableStateOf(PetalPalette.Petal) }
    var librarySource by rememberSaveable { mutableStateOf(org.rab1d.petal.music.model.Source.TIDAL) }
    val addToPlaylist = remember { org.rab1d.petal.music.ui.components.AddToPlaylistController() }

    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.errorId) {
        state.error?.let { snackbar.showSnackbar(it) }
    }

    // The player covers the screens below it, but a search field there keeps its focus, and
    // coming back from another app (a messaging app with its keyboard up) reopens the IME on
    // it, over the player. Drop that focus whenever the player is up or the app resumes under it.
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val playerUp by androidx.compose.runtime.rememberUpdatedState(showNowPlaying || showQueueOverlay)
    LaunchedEffect(showNowPlaying, showQueueOverlay) {
        if (showNowPlaying || showQueueOverlay) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && playerUp) {
                focusManager.clearFocus(force = true)
                keyboard?.hide()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // What's playing colours the chrome; with the Sleeve dye it is also the accent.
    val current = state.current
    val nowTheme = animateArtTheme(
        rememberArtTheme(current?.artUri, seed = current?.let { "${it.title} · ${it.artist}" }),
    )
    val base = Petal.colors
    val sleeve = settings.dye == Dye.SLEEVE.name && current != null
    val colors = if (sleeve) base.withArtAccent(nowTheme.accentOnArt) else base
    val reducedMotion = rememberSystemReducedMotion()
    val reducedTransparency = rememberPowerSave()
    val paper = !base.dark
    // The player is always night: light bar icons while it is up, the theme's otherwise.
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = paper && !showNowPlaying
            isAppearanceLightNavigationBars = paper && !showNowPlaying
        }
    }

    CompositionLocalProvider(
        LocalPetalColors provides colors,
        LocalNowPlayingTheme provides nowTheme,
        LocalReducedMotion provides reducedMotion,
        LocalReducedTransparency provides reducedTransparency,
        org.rab1d.petal.music.ui.theme.LocalPowerSave provides reducedTransparency,
    ) { PetalMaterial {
    PetalEnvironment(palette = palette, artUri = current?.artUri) {
    CompositionLocalProvider(
        LocalPaletteSetter provides { palette = it },
        org.rab1d.petal.music.ui.components.LocalAddToPlaylist provides addToPlaylist::open,
    ) {
    SharedTransitionLayout(Modifier.fillMaxSize()) {
    CompositionLocalProvider(LocalSharedTransition provides this) {
    val rootBackdrop = rememberBackdrop()
    val pageBackdrop = rememberBackdrop()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val downloadedKeys by viewModel.downloadedKeys.collectAsStateWithLifecycle()
    val dlProgress by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val uiScope = androidx.compose.runtime.rememberCoroutineScope()
    val trackActions = remember(favorites, downloadedKeys, dlProgress) {
        org.rab1d.petal.music.ui.components.TrackActions(
            isFavorite = { favorites.contains(it.key()) },
            toggleFavorite = { viewModel.toggleFavorite(it) },
            download = { t ->
                if (t.key() in downloadedKeys) org.rab1d.petal.music.ui.components.DownloadDot.Downloaded
                else dlProgress[t.key()]?.let { p ->
                    when (p.state) {
                        org.rab1d.petal.music.data.downloads.DownloadManager.State.DOWNLOADING ->
                            org.rab1d.petal.music.ui.components.DownloadDot.InProgress(p.fraction)
                        org.rab1d.petal.music.data.downloads.DownloadManager.State.QUEUED ->
                            org.rab1d.petal.music.ui.components.DownloadDot.InProgress(0f)
                        else -> null
                    }
                }
            },
            toggleDownload = { t -> if (viewModel.isDownloaded(t)) viewModel.deleteDownload(t) else viewModel.downloadTrack(t) },
            addToPlaylist = { addToPlaylist.open(it) },
            goToArtist = { t ->
                showNowPlaying = false
                showQueueOverlay = false
                val page = viewModel.findArtist(t)
                if (page != null) nav.navigate(Routes.artist(page.first, page.second))
                else {
                    // No artist page on this source: search for them everywhere instead.
                    org.rab1d.petal.music.ui.components.SearchRequest.pending = t.artist.substringBefore(",").trim()
                    nav.navigate(Routes.SEARCH) { launchSingleTop = true }
                }
            },
            goToAlbum = { t ->
                t.albumId?.let { id ->
                    showNowPlaying = false
                    showQueueOverlay = false
                    nav.navigate(Routes.album(t.source.id, id))
                }
            },
            playNext = { t ->
                viewModel.playNext(listOf(t))
                uiScope.launch { snackbar.showSnackbar("${t.title} plays next") }
            },
            addToQueue = { t ->
                viewModel.addToQueue(listOf(t))
                uiScope.launch { snackbar.showSnackbar("${t.title} added to the queue") }
            },
        )
    }
    // Row equalisers: 15 looks a second reads as a meter, and only while one is on screen.
    val liveLevel = org.rab1d.petal.music.ui.components.rememberLiveLevel(
        active = state.playing && !showNowPlaying, hz = 15, onlyWatched = true,
    )
    val density = LocalDensity.current
    var chromeHeight by remember { mutableStateOf(0) }
    // Paper: a light wash of the cover; Night: the cover's own dark base.
    val chromeTint = if (paper) lerp(Petal.colors.bg, nowTheme.lightMuted, .18f) else nowTheme.base

    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(
            org.rab1d.petal.music.ui.components.LocalTrackActions provides trackActions,
            org.rab1d.petal.music.ui.components.LocalLiveLevel provides liveLevel,
        ) {
        Box(Modifier.fillMaxSize().backdropSource(rootBackdrop)) {
            CompositionLocalProvider(
                LocalChromeInset provides with(density) { chromeHeight.toDp() },
                LocalPageBackdrop provides pageBackdrop,
            ) {
                NavHost(
                    navController = nav,
                    startDestination = startTab,
                    modifier = Modifier.fillMaxSize().backdropSource(pageBackdrop),
                    // The page underneath stays solid while the new one slides over it, so the
                    // two never cross-fade through the background (that read as a white flash).
                    // Back reverses it: the top page slides off the one beneath.
                    enterTransition = {
                        if (reducedMotion) fadeIn(Motion.enter(Motion.BASE))
                        else slideInHorizontally(Motion.enter(Motion.SLOW)) { it / 6 } + fadeIn(Motion.enter(Motion.BASE))
                    },
                    exitTransition = {
                        if (reducedMotion) fadeOut(Motion.exit(Motion.BASE))
                        else slideOutHorizontally(Motion.enter(Motion.SLOW)) { -it / 16 } +
                            fadeOut(androidx.compose.animation.core.tween(Motion.BASE, delayMillis = Motion.SLOW - Motion.BASE))
                    },
                    popEnterTransition = {
                        if (reducedMotion) fadeIn(Motion.enter(Motion.BASE))
                        else slideInHorizontally(Motion.enter(Motion.SLOW)) { -it / 16 }
                    },
                    popExitTransition = {
                        if (reducedMotion) fadeOut(Motion.exit(Motion.BASE))
                        else slideOutHorizontally(Motion.enter(Motion.SLOW)) { it / 6 } + fadeOut(Motion.enter(Motion.SLOW))
                    },
                ) {
                    composable(Routes.HOME) {
                        CompositionLocalProvider(LocalNavScope provides this) {
                            HomeScreen(
                                viewModel,
                                onSettings = { nav.navigate(Routes.SETTINGS) },
                                onOpenSource = { source ->
                                    librarySource = source
                                    nav.navigate(Routes.LIBRARY) { launchSingleTop = true }
                                },
                                onAlbum = { s, id -> nav.navigate(Routes.album(s, id)) },
                                onArtist = { s, id -> nav.navigate(Routes.artist(s, id)) },
                                onPlaylist = { s, id -> nav.navigate(Routes.playlist(s, id)) },
                                onDownloads = { nav.navigate(Routes.DOWNLOADS) { launchSingleTop = true } },
                                initialSource = librarySource,
                            )
                        }
                    }
                    composable(Routes.LIBRARY) {
                        CompositionLocalProvider(LocalNavScope provides this) {
                            HomeScreen(
                                viewModel,
                                onSettings = { nav.navigate(Routes.SETTINGS) },
                                onOpenSource = { source -> librarySource = source },
                                onAlbum = { s, id -> nav.navigate(Routes.album(s, id)) },
                                onArtist = { s, id -> nav.navigate(Routes.artist(s, id)) },
                                onPlaylist = { s, id -> nav.navigate(Routes.playlist(s, id)) },
                                onDownloads = { nav.navigate(Routes.DOWNLOADS) { launchSingleTop = true } },
                                initialSource = librarySource,
                            )
                        }
                    }
                    composable(Routes.DOWNLOADS) {
                        org.rab1d.petal.music.ui.screens.DownloadsScreen(
                            viewModel,
                            onBack = { nav.popBackStack() },
                        )
                    }
                    composable(Routes.SEARCH) {
                        CompositionLocalProvider(LocalNavScope provides this) {
                            SearchScreen(
                                viewModel,
                                onAlbum = { s, id -> nav.navigate(Routes.album(s, id)) },
                                onArtist = { s, id -> nav.navigate(Routes.artist(s, id)) },
                                onPlaylist = { s, id -> nav.navigate(Routes.playlist(s, id)) },
                            )
                        }
                    }
                    composable(Routes.QUEUE) { QueueScreen(viewModel) }
                    composable(Routes.SETTINGS) {
                        SettingsScreen(viewModel, onBack = { nav.popBackStack() })
                    }
                    composable(Routes.ALBUM) { entry ->
                        CompositionLocalProvider(LocalNavScope provides this) {
                            org.rab1d.petal.music.ui.screens.AlbumDetailScreen(
                                viewModel,
                                org.rab1d.petal.music.model.Source.fromId(entry.arguments?.getString("source") ?: "local"),
                                entry.arguments?.getString("id") ?: "",
                                onBack = { nav.popBackStack() },
                                onArtist = { s, id -> nav.navigate(Routes.artist(s, id)) },
                            )
                        }
                    }
                    composable(Routes.ARTIST) { entry ->
                        CompositionLocalProvider(LocalNavScope provides this) {
                            org.rab1d.petal.music.ui.screens.ArtistDetailScreen(
                                viewModel,
                                org.rab1d.petal.music.model.Source.fromId(entry.arguments?.getString("source") ?: "local"),
                                entry.arguments?.getString("id") ?: "",
                                onBack = { nav.popBackStack() },
                                onAlbum = { s, id -> nav.navigate(Routes.album(s, id)) },
                            )
                        }
                    }
                    composable(Routes.PLAYLIST) { entry ->
                        CompositionLocalProvider(LocalNavScope provides this) {
                            org.rab1d.petal.music.ui.screens.PlaylistDetailScreen(
                                viewModel,
                                org.rab1d.petal.music.model.Source.fromId(entry.arguments?.getString("source") ?: "local"),
                                entry.arguments?.getString("id") ?: "",
                                onBack = { nav.popBackStack() },
                            )
                        }
                    }
                }
            }

            // ---- floating chrome: the page scrolls under it, so the glass has something to frost.
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .zIndex(Layer.CHROME)
                    .onSizeChanged { chromeHeight = it.height },
            ) {
                SnackbarHost(snackbar) { data ->
                    GlassToast(data.visuals.message, pageBackdrop, chromeTint)
                }
                AnimatedVisibility(
                    visible = current != null && !showNowPlaying,
                    enter = if (reducedMotion) fadeIn(Motion.enter(Motion.BASE))
                        else slideInVertically(Motion.enter(Motion.SLOW)) { it } + fadeIn(Motion.enter(Motion.BASE)),
                    exit = if (reducedMotion) fadeOut(Motion.exit(Motion.FAST))
                        else slideOutVertically(Motion.exit(Motion.BASE)) { it } + fadeOut(Motion.exit(Motion.FAST)),
                ) {
                    val track = current ?: state.current ?: return@AnimatedVisibility
                    MiniPlayer(
                        track = track,
                        playing = state.playing,
                        theme = nowTheme,
                        backdrop = pageBackdrop,
                        paper = paper,
                        onOpen = { showNowPlaying = true },
                        onPlayPause = { viewModel.playPause() },
                        onNext = { viewModel.next() },
                        onPrevious = { viewModel.previous() },
                        artModifier = Modifier.sharedArt("now-playing-art", this),
                    )
                }
                if (!showNowPlaying) {
                    GlassNavBar(
                        items = listOf(
                            NavItem(Routes.HOME, "Listen now", Icons.Rounded.Home),
                            NavItem(Routes.SEARCH, "Search", Icons.Rounded.Search),
                            NavItem(Routes.QUEUE, "Queue", PetalIcons.Queue),
                        ),
                        selected = route,
                        onSelect = { r ->
                            viewModel.setLastTab(r)
                            nav.navigate(r) {
                                launchSingleTop = true
                                popUpTo(startTab) { saveState = true }
                                restoreState = true
                            }
                        },
                        backdrop = pageBackdrop,
                        tint = chromeTint,
                        accent = Petal.colors.accent,
                    )
                }
            }

            AnimatedVisibility(
                visible = showNowPlaying,
                modifier = Modifier.fillMaxSize().zIndex(Layer.PLAYER),
                enter = if (reducedMotion) fadeIn(Motion.enter(Motion.BASE))
                    else slideInVertically(Motion.settle()) { it } + fadeIn(Motion.enter(Motion.BASE)),
                exit = if (reducedMotion) fadeOut(Motion.exit(Motion.FAST))
                    else slideOutVertically(Motion.settle()) { it } + fadeOut(Motion.exit(Motion.BASE)),
            ) {
                NowPlayingScreen(
                    viewModel,
                    onClose = { showNowPlaying = false },
                    onOpenQueue = { showQueueOverlay = true },
                    artModifier = Modifier.sharedArt("now-playing-art", this),
                    open = showNowPlaying,
                )
            }
        }

        // ---- Up Next: a glass drawer over the player, so back returns to the player.
        AnimatedVisibility(
            visible = showQueueOverlay,
            modifier = Modifier.fillMaxSize().zIndex(Layer.DRAWER),
            enter = fadeIn(Motion.enter(Motion.BASE)),
            exit = fadeOut(Motion.exit(Motion.BASE)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = .32f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        showQueueOverlay = false
                    },
            ) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(.9f)
                        .animateEnterExit(
                            enter = if (reducedMotion) fadeIn() else slideInVertically(Motion.settle()) { it },
                            exit = if (reducedMotion) fadeOut() else slideOutVertically(Motion.settle()) { it },
                        )
                        // Solid, tinted from the cover: frosting the whole app (player and its own
                        // glass deck included) under a drawer this size crashed the GPU on close.
                        .glass(null, GlassLevel.Sheet, chromeTint, Radius.sheet)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                        .windowInsetsPadding(WindowInsets.safeDrawing),
                ) {
                    QueueScreen(viewModel, drawer = true, onClose = { showQueueOverlay = false })
                }
            }
        }

        org.rab1d.petal.music.ui.components.TrackMenuHost(trackActions, rootBackdrop, chromeTint)
        }

        org.rab1d.petal.music.ui.components.AddToPlaylistDialog(viewModel, addToPlaylist)

        // System back: close the queue overlay first, then the player, and only
        // then fall through to the nav stack. Re-add on every recomposition so
        // the callback always sits above the NavController's.
        val backDispatcher =
            androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current
                ?.onBackPressedDispatcher
        val queueBack = remember {
            object : androidx.activity.OnBackPressedCallback(false) {
                override fun handleOnBackPressed() {
                    showQueueOverlay = false
                }
            }
        }
        val overlayBack = remember {
            object : androidx.activity.OnBackPressedCallback(false) {
                override fun handleOnBackPressed() {
                    // The player closes its own sheet or lyrics first.
                    if (org.rab1d.petal.music.ui.screens.PlayerBack.handler?.invoke() != true) showNowPlaying = false
                }
            }
        }
        val menuBack = remember(trackActions) {
            object : androidx.activity.OnBackPressedCallback(false) {
                override fun handleOnBackPressed() { trackActions.close() }
            }
        }
        DisposableEffect(queueBack, overlayBack, menuBack) {
            onDispose { queueBack.remove(); overlayBack.remove(); menuBack.remove() }
        }
        // Read in composition, not inside SideEffect: reads there aren't observed, so
        // opening a menu never recomposed this and Back fell through (exiting from Home).
        val menuOpen = trackActions.open != null
        androidx.compose.runtime.SideEffect {
            if (showQueueOverlay) {
                overlayBack.isEnabled = false
                queueBack.isEnabled = true
                queueBack.remove()
                backDispatcher?.addCallback(queueBack)
            } else {
                queueBack.isEnabled = false
                if (showNowPlaying) {
                    overlayBack.isEnabled = true
                    overlayBack.remove()
                    backDispatcher?.addCallback(overlayBack)
                } else {
                    overlayBack.isEnabled = false
                }
            }
            menuBack.isEnabled = menuOpen
            if (menuBack.isEnabled) {
                menuBack.remove()
                backDispatcher?.addCallback(menuBack)
            }
        }
    }
    }
    }
    }
    }
    }
    }
}
