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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sidespot.bridge.EpisodeSummary
import com.sidespot.offline.DownloadManager
import com.sidespot.viewmodel.LibraryViewModel
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.focusProperties
import com.sidespot.viewmodel.PlayerViewModel

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun NewEpisodesScreen(
    libraryViewModel: LibraryViewModel,
    playerViewModel: PlayerViewModel,
    onBack: () -> Unit,
) {
    val state by libraryViewModel.uiState.collectAsState()
    var selectedEpisodeUri by remember { mutableStateOf<String?>(null) }
    val downloads by remember { DownloadManager.get() }.state.collectAsState()

    LaunchedEffect(Unit) {
        libraryViewModel.loadNewEpisodes()
    }

    val displayedEpisodes = remember(state.newEpisodes, state.newEpisodesDisplayLimit) {
        state.newEpisodes.take(state.newEpisodesDisplayLimit)
    }
    var focusTargetIndex by remember { mutableIntStateOf(-1) }
    val focusRequester = remember { FocusRequester() }
    val firstEpisodeFocus = remember { FocusRequester() }
    var firstEpisodeFocusReady by remember { mutableStateOf(false) }

    LaunchedEffect(firstEpisodeFocusReady) {
        if (firstEpisodeFocusReady) {
            firstEpisodeFocus.requestFocus()
        }
    }

    LaunchedEffect(focusTargetIndex) {
        if (focusTargetIndex >= 0) {
            focusRequester.requestFocus()
            focusTargetIndex = -1
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .focusProperties {
                enter = {
                    if (firstEpisodeFocusReady && !isRestoringIndicatorFocus) firstEpisodeFocus
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
                text = "New Episodes",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        if (state.isLoadingNewEpisodes && state.newEpisodes.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else if (!state.isLoadingNewEpisodes && state.newEpisodes.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No New Episodes",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn {
                itemsIndexed(displayedEpisodes, key = { _, ep -> ep.uri }, contentType = { _, _ -> "episode" }) { index, episode ->
                    if (index == 0) {
                        DisposableEffect(Unit) {
                            firstEpisodeFocusReady = true
                            onDispose { firstEpisodeFocusReady = false }
                        }
                    }
                    val baseModifier = if (index == 0)
                        Modifier.focusRequester(firstEpisodeFocus) else Modifier
                    val rowModifier = if (index == focusTargetIndex)
                        baseModifier.focusRequester(focusRequester) else baseModifier
                    NewEpisodeRow(
                        episode = episode,
                        isDownloaded = episode.uri in downloads.downloaded,
                        modifier = rowModifier,
                        onClick = {
                            playerViewModel.cacheEpisodeMetadata(
                                state.newEpisodes,
                                episode.showName ?: "Podcast",
                            )
                            val episodeUris = state.newEpisodes.map { it.uri }
                            playerViewModel.loadTrackFromContext(
                                episodeUris, index, "New Episodes",
                            )
                        },
                        onLongClick = {
                            playerViewModel.cacheEpisodeMetadata(
                                listOf(episode),
                                episode.showName ?: "Podcast",
                            )
                            selectedEpisodeUri = episode.uri
                        },
                    )
                }

                if (state.hasMoreNewEpisodes) {
                    item(contentType = "show_more") {
                        Text(
                            text = "Show More...",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    focusTargetIndex = displayedEpisodes.lastIndex
                                    libraryViewModel.showMoreNewEpisodes()
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
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
private fun NewEpisodeRow(
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
        if (!episode.showName.isNullOrBlank()) {
            Text(
                text = episode.showName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
        }
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
