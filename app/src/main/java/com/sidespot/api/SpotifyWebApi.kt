package com.sidespot.api

import com.sidespot.auth.AuthManager
import com.sidespot.bridge.EpisodeSummary
import com.sidespot.bridge.ShowSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

sealed class ApiResult {
    data object Success : ApiResult()
    data class Error(val message: String) : ApiResult()
}

class SpotifyWebApi(private val authManager: AuthManager) {

    companion object {
        private const val BASE_URL = "https://api.spotify.com/v1"

        private fun HttpURLConnection.applyDefaults(token: String): HttpURLConnection {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer $token")
            return this
        }
    }

    /**
     * Unfollow (remove) a playlist from the user's library.
     * DELETE /v1/playlists/{playlist_id}/followers
     */
    suspend fun unfollowPlaylist(playlistUri: String): ApiResult = withContext(Dispatchers.IO) {
        val token = authManager.getValidAccessToken()
            ?: return@withContext ApiResult.Error("No access token")
        val playlistId = playlistUri.removePrefix("spotify:playlist:")
        val conn = (URL("$BASE_URL/playlists/$playlistId/followers")
            .openConnection() as HttpURLConnection).applyDefaults(token)
        try {
            conn.requestMethod = "DELETE"
            if (conn.responseCode in 200..299) {
                ApiResult.Success
            } else {
                val err = conn.errorStream?.bufferedReader()?.readText()
                android.util.Log.w("SpotifyWebApi", "unfollowPlaylist HTTP ${conn.responseCode}: $err")
                ApiResult.Error("HTTP ${conn.responseCode}")
            }
        } catch (e: Exception) {
            android.util.Log.e("SpotifyWebApi", "unfollowPlaylist failed", e)
            ApiResult.Error("Failed: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    data class RecentAlbumInfo(
        val uri: String,
        val name: String,
        val artistName: String,
        val imageUrl: String? = null,
    )

    data class RecentlyPlayedOrder(
        /** All context URIs in recency order (playlists + albums interleaved). */
        val orderedUris: List<String> = emptyList(),
        /** Metadata for recently played albums, keyed by URI. */
        val albumDetails: Map<String, RecentAlbumInfo> = emptyMap(),
        /** Most recent played_at timestamp per context URI (epoch millis). */
        val playedAtMs: Map<String, Long> = emptyMap(),
    )

    /**
     * Fetch playlist and album URIs from the user's recently-played history,
     * deduplicated and ordered by most recently played first.
     * GET /v1/me/player/recently-played?limit=50
     */
    suspend fun getRecentlyPlayedOrder(): RecentlyPlayedOrder = withContext(Dispatchers.IO) {
        val token = authManager.getValidAccessToken()
            ?: return@withContext RecentlyPlayedOrder()
        val seenUris = linkedSetOf<String>()
        val albumDetails = mutableMapOf<String, RecentAlbumInfo>()
        val playedAtMap = mutableMapOf<String, Long>()
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        var beforeCursor: String? = null

        for (page in 0 until 20) {
            val urlStr = buildString {
                append("$BASE_URL/me/player/recently-played?limit=50")
                if (beforeCursor != null) append("&before=$beforeCursor")
            }
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).applyDefaults(token)
            try {
                if (conn.responseCode !in 200..299) {
                    val err = conn.errorStream?.bufferedReader()?.readText()
                    android.util.Log.w("SpotifyWebApi", "getRecentlyPlayed HTTP ${conn.responseCode}: $err")
                    break
                }
                val body = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(body)
                val items = json.optJSONArray("items")
                if (items == null || items.length() == 0) break

                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    val context = item.optJSONObject("context") ?: continue
                    val uri = context.getString("uri")
                    val type = context.optString("type")

                    if (type == "playlist" || type == "album") {
                        seenUris.add(uri)
                        // Keep the first (most recent) timestamp per URI
                        if (uri !in playedAtMap) {
                            val playedAt = item.optString("played_at", "")
                            if (playedAt.isNotEmpty()) {
                                try {
                                    playedAtMap[uri] = isoFormat.parse(playedAt)?.time ?: 0L
                                } catch (_: Exception) {}
                            }
                        }
                    }

                    if (type == "album" && uri !in albumDetails) {
                        val track = item.optJSONObject("track")
                        val album = track?.optJSONObject("album")
                        if (album != null) {
                            val trackAlbumUri = album.optString("uri", "")
                            if (trackAlbumUri == uri) {
                                val artists = album.optJSONArray("artists")
                                val artistName = if (artists != null) {
                                    (0 until artists.length()).mapNotNull {
                                        artists.optJSONObject(it)?.optString("name")
                                    }.joinToString(", ")
                                } else ""
                                val images = album.optJSONArray("images")
                                val imageUrl = if (images != null && images.length() > 0)
                                    images.getJSONObject(0).getString("url") else null
                                albumDetails[uri] = RecentAlbumInfo(
                                    uri = uri,
                                    name = album.optString("name", ""),
                                    artistName = artistName,
                                    imageUrl = imageUrl,
                                )
                            }
                        }
                    }
                }

                val cursors = json.optJSONObject("cursors")
                beforeCursor = cursors?.optString("before", "")?.ifEmpty { null }
                if (beforeCursor == null) break
            } catch (e: Exception) {
                android.util.Log.e("SpotifyWebApi", "getRecentlyPlayed failed on page $page", e)
                break
            } finally {
                conn.disconnect()
            }
        }

        // Batch-fetch metadata for album URIs whose track metadata didn't match
        val missingAlbumUris = seenUris.filter { it.startsWith("spotify:album:") && it !in albumDetails }
        if (missingAlbumUris.isNotEmpty()) {
            for (chunk in missingAlbumUris.chunked(20)) {
                val ids = chunk.map { it.removePrefix("spotify:album:") }.joinToString(",")
                val conn = (URL("$BASE_URL/albums?ids=$ids").openConnection() as HttpURLConnection).applyDefaults(token)
                try {
                    if (conn.responseCode in 200..299) {
                        val body = conn.inputStream.bufferedReader().readText()
                        val albums = JSONObject(body).optJSONArray("albums") ?: continue
                        for (i in 0 until albums.length()) {
                            val album = albums.optJSONObject(i) ?: continue
                            val albumUri = album.optString("uri", "")
                            if (albumUri.isEmpty() || albumUri in albumDetails) continue
                            val artists = album.optJSONArray("artists")
                            val artistName = if (artists != null) {
                                (0 until artists.length()).mapNotNull {
                                    artists.optJSONObject(it)?.optString("name")
                                }.joinToString(", ")
                            } else ""
                            val images = album.optJSONArray("images")
                            val imageUrl = if (images != null && images.length() > 0)
                                images.getJSONObject(0).getString("url") else null
                            albumDetails[albumUri] = RecentAlbumInfo(
                                uri = albumUri,
                                name = album.optString("name", ""),
                                artistName = artistName,
                                imageUrl = imageUrl,
                            )
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SpotifyWebApi", "batch album fetch failed", e)
                } finally {
                    conn.disconnect()
                }
            }
        }

        RecentlyPlayedOrder(seenUris.toList(), albumDetails, playedAtMap)
    }

    /**
     * Fetch unplayed episodes across all saved shows.
     * For each show, calls GET /v1/shows/{show_id}/episodes?limit=10
     * and filters to episodes where resume_point.fully_played == false.
     * Results are sorted by release_date descending (newest first).
     */
    suspend fun getUnplayedEpisodesForShows(shows: List<ShowSummary>): List<EpisodeSummary> = withContext(Dispatchers.IO) {
        val token = authManager.getValidAccessToken() ?: return@withContext emptyList()
        val allEpisodes = mutableListOf<EpisodeSummary>()

        for (show in shows) {
            val showId = show.uri.removePrefix("spotify:show:")
            val conn = (URL("$BASE_URL/shows/$showId/episodes?limit=10")
                .openConnection() as HttpURLConnection).applyDefaults(token)
            try {
                if (conn.responseCode !in 200..299) {
                    val err = conn.errorStream?.bufferedReader()?.readText()
                    android.util.Log.w("SpotifyWebApi", "getShowEpisodes HTTP ${conn.responseCode}: $err")
                    continue
                }
                val body = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(body)
                val items = json.optJSONArray("items") ?: continue

                for (i in 0 until items.length()) {
                    val ep = items.optJSONObject(i) ?: continue
                    val resumePoint = ep.optJSONObject("resume_point")
                    val fullyPlayed = resumePoint?.optBoolean("fully_played", false) ?: false
                    if (fullyPlayed) continue

                    val images = ep.optJSONArray("images")
                    val imageUrl = if (images != null && images.length() > 0)
                        images.getJSONObject(0).getString("url") else null

                    allEpisodes.add(EpisodeSummary(
                        uri = ep.getString("uri"),
                        name = ep.getString("name"),
                        description = ep.optString("description", ""),
                        durationMs = ep.optInt("duration_ms", 0),
                        releaseDate = ep.optString("release_date", ""),
                        imageUrl = imageUrl,
                        showName = show.name,
                    ))
                }
            } catch (e: Exception) {
                android.util.Log.e("SpotifyWebApi", "getShowEpisodes failed for ${show.name}", e)
            } finally {
                conn.disconnect()
            }
        }

        allEpisodes.sortedByDescending { it.releaseDate }
    }
}
