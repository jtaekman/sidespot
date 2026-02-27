package com.sidespot.viewmodel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.ImageLoader
import coil.request.ImageRequest
import com.sidespot.api.ApiResult
import com.sidespot.api.CreatePlaylistResult
import com.sidespot.api.SpotifyWebApi
import com.sidespot.auth.AuthManager
import com.sidespot.audio.AudioCallback
import com.sidespot.audio.AudioFocusManager
import com.sidespot.bridge.NativeBridge
import com.sidespot.bridge.PlayerEvent
import com.sidespot.bridge.TrackInfo
import com.sidespot.service.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlayerUiState(
    val isConnected: Boolean = false,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val trackUri: String = "",
    val trackTitle: String = "",
    val artistName: String = "",
    val albumName: String = "",
    val albumArtUrl: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val error: String? = null,
    val connectionStatus: String = "Disconnected",
    val volume: Int = 32768,
    val showVolumeOverlay: Boolean = false,
)

class PlayerViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    val queueManager = QueueManager()

    private val audioCallback = AudioCallback()
    private var eventPollingActive = false
    private var webApi: SpotifyWebApi? = null

    private var appContext: Context? = null
    private var audioFocusManager: AudioFocusManager? = null
    private var mediaCommandReceiver: BroadcastReceiver? = null
    private var savedVolumeBeforeDuck: Int? = null
    private var connectMode: Boolean = false

    /**
     * Initialize platform services. Called from MainActivity after ViewModel creation.
     * Uses application context to avoid activity leak.
     */
    fun initPlatform(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext

        audioFocusManager = AudioFocusManager(context.applicationContext).apply {
            listener = object : AudioFocusManager.Listener {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onStop() = stop()
                override fun onDuck() {
                    savedVolumeBeforeDuck = NativeBridge.playerGetVolume()
                    val ducked = (savedVolumeBeforeDuck!! * 0.3).toInt()
                    NativeBridge.playerSetVolume(ducked)
                }
                override fun onUnduck() {
                    savedVolumeBeforeDuck?.let { NativeBridge.playerSetVolume(it) }
                    savedVolumeBeforeDuck = null
                }
            }
        }

        // Register broadcast receiver for media session commands from PlaybackService
        mediaCommandReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.getStringExtra("command")) {
                    "play" -> play()
                    "pause" -> pause()
                    "next" -> next()
                    "previous" -> previous()
                    "stop" -> stop()
                    "seek" -> {
                        val pos = intent.getLongExtra("position", 0)
                        seek(pos.toInt())
                    }
                }
            }
        }
        context.applicationContext.registerReceiver(
            mediaCommandReceiver,
            IntentFilter("com.sidespot.MEDIA_COMMAND"),
            Context.RECEIVER_NOT_EXPORTED,
        )
    }

    fun connect(accessToken: String, connectEnabled: Boolean = false, deviceName: String = "Sidespot") {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(connectionStatus = "Connecting...", error = null) }

            NativeBridge.registerAudioCallback(audioCallback)

            if (connectEnabled) {
                // Connect mode: session + player + Spirc created in one step
                val error = NativeBridge.connectStart(accessToken, deviceName)
                if (error != null) {
                    _uiState.update {
                        it.copy(
                            connectionStatus = "Connect failed",
                            error = error,
                            isConnected = false,
                        )
                    }
                    return@launch
                }
                connectMode = true
                _uiState.update {
                    it.copy(connectionStatus = "Ready (Connect)", isConnected = true, error = null)
                }
            } else {
                // Direct mode: session + player created separately
                val error = NativeBridge.sessionConnect(accessToken)
                if (error != null) {
                    _uiState.update {
                        it.copy(
                            connectionStatus = "Connection failed",
                            error = error,
                            isConnected = false,
                        )
                    }
                    return@launch
                }

                _uiState.update {
                    it.copy(connectionStatus = "Connected", isConnected = true, error = null)
                }

                val playerError = NativeBridge.playerCreate()
                if (playerError != null) {
                    _uiState.update {
                        it.copy(
                            connectionStatus = "Player creation failed",
                            error = playerError,
                        )
                    }
                    return@launch
                }
            }

            val vol = NativeBridge.playerGetVolume()
            _uiState.update { it.copy(connectionStatus = if (connectMode) "Ready (Connect)" else "Ready", volume = vol) }

            startEventPolling()
        }
    }

    fun loadTrack(uri: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val cached = queueManager.state.value.trackMetadata[uri]
            _uiState.update {
                it.copy(
                    isLoading = true,
                    trackUri = uri,
                    trackTitle = cached?.name ?: uri.substringAfterLast(":"),
                    artistName = cached?.artistName ?: "",
                    albumName = cached?.albumName ?: "",
                    albumArtUrl = cached?.albumArtUrl ?: it.albumArtUrl,
                    durationMs = cached?.durationMs?.toLong() ?: it.durationMs,
                    error = null,
                )
            }

            // Request audio focus before playing
            audioFocusManager?.requestFocus()

            val error = if (connectMode) {
                // In Connect mode, load single track via Spirc
                val json = org.json.JSONArray(listOf(uri)).toString()
                NativeBridge.connectLoadTracks(json, true, 0)
            } else {
                NativeBridge.playerLoad(uri, true)
            }
            if (error != null) {
                // Track unavailable — skip to next
                val nextUri = queueManager.next()
                if (nextUri != null) {
                    loadTrack(nextUri)
                }
                return@launch
            }

            // Start foreground service
            appContext?.let { PlaybackService.startService(it) }

            // Fetch metadata
            fetchAndApplyMetadata(uri)
        }
    }

    fun loadTrackFromContext(
        tracks: List<String>,
        index: Int,
        contextName: String = "",
        contextUri: String? = null,
    ) {
        if (connectMode && contextUri != null) {
            // In Connect mode, load via Spirc so remote clients see the context
            val error = NativeBridge.connectLoadContext(contextUri, true, index)
            if (error != null) {
                // Fallback: load as track list
                val json = org.json.JSONArray(tracks).toString()
                NativeBridge.connectLoadTracks(json, true, index)
            }
            // Still update local queue state for UI
            queueManager.loadContext(tracks, index, contextName)
            val uri = tracks.getOrNull(index) ?: return
            viewModelScope.launch(Dispatchers.IO) { fetchAndApplyMetadata(uri) }
            return
        } else if (connectMode) {
            // Connect mode but no context URI — load as track list
            val json = org.json.JSONArray(tracks).toString()
            NativeBridge.connectLoadTracks(json, true, index)
            queueManager.loadContext(tracks, index, contextName)
            val uri = tracks.getOrNull(index) ?: return
            viewModelScope.launch(Dispatchers.IO) { fetchAndApplyMetadata(uri) }
            return
        }

        queueManager.loadContext(tracks, index, contextName)
        val uri = tracks.getOrNull(index) ?: return
        loadTrack(uri)
        // Preload metadata + art + audio for upcoming tracks
        viewModelScope.launch(Dispatchers.IO) {
            tracks.drop(index + 1).take(5).forEach { nextUri ->
                resolveAndCacheMetadata(nextUri)
            }
            val cached = queueManager.state.value.trackMetadata
            val artUrls = tracks.drop(index).take(5).mapNotNull { cached[it]?.albumArtUrl }
            preloadAlbumArt(artUrls)

            // Preload audio for the very next track
            tracks.getOrNull(index + 1)?.let { nextUri ->
                NativeBridge.playerPreload(nextUri)
            }
        }
    }

    fun play() {
        viewModelScope.launch(Dispatchers.IO) {
            audioFocusManager?.requestFocus()
            NativeBridge.playerPlay()
        }
    }

    fun pause() {
        viewModelScope.launch(Dispatchers.IO) { NativeBridge.playerPause() }
    }

    fun seek(positionMs: Int) {
        viewModelScope.launch(Dispatchers.IO) { NativeBridge.playerSeek(positionMs) }
    }

    fun stop() {
        viewModelScope.launch(Dispatchers.IO) {
            NativeBridge.playerStop()
            audioFocusManager?.abandonFocus()
            appContext?.let { PlaybackService.stopService(it) }
        }
    }

    fun next() {
        if (connectMode) {
            // Spirc manages queue state; SetQueue events will sync UI
            viewModelScope.launch(Dispatchers.IO) { NativeBridge.playerNext() }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val nextUri = queueManager.next() ?: return@launch
            loadTrack(nextUri)
            preloadUpcoming()
        }
    }

    fun previous() {
        if (connectMode) {
            // Spirc handles prev (restart if >3s, else previous track)
            viewModelScope.launch(Dispatchers.IO) { NativeBridge.playerPrev() }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val state = _uiState.value
            if (state.positionMs > 3000) {
                NativeBridge.playerSeek(0)
                return@launch
            }
            val prevUri = queueManager.previous() ?: return@launch
            loadTrack(prevUri)
            preloadUpcoming()
        }
    }

    fun skipToQueueItem(isUserQueue: Boolean, index: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val uri = if (isUserQueue) {
                queueManager.playFromUserQueue(index)
            } else {
                queueManager.playFromContext(index)
            } ?: return@launch
            loadTrack(uri)
            preloadUpcoming()
        }
    }

    fun addToQueue(uri: String) {
        queueManager.addToQueue(uri)
        // Also resolve metadata for the queued track
        viewModelScope.launch(Dispatchers.IO) {
            resolveAndCacheMetadata(uri)
        }
    }

    /**
     * Recreate the native player with updated config, preserving playback state.
     * Called after SettingsManager.applyAudioSettings().
     */
    fun recreatePlayer() {
        viewModelScope.launch(Dispatchers.IO) {
            val state = _uiState.value
            val savedUri = state.trackUri
            val savedPosition = state.positionMs
            val wasPlaying = state.isPlaying

            val error = NativeBridge.playerRecreate()
            if (error != null) {
                _uiState.update { it.copy(error = "Player recreate failed: $error") }
                return@launch
            }

            // Resume playback if a track was loaded
            if (savedUri.isNotEmpty()) {
                NativeBridge.playerLoad(savedUri, wasPlaying)
                if (savedPosition > 0) {
                    // Add 1s offset to account for propagation delay
                    NativeBridge.playerSeek((savedPosition + 1000).toInt())
                }
            }
        }
    }

    fun initApi(authManager: AuthManager) {
        if (webApi == null) {
            webApi = SpotifyWebApi(authManager)
        }
    }

    fun addToLikedSongs(trackUri: String, onResult: (ApiResult) -> Unit) {
        val api = webApi ?: run {
            onResult(ApiResult.Error("API not initialized"))
            return
        }
        viewModelScope.launch {
            val result = api.addToLikedSongs(trackUri)
            onResult(result)
        }
    }

    fun addToPlaylist(playlistUri: String, trackUri: String, onResult: (ApiResult) -> Unit) {
        val api = webApi ?: run {
            onResult(ApiResult.Error("API not initialized"))
            return
        }
        viewModelScope.launch {
            val result = api.addToPlaylist(playlistUri, trackUri)
            onResult(result)
        }
    }

    fun createPlaylistAndAddTrack(
        name: String,
        trackUri: String,
        onResult: (ApiResult) -> Unit,
    ) {
        val api = webApi ?: run {
            onResult(ApiResult.Error("API not initialized"))
            return
        }
        viewModelScope.launch {
            when (val createResult = api.createPlaylist(name)) {
                is CreatePlaylistResult.Success -> {
                    val addResult = api.addToPlaylist(createResult.playlistUri, trackUri)
                    onResult(addResult)
                }
                is CreatePlaylistResult.Error -> {
                    onResult(ApiResult.Error(createResult.message))
                }
            }
        }
    }

    fun toggleShuffle() {
        queueManager.toggleShuffle()
    }

    fun cycleRepeatMode() {
        queueManager.cycleRepeatMode()
    }

    fun resolveQueueMetadata() {
        viewModelScope.launch(Dispatchers.IO) {
            val queueState = queueManager.state.value
            val urisToResolve = mutableListOf<String>()
            urisToResolve.addAll(queueState.userQueue)
            urisToResolve.addAll(
                queueState.contextTracks.drop(queueState.contextIndex + 1).take(20),
            )

            val cached = queueState.trackMetadata
            val artUrlsToPreload = mutableListOf<String>()
            for (uri in urisToResolve) {
                if (uri !in cached) {
                    resolveAndCacheMetadata(uri)
                }
            }

            // Preload album art for the next few tracks
            val updated = queueManager.state.value.trackMetadata
            urisToResolve.take(5).forEach { uri ->
                updated[uri]?.albumArtUrl?.let { artUrlsToPreload.add(it) }
            }
            preloadAlbumArt(artUrlsToPreload)
        }
    }

    private suspend fun preloadUpcoming() {
        val qState = queueManager.state.value
        val upcoming = qState.userQueue.ifEmpty {
            qState.contextTracks.drop(qState.contextIndex + 1)
        }.take(3)
        val cached = qState.trackMetadata
        val artUrls = mutableListOf<String>()
        for (uri in upcoming) {
            if (uri !in cached) {
                resolveAndCacheMetadata(uri)
            }
            queueManager.state.value.trackMetadata[uri]?.albumArtUrl?.let { artUrls.add(it) }
        }
        preloadAlbumArt(artUrls)

        // Preload audio for the very next track
        upcoming.firstOrNull()?.let { nextUri ->
            NativeBridge.playerPreload(nextUri)
        }
    }

    private suspend fun resolveAndCacheMetadata(uri: String) {
        val json = NativeBridge.metadataGetTrack(uri) ?: return
        val info = TrackInfo.fromJson(json) ?: return
        queueManager.cacheMetadata(uri, info)
    }

    private fun preloadAlbumArt(urls: List<String>) {
        val ctx = appContext ?: return
        val loader = ImageLoader(ctx)
        for (url in urls) {
            loader.enqueue(
                ImageRequest.Builder(ctx)
                    .data(url)
                    .build(),
            )
        }
    }

    fun onVolumeChanged(volume: Int) {
        _uiState.update { it.copy(volume = volume, showVolumeOverlay = true) }
        viewModelScope.launch {
            delay(1500)
            _uiState.update { it.copy(showVolumeOverlay = false) }
        }
    }

    private suspend fun fetchAndApplyMetadata(uri: String) {
        val json = NativeBridge.metadataGetTrack(uri) ?: return
        val trackInfo = TrackInfo.fromJson(json) ?: return
        _uiState.update {
            it.copy(
                trackTitle = trackInfo.name,
                artistName = trackInfo.artistName,
                albumName = trackInfo.albumName,
                albumArtUrl = trackInfo.albumArtUrl,
                durationMs = trackInfo.durationMs.toLong(),
            )
        }
        queueManager.cacheMetadata(uri, trackInfo)
        updatePlaybackService()
    }

    private fun updatePlaybackService() {
        val state = _uiState.value
        appContext?.let { ctx ->
            PlaybackService.updateMetadata(
                context = ctx,
                title = state.trackTitle,
                artist = state.artistName,
                artUrl = state.albumArtUrl,
                isPlaying = state.isPlaying,
                positionMs = state.positionMs,
                durationMs = state.durationMs,
            )
        }
    }

    private fun startEventPolling() {
        if (eventPollingActive) return
        eventPollingActive = true

        viewModelScope.launch(Dispatchers.IO) {
            while (eventPollingActive) {
                val json = NativeBridge.playerPollEvent()
                if (json != null) {
                    val event = PlayerEvent.fromJson(json)
                    if (event != null) {
                        handlePlayerEvent(event)
                    }
                }
                delay(50)
            }
        }
    }

    private fun handlePlayerEvent(event: PlayerEvent) {
        when (event) {
            is PlayerEvent.Playing -> {
                val currentTrackUri = _uiState.value.trackUri
                _uiState.update {
                    it.copy(
                        isPlaying = true,
                        isLoading = false,
                        positionMs = event.positionMs.toLong(),
                    )
                }
                // In Connect mode, if track changed remotely, fetch new metadata
                if (connectMode && event.trackId != currentTrackUri && event.trackId.isNotEmpty()) {
                    _uiState.update { it.copy(trackUri = event.trackId) }
                    viewModelScope.launch(Dispatchers.IO) { fetchAndApplyMetadata(event.trackId) }
                }
                updatePlaybackService()
            }
            is PlayerEvent.Paused -> {
                _uiState.update {
                    it.copy(
                        isPlaying = false,
                        positionMs = event.positionMs.toLong(),
                    )
                }
                updatePlaybackService()
            }
            is PlayerEvent.Stopped -> {
                _uiState.update {
                    it.copy(isPlaying = false, positionMs = 0L)
                }
                updatePlaybackService()
            }
            is PlayerEvent.Loading -> {
                _uiState.update {
                    it.copy(isLoading = true)
                }
                // In Connect mode, if the loading track differs, update URI and fetch metadata
                if (connectMode && event.trackId != _uiState.value.trackUri && event.trackId.isNotEmpty()) {
                    _uiState.update { it.copy(trackUri = event.trackId) }
                    viewModelScope.launch(Dispatchers.IO) { fetchAndApplyMetadata(event.trackId) }
                }
            }
            is PlayerEvent.EndOfTrack -> {
                if (connectMode) {
                    // Spirc handles track advancement; skip local queue logic
                    return
                }
                viewModelScope.launch(Dispatchers.IO) {
                    val nextUri = queueManager.next()
                    if (nextUri != null) {
                        loadTrack(nextUri)
                        preloadUpcoming()
                    } else {
                        _uiState.update {
                            it.copy(isPlaying = false, positionMs = 0L)
                        }
                        audioFocusManager?.abandonFocus()
                        appContext?.let { PlaybackService.stopService(it) }
                    }
                }
            }
            is PlayerEvent.Error -> {
                if (connectMode) return // Spirc handles errors
                val nextUri = queueManager.next()
                if (nextUri != null) {
                    loadTrack(nextUri)
                }
            }
            is PlayerEvent.SetQueue -> {
                // Sync local queue state from Spirc
                queueManager.syncFromRemote(
                    prevTracks = event.prevTrackUris,
                    currentTrackUri = event.currentTrackUri,
                    nextTracks = event.nextTrackUris,
                    contextUri = event.contextUri,
                )
                // Fetch metadata for the current track if needed
                event.currentTrackUri?.let { uri ->
                    if (uri != _uiState.value.trackUri) {
                        _uiState.update { it.copy(trackUri = uri) }
                        viewModelScope.launch(Dispatchers.IO) { fetchAndApplyMetadata(uri) }
                    }
                }
                // Resolve metadata for upcoming tracks
                viewModelScope.launch(Dispatchers.IO) {
                    event.nextTrackUris.take(5).forEach { uri ->
                        resolveAndCacheMetadata(uri)
                    }
                }
            }
            is PlayerEvent.VolumeChanged -> {
                onVolumeChanged(event.volume)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        eventPollingActive = false
        audioCallback.release()
        audioFocusManager?.abandonFocus()
        appContext?.let { ctx ->
            mediaCommandReceiver?.let { ctx.unregisterReceiver(it) }
            PlaybackService.stopService(ctx)
        }
        if (connectMode) {
            NativeBridge.connectStop()
        }
        NativeBridge.sessionDisconnect()
    }
}
