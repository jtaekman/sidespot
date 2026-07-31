package com.sidespot.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sidespot.bridge.ArtistAlbum
import com.sidespot.bridge.ArtistInfo
import com.sidespot.bridge.NativeBridge
import com.sidespot.bridge.TrackInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ArtistUiState(
    val uri: String = "",
    val name: String = "",
    val imageUrl: String? = null,
    val topTracks: List<TrackInfo> = emptyList(),
    val albums: List<ArtistAlbum> = emptyList(),
    val singles: List<ArtistAlbum> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

class ArtistViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ArtistUiState())
    val uiState: StateFlow<ArtistUiState> = _uiState.asStateFlow()

    private var loadedUri: String? = null

    fun loadArtist(uri: String) {
        if (uri == loadedUri) return
        loadedUri = uri

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val json = NativeBridge.metadataGetArtist(uri)
            if (json == null || json.startsWith("{\"error\"")) {
                loadedUri = null
                _uiState.update {
                    it.copy(isLoading = false, error = "Failed to load artist")
                }
                return@launch
            }

            val info = ArtistInfo.fromJson(json)
            if (info == null) {
                loadedUri = null
                _uiState.update {
                    it.copy(isLoading = false, error = "Failed to parse artist")
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    uri = info.uri,
                    name = info.name,
                    imageUrl = info.imageUrl,
                    topTracks = info.topTracks,
                    albums = info.albums,
                    singles = info.singles,
                    isLoading = false,
                )
            }
        }
    }
}
