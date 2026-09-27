package com.sidespot.ui

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import com.sidespot.viewmodel.RepeatMode
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.imageLoader
import coil.request.ImageRequest
import com.sidespot.viewmodel.PlayerViewModel

@Composable
fun NowPlayingScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    val positionMs by viewModel.positionMs.collectAsState()
    val queueState by viewModel.queueManager.state.collectAsState()

    val einkMode = LocalEinkMode.current

    // Album art background with controls overlaid
    var showControls by remember { mutableStateOf(true) }

    // Hide system navigation bar when viewing unblurred art
    val activity = LocalContext.current as? Activity
    // Use separate effects to avoid show/hide race: DisposableEffect's onDispose
    // fires before the new effect body on key change, so the async show() can
    // override the subsequent hide().
    DisposableEffect(showControls) {
        val window = activity?.window ?: return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (!showControls) {
            controller.hide(WindowInsetsCompat.Type.navigationBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.navigationBars())
        }
        onDispose { }
    }
    // Restore nav bars when leaving the Now Playing screen
    DisposableEffect(Unit) {
        onDispose {
            val window = activity?.window ?: return@onDispose
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.show(WindowInsetsCompat.Type.navigationBars())
        }
    }

    // Load album art bitmaps — blurred for background, sharp for full-screen view
    var blurredBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var sharpBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val context = LocalContext.current
    LaunchedEffect(state.albumArtUrl) {
        val url = state.albumArtUrl ?: return@LaunchedEffect
        val sharpResult = context.imageLoader.execute(
            ImageRequest.Builder(context)
                .data(url)
                .size(720)
                .allowHardware(false)
                .build(),
        )
        sharpResult.drawable?.toBitmap()?.let { sharpBitmap = it }
        if (!einkMode) {
            val blurredResult = context.imageLoader.execute(
                ImageRequest.Builder(context)
                    .data(url)
                    .size(720)
                    .allowHardware(false)
                    .transformations(listOf(BitmapBlurTransformation()))
                    .build(),
            )
            blurredResult.drawable?.toBitmap()?.let { blurredBitmap = it }
        }
    }

    // Resolve colors: use theme colors in e-ink mode, hardcoded white in normal mode
    val controlColor = if (einkMode) MaterialTheme.colorScheme.onBackground else Color.White
    val controlColorDim = if (einkMode) MaterialTheme.colorScheme.onSurfaceVariant
        else Color.White.copy(alpha = 0.7f)
    val controlColorFaint = if (einkMode) MaterialTheme.colorScheme.onSurfaceVariant
        else Color.White.copy(alpha = 0.5f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (einkMode) Modifier.background(MaterialTheme.colorScheme.background)
                else Modifier
            )
            // A touch-only target: as a focusable it would swallow D-pad focus
            // search, leaving the controls inside it unreachable.
            .focusProperties { canFocus = false }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { if (!einkMode) showControls = !showControls },
    ) {
        // Album art background
        if (einkMode) {
            sharpBitmap?.let { bitmap ->
                val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
                // E-ink: sharp art, no blur
                Image(
                    bitmap = imageBitmap,
                    contentDescription = "Album art",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                // Light scrim so black controls stay readable over art
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White.copy(alpha = 0.65f)),
                )
            }
        } else {
            blurredBitmap?.let { bitmap ->
                val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
                key(bitmap) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }

            // Album art sharp (visible when controls hidden)
            sharpBitmap?.let { bitmap ->
                val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
                AnimatedVisibility(
                    visible = !showControls,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = "Album art",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }

        if (!einkMode) {
            // Dark scrim gradient (only with controls)
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.4f),
                                    Color.Black.copy(alpha = 0.7f),
                                ),
                            ),
                        ),
                )
            }
        }

        // Controls overlay — always visible in e-ink mode, animated otherwise
        val controlsVisible = einkMode || showControls
        if (einkMode && controlsVisible) {
            NowPlayingControls(
                state = state,
                queueState = queueState,
                positionMs = positionMs,
                viewModel = viewModel,
                onBack = onBack,
                controlColor = controlColor,
                controlColorDim = controlColorDim,
                controlColorFaint = controlColorFaint,
                einkMode = true,
            )
        } else {
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                NowPlayingControls(
                    state = state,
                    queueState = queueState,
                    positionMs = positionMs,
                    viewModel = viewModel,
                    onBack = onBack,
                    controlColor = controlColor,
                    controlColorDim = controlColorDim,
                    controlColorFaint = controlColorFaint,
                    einkMode = false,
                )
            }
        }

        // Volume overlay — instant in e-ink mode, animated otherwise
        if (einkMode) {
            if (state.showVolumeOverlay) {
                VolumeBar(
                    volume = state.volume,
                    controlColor = controlColor,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 12.dp),
                )
            }
        } else {
            AnimatedVisibility(
                visible = state.showVolumeOverlay,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp),
            ) {
                VolumeBar(
                    volume = state.volume,
                    controlColor = controlColor,
                )
            }
        }
    }
}

