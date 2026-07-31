package com.sidespot.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.sidespot.api.ApiResult
import com.sidespot.bridge.SavedArtist
import com.sidespot.viewmodel.LibraryViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun SavedArtistsScreen(
    libraryViewModel: LibraryViewModel,
    onArtistClick: (uri: String) -> Unit,
    onBack: () -> Unit,
) {
    val state by libraryViewModel.uiState.collectAsState()
    var selectedArtistUri by remember { mutableStateOf<String?>(null) }
    var feedbackText by remember { mutableStateOf<String?>(null) }
    val firstArtistFocus = remember { FocusRequester() }
    var firstArtistFocusReady by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        libraryViewModel.loadFollowedArtists()
    }

    LaunchedEffect(firstArtistFocusReady) {
        if (firstArtistFocusReady) {
            // The row can be detached between the flag flipping and this running.
            try {
                firstArtistFocus.requestFocus()
            } catch (_: IllegalStateException) {
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .focusProperties {
                enter = {
                    if (firstArtistFocusReady) firstArtistFocus
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
                text = "Artists",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        if (state.isLoadingArtists && state.artists.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else {
            LazyColumn {
                itemsIndexed(state.artists, key = { _, artist -> artist.uri }, contentType = { _, _ -> "artist" }) { index, artist ->
                    if (index == 0) {
                        DisposableEffect(Unit) {
                            firstArtistFocusReady = true
                            onDispose { firstArtistFocusReady = false }
                        }
                    }
                    ArtistRow(
                        artist = artist,
                        modifier = if (index == 0) Modifier.focusRequester(firstArtistFocus) else Modifier,
                        onClick = { onArtistClick(artist.uri) },
                        onLongClick = { selectedArtistUri = artist.uri },
                    )
                }
            }
        }
    }

    // Bottom sheet for long-press actions
    if (selectedArtistUri != null) {
        if (feedbackText != null) {
            LaunchedEffect(feedbackText) {
                delay(1000)
                feedbackText = null
                selectedArtistUri = null
            }
        }

        ModalBottomSheet(
            onDismissRequest = { selectedArtistUri = null; feedbackText = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier.dismissOnDpad { selectedArtistUri = null; feedbackText = null },
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
                    val unfollow = {
                        // Removing the focused row makes the focus system fall back to
                        // this screen's `enter` requester while it is briefly detached,
                        // which throws.  Disarm it until a row re-attaches.
                        firstArtistFocusReady = false
                        libraryViewModel.unfollowArtist(selectedArtistUri!!) { result ->
                            feedbackText = when (result) {
                                is ApiResult.Success -> "Unfollowed"
                                is ApiResult.Error -> "Error: ${result.message}"
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusHighlight(onEnterKey = unfollow)
                            .clickable(onClick = unfollow)
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
                            text = "Unfollow",
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
private fun ArtistRow(
    artist: SavedArtist,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .focusHighlight(onEnterKey = onLongClick)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
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
