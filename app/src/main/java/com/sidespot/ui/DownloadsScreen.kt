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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.sidespot.offline.DownloadManager
import com.sidespot.offline.DownloadStatus
import com.sidespot.offline.DownloadedCollection
import com.sidespot.offline.DownloadsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onCollectionClick: (uri: String) -> Unit,
    onBack: () -> Unit,
) {
    val downloadManager = remember { DownloadManager.get() }
    val state by downloadManager.state.collectAsState()
    var selectedUri by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { downloadManager.refreshStorageUsed() }

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
                text = "Downloads",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        Text(
            text = downloadStatusText(state),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 8.dp),
        )

        LazyColumn {
            downloadedCollectionItems(
                state = state,
                onClick = onCollectionClick,
                onLongClick = { selectedUri = it },
            )
        }
    }

    val selected = selectedUri?.let { state.collection(it) }
    if (selected != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedUri = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier.dismissOnDpad { selectedUri = null },
        ) {
            Column(modifier = Modifier.navigationBarsPadding().padding(16.dp)) {
                val remove = {
                    downloadManager.removeCollection(selected.uri)
                    selectedUri = null
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusHighlight(onEnterKey = remove)
                        .clickable(onClick = remove)
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
                        text = "Remove Download",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/** One line summarising what the download queue is doing and the space used. */
internal fun downloadStatusText(state: DownloadsState): String {
    val left = if (state.remaining == 1) "1 track left" else "${state.remaining} tracks left"
    val progress = when (state.status) {
        DownloadStatus.DOWNLOADING -> "Downloading, $left"
        DownloadStatus.WAITING_FOR_WIFI -> "Waiting for Wi-Fi, $left"
        DownloadStatus.WAITING_FOR_CONNECTION ->
            if (state.remaining > 0) "Waiting for a connection, $left" else null
        DownloadStatus.RETRYING -> "Downloads failed, retrying soon, $left"
        DownloadStatus.IDLE -> null
    }
    val used = "${formatBytes(state.storageBytes)} used"
    return if (progress != null) "$progress · $used" else used
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    else -> "%.0f KB".format(bytes / 1024.0)
}

/** Rows for every downloaded collection, most recently added first. */
internal fun LazyListScope.downloadedCollectionItems(
    state: DownloadsState,
    onClick: (uri: String) -> Unit,
    onLongClick: ((uri: String) -> Unit)? = null,
) {
    val collections = state.collections.sortedByDescending { it.addedAtMs }
    if (collections.isEmpty()) {
        item(contentType = "empty") {
            Text(
                text = "Nothing downloaded yet. Open an album or playlist and tap Download " +
                    "to listen without a connection.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }
        return
    }
    items(collections, key = { it.uri }, contentType = { "downloaded_collection" }) { collection ->
        DownloadedCollectionRow(
            collection = collection,
            state = state,
            onClick = { onClick(collection.uri) },
            onLongClick = onLongClick?.let { { it(collection.uri) } },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadedCollectionRow(
    collection: DownloadedCollection,
    state: DownloadsState,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val context = LocalContext.current
    val (done, total) = state.progress(collection)
    val isEpisodes = collection.uri == DownloadManager.EPISODES_URI
    val isAlbum = collection.uri.startsWith("spotify:album:")

    // Albums show the art saved with their tracks, which works offline.
    val firstDownloaded = collection.trackUris.firstOrNull { it in state.downloaded }
    val artUrl by produceState(collection.imageUrl, firstDownloaded) {
        if (isAlbum && firstDownloaded != null) {
            value = withContext(Dispatchers.IO) {
                DownloadManager.get().trackInfos(listOf(firstDownloaded))
                    .firstOrNull()?.albumArtUrl
            } ?: collection.imageUrl
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusHighlight(onEnterKey = onLongClick)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isAlbum && artUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(artUrl).size(96).build(),
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            val icon: ImageVector = when {
                isEpisodes -> Icons.Default.Podcasts
                collection.uri == DownloadManager.LIKED_SONGS_URI -> Icons.Default.Favorite
                isAlbum -> Icons.Default.Album
                else -> Icons.Default.QueueMusic
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = collection.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val unit = if (isEpisodes) "episode" else "track"
            val counts = if (done < total) "$done of $total ${unit}s downloaded"
                else "$total ${if (total == 1) unit else "${unit}s"}"
            Text(
                text = listOf(collection.subtitle, counts).filter { it.isNotEmpty() }
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
