package com.sidespot.ui

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sidespot.api.ApiResult
import com.sidespot.viewmodel.LibraryItem
import com.sidespot.viewmodel.LibraryViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun LibraryScreen(
    onPlaylistClick: (uri: String) -> Unit,
    onAlbumClick: (uri: String) -> Unit = {},
    onLikedSongsClick: () -> Unit,
    onSavedAlbumsClick: () -> Unit = {},
    onArtistsClick: () -> Unit = {},
    onPodcastsClick: () -> Unit = {},
    onHistoryClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    viewModel: LibraryViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var selectedPlaylistUri by remember { mutableStateOf<String?>(null) }
    var feedbackText by remember { mutableStateOf<String?>(null) }
    val likedSongsFocus = remember { FocusRequester() }
    val historyFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    var likedSongsFocusReady by remember { mutableStateOf(false) }

    LaunchedEffect(likedSongsFocusReady) {
        if (likedSongsFocusReady) {
            try { likedSongsFocus.requestFocus() } catch (_: IllegalStateException) {}
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .focusGroup(),
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Library",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onHistoryClick,
                modifier = Modifier
                    .focusRequester(historyFocus)
                    .focusProperties { down = settingsFocus }
                    .focusCircle(),
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = "History",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier
                    .focusRequester(settingsFocus)
                    .focusProperties { up = historyFocus }
                    .focusCircle(),
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (state.isLoading && state.playlists.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else if (state.error != null && state.playlists.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = state.error ?: "Unknown error",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        } else {
            LazyColumn {
                // Liked Songs entry
                item(contentType = "nav_entry") {
                    DisposableEffect(Unit) {
                        likedSongsFocusReady = true
                        onDispose { likedSongsFocusReady = false }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(likedSongsFocus)
                            .focusHighlight()
                            .clickable(onClick = onLikedSongsClick)
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "Liked Songs",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }

                // Saved Albums entry
                item(contentType = "nav_entry") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusHighlight()
                            .clickable(onClick = onSavedAlbumsClick)
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Album,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "Albums",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }

                // Artists entry
                item(contentType = "nav_entry") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusHighlight()
                            .clickable(onClick = onArtistsClick)
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "Artists",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }

                // Podcasts entry
                item(contentType = "nav_entry") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusHighlight()
                            .clickable(onClick = onPodcastsClick)
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Podcasts,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "Podcasts",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }

                // Playlists and recently played albums
                items(state.libraryItems, key = { it.uri }, contentType = { if (it is LibraryItem.Playlist) "playlist" else "album" }) { item ->
                    val isPlaylist = item is LibraryItem.Playlist
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusHighlight(onEnterKey = {
                                if (isPlaylist) selectedPlaylistUri = item.uri
                            })
                            .combinedClickable(
                                onClick = {
                                    if (isPlaylist) onPlaylistClick(item.uri)
                                    else onAlbumClick(item.uri)
                                },
                                onLongClick = {
                                    if (isPlaylist) selectedPlaylistUri = item.uri
                                },
                            )
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val imageUrl = (item as? LibraryItem.Album)?.imageUrl
                        if (imageUrl != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(imageUrl).size(80).build(),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(6.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = if (isPlaylist) Icons.Default.QueueMusic
                                        else Icons.Default.Album,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.name.ifEmpty {
                                    if (isPlaylist) "Untitled Playlist" else "Untitled Album"
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (item is LibraryItem.Album && item.artistName.isNotEmpty()) {
                                Text(
                                    text = item.artistName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }

    // Bottom sheet for long-press actions on playlists
    if (selectedPlaylistUri != null) {
        if (feedbackText != null) {
            LaunchedEffect(feedbackText) {
                delay(1000)
                feedbackText = null
                selectedPlaylistUri = null
            }
        }

        ModalBottomSheet(
            onDismissRequest = { selectedPlaylistUri = null; feedbackText = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier.dismissOnDpad { selectedPlaylistUri = null; feedbackText = null },
        ) {
            Column(modifier = Modifier.navigationBarsPadding().padding(16.dp)) {
                if (feedbackText != null) {
                    Text(
                        text = feedbackText!!,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusHighlight(onEnterKey = {
                                viewModel.unsavePlaylist(selectedPlaylistUri!!) { result ->
                                    feedbackText = when (result) {
                                        is ApiResult.Success -> "Removed from Library"
                                        is ApiResult.Error -> "Error: ${result.message}"
                                    }
                                }
                            })
                            .clickable {
                                viewModel.unsavePlaylist(selectedPlaylistUri!!) { result ->
                                    feedbackText = when (result) {
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
                }
            }
        }
    }
}
