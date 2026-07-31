package com.sidespot.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.sidespot.api.ApiResult
import com.sidespot.api.SpotifyWebApi
import com.sidespot.auth.AuthManager
import com.sidespot.history.PlayHistoryManager
import com.sidespot.bridge.AlbumSummary
import com.sidespot.bridge.EpisodeSummary
import com.sidespot.bridge.NativeBridge
import com.sidespot.bridge.PlaylistSummary
import com.sidespot.bridge.SavedArtist
import com.sidespot.bridge.ShowSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed class LibraryItem(val uri: String, val name: String) {
    class Playlist(val summary: PlaylistSummary) : LibraryItem(summary.uri, summary.name)
    class Album(
        uri: String,
        name: String,
        val artistName: String,
        val imageUrl: String? = null,
    ) : LibraryItem(uri, name)
}

data class LibraryUiState(
    val playlists: List<PlaylistSummary> = emptyList(),
    val libraryItems: List<LibraryItem> = emptyList(),
    val albums: List<AlbumSummary> = emptyList(),
    val shows: List<ShowSummary> = emptyList(),
    val artists: List<SavedArtist> = emptyList(),
    val episodes: List<EpisodeSummary> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingAlbums: Boolean = false,
    val isLoadingShows: Boolean = false,
    val isLoadingArtists: Boolean = false,
    val isLoadingEpisodes: Boolean = false,
    val newEpisodes: List<EpisodeSummary> = emptyList(),
    val isLoadingNewEpisodes: Boolean = false,
    val newEpisodesDisplayLimit: Int = 5,
    val hasMoreNewEpisodes: Boolean = false,
    val historyItems: List<LibraryItem> = emptyList(),
    val historyDisplayLimit: Int = 50,
    val hasMoreHistory: Boolean = false,
    val error: String? = null,
)

class LibraryViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()
    private var api: SpotifyWebApi? = null
    private var historyManager: PlayHistoryManager? = null

    private var recentOrder: SpotifyWebApi.RecentlyPlayedOrder? = null
    private var lastFetchedAt: Long = 0L
    private val CACHE_TTL_MS = 2 * 60 * 1000L // 2 minutes

    fun initApi(authManager: AuthManager) {
        if (api == null) api = SpotifyWebApi(authManager)
    }

    fun initHistory(context: Context) {
        if (historyManager == null) historyManager = PlayHistoryManager(context.applicationContext)
    }

    private var cachedApiOrder: SpotifyWebApi.RecentlyPlayedOrder? = null

    private suspend fun refreshRecentOrder() {
        // Only re-fetch API when cache expires, but always re-read local entries
        val now = System.currentTimeMillis()
        if (now - lastFetchedAt >= CACHE_TTL_MS) {
            val result = api?.getRecentlyPlayedOrder()
            if (result != null) {
                cachedApiOrder = result
                lastFetchedAt = now
            }
        }
        val apiOrder = cachedApiOrder ?: return

        val localEntries = historyManager?.loadEntries() ?: emptyList()
        if (localEntries.isEmpty()) {
            recentOrder = apiOrder
            return
        }

        // Merge: for each URI keep whichever source has the more recent timestamp
        val mergedTimestamps = mutableMapOf<String, Long>()
        val mergedAlbumDetails = apiOrder.albumDetails.toMutableMap()

        // Seed with API timestamps
        for ((uri, ts) in apiOrder.playedAtMs) {
            mergedTimestamps[uri] = ts
        }

        // Merge local entries — keep the more recent timestamp per URI
        for (entry in localEntries) {
            val existing = mergedTimestamps[entry.contextUri]
            if (existing == null || entry.playedAtMs > existing) {
                mergedTimestamps[entry.contextUri] = entry.playedAtMs
            }
            // Fill album details from local history for items not in API response
            if (entry.contextUri !in mergedAlbumDetails) {
                mergedAlbumDetails[entry.contextUri] = SpotifyWebApi.RecentAlbumInfo(
                    uri = entry.contextUri,
                    name = entry.contextName,
                    artistName = entry.artistName,
                    imageUrl = entry.imageUrl,
                )
            }
        }

        // Also include any API URIs that had no timestamp (fallback: preserve API order)
        for (uri in apiOrder.orderedUris) {
            if (uri !in mergedTimestamps) {
                mergedTimestamps[uri] = 0L
            }
        }

        // Sort all URIs by timestamp descending
        val sortedUris = mergedTimestamps.entries
            .sortedByDescending { it.value }
            .map { it.key }

        recentOrder = SpotifyWebApi.RecentlyPlayedOrder(
            orderedUris = sortedUris,
            albumDetails = mergedAlbumDetails,
            playedAtMs = mergedTimestamps,
        )
    }

    private fun buildLibraryItems(playlists: List<PlaylistSummary>): List<LibraryItem> {
        val order = recentOrder ?: return playlists.map { LibraryItem.Playlist(it) }
        val orderedUris = order.orderedUris
        if (orderedUris.isEmpty()) return playlists.map { LibraryItem.Playlist(it) }

        val playlistByUri = playlists.associateBy { it.uri }
        val orderIndex = orderedUris.withIndex().associate { (i, uri) -> uri to i }

        // Build recent items (playlists + albums interleaved by recency)
        val recentItems = mutableListOf<LibraryItem>()
        for (uri in orderedUris) {
            val playlist = playlistByUri[uri]
            if (playlist != null) {
                recentItems.add(LibraryItem.Playlist(playlist))
                continue
            }
            val album = order.albumDetails[uri]
            if (album != null) {
                recentItems.add(LibraryItem.Album(
                    uri = album.uri,
                    name = album.name,
                    artistName = album.artistName,
                    imageUrl = album.imageUrl,
                ))
            }
        }

        // Cap recent items to 15 for Library view
        val capped = recentItems.take(15)

        // Append playlists not in recent order
        val remaining = playlists.filter { it.uri !in orderIndex }
            .map { LibraryItem.Playlist(it) }

        return capped + remaining
    }

    private fun buildHistoryItems(): List<LibraryItem> {
        val order = recentOrder ?: return emptyList()
        val orderedUris = order.orderedUris
        if (orderedUris.isEmpty()) return emptyList()

        val playlists = _uiState.value.playlists.associateBy { it.uri }
        val items = mutableListOf<LibraryItem>()
        for (uri in orderedUris) {
            val playlist = playlists[uri]
            if (playlist != null) {
                items.add(LibraryItem.Playlist(playlist))
                continue
            }
            val album = order.albumDetails[uri]
            if (album != null) {
                items.add(LibraryItem.Album(
                    uri = album.uri,
                    name = album.name,
                    artistName = album.artistName,
                    imageUrl = album.imageUrl,
                ))
            }
        }
        return items
    }

    fun loadHistoryItems() {
        viewModelScope.launch(Dispatchers.IO) {
            refreshRecentOrder()
            val items = buildHistoryItems()
            _uiState.update {
                it.copy(
                    historyItems = items,
                    historyDisplayLimit = 50,
                    hasMoreHistory = items.size > 50,
                )
            }
        }
    }

    fun showMoreHistory() {
        _uiState.update {
            val newLimit = it.historyDisplayLimit + 50
            it.copy(
                historyDisplayLimit = newLimit,
                hasMoreHistory = newLimit < it.historyItems.size,
            )
        }
    }

    fun refreshLibrary() {
        viewModelScope.launch(Dispatchers.IO) {
            refreshRecentOrder()
            val playlists = _uiState.value.playlists
            if (playlists.isNotEmpty()) {
                val items = buildLibraryItems(playlists)
                _uiState.update { it.copy(libraryItems = items) }
            }
        }
    }

    fun loadPlaylists() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val json = NativeBridge.metadataGetUserPlaylists()
            if (json == null) {
                _uiState.update {
                    it.copy(isLoading = false, error = "Failed to load playlists")
                }
                return@launch
            }

            // Check for error response
            if (json.startsWith("{\"error\"")) {
                _uiState.update { it.copy(isLoading = false, error = json) }
                return@launch
            }

            val playlists = PlaylistSummary.listFromJson(json)
            if (playlists != null) {
                refreshRecentOrder()
                val items = buildLibraryItems(playlists)
                _uiState.update {
                    it.copy(playlists = playlists, libraryItems = items, isLoading = false)
                }
            } else {
                _uiState.update {
                    it.copy(isLoading = false, error = "Failed to parse playlists")
                }
            }
        }
    }

    fun loadSavedAlbums() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoadingAlbums = true) }
            val json = NativeBridge.metadataGetSavedAlbums()
            val albums = if (json != null && !json.startsWith("{\"error\"")) {
                AlbumSummary.listFromJson(json) ?: emptyList()
            } else {
                emptyList()
            }
            _uiState.update { it.copy(albums = albums, isLoadingAlbums = false) }
        }
    }

    fun loadSavedShows() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoadingShows = true) }
            val json = NativeBridge.metadataGetSavedShows()
            val shows = if (json != null && !json.startsWith("{\"error\"")) {
                ShowSummary.listFromJson(json) ?: emptyList()
            } else {
                emptyList()
            }
            _uiState.update { it.copy(shows = shows, isLoadingShows = false) }
        }
    }

    fun loadFollowedArtists() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoadingArtists = true) }
            val json = NativeBridge.metadataGetFollowedArtists()
            val artists = if (json != null && !json.startsWith("{\"error\"")) {
                SavedArtist.listFromJson(json) ?: emptyList()
            } else {
                emptyList()
            }
            _uiState.update { it.copy(artists = artists, isLoadingArtists = false) }
        }
    }

    fun loadShowEpisodes(showUri: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoadingEpisodes = true, episodes = emptyList()) }
            val json = NativeBridge.metadataGetShowEpisodes(showUri)
            val episodes = if (json != null && !json.startsWith("{\"error\"")) {
                EpisodeSummary.listFromJson(json) ?: emptyList()
            } else {
                emptyList()
            }
            _uiState.update { it.copy(episodes = episodes, isLoadingEpisodes = false) }
        }
    }

    fun loadNewEpisodes() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoadingNewEpisodes = true) }
            // Ensure shows are loaded first
            if (_uiState.value.shows.isEmpty()) {
                val json = NativeBridge.metadataGetSavedShows()
                val shows = if (json != null && !json.startsWith("{\"error\"")) {
                    ShowSummary.listFromJson(json) ?: emptyList()
                } else {
                    emptyList()
                }
                _uiState.update { it.copy(shows = shows) }
            }
            val shows = _uiState.value.shows
            val episodes = api?.getUnplayedEpisodesForShows(shows) ?: emptyList()
            _uiState.update {
                it.copy(
                    newEpisodes = episodes,
                    isLoadingNewEpisodes = false,
                    newEpisodesDisplayLimit = 5,
                    hasMoreNewEpisodes = episodes.size > 5,
                )
            }
        }
    }

    fun showMoreNewEpisodes() {
        _uiState.update {
            val newLimit = it.newEpisodesDisplayLimit + 5
            it.copy(
                newEpisodesDisplayLimit = newLimit,
                hasMoreNewEpisodes = newLimit < it.newEpisodes.size,
            )
        }
    }

    fun saveAlbum(albumUri: String, onResult: (ApiResult) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = parseNativeResult(NativeBridge.librarySaveAlbum(albumUri))
            if (result is ApiResult.Success) {
                _uiState.update { state ->
                    if (state.albums.none { it.uri == albumUri }) {
                        state.copy(albums = listOf(
                            AlbumSummary(uri = albumUri, name = "", artistName = ""),
                        ) + state.albums)
                    } else state
                }
            }
            onResult(result)
        }
    }

    fun unsaveAlbum(albumUri: String, onResult: (ApiResult) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = parseNativeResult(NativeBridge.libraryUnsaveAlbum(albumUri))
            if (result is ApiResult.Success) {
                _uiState.update { state ->
                    state.copy(albums = state.albums.filter { it.uri != albumUri })
                }
            }
            onResult(result)
        }
    }

    fun saveShow(showUri: String, onResult: (ApiResult) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = parseNativeResult(NativeBridge.librarySaveShow(showUri))
            if (result is ApiResult.Success) {
                _uiState.update { state ->
                    if (state.shows.none { it.uri == showUri }) {
                        state.copy(shows = listOf(
                            ShowSummary(uri = showUri, name = "", publisher = ""),
                        ) + state.shows)
                    } else state
                }
            }
            onResult(result)
        }
    }

    fun unsaveShow(showUri: String, onResult: (ApiResult) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = parseNativeResult(NativeBridge.libraryUnsaveShow(showUri))
            if (result is ApiResult.Success) {
                _uiState.update { state ->
                    state.copy(shows = state.shows.filter { it.uri != showUri })
                }
            }
            onResult(result)
        }
    }

    fun unfollowArtist(artistUri: String, onResult: (ApiResult) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = parseNativeResult(NativeBridge.libraryUnfollowArtist(artistUri))
            if (result is ApiResult.Success) {
                _uiState.update { state ->
                    state.copy(artists = state.artists.filter { it.uri != artistUri })
                }
            }
            onResult(result)
        }
    }

    fun savePlaylist(playlistUri: String, playlistName: String, onResult: (ApiResult) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = parseNativeResult(NativeBridge.librarySavePlaylist(playlistUri))
            if (result is ApiResult.Success) {
                _uiState.update { state ->
                    if (state.playlists.none { it.uri == playlistUri }) {
                        state.copy(playlists = listOf(
                            PlaylistSummary(uri = playlistUri, name = playlistName),
                        ) + state.playlists)
                    } else state
                }
            }
            onResult(result)
        }
    }

    fun unsavePlaylist(playlistUri: String, onResult: (ApiResult) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = api?.unfollowPlaylist(playlistUri)
                ?: ApiResult.Error("API not initialized")
            if (result is ApiResult.Success) {
                _uiState.update { state ->
                    state.copy(playlists = state.playlists.filter { it.uri != playlistUri })
                }
            }
            onResult(result)
        }
    }

    private fun parseNativeResult(json: String?): ApiResult {
        if (json == null) return ApiResult.Error("Null response from native")
        return try {
            val obj = org.json.JSONObject(json)
            if (obj.optBoolean("success", false)) {
                ApiResult.Success
            } else {
                ApiResult.Error(obj.optString("error", "Unknown error"))
            }
        } catch (e: Exception) {
            ApiResult.Error("Parse error: ${e.message}")
        }
    }
}
