package com.sidespot.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sidespot.MainActivity
import com.sidespot.auth.AuthManager
import com.sidespot.settings.SettingsManager
import com.sidespot.viewmodel.LibraryViewModel
import com.sidespot.viewmodel.PlayerViewModel
import com.sidespot.viewmodel.SearchViewModel
import java.net.URLDecoder
import java.net.URLEncoder

object Routes {
    const val LOGIN = "login"
    const val NOW_PLAYING = "now_playing"
    const val LIBRARY = "library"
    const val SEARCH = "search"
    const val TRACK_LIST = "track_list/{uri}"
    const val QUEUE = "queue"
    const val SETTINGS = "settings"
    const val SAVED_ALBUMS = "saved_albums"
    const val SAVED_SHOWS = "saved_shows"
    const val NEW_EPISODES = "new_episodes"
    const val HISTORY = "history"
    const val SHOW_DETAIL = "show_detail/{uri}/{name}"

    fun trackList(uri: String): String = "track_list/${URLEncoder.encode(uri, "UTF-8")}"
    fun showDetail(uri: String, name: String): String =
        "show_detail/${URLEncoder.encode(uri, "UTF-8")}/${URLEncoder.encode(name, "UTF-8")}"
}

private data class BottomNavItem(
    val route: String,
    val icon: ImageVector,
    val label: String,
)

private val bottomNavItems = listOf(
    BottomNavItem(Routes.QUEUE, Icons.Default.QueueMusic, "Queue"),
    BottomNavItem(Routes.LIBRARY, Icons.Default.LibraryMusic, "Library"),
    BottomNavItem(Routes.SEARCH, Icons.Default.Search, "Search"),
)

