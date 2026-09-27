package com.sidespot.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sidespot.offline.DownloadManager
import kotlinx.coroutines.delay

/** Long-press actions for a podcast episode. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeActionsSheet(
    episodeUri: String,
    onAddToQueue: () -> Unit,
    onDismiss: () -> Unit,
) {
    val downloadManager = remember { DownloadManager.get() }
    val downloads by downloadManager.state.collectAsState()
    val isDownloadWanted = downloads.collection(DownloadManager.EPISODES_URI)
        ?.trackUris?.contains(episodeUri) == true
    var feedbackText by remember { mutableStateOf<String?>(null) }

    if (feedbackText != null) {
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
        Column(modifier = Modifier.navigationBarsPadding().padding(16.dp)) {
            if (feedbackText != null) {
                Text(
                    text = feedbackText!!,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(32.dp))
            } else {
                SheetActionRow(Icons.Default.QueueMusic, "Add to Queue") {
                    onAddToQueue()
                    feedbackText = "Added to Queue"
                }
                if (isDownloadWanted) {
                    SheetActionRow(Icons.Default.RemoveCircleOutline, "Remove Download") {
                        downloadManager.removeEpisode(episodeUri)
                        feedbackText = "Download Removed"
                    }
                } else {
                    SheetActionRow(Icons.Default.Download, "Download Episode") {
                        downloadManager.addEpisode(episodeUri)
                        feedbackText = "Downloading"
                    }
                }
            }
        }
    }
}

/** Marks an episode or track row as available offline. */
@Composable
internal fun DownloadedBadge() {
    Icon(
        imageVector = Icons.Default.DownloadDone,
        contentDescription = "Downloaded",
        modifier = Modifier.size(14.dp),
        tint = MaterialTheme.colorScheme.primary,
    )
    Spacer(modifier = Modifier.width(6.dp))
}
