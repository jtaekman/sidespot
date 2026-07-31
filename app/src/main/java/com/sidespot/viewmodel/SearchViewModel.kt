package com.sidespot.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sidespot.bridge.NativeBridge
import com.sidespot.bridge.SearchAlbumResult
import com.sidespot.bridge.SearchArtistResult
import com.sidespot.bridge.SearchPlaylistResult
import com.sidespot.bridge.SearchResults
import com.sidespot.bridge.SearchShowResult
import com.sidespot.bridge.ShowSummary
import com.sidespot.bridge.TrackInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class ArtistResult(
    val uri: String,
    val name: String,
    val imageUrl: String?,
)

data class AlbumResult(
    val uri: String,
    val name: String,
    val artistName: String,
    val albumArtUrl: String?,
)

data class PlaylistResult(
    val uri: String,
    val name: String,
    val ownerName: String,
    val imageUrl: String?,
)

data class SearchUiState(
    val query: String = "",
    val trackUris: List<String> = emptyList(),
    val tracks: List<TrackInfo> = emptyList(),
    val artists: List<ArtistResult> = emptyList(),
    val albums: List<AlbumResult> = emptyList(),
    val shows: List<ShowSummary> = emptyList(),
    val playlists: List<PlaylistResult> = emptyList(),
    val tracksDisplayLimit: Int = SEARCH_PAGE_SIZE,
    val artistsDisplayLimit: Int = SEARCH_PAGE_SIZE,
    val albumsDisplayLimit: Int = SEARCH_PAGE_SIZE,
    val showsDisplayLimit: Int = SEARCH_PAGE_SIZE,
    val playlistsDisplayLimit: Int = SEARCH_PAGE_SIZE,
    val hasMoreTracks: Boolean = false,
    val hasMoreArtists: Boolean = false,
    val hasMoreAlbums: Boolean = false,
    val hasMoreShows: Boolean = false,
    val hasMorePlaylists: Boolean = false,
    val isSearching: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
)

/** Rows revealed per section, and per tap of "Show More". */
private const val SEARCH_PAGE_SIZE = 5

/** Rows fetched per request; two taps of "Show More" before another round trip. */
private const val SEARCH_FETCH_LIMIT = 10

private const val SEARCH_TIMEOUT_MS = 15_000L

private const val TYPE_TRACK = "track"
private const val TYPE_ARTIST = "artist"
private const val TYPE_ALBUM = "album"
private const val TYPE_PLAYLIST = "playlist"
private const val TYPE_SHOW = "show"

class SearchViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    /** Catalogue totals per section, so we know when to fetch another page. */
    private val totals = mutableMapOf<String, Int>()

    fun updateQuery(query: String) {
        _uiState.update { it.copy(query = query) }

        // Debounce search
        searchJob?.cancel()
        totals.clear()
        if (query.isBlank()) {
            _uiState.update {
                it.copy(
                    trackUris = emptyList(),
                    tracks = emptyList(),
                    artists = emptyList(),
                    albums = emptyList(),
                    shows = emptyList(),
                    playlists = emptyList(),
                    tracksDisplayLimit = SEARCH_PAGE_SIZE,
                    artistsDisplayLimit = SEARCH_PAGE_SIZE,
                    albumsDisplayLimit = SEARCH_PAGE_SIZE,
                    showsDisplayLimit = SEARCH_PAGE_SIZE,
                    playlistsDisplayLimit = SEARCH_PAGE_SIZE,
                    hasMoreTracks = false,
                    hasMoreArtists = false,
                    hasMoreAlbums = false,
                    hasMoreShows = false,
                    hasMorePlaylists = false,
                    error = null,
                )
            }
            return
        }

        searchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(400) // debounce
            performSearch(query)
        }
    }

    private suspend fun performSearch(query: String) {
        _uiState.update { it.copy(isSearching = true, error = null) }

        val page = withTimeoutOrNull(SEARCH_TIMEOUT_MS) { fetchPage(query, offset = 0) }

        if (page == null) {
            _uiState.update { it.copy(isSearching = false, error = "Search failed") }
            return
        }

        val tracks = page.tracks
        val artists = page.artists.map { it.toUiModel() }
        val albums = page.albums.map { it.toUiModel() }
        val playlists = page.playlists.map { it.toUiModel() }
        val shows = page.shows.map { it.toUiModel() }

        totals[TYPE_TRACK] = page.totalTracks
        totals[TYPE_ARTIST] = page.totalArtists
        totals[TYPE_ALBUM] = page.totalAlbums
        totals[TYPE_PLAYLIST] = page.totalPlaylists
        totals[TYPE_SHOW] = page.totalShows

        _uiState.update {
            it.copy(
                isSearching = false,
                tracks = tracks,
                trackUris = tracks.map { t -> t.uri },
                artists = artists,
                albums = albums,
                shows = shows,
                playlists = playlists,
                tracksDisplayLimit = SEARCH_PAGE_SIZE,
                artistsDisplayLimit = SEARCH_PAGE_SIZE,
                albumsDisplayLimit = SEARCH_PAGE_SIZE,
                showsDisplayLimit = SEARCH_PAGE_SIZE,
                playlistsDisplayLimit = SEARCH_PAGE_SIZE,
                hasMoreTracks = hasMore(SEARCH_PAGE_SIZE, tracks.size, TYPE_TRACK),
                hasMoreArtists = hasMore(SEARCH_PAGE_SIZE, artists.size, TYPE_ARTIST),
                hasMoreAlbums = hasMore(SEARCH_PAGE_SIZE, albums.size, TYPE_ALBUM),
                hasMoreShows = hasMore(SEARCH_PAGE_SIZE, shows.size, TYPE_SHOW),
                hasMorePlaylists = hasMore(SEARCH_PAGE_SIZE, playlists.size, TYPE_PLAYLIST),
            )
        }
    }

    /** One page of every section, straight from the native search. */
    private suspend fun fetchPage(query: String, offset: Int): SearchResults? =
        withContext(Dispatchers.IO) {
            val json = NativeBridge.metadataSearch(query, SEARCH_FETCH_LIMIT, offset)
            if (json == null || json.startsWith("{\"error\"")) null else SearchResults.fromJson(json)
        }

    fun showMoreTracks() = showMore(
        type = TYPE_TRACK,
        loaded = { tracks },
        displayLimit = { tracksDisplayLimit },
        extract = { it.tracks },
        key = { it.uri },
    ) { items, limit, more ->
        copy(
            tracks = items,
            trackUris = items.map { it.uri },
            tracksDisplayLimit = limit,
            hasMoreTracks = more,
        )
    }

    fun showMoreArtists() = showMore(
        type = TYPE_ARTIST,
        loaded = { artists },
        displayLimit = { artistsDisplayLimit },
        extract = { page -> page.artists.map { it.toUiModel() } },
        key = { it.uri },
    ) { items, limit, more ->
        copy(artists = items, artistsDisplayLimit = limit, hasMoreArtists = more)
    }

    fun showMoreAlbums() = showMore(
        type = TYPE_ALBUM,
        loaded = { albums },
        displayLimit = { albumsDisplayLimit },
        extract = { page -> page.albums.map { it.toUiModel() } },
        key = { it.uri },
    ) { items, limit, more ->
        copy(albums = items, albumsDisplayLimit = limit, hasMoreAlbums = more)
    }

    fun showMorePlaylists() = showMore(
        type = TYPE_PLAYLIST,
        loaded = { playlists },
        displayLimit = { playlistsDisplayLimit },
        extract = { page -> page.playlists.map { it.toUiModel() } },
        key = { it.uri },
    ) { items, limit, more ->
        copy(playlists = items, playlistsDisplayLimit = limit, hasMorePlaylists = more)
    }

    fun showMoreShows() = showMore(
        type = TYPE_SHOW,
        loaded = { shows },
        displayLimit = { showsDisplayLimit },
        extract = { page -> page.shows.map { it.toUiModel() } },
        key = { it.uri },
    ) { items, limit, more ->
        copy(shows = items, showsDisplayLimit = limit, hasMoreShows = more)
    }

    /**
     * Reveal another [SEARCH_PAGE_SIZE] rows of one section, fetching the next
     * catalogue page first when the already-loaded results run out.
     */
    private fun <T> showMore(
        type: String,
        loaded: SearchUiState.() -> List<T>,
        displayLimit: SearchUiState.() -> Int,
        extract: (SearchResults) -> List<T>,
        key: (T) -> String,
        apply: SearchUiState.(items: List<T>, limit: Int, hasMore: Boolean) -> SearchUiState,
    ) {
        val state = _uiState.value
        val items = state.loaded()
        val newLimit = state.displayLimit() + SEARCH_PAGE_SIZE

        if (newLimit <= items.size || items.size >= (totals[type] ?: 0)) {
            _uiState.update { it.apply(items, newLimit, hasMore(newLimit, items.size, type)) }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoadingMore = true) }
            val page = fetchPage(state.query, offset = items.size)
            if (page == null) {
                _uiState.update { it.copy(isLoadingMore = false) }
                return@launch
            }
            totals[type] = totalFor(type, page)
            // Pages can overlap; duplicate keys would crash the lazy list.
            val seen = items.mapTo(mutableSetOf(), key)
            val all = items + extract(page).filter { seen.add(key(it)) }
            _uiState.update {
                it.apply(all, newLimit, hasMore(newLimit, all.size, type)).copy(isLoadingMore = false)
            }
        }
    }

    private fun hasMore(displayLimit: Int, loaded: Int, type: String): Boolean =
        displayLimit < loaded || loaded < (totals[type] ?: 0)

    private fun totalFor(type: String, page: SearchResults): Int = when (type) {
        TYPE_TRACK -> page.totalTracks
        TYPE_ARTIST -> page.totalArtists
        TYPE_ALBUM -> page.totalAlbums
        TYPE_PLAYLIST -> page.totalPlaylists
        else -> page.totalShows
    }
}

// Mapping extensions from bridge types to UI types
private fun SearchArtistResult.toUiModel() = ArtistResult(
    uri = uri,
    name = name,
    imageUrl = imageUrl,
)

private fun SearchAlbumResult.toUiModel() = AlbumResult(
    uri = uri,
    name = name,
    artistName = artistName,
    albumArtUrl = albumArtUrl,
)

private fun SearchPlaylistResult.toUiModel() = PlaylistResult(
    uri = uri,
    name = name,
    ownerName = ownerName,
    imageUrl = imageUrl,
)

private fun SearchShowResult.toUiModel() = ShowSummary(
    uri = uri,
    name = name,
    publisher = publisher,
    imageUrl = imageUrl,
)
