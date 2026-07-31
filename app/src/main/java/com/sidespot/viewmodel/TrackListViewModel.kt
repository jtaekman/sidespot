package com.sidespot.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sidespot.bridge.NativeBridge
import com.sidespot.bridge.PlaylistInfo
import com.sidespot.bridge.TrackInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class TrackListUiState(
    val name: String = "",
    val trackUris: List<String> = emptyList(),
    val tracks: List<TrackInfo> = emptyList(),
    val albumArtUrl: String? = null,
    val isAlbum: Boolean = false,
    val isLoading: Boolean = false,
    val hasMoreTracks: Boolean = false,
    val error: String? = null,
)

class TrackListViewModel : ViewModel() {

    private companion object {
        /** Track metadata is fetched one request per track, so page it in as the user scrolls. */
        const val PAGE_SIZE = 100
    }

    private val _uiState = MutableStateFlow(TrackListUiState())
    val uiState: StateFlow<TrackListUiState> = _uiState.asStateFlow()

    private var loadedUri: String? = null
    private val metadataDispatcher = Dispatchers.IO.limitedParallelism(4)

    /** Index into [TrackListUiState.trackUris] of the next URI to resolve. */
    private var nextUriIndex = 0
    private val loadedTracks = mutableListOf<TrackInfo>()
    private val pageMutex = Mutex()

    fun loadTrackList(uri: String) {
        if (uri == loadedUri) return
        loadedUri = uri

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true, error = null) }

            if (uri == "liked_songs") {
                loadLikedSongs()
            } else if (uri.startsWith("spotify:playlist:")) {
                loadPlaylist(uri)
            } else if (uri.startsWith("spotify:album:")) {
                loadAlbum(uri)
            } else {
                _uiState.update {
                    it.copy(isLoading = false, error = "Unknown URI type: $uri")
                }
            }
        }
    }

    private suspend fun loadPlaylist(uri: String) {
        val json = NativeBridge.metadataGetPlaylist(uri)
        if (json == null || json.startsWith("{\"error\"")) {
            _uiState.update {
                it.copy(isLoading = false, error = json ?: "Failed to load playlist")
            }
            return
        }

        val playlist = PlaylistInfo.fromJson(json)
        if (playlist == null) {
            _uiState.update { it.copy(isLoading = false, error = "Failed to parse playlist") }
            return
        }

        _uiState.update {
            it.copy(
                name = playlist.name,
                trackUris = playlist.trackUris,
                hasMoreTracks = playlist.trackUris.isNotEmpty(),
            )
        }

        pageMutex.withLock { fetchNextPage() }
    }

    private suspend fun loadAlbum(uri: String) {
        val json = NativeBridge.metadataGetAlbum(uri)
        if (json == null || json.startsWith("{\"error\"")) {
            _uiState.update {
                it.copy(isLoading = false, error = json ?: "Failed to load album")
            }
            return
        }

        val album = com.sidespot.bridge.AlbumInfo.fromJson(json)
        if (album == null) {
            _uiState.update { it.copy(isLoading = false, error = "Failed to parse album") }
            return
        }

        val trackUris = album.tracks.map { it.uri }
        val trackInfos = album.tracks.map { ts ->
            TrackInfo(
                uri = ts.uri,
                name = ts.name,
                artists = ts.artists,
                albumName = album.name,
                albumUri = album.uri,
                albumArtUrl = album.albumArtUrl,
                durationMs = ts.durationMs,
                trackNumber = ts.trackNumber,
                discNumber = ts.discNumber,
                isExplicit = ts.isExplicit,
            )
        }

        _uiState.update {
            it.copy(
                name = album.name,
                trackUris = trackUris,
                tracks = trackInfos,
                albumArtUrl = album.albumArtUrl,
                isAlbum = true,
                isLoading = false,
            )
        }
    }

    private suspend fun loadLikedSongs() {
        val json = NativeBridge.metadataGetLikedSongs()
        if (json == null || json.startsWith("{\"error\"")) {
            _uiState.update {
                it.copy(isLoading = false, error = json ?: "Failed to load liked songs")
            }
            return
        }

        val playlist = PlaylistInfo.fromJson(json)
        if (playlist == null) {
            _uiState.update { it.copy(isLoading = false, error = "Failed to parse liked songs") }
            return
        }

        _uiState.update {
            it.copy(
                name = "Liked Songs",
                trackUris = playlist.trackUris,
                hasMoreTracks = playlist.trackUris.isNotEmpty(),
            )
        }

        pageMutex.withLock { fetchNextPage() }
    }

    /**
     * Resolve metadata for the next page of track URIs. Safe to call repeatedly:
     * overlapping calls are dropped rather than queued.
     */
    fun loadMoreTracks() {
        if (!_uiState.value.hasMoreTracks) return
        viewModelScope.launch(Dispatchers.IO) {
            if (!pageMutex.tryLock()) return@launch
            try {
                fetchNextPage()
            } finally {
                pageMutex.unlock()
            }
        }
    }

    private suspend fun fetchNextPage() {
        val uris = _uiState.value.trackUris
        if (nextUriIndex >= uris.size) {
            _uiState.update { it.copy(isLoading = false, hasMoreTracks = false) }
            return
        }

        _uiState.update { it.copy(isLoading = true) }

        val end = minOf(nextUriIndex + PAGE_SIZE, uris.size)
        for (chunk in uris.subList(nextUriIndex, end).chunked(10)) {
            val deferred = chunk.map { uri ->
                viewModelScope.async(metadataDispatcher) {
                    val trackJson = NativeBridge.metadataGetTrack(uri)
                    trackJson?.let { TrackInfo.fromJson(it) }
                }
            }
            loadedTracks.addAll(deferred.awaitAll().filterNotNull())
        }
        nextUriIndex = end

        _uiState.update {
            it.copy(
                tracks = loadedTracks.toList(),
                isLoading = false,
                hasMoreTracks = end < uris.size,
            )
        }
    }
}
