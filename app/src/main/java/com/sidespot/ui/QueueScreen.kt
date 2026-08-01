package com.sidespot.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.sidespot.api.ApiResult
import com.sidespot.viewmodel.LibraryViewModel
import com.sidespot.viewmodel.PlayerViewModel

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    playerViewModel: PlayerViewModel,
    libraryViewModel: LibraryViewModel = viewModel(),
    onGoToAlbum: (String) -> Unit = {},
    onGoToArtist: (String) -> Unit = {},
) {
    val playerState by playerViewModel.uiState.collectAsState()
    val queueState by playerViewModel.queueManager.state.collectAsState()
    val libraryState by libraryViewModel.uiState.collectAsState()
    var selectedQueueIndex by remember { mutableStateOf<Int?>(null) }

    // Resolve metadata for visible queue items
    LaunchedEffect(queueState.userQueue, queueState.contextIndex) {
        playerViewModel.resolveQueueMetadata()
    }

    val upcoming = remember(queueState.contextTracks, queueState.contextIndex) {
        queueState.contextTracks.drop(queueState.contextIndex + 1)
    }

    val listState = rememberLazyListState()

    // Focusing the final row only scrolls it just into view, which leaves the
    // "...and N more" footer below the fold.  Scroll to the end instead —
    // animateScrollToItem clamps at the content bottom, so the footer lands on
    // screen.  Driven off state (not straight from onFocusChanged) so the scroll
    // starts after the focusable's own bring-into-view request.
    var lastRowFocused by remember { mutableStateOf(false) }
    LaunchedEffect(lastRowFocused) {
        if (lastRowFocused) {
            listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        // Header
        item(contentType = "header") {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Queue",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Now playing
        if (playerState.trackUri.isNotEmpty()) {
            item(contentType = "now_playing") {
                Text(
                    text = "Now Playing",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .focusHighlight()
                        .clickable { },
                ) {
                    QueueTrackRow(
                        albumArtUrl = playerState.albumArtUrl,
                        title = playerState.trackTitle.ifEmpty { playerState.trackUri },
                        artist = playerState.artistName,
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // User queue header
        if (queueState.userQueue.isNotEmpty()) {
            item(contentType = "header") {
                Text(
                    text = "Next in Queue",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        // User queue items
        itemsIndexed(queueState.userQueue, key = { index, uri -> "uq_${index}_$uri" }, contentType = { _, _ -> "track" }) { index, uri ->
            val metadata = queueState.trackMetadata[uri]
            Box(
                modifier = Modifier
                    .focusHighlight(onEnterKey = { selectedQueueIndex = index })
                    .combinedClickable(
                        onClick = { playerViewModel.skipToQueueItem(isUserQueue = true, index = index) },
                        onLongClick = { selectedQueueIndex = index },
                    ),
            ) {
                QueueTrackRow(
                    albumArtUrl = metadata?.albumArtUrl,
                    title = metadata?.name ?: uri.substringAfterLast(":"),
                    artist = metadata?.artistName ?: "",
                    durationMs = metadata?.durationMs,
                )
            }
        }

        // Context queue header
        if (upcoming.isNotEmpty()) {
            item(contentType = "header") {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = if (queueState.contextName.isNotEmpty())
                        "Next from ${queueState.contextName}"
                    else "Up Next",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        // Context queue items
        val displayUpcoming = upcoming.take(20)
        itemsIndexed(displayUpcoming, key = { index, uri -> "ctx_${index}_$uri" }, contentType = { _, _ -> "track" }) { index, uri ->
            val metadata = queueState.trackMetadata[uri]
            val absoluteIndex = queueState.contextIndex + 1 + index
            Box(
                modifier = Modifier
                    .then(
                        if (index == displayUpcoming.lastIndex) {
                            Modifier.onFocusChanged { lastRowFocused = it.isFocused }
                        } else {
                            Modifier
                        },
                    )
                    .focusHighlight()
                    .clickable {
                        playerViewModel.skipToQueueItem(isUserQueue = false, index = absoluteIndex)
                    },
            ) {
                QueueTrackRow(
                    albumArtUrl = metadata?.albumArtUrl,
                    title = metadata?.name ?: uri.substringAfterLast(":"),
                    artist = metadata?.artistName ?: "",
                    durationMs = metadata?.durationMs,
                    dimmed = true,
                )
            }
        }

        if (upcoming.size > 20) {
            item(contentType = "footer") {
                Text(
                    text = "...and ${upcoming.size - 20} more",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }

        // Empty state
        if (queueState.userQueue.isEmpty() && upcoming.isEmpty() && playerState.trackUri.isEmpty()) {
            item(contentType = "empty") {
                Text(
                    text = "Queue is empty",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // Bottom sheet for long-press actions on user queue items
    if (selectedQueueIndex != null) {
        val selectedUri = queueState.userQueue.getOrNull(selectedQueueIndex!!)
        var sheetView by remember { mutableStateOf("actions") }
        var feedbackText by remember { mutableStateOf("") }

        // Podcast episodes carry a placeholder artist with a blank URI.
        val trackArtists = queueState.trackMetadata[selectedUri]?.artists
            ?.filter { it.uri.startsWith("spotify:artist:") }
            ?: emptyList()

        if (sheetView == "feedback") {
            LaunchedEffect(feedbackText) {
                kotlinx.coroutines.delay(1000)
                selectedQueueIndex = null
            }
        }

        ModalBottomSheet(
            onDismissRequest = { selectedQueueIndex = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier.dismissOnDpad { selectedQueueIndex = null },
        ) {
            when (sheetView) {
                "actions" -> {
                    // The action list can outgrow the sheet on short screens, so scroll it.
                    Column(
                        modifier = Modifier
                            .navigationBarsPadding()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    ) {
                        QueueSheetActionRow(Icons.Default.RemoveCircleOutline, "Remove from Queue") {
                            selectedQueueIndex?.let {
                                playerViewModel.queueManager.removeFromQueue(it)
                            }
                            selectedQueueIndex = null
                        }
                        if (selectedUri != null) {
                            QueueSheetActionRow(Icons.Default.Favorite, "Add to Liked Songs") {
                                playerViewModel.addToLikedSongs(selectedUri) { result ->
                                    feedbackText = when (result) {
                                        is ApiResult.Success -> "Added to Liked Songs"
                                        is ApiResult.Error -> "Error: ${result.message}"
                                    }
                                    sheetView = "feedback"
                                }
                            }
                            QueueSheetActionRow(Icons.Default.Add, "Add to Playlist...") {
                                sheetView = "playlists"
                            }
                            val albumUri = queueState.trackMetadata[selectedUri]?.albumUri
                            if (albumUri != null) {
                                QueueSheetActionRow(Icons.Default.Album, "Go to Album") {
                                    selectedQueueIndex = null
                                    onGoToAlbum(albumUri)
                                }
                            }
                            if (trackArtists.isNotEmpty()) {
                                QueueSheetActionRow(Icons.Default.Person, "Go to Artist") {
                                    if (trackArtists.size == 1) {
                                        selectedQueueIndex = null
                                        onGoToArtist(trackArtists[0].uri)
                                    } else {
                                        sheetView = "artists"
                                    }
                                }
                            }
                        }
                    }
                }
                "artists" -> {
                    Column(modifier = Modifier.navigationBarsPadding().padding(16.dp)) {
                        Text(
                            text = "Choose Artist",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyColumn {
                            items(trackArtists, key = { it.uri }) { artist ->
                                val goToArtist = {
                                    selectedQueueIndex = null
                                    onGoToArtist(artist.uri)
                                }
                                Text(
                                    text = artist.name.ifEmpty { "Unknown Artist" },
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusHighlight(onEnterKey = goToArtist)
                                        .clickable(onClick = goToArtist)
                                        .padding(vertical = 12.dp),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
                "playlists" -> {
                    Column(modifier = Modifier.navigationBarsPadding().padding(16.dp)) {
                        Text(
                            text = "Choose Playlist",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { sheetView = "newPlaylist" }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "New Playlist",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "+",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        val writablePlaylists = remember(libraryState.playlists) {
                            libraryState.playlists.filter { it.isWritable }
                        }
                        LazyColumn {
                            items(writablePlaylists, key = { it.uri }) { playlist ->
                                Text(
                                    text = playlist.name.ifEmpty { "Untitled Playlist" },
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (selectedUri != null) {
                                                playerViewModel.addToPlaylist(
                                                    playlist.uri,
                                                    selectedUri,
                                                ) { result ->
                                                    feedbackText = when (result) {
                                                        is ApiResult.Success ->
                                                            "Added to ${playlist.name.ifEmpty { "playlist" }}"
                                                        is ApiResult.Error ->
                                                            "Error: ${result.message}"
                                                    }
                                                    sheetView = "feedback"
                                                }
                                            }
                                        }
                                        .padding(vertical = 12.dp),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
                "newPlaylist" -> {
                    var playlistName by remember { mutableStateOf("") }
                    val focusRequester = remember { FocusRequester() }

                    LaunchedEffect(Unit) { focusRequester.requestFocus() }

                    Column(modifier = Modifier.navigationBarsPadding().padding(16.dp)) {
                        Text(
                            text = "New Playlist",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = playlistName,
                            onValueChange = { playlistName = it },
                            label = { Text("Playlist name") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    if (playlistName.isNotBlank() && selectedUri != null) {
                                        playerViewModel.createPlaylistAndAddTrack(
                                            playlistName.trim(),
                                            selectedUri,
                                        ) { result ->
                                            feedbackText = when (result) {
                                                is ApiResult.Success -> "Created & added to $playlistName"
                                                is ApiResult.Error -> "Error: ${result.message}"
                                            }
                                            sheetView = "feedback"
                                        }
                                    }
                                },
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = {
                                if (playlistName.isNotBlank() && selectedUri != null) {
                                    playerViewModel.createPlaylistAndAddTrack(
                                        playlistName.trim(),
                                        selectedUri,
                                    ) { result ->
                                        feedbackText = when (result) {
                                            is ApiResult.Success -> "Created & added to $playlistName"
                                            is ApiResult.Error -> "Error: ${result.message}"
                                        }
                                        sheetView = "feedback"
                                    }
                                }
                            },
                            enabled = playlistName.isNotBlank(),
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Text("Create")
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
                "feedback" -> {
                    Column(modifier = Modifier.navigationBarsPadding().padding(16.dp)) {
                        Text(
                            text = feedbackText,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueTrackRow(
    albumArtUrl: String?,
    title: String,
    artist: String,
    durationMs: Int? = null,
    dimmed: Boolean = false,
) {
    val context = LocalContext.current
    val titleColor = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant
    else MaterialTheme.colorScheme.onBackground

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Album art thumbnail
        if (albumArtUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(albumArtUrl).size(80).build(),
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

        // Track info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (artist.isNotEmpty()) {
                Text(
                    text = artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Duration
        if (durationMs != null) {
            Text(
                text = formatQueueDuration(durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QueueSheetActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusHighlight(onEnterKey = onClick)
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun formatQueueDuration(ms: Int): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
