package com.sidespot.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.sidespot.api.ApiResult
import com.sidespot.bridge.ShowSummary
import com.sidespot.bridge.TrackInfo
import com.sidespot.viewmodel.AlbumResult
import com.sidespot.viewmodel.ArtistResult
import com.sidespot.viewmodel.LibraryViewModel
import com.sidespot.viewmodel.PlayerViewModel
import com.sidespot.viewmodel.PlaylistResult
import com.sidespot.viewmodel.SearchViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    playerViewModel: PlayerViewModel,
    libraryViewModel: LibraryViewModel = viewModel(),
    searchViewModel: SearchViewModel = viewModel(),
    onAlbumClick: (String) -> Unit = {},
    onArtistClick: (String) -> Unit = {},
    onPlaylistClick: (String) -> Unit = {},
    onShowClick: (String) -> Unit = {},
) {
    val state by searchViewModel.uiState.collectAsState()
    val libraryState by libraryViewModel.uiState.collectAsState()
    var selectedTrackUri by remember { mutableStateOf<String?>(null) }
    var selectedPlaylistUri by remember { mutableStateOf<String?>(null) }
    var playlistFeedbackText by remember { mutableStateOf<String?>(null) }

    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Search",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = state.query,
            onValueChange = searchViewModel::updateQuery,
            modifier = Modifier
                .fillMaxWidth()
                .onPreviewKeyEvent { event ->
                    if (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN &&
                        event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN
                    ) {
                        focusManager.moveFocus(FocusDirection.Down)
                        true
                    } else false
                },
            placeholder = { Text("Songs, artists, albums...") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )

        Spacer(modifier = Modifier.height(12.dp))

        val hasResults = state.tracks.isNotEmpty() || state.artists.isNotEmpty() ||
            state.albums.isNotEmpty() || state.shows.isNotEmpty() || state.playlists.isNotEmpty()

        if (state.isSearching && !hasResults) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else if (!hasResults && state.query.isNotBlank() && !state.isSearching) {
            Text(
                text = state.error ?: "No results",
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.error != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val displayedTracks = remember(state.tracks, state.tracksDisplayLimit) {
                state.tracks.take(state.tracksDisplayLimit)
            }
            val displayedArtists = remember(state.artists, state.artistsDisplayLimit) {
                state.artists.take(state.artistsDisplayLimit)
            }
            val displayedAlbums = remember(state.albums, state.albumsDisplayLimit) {
                state.albums.take(state.albumsDisplayLimit)
            }
            val displayedPlaylists = remember(state.playlists, state.playlistsDisplayLimit) {
                state.playlists.take(state.playlistsDisplayLimit)
            }
            val displayedShows = remember(state.shows, state.showsDisplayLimit) {
                state.shows.take(state.showsDisplayLimit)
            }
            LazyColumn {
                // Songs section
                if (state.tracks.isNotEmpty()) {
                    item(contentType = "header") {
                        Text(
                            text = "Songs",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }

                    itemsIndexed(displayedTracks, key = { _, track -> track.uri }, contentType = { _, _ -> "track" }) { index, track ->
                        SearchResultRow(
                            track = track,
                            onClick = {
                                playerViewModel.loadTrackFromContext(
                                    listOf(track.uri), 0, "Search: ${state.query}",
                                )
                            },
                            onLongClick = {
                                selectedTrackUri = track.uri
                            },
                        )
                    }

                    if (state.hasMoreTracks) {
                        item(contentType = "show_more") {
                            Text(
                                text = "Show More...",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { searchViewModel.showMoreTracks() }
                                    .padding(vertical = 12.dp),
                            )
                        }
                    }
                }

                // Artists section
                if (state.artists.isNotEmpty()) {
                    item(contentType = "header") {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Artists",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }

                    itemsIndexed(displayedArtists, key = { _, artist -> artist.uri }, contentType = { _, _ -> "artist" }) { _, artist ->
                        ArtistResultRow(
                            artist = artist,
                            onClick = { onArtistClick(artist.uri) },
                        )
                    }

                    if (state.hasMoreArtists) {
                        item(contentType = "show_more") {
                            Text(
                                text = "Show More...",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { searchViewModel.showMoreArtists() }
                                    .padding(vertical = 12.dp),
                            )
                        }
                    }
                }

                // Albums section
                if (state.albums.isNotEmpty()) {
                    item(contentType = "header") {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Albums",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }

                    itemsIndexed(displayedAlbums, key = { _, album -> album.uri }, contentType = { _, _ -> "album" }) { _, album ->
                        AlbumResultRow(
                            album = album,
                            onClick = { onAlbumClick(album.uri) },
                        )
                    }

                    if (state.hasMoreAlbums) {
                        item(contentType = "show_more") {
                            Text(
                                text = "Show More...",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { searchViewModel.showMoreAlbums() }
                                    .padding(vertical = 12.dp),
                            )
                        }
                    }
                }

                // Playlists section
                if (state.playlists.isNotEmpty()) {
                    item(contentType = "header") {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Playlists",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }

                    itemsIndexed(displayedPlaylists, key = { _, playlist -> playlist.uri }, contentType = { _, _ -> "playlist" }) { _, playlist ->
                        PlaylistResultRow(
                            playlist = playlist,
                            onClick = { onPlaylistClick(playlist.uri) },
                            onLongClick = { selectedPlaylistUri = playlist.uri },
                        )
                    }

                    if (state.hasMorePlaylists) {
                        item(contentType = "show_more") {
                            Text(
                                text = "Show More...",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { searchViewModel.showMorePlaylists() }
                                    .padding(vertical = 12.dp),
                            )
                        }
                    }
                }

                // Podcasts section
                if (state.shows.isNotEmpty()) {
                    item(contentType = "header") {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Podcasts",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }

                    itemsIndexed(displayedShows, key = { _, show -> show.uri }, contentType = { _, _ -> "show" }) { _, show ->
                        ShowResultRow(
                            show = show,
                            onClick = { onShowClick(show.uri) },
                        )
                    }

                    if (state.hasMoreShows) {
                        item(contentType = "show_more") {
                            Text(
                                text = "Show More...",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { searchViewModel.showMoreShows() }
                                    .padding(vertical = 12.dp),
                            )
                        }
                    }
                }

                if (state.isSearching || state.isLoadingMore) {
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
    }

    // Track actions bottom sheet
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
            onGoToAlbum = if (selectedTrack != null) {
                { onAlbumClick(selectedTrack.albumUri) }
            } else null,
            artists = selectedTrack?.artists.orEmpty(),
            onGoToArtist = onArtistClick,
        )
    }

    // Playlist actions bottom sheet
    if (selectedPlaylistUri != null) {
        val isInLibrary = remember(libraryState.playlists, selectedPlaylistUri) {
            libraryState.playlists.any { it.uri == selectedPlaylistUri }
        }
        val selectedPlaylist = remember(state.playlists, selectedPlaylistUri) {
            state.playlists.find { it.uri == selectedPlaylistUri }
        }

        if (playlistFeedbackText != null) {
            LaunchedEffect(playlistFeedbackText) {
                delay(1000)
                playlistFeedbackText = null
                selectedPlaylistUri = null
            }
        }

        ModalBottomSheet(
            onDismissRequest = { selectedPlaylistUri = null; playlistFeedbackText = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier.dismissOnDpad { selectedPlaylistUri = null; playlistFeedbackText = null },
        ) {
            Column(modifier = Modifier.navigationBarsPadding().padding(16.dp)) {
                if (playlistFeedbackText != null) {
                    Text(
                        text = playlistFeedbackText!!,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                } else if (isInLibrary) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusHighlight(onEnterKey = {
                                libraryViewModel.unsavePlaylist(selectedPlaylistUri!!) { result ->
                                    playlistFeedbackText = when (result) {
                                        is ApiResult.Success -> "Removed from Library"
                                        is ApiResult.Error -> "Error: ${result.message}"
                                    }
                                }
                            })
                            .clickable {
                                libraryViewModel.unsavePlaylist(selectedPlaylistUri!!) { result ->
                                    playlistFeedbackText = when (result) {
                                        is ApiResult.Success -> "Removed from Library"
                                        is ApiResult.Error -> "Error: ${result.message}"
                                    }
                                }
                            }
                            .padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.RemoveCircleOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "Remove from Library",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusHighlight(onEnterKey = {
                                libraryViewModel.savePlaylist(
                                    selectedPlaylistUri!!,
                                    selectedPlaylist?.name ?: "",
                                ) { result ->
                                    playlistFeedbackText = when (result) {
                                        is ApiResult.Success -> "Saved to Library"
                                        is ApiResult.Error -> "Error: ${result.message}"
                                    }
                                }
                            })
                            .clickable {
                                libraryViewModel.savePlaylist(
                                    selectedPlaylistUri!!,
                                    selectedPlaylist?.name ?: "",
                                ) { result ->
                                    playlistFeedbackText = when (result) {
                                        is ApiResult.Success -> "Saved to Library"
                                        is ApiResult.Error -> "Error: ${result.message}"
                                    }
                                }
                            }
                            .padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.LibraryAdd,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "Save to Library",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchResultRow(
    track: TrackInfo,
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
        // Album art thumbnail
        if (track.albumArtUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(track.albumArtUrl).size(88).build(),
                contentDescription = null,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(4.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${track.artistName} - ${track.albumName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Text(
            text = formatSearchDuration(track.durationMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ArtistResultRow(
    artist: ArtistResult,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusHighlight()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (artist.imageUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(artist.imageUrl).size(96).build(),
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = artist.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AlbumResultRow(
    album: AlbumResult,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusHighlight()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (album.albumArtUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(album.albumArtUrl).size(96).build(),
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = album.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = album.artistName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistResultRow(
    playlist: PlaylistResult,
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
        if (playlist.imageUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(playlist.imageUrl).size(96).build(),
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.QueueMusic,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = playlist.ownerName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ShowResultRow(
    show: ShowSummary,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusHighlight()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (show.imageUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(show.imageUrl).size(96).build(),
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Podcasts,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = show.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Search results have no publisher; skip the line rather than leave it blank.
            if (show.publisher.isNotBlank()) {
                Text(
                    text = show.publisher,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun formatSearchDuration(ms: Int): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
