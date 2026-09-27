package com.sidespot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.sidespot.viewmodel.LibraryItem
import com.sidespot.viewmodel.LibraryViewModel

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HistoryScreen(
    libraryViewModel: LibraryViewModel,
    onItemClick: (uri: String) -> Unit,
    onBack: () -> Unit,
) {
    val state by libraryViewModel.uiState.collectAsState()
    val context = LocalContext.current
    val firstItemFocus = remember { FocusRequester() }
    var firstItemFocusReady by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        libraryViewModel.loadHistoryItems()
    }

    LaunchedEffect(firstItemFocusReady) {
        if (firstItemFocusReady) {
            firstItemFocus.requestFocus()
        }
    }

    val displayItems = state.historyItems.take(state.historyDisplayLimit)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .focusProperties {
                enter = {
                    if (firstItemFocusReady && !isRestoringIndicatorFocus) firstItemFocus
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
                text = "History",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        if (displayItems.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No play history yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn {
                itemsIndexed(displayItems, key = { _, item -> item.uri }, contentType = { _, item ->
                    if (item is LibraryItem.Playlist) "playlist" else "album"
                }) { index, item ->
                    if (index == 0) {
                        DisposableEffect(Unit) {
                            firstItemFocusReady = true
                            onDispose { firstItemFocusReady = false }
                        }
                    }
                    val isPlaylist = item is LibraryItem.Playlist
                    Row(
                        modifier = (if (index == 0) Modifier.focusRequester(firstItemFocus) else Modifier)
                            .fillMaxWidth()
                            .focusHighlight()
                            .clickable { onItemClick(item.uri) }
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
                }

                if (state.hasMoreHistory) {
                    item(contentType = "load_more") {
                        TextButton(
                            onClick = { libraryViewModel.showMoreHistory() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusHighlight()
                                .padding(vertical = 8.dp),
                        ) {
                            Text("Load More...")
                        }
                    }
                }
            }
        }
    }
}
