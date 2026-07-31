package com.sidespot.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import android.view.InputDevice
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.sidespot.api.ApiResult
import com.sidespot.bridge.TrackInfo
import com.sidespot.viewmodel.LibraryViewModel
import com.sidespot.viewmodel.PlayerViewModel
import com.sidespot.viewmodel.TrackListViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TrackListScreen(
    uri: String,
    playerViewModel: PlayerViewModel,
    libraryViewModel: LibraryViewModel = viewModel(),
    onBack: () -> Unit,
    onGoToAlbum: (String) -> Unit = {},
    onPlayStarted: () -> Unit = {},
    trackListViewModel: TrackListViewModel = viewModel(key = uri),
) {
    val state by trackListViewModel.uiState.collectAsState()
    val libraryState by libraryViewModel.uiState.collectAsState()
    var selectedTrackUri by remember { mutableStateOf<String?>(null) }
    var saveAlbumFeedback by remember { mutableStateOf<String?>(null) }
    var savePlaylistFeedback by remember { mutableStateOf<String?>(null) }
    val isAlbumSaved = remember(libraryState.albums, uri) {
        libraryState.albums.any { it.uri == uri }
    }
    val isPlaylist = remember(uri) { uri.startsWith("spotify:playlist:") }
    val isPlaylistSaved = remember(libraryState.playlists, uri) {
        libraryState.playlists.any { it.uri == uri }
    }

    LaunchedEffect(uri) {
        trackListViewModel.loadTrackList(uri)
    }

    // Load saved albums if not yet loaded so we can derive saved status
    LaunchedEffect(Unit) {
        if (libraryState.albums.isEmpty() && !libraryState.isLoadingAlbums) {
            libraryViewModel.loadSavedAlbums()
        }
    }

    // Reset feedback text when saved status changes
    LaunchedEffect(isAlbumSaved) {
        saveAlbumFeedback = null
    }
    LaunchedEffect(isPlaylistSaved) {
        savePlaylistFeedback = null
    }

    if (state.isLoading && state.tracks.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.focusCircle()) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Loading...",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
    } else if (state.error != null && state.tracks.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.focusCircle()) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = state.name.ifEmpty { "Error" },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = state.error ?: "Error",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    } else {
        val hasDpad = remember {
            InputDevice.getDeviceIds().any { id ->
                val dev = InputDevice.getDevice(id)
                dev != null && !dev.isVirtual &&
                    dev.sources and InputDevice.SOURCE_DPAD != 0
            }
        }
        val listState = rememberLazyListState()

        // Pull in the next page of track metadata as the user nears the end.
        LaunchedEffect(listState) {
            snapshotFlow {
                val info = listState.layoutInfo
                (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
            }.collect { (lastVisible, total) ->
                if (total > 0 && lastVisible >= total - 15) {
                    trackListViewModel.loadMoreTracks()
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            // Header with back button
            item(contentType = "header") {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.focusCircle()) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = state.name.ifEmpty { "Loading..." },
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Album art
            if (state.albumArtUrl != null) {
                item(contentType = "album_art") {
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current).data(state.albumArtUrl).size(240).build(),
                            contentDescription = "Album art",
                            modifier = Modifier
                                .size(120.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            }

            // Track count + Play All
            if (state.trackUris.isNotEmpty()) {
                item(contentType = "controls") {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${state.trackUris.size} tracks",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (hasDpad) {
                        val playAllFocus = remember { FocusRequester() }
                        LaunchedEffect(Unit) { playAllFocus.requestFocus() }

                        // Stacked buttons for D-pad navigation
                        Button(
                            onClick = {
                                playerViewModel.loadTrackFromContext(
                                    state.trackUris, 0, state.name, contextUri = uri,
                                    contextImageUrl = state.albumArtUrl,
                                    contextArtistName = state.tracks.firstOrNull()?.artistName ?: "",
                                )
                                onPlayStarted()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                            ),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(playAllFocus)
                                .focusDarken(),
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Play All")
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = {
                                val shuffledTracks = state.trackUris.shuffled()
                                playerViewModel.loadTrackFromContext(
                                    shuffledTracks, 0, state.name, contextUri = uri,
                                    contextImageUrl = state.albumArtUrl,
                                    contextArtistName = state.tracks.firstOrNull()?.artistName ?: "",
                                )
                                onPlayStarted()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                            ),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusDarken(),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shuffle,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Shuffle")
                        }
                        if (state.isAlbum && !isAlbumSaved) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Button(
                                onClick = {
                                    libraryViewModel.saveAlbum(uri) { result ->
                                        saveAlbumFeedback = when (result) {
                                            is ApiResult.Success -> "Saved to Library"
                                            is ApiResult.Error -> "Error: ${result.message}"
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary,
                                ),
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusDarken(),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LibraryAdd,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(saveAlbumFeedback ?: "Save Album")
                            }
                        }
                        if (isPlaylist && !isPlaylistSaved) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Button(
                                onClick = {
                                    libraryViewModel.savePlaylist(uri, state.name) { result ->
                                        savePlaylistFeedback = when (result) {
                                            is ApiResult.Success -> "Saved to Library"
                                            is ApiResult.Error -> "Error: ${result.message}"
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary,
                                ),
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusDarken(),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LibraryAdd,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(savePlaylistFeedback ?: "Follow Playlist")
                            }
                        }
                    } else {
                        // Inline buttons for touch
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Spacer(modifier = Modifier.weight(1f))
                            FilledIconButton(
                                onClick = {
                                    val shuffledTracks = state.trackUris.shuffled()
                                    playerViewModel.loadTrackFromContext(
                                        shuffledTracks, 0, state.name,
                                    )
                                    onPlayStarted()
                                },
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                ),
                                modifier = Modifier.size(40.dp).focusCircle(),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Shuffle,
                                    contentDescription = "Shuffle",
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Button(
                                onClick = {
                                    playerViewModel.loadTrackFromContext(
                                        state.trackUris, 0, state.name,
                                    )
                                    onPlayStarted()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                ),
                                shape = RoundedCornerShape(20.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Play All")
                            }
                        }
                        if (state.isAlbum && !isAlbumSaved) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Spacer(modifier = Modifier.weight(1f))
                                Button(
                                    onClick = {
                                        libraryViewModel.saveAlbum(uri) { result ->
                                            saveAlbumFeedback = when (result) {
                                                is ApiResult.Success -> "Saved to Library"
                                                is ApiResult.Error -> "Error: ${result.message}"
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.secondary,
                                    ),
                                    shape = RoundedCornerShape(20.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.LibraryAdd,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(saveAlbumFeedback ?: "Save Album")
                                }
                            }
                        }
                        if (isPlaylist && !isPlaylistSaved) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Spacer(modifier = Modifier.weight(1f))
                                Button(
                                    onClick = {
                                        libraryViewModel.savePlaylist(uri, state.name) { result ->
                                            savePlaylistFeedback = when (result) {
                                                is ApiResult.Success -> "Saved to Library"
                                                is ApiResult.Error -> "Error: ${result.message}"
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.secondary,
                                    ),
                                    shape = RoundedCornerShape(20.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.LibraryAdd,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(savePlaylistFeedback ?: "Follow Playlist")
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // Track list
            itemsIndexed(state.tracks, key = { _, track -> track.uri }, contentType = { _, _ -> "track" }) { index, track ->
                TrackRow(
                    index = index + 1,
                    track = track,
                    showAlbumArt = !state.isAlbum,
                    onClick = {
                        // Tracks whose metadata failed to resolve are skipped, so the
                        // row index can drift from the position in trackUris.
                        val contextIndex = state.trackUris.indexOf(track.uri)
                            .takeIf { it >= 0 } ?: index
                        playerViewModel.loadTrackFromContext(
                            state.trackUris, contextIndex, state.name, contextUri = uri,
                            contextImageUrl = state.albumArtUrl,
                            contextArtistName = state.tracks.firstOrNull()?.artistName ?: "",
                        )
                    },
                    onLongClick = {
                        selectedTrackUri = track.uri
                    },
                )
            }

            // Show loading indicator while fetching more tracks
            if (state.isLoading) {
                item(contentType = "loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }

    // Bottom sheet for long-press actions
    if (selectedTrackUri != null) {
        val selectedTrack = state.tracks.find { it.uri == selectedTrackUri }
        val writablePlaylists = remember(libraryState.playlists) {
            libraryState.playlists.filter { it.isWritable }
        }
        TrackActionsSheet(
            trackUri = selectedTrackUri!!,
            playerViewModel = playerViewModel,
            playlists = writablePlaylists,
            onDismiss = { selectedTrackUri = null },
            onGoToAlbum = if (!state.isAlbum && selectedTrack != null) {
                { onGoToAlbum(selectedTrack.albumUri) }
            } else null,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackRow(
    index: Int,
    track: TrackInfo,
    showAlbumArt: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusHighlight(onEnterKey = onLongClick)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Track number
        Text(
            text = "$index",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp),
        )

        // Album art thumbnail (hidden for album views)
        if (showAlbumArt) {
            if (track.albumArtUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(track.albumArtUrl).size(80).build(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
        }

        // Track info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artistName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Duration
        Text(
            text = formatDuration(track.durationMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatDuration(ms: Int): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