@Composable
fun SidespotNavigation(
    playerViewModel: PlayerViewModel = viewModel(),
    authManager: AuthManager,
    settingsManager: SettingsManager,
    mainActivity: MainActivity? = null,
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val state by playerViewModel.uiState.collectAsState()
    val authState by authManager.state.collectAsState()
    val libraryViewModel: LibraryViewModel = viewModel()
    val searchViewModel: SearchViewModel = viewModel()

    // Always use LIBRARY as start destination so the NavController's saved state
    // is consistent across Activity recreation.  Login redirect is handled by the
    // auth-lost LaunchedEffect below.
    val startDestination = Routes.LIBRARY
    Log.i("SidespotAuth", "startDestination=$startDestination authState=$authState")

    // Now Playing is a full-screen overlay (not a NavHost destination) so it can
    // fade over the entire screen including the bottom bar with zero layout changes.
    var showNowPlaying by remember { mutableStateOf(false) }

    // Hide bottom nav + mini-player on Login
    val hideChrome = currentRoute == Routes.LOGIN

    val settingsState by settingsManager.state.collectAsState()
    val albumColors = rememberAlbumColors(state.albumArtUrl)
    // Hide status bar while Now Playing overlay is visible
    val activity = LocalContext.current as? Activity
    DisposableEffect(showNowPlaying) {
        val window = activity?.window ?: return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (showNowPlaying) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
        onDispose {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }

    // Sync current route and wire Sundial keypad callbacks to MainActivity
    DisposableEffect(mainActivity, navController) {
        // Debounce: only prevent popping back within 400ms of navigating to Now Playing.
        // This stops hardware button bounce (rapid double-press) from toggling back
        // without blocking the initial "open Now Playing" press.
        var nowPlayingOpenedAt = 0L
        if (mainActivity != null) {
            mainActivity.onNowPlayingToggleRequested = {
                if (showNowPlaying) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - nowPlayingOpenedAt > 400) {
                        showNowPlaying = false
                    }
                } else {
                    nowPlayingOpenedAt = android.os.SystemClock.uptimeMillis()
                    showNowPlaying = true
                }
            }
            mainActivity.onTabCycleRequested = {
                val currentIdx = bottomNavItems.indexOfFirst {
                    it.route == navController.currentDestination?.route
                }
                val nextIdx = (currentIdx + 1) % bottomNavItems.size
                navController.navigate(bottomNavItems[nextIdx].route) {
                    popUpTo(navController.graph.findStartDestination().id) {
                        inclusive = false
                    }
                    launchSingleTop = true
                }
            }
        }
        onDispose {
            mainActivity?.onNowPlayingToggleRequested = null
            mainActivity?.onTabCycleRequested = null
        }
    }

    // Sync currentRoute to activity for context-sensitive key handling
    LaunchedEffect(currentRoute) {
        mainActivity?.currentRoute = currentRoute
        if (currentRoute == Routes.LIBRARY) {
            libraryViewModel.refreshLibrary()
        }
    }

    // Auto-connect when authenticated but not yet connected (handles app relaunch
    // and fresh sign-in).  The `version` key ensures this re-triggers even when
    // isAuthenticated stays true across back-to-back sign-ins.  Skipping while
    // isLoading avoids racing with an in-flight exchangeCode().
    LaunchedEffect(authState.isAuthenticated, authState.version, authState.isLoading, state.isConnected) {
        Log.i("SidespotAuth", "auto-connect: auth=${authState.isAuthenticated} version=${authState.version} loading=${authState.isLoading} connected=${state.isConnected}")
        if (authState.isAuthenticated && !state.isConnected && !authState.isLoading) {
            if (authManager.needsReauth()) {
                Log.i("SidespotAuth", "auto-connect: needsReauth=true, logging out")
                authManager.logout()
                return@LaunchedEffect
            }
            val token = authManager.getValidAccessToken()
            Log.i("SidespotAuth", "auto-connect: token=${if (token != null) "present" else "null"}")
            if (token != null) {
                playerViewModel.connect(token) { authManager.getValidAccessToken() }
            }
        }
    }

    // Initialize history manager with context
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        libraryViewModel.initHistory(context)
    }

    // Navigate from login to library once connected, and load library data
    LaunchedEffect(state.isConnected) {
        Log.i("SidespotAuth", "isConnected changed: ${state.isConnected} currentRoute=$currentRoute")
        if (state.isConnected) {
            libraryViewModel.initApi(authManager)
            libraryViewModel.loadPlaylists()
            if (currentRoute == Routes.LOGIN) {
                Log.i("SidespotAuth", "navigating from login to library (connected)")
                navController.navigate(Routes.LIBRARY) {
                    popUpTo(Routes.LOGIN) { inclusive = true }
                }
            }
        }
    }

    // If auth is lost (e.g. token refresh failed) or not yet signed in,
    // navigate to login.  This also handles the fresh-install case where
    // startDestination is LIBRARY but the user isn't authenticated yet.
    // Conversely, if auth is gained while the player is already connected
    // (e.g. sign-out → sign-in without the player disconnecting), navigate
    // straight to library since the isConnected LaunchedEffect won't re-fire.
    LaunchedEffect(authState.isAuthenticated) {
        if (!authState.isAuthenticated && currentRoute != Routes.LOGIN) {
            Log.i("SidespotAuth", "auth-lost: navigating to login, currentRoute=$currentRoute")
            navController.navigate(Routes.LOGIN) {
                popUpTo(0) { inclusive = true }
            }
        } else if (authState.isAuthenticated && state.isConnected && currentRoute == Routes.LOGIN) {
            Log.i("SidespotAuth", "auth-gained: player already connected, navigating to library")
            navController.navigate(Routes.LIBRARY) {
                popUpTo(Routes.LOGIN) { inclusive = true }
            }
        }
    }

    DynamicSidespotTheme(albumColors = albumColors, einkMode = settingsState.einkMode) {
        Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (!hideChrome) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Mini-player above bottom nav.  Gated on trackUri only (not
                        // trackTitle) so it stays mounted across track changes and the
                        // theme's colour tween is visible instead of an unmount/remount.
                        if (state.isConnected && state.trackUri.isNotEmpty()) {
                            MiniPlayer(
                                trackTitle = state.trackTitle,
                                artistName = state.artistName,
                                isPlaying = state.isPlaying,
                                onPlayPause = {
                                    if (state.isPlaying) playerViewModel.pause()
                                    else playerViewModel.play()
                                },
                                onClick = { showNowPlaying = true },
                            )
                        }

                        BottomNavBar(
                            navController = navController,
                            currentRoute = currentRoute,
                        )
                    }
                }
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                NavHost(
                    navController = navController,
                    startDestination = startDestination,
                ) {
                    composable(Routes.LOGIN) {
                        val context = LocalContext.current
                        LoginScreen(
                            authState = authState,
                            onSignIn = {
                                val authUri = authManager.buildAuthUri()
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, authUri))
                                } catch (e: ActivityNotFoundException) {
                                    authManager.setError(
                                        "No browser installed — please install a web browser to sign in."
                                    )
                                }
                            },
                        )
                    }

                    composable(Routes.LIBRARY) {
                        LibraryScreen(
                            onPlaylistClick = { uri ->
                                navController.navigate(Routes.trackList(uri))
                            },
                            onAlbumClick = { uri ->
                                navController.navigate(Routes.trackList(uri))
                            },
                            onLikedSongsClick = {
                                navController.navigate(Routes.trackList("liked_songs"))
                            },
                            onSavedAlbumsClick = {
                                navController.navigate(Routes.SAVED_ALBUMS)
                            },
                            onPodcastsClick = {
                                navController.navigate(Routes.SAVED_SHOWS)
                            },
                            onHistoryClick = {
                                navController.navigate(Routes.HISTORY)
                            },
                            onSettingsClick = {
                                navController.navigate(Routes.SETTINGS)
                            },
                            viewModel = libraryViewModel,
                        )
                    }

                    composable(Routes.SEARCH) {
                        SearchScreen(
                            playerViewModel = playerViewModel,
                            libraryViewModel = libraryViewModel,
                            searchViewModel = searchViewModel,
                            onAlbumClick = { uri ->
                                navController.navigate(Routes.trackList(uri))
                            },
                            onPlaylistClick = { uri ->
                                navController.navigate(Routes.trackList(uri))
                            },
                            onShowClick = { uri ->
                                val show = searchViewModel.uiState.value.shows
                                    .find { it.uri == uri }
                                val name = show?.name ?: "Podcast"
                                navController.navigate(Routes.showDetail(uri, name))
                            },
                        )
                    }

                    composable(
                        route = Routes.TRACK_LIST,
                        arguments = listOf(navArgument("uri") { type = NavType.StringType }),
                    ) { backStackEntry ->
                        val uri = backStackEntry.arguments?.getString("uri")
                            ?.let { URLDecoder.decode(it, "UTF-8") } ?: return@composable
                        TrackListScreen(
                            uri = uri,
                            playerViewModel = playerViewModel,
                            libraryViewModel = libraryViewModel,
                            onBack = { navController.popBackStack() },
                            onGoToAlbum = { albumUri ->
                                navController.navigate(Routes.trackList(albumUri))
                            },
                            onPlayStarted = { showNowPlaying = true },
                        )
                    }

                    composable(Routes.QUEUE) {
                        QueueScreen(
                            playerViewModel = playerViewModel,
                            libraryViewModel = libraryViewModel,
                            onGoToAlbum = { albumUri ->
                                navController.navigate(Routes.trackList(albumUri))
                            },
                        )
                    }

                    composable(Routes.SETTINGS) {
                        SettingsScreen(
                            settingsManager = settingsManager,
                            playerViewModel = playerViewModel,
                            authManager = authManager,
                            onBack = { navController.popBackStack() },
                            onSignOut = {
                                navController.navigate(Routes.LOGIN) {
                                    popUpTo(0) { inclusive = true }
                                }
                            },
                        )
                    }

                    composable(Routes.SAVED_ALBUMS) {
                        SavedAlbumsScreen(
                            libraryViewModel = libraryViewModel,
                            onAlbumClick = { uri ->
                                navController.navigate(Routes.trackList(uri))
                            },
                            onBack = { navController.popBackStack() },
                        )
                    }

                    composable(Routes.SAVED_SHOWS) {
                        SavedShowsScreen(
                            libraryViewModel = libraryViewModel,
                            onShowClick = { uri ->
                                val show = libraryViewModel.uiState.value.shows
                                    .find { it.uri == uri }
                                val name = show?.name ?: "Podcast"
                                navController.navigate(Routes.showDetail(uri, name))
                            },
                            onNewEpisodesClick = {
                                navController.navigate(Routes.NEW_EPISODES)
                            },
                            onBack = { navController.popBackStack() },
                        )
                    }

                    composable(Routes.NEW_EPISODES) {
                        NewEpisodesScreen(
                            libraryViewModel = libraryViewModel,
                            playerViewModel = playerViewModel,
                            onBack = { navController.popBackStack() },
                        )
                    }

                    composable(Routes.HISTORY) {
                        HistoryScreen(
                            libraryViewModel = libraryViewModel,
                            onItemClick = { uri ->
                                navController.navigate(Routes.trackList(uri))
                            },
                            onBack = { navController.popBackStack() },
                        )
                    }

                    composable(
                        route = Routes.SHOW_DETAIL,
                        arguments = listOf(
                            navArgument("uri") { type = NavType.StringType },
                            navArgument("name") { type = NavType.StringType },
                        ),
                    ) { backStackEntry ->
                        val uri = backStackEntry.arguments?.getString("uri")
                            ?.let { URLDecoder.decode(it, "UTF-8") } ?: return@composable
                        val name = backStackEntry.arguments?.getString("name")
                            ?.let { URLDecoder.decode(it, "UTF-8") } ?: "Podcast"
                        ShowDetailScreen(
                            showUri = uri,
                            showName = name,
                            libraryViewModel = libraryViewModel,
                            playerViewModel = playerViewModel,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
            }
        }

        // Now Playing full-screen overlay — fades over everything including bottom bar
        AnimatedVisibility(
            visible = showNowPlaying,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            BackHandler { showNowPlaying = false }
            NowPlayingScreen(
                viewModel = playerViewModel,
                onBack = { showNowPlaying = false },
            )
        }
        }
    }
}

@Composable
private fun BottomNavBar(
    navController: NavHostController,
    currentRoute: String?,
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        modifier = Modifier.height(72.dp),
        windowInsets = WindowInsets(0),
    ) {
        bottomNavItems.forEach { item ->
            val selected = currentRoute == item.route
            NavigationBarItem(
                selected = selected,
                onClick = {
                    navController.navigate(item.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            inclusive = false
                        }
                        launchSingleTop = true
                    }
                },
                icon = {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        modifier = Modifier.size(18.dp),
                    )
                },
                label = {
                    Text(text = item.label, style = MaterialTheme.typography.labelSmall)
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    indicatorColor = MaterialTheme.colorScheme.surface,
                ),
            )
        }
    }
}