@Composable
private fun NowPlayingControls(
    state: com.sidespot.viewmodel.PlayerUiState,
    queueState: com.sidespot.viewmodel.QueueState,
    positionMs: Long,
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
    controlColor: Color,
    controlColorDim: Color,
    controlColorFaint: Color,
    einkMode: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Back button + context name
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(40.dp).focusCircle(),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    modifier = Modifier.size(24.dp),
                    tint = controlColor,
                )
            }
            if (queueState.contextName.isNotEmpty()) {
                Text(
                    text = queueState.contextName,
                    style = MaterialTheme.typography.bodySmall,
                    color = controlColorFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
                // Invisible spacer to balance the icon and center the text
                Spacer(modifier = Modifier.size(40.dp))
            }
        }

        // Error display
        if (state.error != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = state.error!!,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Push everything else to the bottom
        Spacer(modifier = Modifier.weight(1f))

        // Track info
        if (state.albumName.isNotEmpty()) {
            Text(
                text = state.albumName,
                style = MaterialTheme.typography.bodyMedium,
                color = controlColorFaint,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
        }

        Text(
            text = state.trackTitle.ifEmpty { "No track loaded" },
            style = MaterialTheme.typography.titleLarge,
            color = controlColor,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = state.artistName.ifEmpty { "---" },
            style = MaterialTheme.typography.bodyLarge,
            color = controlColorDim,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Seek slider
        SeekBar(
            positionMs = positionMs,
            durationMs = state.durationMs,
            onSeek = { viewModel.seek(it) },
            controlColor = controlColor,
            controlColorDim = controlColorFaint,
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Transport controls
        TransportControls(
            isPlaying = state.isPlaying,
            isLoading = state.isLoading,
            shuffleEnabled = queueState.shuffleEnabled,
            repeatMode = queueState.repeatMode,
            isAutoplay = queueState.isAutoplay,
            onPlay = viewModel::play,
            onPause = viewModel::pause,
            onPrevious = viewModel::previous,
            onNext = viewModel::next,
            onToggleShuffle = viewModel::toggleShuffle,
            onCycleRepeat = viewModel::cycleRepeatMode,
            controlColor = controlColor,
            einkMode = einkMode,
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun VolumeBar(
    volume: Int,
    controlColor: Color,
    modifier: Modifier = Modifier,
) {
    val fraction = volume / 65535f
    Box(
        modifier = modifier
            .width(4.dp)
            .height(120.dp)
            .background(
                controlColor.copy(alpha = 0.3f),
                RoundedCornerShape(2.dp),
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight(fraction)
                .background(controlColor, RoundedCornerShape(2.dp)),
        )
    }
}

@Composable
private fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Int) -> Unit,
    controlColor: Color = Color.White,
    controlColorDim: Color = Color.White.copy(alpha = 0.6f),
) {
    var isSeeking by remember { mutableStateOf(false) }
    var seekFraction by remember { mutableFloatStateOf(0f) }

    val fraction = if (isSeeking) seekFraction
    else if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    else 0f

    val displayMs = if (isSeeking) (seekFraction * durationMs).toLong() else positionMs

    Column(modifier = Modifier.fillMaxWidth()) {
        // Custom track + thumb
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .pointerInput(durationMs) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            isSeeking = true
                            seekFraction = (offset.x / size.width).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            onSeek((seekFraction * durationMs).toInt())
                            isSeeking = false
                        },
                        onDragCancel = { isSeeking = false },
                        onHorizontalDrag = { _, dragAmount ->
                            seekFraction = (seekFraction + dragAmount / size.width)
                                .coerceIn(0f, 1f)
                        },
                    )
                }
                .pointerInput(durationMs) {
                    detectTapGestures { offset ->
                        val f = (offset.x / size.width).coerceIn(0f, 1f)
                        onSeek((f * durationMs).toInt())
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            // Track background
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(controlColor.copy(alpha = 0.3f), RoundedCornerShape(1.5.dp)),
            )
            // Track progress
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(3.dp)
                    .background(controlColor, RoundedCornerShape(1.5.dp)),
            )
            // Thumb — thin vertical line at progress position, offset-based
            val thumbOffset = (maxWidth * fraction - 2.dp).coerceAtLeast(0.dp)
            Box(
                modifier = Modifier
                    .padding(start = thumbOffset)
                    .width(4.dp)
                    .height(18.dp)
                    .background(controlColor, RoundedCornerShape(2.dp)),
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatTime(displayMs),
                style = MaterialTheme.typography.bodySmall,
                color = controlColorDim,
            )
            Text(
                text = formatTime(durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = controlColorDim,
            )
        }
    }
}

