package com.sidespot.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.sidespot.bridge.ArtistAlbum
import com.sidespot.viewmodel.ArtistViewModel
import com.sidespot.viewmodel.LibraryViewModel
import com.sidespot.viewmodel.PlayerViewModel

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ArtistScreen(
    artistUri: String,
    playerViewModel: PlayerViewModel,
    libraryViewModel: LibraryViewModel = viewModel(),
    onBack: () -> Unit,
    onAlbumClick: (String) -> Unit = {},
    onGoToArtist: (String) -> Unit = {},
    onPlayStarted: () -> Unit = {},
    artistViewModel: ArtistViewModel = viewModel(key = artistUri),
) {
    val state by artistViewModel.uiState.collectAsState()
    val libraryState by libraryViewModel.uiState.collectAsState()
    var selectedTrackUri by remember { mutableStateOf<String?>(null) }
    val firstItemFocus = remember { FocusRequester() }
    var firstItemFocusReady by remember { mutableStateOf(false) }

    LaunchedEffect(artistUri) {
        artistViewModel.loadArtist(artistUri)
    }

    LaunchedEffect(firstItemFocusReady) {
        if (firstItemFocusReady) {
            firstItemFocus.requestFocus()
        }
    }

    // The first focusable row is whichever section actually has content.
    val focusSection = when {
        state.topTracks.isNotEmpty() -> "top"
        state.albums.isNotEmpty() -> "albums"
        else -> "singles"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .focusProperties {
                enter = {
                    if (firstItemFocusReady) firstItemFocus
                    else FocusRequester.Default
                }
            }
            .focusGroup(),
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
                text = state.name.ifEmpty { "Loading..." },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (state.isLoading && state.name.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else if (state.error != null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = state.error ?: "Error",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        } else {
            LazyColumn {
                if (state.imageUrl != null) {
                    item(contentType = "portrait") {
                        val context = LocalContext.current
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(state.imageUrl)
                                    .size(240)
                                    .build(),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(120.dp)
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                }

                if (state.topTracks.isNotEmpty()) {
                    item(contentType = "section") { SectionHeader("Popular") }
                    itemsIndexed(
                        state.topTracks,
                        key = { _, track -> track.uri },
                        contentType = { _, _ -> "track" },
                    ) { index, track ->
                        if (index == 0 && focusSection == "top") {
                            DisposableEffect(Unit) {
                                firstItemFocusReady = true
                                onDispose { firstItemFocusReady = false }
                            }
                        }
                        TrackRow(
                            index = index + 1,
                            track = track,
                            showAlbumArt = true,
                            onClick = {
                                playerViewModel.loadTrackFromContext(
                                    state.topTracks.map { it.uri },
                                    index,
                                    state.name,
                                    contextUri = artistUri,
                                    contextImageUrl = state.imageUrl,
                                    contextArtistName = state.name,
                                )
                                onPlayStarted()
                            },
                            onLongClick = { selectedTrackUri = track.uri },
                            modifier = if (index == 0 && focusSection == "top") {
                                Modifier.focusRequester(firstItemFocus)
                            } else Modifier,
                        )
                    }
                }

                if (state.albums.isNotEmpty()) {
                    item(contentType = "section") { SectionHeader("Albums") }
                    itemsIndexed(
                        state.albums,
                        key = { _, album -> album.uri },
                        contentType = { _, _ -> "artist_album" },
                    ) { index, album ->
                        if (index == 0 && focusSection == "albums") {
                            DisposableEffect(Unit) {
                                firstItemFocusReady = true
                                onDispose { firstItemFocusReady = false }
                            }
                        }
                        ArtistAlbumRow(
                            album = album,
                            onClick = { onAlbumClick(album.uri) },
                            modifier = if (index == 0 && focusSection == "albums") {
                                Modifier.focusRequester(firstItemFocus)
                            } else Modifier,
                        )
                    }
                }

                if (state.singles.isNotEmpty()) {
                    item(contentType = "section") { SectionHeader("Singles") }
                    itemsIndexed(
                        state.singles,
                        key = { _, single -> single.uri },
                        contentType = { _, _ -> "artist_album" },
                    ) { index, single ->
                        if (index == 0 && focusSection == "singles") {
                            DisposableEffect(Unit) {
                                firstItemFocusReady = true
                                onDispose { firstItemFocusReady = false }
                            }
                        }
                        ArtistAlbumRow(
                            album = single,
                            onClick = { onAlbumClick(single.uri) },
                            modifier = if (index == 0 && focusSection == "singles") {
                                Modifier.focusRequester(firstItemFocus)
                            } else Modifier,
                        )
                    }
                }

                item(contentType = "spacer") {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }

    // Bottom sheet for long-press actions
    if (selectedTrackUri != null) {
        val selectedTrack = state.topTracks.find { it.uri == selectedTrackUri }
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
            // Only offer the artists we are not already looking at.
            artists = selectedTrack?.artists.orEmpty().filter { it.uri != artistUri },
            onGoToArtist = onGoToArtist,
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtistAlbumRow(
    album: ArtistAlbum,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .focusHighlight(onEnterKey = onClick)
            .combinedClickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (album.imageUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(album.imageUrl).size(96).build(),
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
                    imageVector = Icons.Default.Album,
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
            val subtitle = buildString {
                if (album.year > 0) append(album.year)
                if (album.trackCount > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("${album.trackCount} track${if (album.trackCount == 1) "" else "s"}")
                }
            }
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
