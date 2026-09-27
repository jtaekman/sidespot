package com.sidespot.ui

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sidespot.api.ApiResult
import com.sidespot.bridge.EpisodeSummary
import com.sidespot.offline.DownloadManager
import com.sidespot.viewmodel.LibraryViewModel
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import com.sidespot.viewmodel.PlayerViewModel

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun ShowDetailScreen(
    showUri: String,
    showName: String,
    libraryViewModel: LibraryViewModel,
    playerViewModel: PlayerViewModel,
    onBack: () -> Unit,
) {
    val state by libraryViewModel.uiState.collectAsState()
    var saveShowFeedback by remember { mutableStateOf<String?>(null) }
    var selectedEpisodeUri by remember { mutableStateOf<String?>(null) }
    val downloads by remember { DownloadManager.get() }.state.collectAsState()
    val firstContentFocus = remember { FocusRequester() }
    var firstContentFocusReady by remember { mutableStateOf(false) }
    val isShowSaved = remember(state.shows, showUri) {
        state.shows.any { it.uri == showUri }
    }

    LaunchedEffect(showUri) {
        libraryViewModel.loadShowEpisodes(showUri)
    }

    // Load saved shows if not yet loaded so we can derive saved status
    LaunchedEffect(Unit) {
        if (state.shows.isEmpty() && !state.isLoadingShows) {
            libraryViewModel.loadSavedShows()
        }
    }

    // Reset feedback text when saved status changes
    LaunchedEffect(isShowSaved) {
        saveShowFeedback = null
    }

    LaunchedEffect(firstContentFocusReady) {
        if (firstContentFocusReady) {
            firstContentFocus.requestFocus()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .focusProperties {
                enter = {
                    if (firstContentFocusReady && !isRestoringIndicatorFocus) firstContentFocus
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
                text = showName,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Save Show button (hidden if already saved)
        if (!isShowSaved) {
            DisposableEffect(Unit) {
                firstContentFocusReady = true
                onDispose { firstContentFocusReady = false }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    libraryViewModel.saveShow(showUri) { result ->
                        saveShowFeedback = when (result) {
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
                    .focusRequester(firstContentFocus)
                    .focusDarken(),
            ) {
                Icon(
                    imageVector = Icons.Default.LibraryAdd,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(saveShowFeedback ?: "Save Show")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (state.isLoadingEpisodes && state.episodes.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else {
            LazyColumn {
                itemsIndexed(state.episodes, key = { _, episode -> episode.uri }, contentType = { _, _ -> "episode" }) { index, episode ->
                    if (index == 0 && isShowSaved) {
                        DisposableEffect(Unit) {
                            firstContentFocusReady = true
                            onDispose { firstContentFocusReady = false }
                        }
                    }
                    EpisodeRow(
                        episode = episode,
                        isDownloaded = episode.uri in downloads.downloaded,
                        modifier = if (index == 0 && isShowSaved) Modifier.focusRequester(firstContentFocus) else Modifier,
                        onClick = {
                            playerViewModel.cacheEpisodeMetadata(state.episodes, showName)
                            val episodeUris = state.episodes.map { it.uri }
                            playerViewModel.loadTrackFromContext(
                                episodeUris, index, showName, contextUri = showUri,
                                contextImageUrl = episode.imageUrl,
                            )
                        },
                        onLongClick = {
                            playerViewModel.cacheEpisodeMetadata(listOf(episode), showName)
                            selectedEpisodeUri = episode.uri
                        },
                    )
                }
            }
        }
    }

    selectedEpisodeUri?.let { episodeUri ->
        EpisodeActionsSheet(
            episodeUri = episodeUri,
            onAddToQueue = { playerViewModel.addToQueue(episodeUri) },
            onDismiss = { selectedEpisodeUri = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRow(
    episode: EpisodeSummary,
    isDownloaded: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .focusHighlight(onEnterKey = onLongClick)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(vertical = 10.dp),
    ) {
        Text(
            text = episode.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (episode.description.isNotBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = episode.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isDownloaded) DownloadedBadge()
            Text(
                text = formatEpisodeDuration(episode.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (episode.releaseDate.isNotBlank()) {
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = episode.releaseDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal fun formatEpisodeDuration(ms: Int): String {
    val totalMinutes = ms / 60000
    return if (totalMinutes >= 60) {
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        "${hours}h ${minutes}m"
    } else {
        "${totalMinutes} min"
    }
}
