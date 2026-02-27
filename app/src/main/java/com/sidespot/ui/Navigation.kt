package com.sidespot.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val state by playerViewModel.uiState.collectAsState()
    val authState by authManager.state.collectAsState()
    val libraryViewModel: LibraryViewModel = viewModel()
    val searchViewModel: SearchViewModel = viewModel()

    // Start at login unless already connected (handles both fresh launch and relaunch)
    val startDestination = if (state.isConnected) Routes.LIBRARY else Routes.LOGIN

    // Hide bottom nav + mini-player on full-screen Now Playing and Login
    val hideChrome = currentRoute == Routes.NOW_PLAYING || currentRoute == Routes.LOGIN

    val albumColors = rememberAlbumColors(state.albumArtUrl)

    // Hide status bar on Now Playing screen
    val isNowPlaying = currentRoute == Routes.NOW_PLAYING
    val activity = LocalContext.current as? Activity
    DisposableEffect(isNowPlaying) {
        val window = activity?.window ?: return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (isNowPlaying) {
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

    // Auto-connect when authenticated but not yet connected (handles app relaunch
    // and fresh sign-in).  The `version` key ensures this re-triggers even when
    // isAuthenticated stays true across back-to-back sign-ins.  Skipping while
    // isLoading avoids racing with an in-flight exchangeCode().
    val settingsState by settingsManager.state.collectAsState()
    LaunchedEffect(authState.isAuthenticated, authState.version, authState.isLoading, state.isConnected) {
        if (authState.isAuthenticated && !state.isConnected && !authState.isLoading) {
            val token = authManager.getValidAccessToken()
            if (token != null) {
                playerViewModel.connect(
                    accessToken = token,
                    connectEnabled = settingsState.connectEnabled,
                    deviceName = settingsState.deviceName,
                )
            }
        }
    }

    // Navigate from login to library once connected, and load library data
    LaunchedEffect(state.isConnected) {
        if (state.isConnected) {
            libraryViewModel.initApi(authManager)
            searchViewModel.initApi(authManager)
            libraryViewModel.loadPlaylists()
            if (currentRoute == Routes.LOGIN) {
                navController.navigate(Routes.LIBRARY) {
                    popUpTo(Routes.LOGIN) { inclusive = true }
                }
            }
        }
    }

    DynamicSidespotTheme(albumColors = albumColors) {
        Scaffold(
            containerColor = if (isNowPlaying) Color.Transparent
                else MaterialTheme.colorScheme.background,
            contentWindowInsets = if (isNowPlaying) WindowInsets(0)
                else ScaffoldDefaults.contentWindowInsets,
            bottomBar = {
                if (!hideChrome) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Mini-player above bottom nav
                        if (state.isConnected && state.trackUri.isNotEmpty()) {
                            MiniPlayer(
                                trackTitle = state.trackTitle,
                                artistName = state.artistName,
                                isPlaying = state.isPlaying,
                                onPlayPause = {
                                    if (state.isPlaying) playerViewModel.pause()
                                    else playerViewModel.play()
                                },
                                onClick = {
                                    navController.navigate(Routes.NOW_PLAYING) {
                                        launchSingleTop = true
                                    }
                                },
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
                    .then(
                        if (isNowPlaying) Modifier
                        else Modifier.padding(innerPadding)
                    ),
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
                                context.startActivity(Intent(Intent.ACTION_VIEW, authUri))
                            },
                        )
                    }

                    composable(Routes.NOW_PLAYING) {
                        NowPlayingScreen(
                            viewModel = playerViewModel,
                            onBack = { navController.popBackStack() },
                        )
                    }

                    composable(Routes.LIBRARY) {
                        LibraryScreen(
                            onPlaylistClick = { uri ->
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
                        )
                    }

                    composable(Routes.QUEUE) {
                        QueueScreen(
                            playerViewModel = playerViewModel,
                            libraryViewModel = libraryViewModel,
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
