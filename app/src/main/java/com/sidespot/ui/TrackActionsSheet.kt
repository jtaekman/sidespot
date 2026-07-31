package com.sidespot.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QueueMusic
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.sidespot.api.ApiResult
import com.sidespot.bridge.ArtistSummary
import com.sidespot.bridge.PlaylistSummary
import com.sidespot.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay

private enum class SheetView { Actions, PlaylistPicker, ArtistPicker, NewPlaylist, Feedback }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackActionsSheet(
    trackUri: String,
    playerViewModel: PlayerViewModel,
    playlists: List<PlaylistSummary>,
    onDismiss: () -> Unit,
    onGoToAlbum: (() -> Unit)? = null,
    artists: List<ArtistSummary> = emptyList(),
    onGoToArtist: ((String) -> Unit)? = null,
) {
    var view by remember { mutableStateOf(SheetView.Actions) }
    var feedbackText by remember { mutableStateOf("") }

    // Podcast episodes carry a placeholder artist with a blank URI, so filter to
    // the ones we can actually navigate to.
    val navigableArtists = remember(artists) {
        artists.filter { it.uri.startsWith("spotify:artist:") }
    }

    // Auto-dismiss after showing feedback
    if (view == SheetView.Feedback) {
        LaunchedEffect(feedbackText) {
            delay(1000)
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.dismissOnDpad(onDismiss),
    ) {
        when (view) {
            SheetView.Actions -> {
                // The action list can outgrow the sheet on short screens, so scroll it.
                Column(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    SheetActionRow(Icons.Default.QueueMusic, "Add to Queue") {
                        playerViewModel.addToQueue(trackUri)
                        feedbackText = "Added to Queue"
                        view = SheetView.Feedback
                    }
                    SheetActionRow(Icons.Default.Favorite, "Add to Liked Songs") {
                        playerViewModel.addToLikedSongs(trackUri) { result ->
                            feedbackText = when (result) {
                                is ApiResult.Success -> "Added to Liked Songs"
                                is ApiResult.Error -> "Error: ${result.message}"
                            }
                            view = SheetView.Feedback
                        }
                    }
                    SheetActionRow(Icons.Default.Add, "Add to Playlist...") {
                        view = SheetView.PlaylistPicker
                    }
                    if (onGoToAlbum != null) {
                        SheetActionRow(Icons.Default.Album, "Go to Album") {
                            onDismiss()
                            onGoToAlbum()
                        }
                    }
                    if (onGoToArtist != null && navigableArtists.isNotEmpty()) {
                        SheetActionRow(Icons.Default.Person, "Go to Artist") {
                            if (navigableArtists.size == 1) {
                                onDismiss()
                                onGoToArtist(navigableArtists[0].uri)
                            } else {
                                view = SheetView.ArtistPicker
                            }
                        }
                    }
                }
            }
            SheetView.ArtistPicker -> {
                Column(modifier = Modifier.navigationBarsPadding().padding(16.dp)) {
                    Text(
                        text = "Choose Artist",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn {
                        items(navigableArtists, key = { it.uri }) { artist ->
                            val goToArtist = {
                                onDismiss()
                                onGoToArtist?.invoke(artist.uri)
                                Unit
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
            SheetView.PlaylistPicker -> {
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
                            .clickable { view = SheetView.NewPlaylist }
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
                    LazyColumn {
                        items(playlists, key = { it.uri }) { playlist ->
                            val addToPlaylist = {
                                playerViewModel.addToPlaylist(
                                    playlist.uri,
                                    trackUri,
                                ) { result ->
                                    feedbackText = when (result) {
                                        is ApiResult.Success ->
                                            "Added to ${playlist.name.ifEmpty { "playlist" }}"
                                        is ApiResult.Error ->
                                            "Error: ${result.message}"
                                    }
                                    view = SheetView.Feedback
                                }
                            }
                            Text(
                                text = playlist.name.ifEmpty { "Untitled Playlist" },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusHighlight(onEnterKey = addToPlaylist)
                                    .clickable(onClick = addToPlaylist)
                                    .padding(vertical = 12.dp),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
            SheetView.NewPlaylist -> {
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
                                if (playlistName.isNotBlank()) {
                                    playerViewModel.createPlaylistAndAddTrack(
                                        playlistName.trim(),
                                        trackUri,
                                    ) { result ->
                                        feedbackText = when (result) {
                                            is ApiResult.Success -> "Created & added to $playlistName"
                                            is ApiResult.Error -> "Error: ${result.message}"
                                        }
                                        view = SheetView.Feedback
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
                            if (playlistName.isNotBlank()) {
                                playerViewModel.createPlaylistAndAddTrack(
                                    playlistName.trim(),
                                    trackUri,
                                ) { result ->
                                    feedbackText = when (result) {
                                        is ApiResult.Success -> "Created & added to $playlistName"
                                        is ApiResult.Error -> "Error: ${result.message}"
                                    }
                                    view = SheetView.Feedback
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
            SheetView.Feedback -> {
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

@Composable
private fun SheetActionRow(
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
