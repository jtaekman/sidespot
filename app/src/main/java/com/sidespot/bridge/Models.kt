package com.sidespot.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

@Serializable
data class TrackInfo(
    val uri: String,
    val name: String,
    val artists: List<ArtistSummary>,
    @SerialName("album_name") val albumName: String,
    @SerialName("album_uri") val albumUri: String,
    @SerialName("album_art_url") val albumArtUrl: String? = null,
    @SerialName("duration_ms") val durationMs: Int,
    @SerialName("track_number") val trackNumber: Int,
    @SerialName("disc_number") val discNumber: Int,
    @SerialName("is_explicit") val isExplicit: Boolean,
) {
    val artistName: String get() = artists.joinToString(", ") { it.name }

    companion object {
        fun fromJson(jsonString: String): TrackInfo? = try {
            json.decodeFromString<TrackInfo>(jsonString)
        } catch (_: Exception) {
            null
        }
    }
}

@Serializable
data class ArtistSummary(
    val uri: String,
    val name: String,
)

@Serializable
data class AlbumInfo(
    val uri: String,
    val name: String,
    val artists: List<ArtistSummary>,
    @SerialName("album_art_url") val albumArtUrl: String? = null,
    val tracks: List<TrackSummary>,
    @SerialName("album_type") val albumType: String,
    val label: String,
) {
    val artistName: String get() = artists.joinToString(", ") { it.name }

    companion object {
        fun fromJson(jsonString: String): AlbumInfo? = try {
            json.decodeFromString<AlbumInfo>(jsonString)
        } catch (_: Exception) {
            null
        }
    }
}

@Serializable
data class TrackSummary(
    val uri: String,
    val name: String,
    val artists: List<ArtistSummary>,
    @SerialName("duration_ms") val durationMs: Int,
    @SerialName("track_number") val trackNumber: Int,
    @SerialName("disc_number") val discNumber: Int,
    @SerialName("is_explicit") val isExplicit: Boolean,
) {
    val artistName: String get() = artists.joinToString(", ") { it.name }
}

@Serializable
data class PlaylistInfo(
    val uri: String,
    val name: String,
    @SerialName("track_uris") val trackUris: List<String>,
    @SerialName("track_count") val trackCount: Int,
) {
    companion object {
        fun fromJson(jsonString: String): PlaylistInfo? = try {
            json.decodeFromString<PlaylistInfo>(jsonString)
        } catch (_: Exception) {
            null
        }
    }
}

@Serializable
data class PlaylistSummary(
    val uri: String,
    val name: String,
    @SerialName("is_writable") val isWritable: Boolean = true,
) {
    companion object {
        fun listFromJson(jsonString: String): List<PlaylistSummary>? = try {
            json.decodeFromString<List<PlaylistSummary>>(jsonString)
        } catch (_: Exception) {
            null
        }
    }
}

@Serializable
data class SearchResults(
    val tracks: List<TrackInfo> = emptyList(),
    val albums: List<SearchAlbumResult> = emptyList(),
    val playlists: List<SearchPlaylistResult> = emptyList(),
    val shows: List<SearchShowResult> = emptyList(),
    @SerialName("total_tracks") val totalTracks: Int = 0,
    @SerialName("total_albums") val totalAlbums: Int = 0,
    @SerialName("total_playlists") val totalPlaylists: Int = 0,
    @SerialName("total_shows") val totalShows: Int = 0,
) {
    companion object {
        fun fromJson(jsonString: String): SearchResults? = try {
            json.decodeFromString<SearchResults>(jsonString)
        } catch (_: Exception) {
            null
        }
    }
}

@Serializable
data class SearchAlbumResult(
    val uri: String,
    val name: String,
    @SerialName("artist_name") val artistName: String,
    @SerialName("album_art_url") val albumArtUrl: String? = null,
)

@Serializable
data class SearchPlaylistResult(
    val uri: String,
    val name: String,
    @SerialName("owner_name") val ownerName: String,
    @SerialName("image_url") val imageUrl: String? = null,
)

@Serializable
data class SearchShowResult(
    val uri: String,
    val name: String,
    val publisher: String,
    @SerialName("image_url") val imageUrl: String? = null,
)

@Serializable
data class SearchPageResult<T>(
    val items: List<T>,
    val total: Int,
)

@Serializable
data class AlbumSummary(
    val uri: String,
    val name: String,
    @SerialName("artist_name") val artistName: String,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("added_at") val addedAt: Long = 0,
) {
    companion object {
        fun listFromJson(jsonString: String): List<AlbumSummary>? = try {
            json.decodeFromString<List<AlbumSummary>>(jsonString)
        } catch (_: Exception) {
            null
        }
    }
}

@Serializable
data class ShowSummary(
    val uri: String,
    val name: String,
    val publisher: String,
    @SerialName("image_url") val imageUrl: String? = null,
) {
    companion object {
        fun listFromJson(jsonString: String): List<ShowSummary>? = try {
            json.decodeFromString<List<ShowSummary>>(jsonString)
        } catch (_: Exception) {
            null
        }
    }
}

@Serializable
data class SavedArtist(
    val uri: String,
    val name: String,
    @SerialName("image_url") val imageUrl: String? = null,
) {
    companion object {
        fun listFromJson(jsonString: String): List<SavedArtist>? = try {
            json.decodeFromString<List<SavedArtist>>(jsonString)
        } catch (_: Exception) {
            null
        }
    }
}

@Serializable
data class ArtistAlbum(
    val uri: String,
    val name: String,
    @SerialName("image_url") val imageUrl: String? = null,
    val year: Int = 0,
    @SerialName("track_count") val trackCount: Int = 0,
)

@Serializable
data class ArtistInfo(
    val uri: String,
    val name: String,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("top_tracks") val topTracks: List<TrackInfo> = emptyList(),
    val albums: List<ArtistAlbum> = emptyList(),
    val singles: List<ArtistAlbum> = emptyList(),
) {
    companion object {
        fun fromJson(jsonString: String): ArtistInfo? = try {
            json.decodeFromString<ArtistInfo>(jsonString)
        } catch (_: Exception) {
            null
        }
    }
}

@Serializable
data class EpisodeSummary(
    val uri: String,
    val name: String,
    val description: String,
    @SerialName("duration_ms") val durationMs: Int,
    @SerialName("release_date") val releaseDate: String,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("show_name") val showName: String? = null,
) {
    companion object {
        fun listFromJson(jsonString: String): List<EpisodeSummary>? = try {
            json.decodeFromString<List<EpisodeSummary>>(jsonString)
        } catch (_: Exception) {
            null
        }
    }
}