@Composable
private fun TransportControls(
    isPlaying: Boolean,
    isLoading: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    isAutoplay: Boolean = false,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    controlColor: Color = Color.White,
    einkMode: Boolean = false,
) {
    // In e-ink mode the play/pause button uses theme colors instead of white/black
    val buttonBg = if (einkMode) controlColor else Color.White
    val buttonFg = if (einkMode) MaterialTheme.colorScheme.onPrimary else Color.Black

    // On D-pad keypads, start focus on play/pause so the centre key toggles
    // playback and the arrows reach the other controls.  The Sundial's keys all
    // have fixed roles on Now Playing, so focus stays on the screen underneath.
    val playPauseFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (Keypad.isSundial) return@LaunchedEffect
        try {
            playPauseFocus.requestFocus()
        } catch (_: IllegalStateException) {
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Shuffle (hidden during autoplay)
        if (!isAutoplay) {
            IconButton(
                onClick = onToggleShuffle,
                modifier = Modifier.size(48.dp).focusCircle(),
            ) {
                Icon(
                    imageVector = Icons.Default.Shuffle,
                    contentDescription = "Shuffle",
                    modifier = Modifier.size(24.dp),
                    tint = if (shuffleEnabled) controlColor
                    else controlColor.copy(alpha = 0.4f),
                )
            }
        } else {
            Spacer(modifier = Modifier.size(48.dp))
        }

        // Previous
        IconButton(
            onClick = onPrevious,
            modifier = Modifier.size(56.dp).focusCircle(),
        ) {
            Icon(
                imageVector = Icons.Default.SkipPrevious,
                contentDescription = "Previous",
                modifier = Modifier.size(36.dp),
                tint = controlColor,
            )
        }

        // Play/Pause/Loading
        IconButton(
            onClick = if (isPlaying) onPause else onPlay,
            modifier = Modifier
                .size(64.dp)
                .background(
                    color = buttonBg,
                    shape = CircleShape,
                )
                .focusRequester(playPauseFocus)
                .focusCircle(),
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 3.dp,
                    color = buttonFg,
                )
            } else {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(40.dp),
                    tint = buttonFg,
                )
            }
        }

        // Next
        IconButton(
            onClick = onNext,
            modifier = Modifier.size(56.dp).focusCircle(),
        ) {
            Icon(
                imageVector = Icons.Default.SkipNext,
                contentDescription = "Next",
                modifier = Modifier.size(36.dp),
                tint = controlColor,
            )
        }

        // Repeat
        IconButton(
            onClick = onCycleRepeat,
            modifier = Modifier.size(48.dp).focusCircle(),
        ) {
            Icon(
                imageVector = when (repeatMode) {
                    RepeatMode.ONE -> Icons.Default.RepeatOne
                    else -> Icons.Default.Repeat
                },
                contentDescription = "Repeat",
                modifier = Modifier.size(24.dp),
                tint = if (repeatMode != RepeatMode.OFF) controlColor
                else controlColor.copy(alpha = 0.4f),
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
