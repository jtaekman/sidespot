package com.sidespot.bridge

/**
 * Kotlin-side JNI bindings to the sidespot native library (libsidespot.so).
 *
 * All methods are static and map to extern "C" functions in native/src/bridge.rs.
 * Complex return values are serialized as JSON strings by the Rust side.
 */
object NativeBridge {

    init {
        System.loadLibrary("sidespot")
    }

    /** Initialize the native library (logging, runtime). Call once from Application.onCreate(). */
    external fun nativeInit()

    // -- Configuration --

    /** Set the temporary directory for audio file downloads. Must be called before sessionConnect. */
    external fun setTmpDir(path: String)

    /**
     * Update player/session configuration from a JSON string.
     * @return null on success, or an error message string on failure.
     */
    external fun playerConfigure(configJson: String): String?

    /**
     * Recreate the player with the current config.
     * @return null on success, or an error message string on failure.
     */
    external fun playerRecreate(): String?

    // -- Session management --

    /**
     * Connect to Spotify with an OAuth access token.
     * @return null on success, or an error message string on failure.
     */
    external fun sessionConnect(accessToken: String): String?

    /** Disconnect the current Spotify session. */
    external fun sessionDisconnect()

    /** Check if a session is currently connected. */
    external fun sessionIsConnected(): Boolean

    // -- Audio callback registration --

    /**
     * Register the audio callback that receives PCM data from the native player.
     * The callback object must have a method: void onAudioData(byte[] data)
     * @return null on success, or an error message string on failure.
     */
    external fun registerAudioCallback(callback: Any): String?

    // -- Player control --

    /**
     * Create the player instance. Must be called after sessionConnect() succeeds
     * and registerAudioCallback() has been called.
     * @return null on success, or an error message string on failure.
     */
    external fun playerCreate(): String?

    /**
     * Load a track by Spotify URI and optionally start playing.
     * @param trackUri e.g. "spotify:track:4uLU6hMCjMI75M1A2tKUQC"
     * @param startPlaying whether to auto-play after loading
     * @param positionMs start position in milliseconds (default 0)
     * @return null on success, or an error message string on failure.
     */
    external fun playerLoad(trackUri: String, startPlaying: Boolean, positionMs: Int = 0): String?

    /**
     * Preload a track so it starts instantly when loaded next.
     * @return null on success, or an error message string on failure.
     */
    external fun playerPreload(trackUri: String): String?

    /** Resume playback. */
    external fun playerPlay()

    /** Pause playback. */
    external fun playerPause()

    /** Seek to a position in milliseconds. */
    external fun playerSeek(positionMs: Int)

    /** Stop playback. */
    external fun playerStop()

    /**
     * Poll for the next player event.
     * @return JSON string of the event, or null if no event pending.
     */
    external fun playerPollEvent(): String?

    // -- Volume control --

    /** Set playback volume (0-65535). */
    external fun playerSetVolume(volume: Int)

    /** Get current playback volume (0-65535). */
    external fun playerGetVolume(): Int

    // -- Metadata --

    /** Get track metadata by URI. Returns JSON string. */
    external fun metadataGetTrack(trackUri: String): String?

    /** Get album metadata by URI. Returns JSON string. */
    external fun metadataGetAlbum(albumUri: String): String?

    /** Get artist metadata by URI (portrait, top tracks, albums, singles). Returns JSON string. */
    external fun metadataGetArtist(artistUri: String): String?

    /** Get playlist metadata by URI. Returns JSON string. */
    external fun metadataGetPlaylist(playlistUri: String): String?

    /** Get user's playlists. Returns JSON array of playlist summaries. */
    external fun metadataGetUserPlaylists(): String?

    /** Get user's liked songs. Returns JSON playlist info. */
    external fun metadataGetLikedSongs(): String?

    /** Search Spotify. Returns one page of JSON results for every entity type. */
    external fun metadataSearch(query: String, limit: Int, offset: Int): String?

    /** Get autoplay tracks for a context. Returns JSON array of track URIs. */
    external fun metadataGetAutoplayTracks(contextUri: String, recentTrackUrisJson: String): String?

    // -- Library write operations --

    /** Add a track to liked songs via native collection v2. Returns JSON result. */
    external fun libraryAddToLikedSongs(trackUri: String): String?

    /** Save an album to the library via native collection v2. Returns JSON result. */
    external fun librarySaveAlbum(albumUri: String): String?

    /** Save a show to the library via native collection v2. Returns JSON result. */
    external fun librarySaveShow(showUri: String): String?

    /** Remove an album from the library via native collection v2. Returns JSON result. */
    external fun libraryUnsaveAlbum(albumUri: String): String?

    /** Remove a show from the library via native collection v2. Returns JSON result. */
    external fun libraryUnsaveShow(showUri: String): String?

    /** Follow an artist via native collection v2. Returns JSON result. */
    external fun libraryFollowArtist(artistUri: String): String?

    /** Unfollow an artist via native collection v2. Returns JSON result. */
    external fun libraryUnfollowArtist(artistUri: String): String?

    /** Save (follow) a playlist to the library via rootlist v2. Returns JSON result. */
    external fun librarySavePlaylist(playlistUri: String): String?

    /** Remove (unfollow) a playlist from the library via rootlist v2. Returns JSON result. */
    external fun libraryUnsavePlaylist(playlistUri: String): String?

    /** Add a track to an existing playlist via native playlist v2. Returns JSON result. */
    external fun libraryAddToPlaylist(playlistUri: String, trackUri: String): String?

    /** Create a new playlist via native rootlist v2. Returns JSON result with URI. */
    external fun libraryCreatePlaylist(name: String): String?

    // -- Library read operations --

    /** Get user's saved albums via native collection v2. Returns JSON array. */
    external fun metadataGetSavedAlbums(): String?

    /** Get user's saved shows via native collection v2. Returns JSON array. */
    external fun metadataGetSavedShows(): String?

    /** Get user's followed artists via native collection v2. Returns JSON array. */
    external fun metadataGetFollowedArtists(): String?

    /** Get episodes for a show. Returns JSON array of episode summaries. */
    external fun metadataGetShowEpisodes(showUri: String): String?

    // -- Convenience --

    /** Initialize the native library. */
    fun init() {
        nativeInit()
    }
}
