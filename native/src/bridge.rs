//! JNI bridge functions exposed to Kotlin.
//!
//! All functions follow the JNI naming convention:
//!   Java_com_sidespot_bridge_NativeBridge_<methodName>
//!
//! Complex return values are serialized as JSON strings.

use std::sync::Arc;

use jni::JNIEnv;
use jni::objects::{JClass, JObject, JString};
use jni::sys::{jboolean, jint, jstring, JNI_TRUE, JNI_FALSE};

use crate::{library, metadata, player, session};
use crate::audio_sink;

/// Helper: convert a JNI string to a Rust String.
fn jstring_to_string(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s)
        .map(|s| s.into())
        .unwrap_or_default()
}

/// Helper: convert a Rust string to a JNI string, returning null on failure.
fn string_to_jstring(env: &mut JNIEnv, s: &str) -> jstring {
    env.new_string(s)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Helper: run an async block on the shared tokio runtime, blocking the current thread.
fn block_on<F: std::future::Future>(f: F) -> F::Output {
    session::runtime().block_on(f)
}

// ---------------------------------------------------------------------------
// Session management
// ---------------------------------------------------------------------------

/// Set the temporary directory for audio file downloads.
/// Must be called before sessionConnect.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_setTmpDir(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) {
    let dir = jstring_to_string(&mut env, &path);
    session::set_tmp_dir(&dir);
}

/// Connect to Spotify with an OAuth access token.
/// Returns null on success, or an error message string on failure.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_sessionConnect(
    mut env: JNIEnv,
    _class: JClass,
    access_token: JString,
) -> jstring {
    let token = jstring_to_string(&mut env, &access_token);

    match block_on(session::connect_with_token(&token)) {
        Ok(()) => std::ptr::null_mut(), // null == success
        Err(e) => {
            let msg = format!("{e}");
            log::error!("sessionConnect failed: {msg}");
            string_to_jstring(&mut env, &msg)
        }
    }
}

/// Disconnect the current Spotify session.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_sessionDisconnect(
    _env: JNIEnv,
    _class: JClass,
) {
    block_on(session::disconnect());
}

/// Check if a session is currently connected.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_sessionIsConnected(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    if block_on(session::is_connected()) { JNI_TRUE } else { JNI_FALSE }
}

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

/// Update player/session configuration from a JSON string.
/// Returns null on success, or an error message on failure.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_playerConfigure(
    mut env: JNIEnv,
    _class: JClass,
    config_json: JString,
) -> jstring {
    let json = jstring_to_string(&mut env, &config_json);
    match player::set_config(&json) {
        Ok(()) => std::ptr::null_mut(),
        Err(e) => {
            let msg = format!("{e}");
            log::error!("playerConfigure failed: {msg}");
            string_to_jstring(&mut env, &msg)
        }
    }
}

/// Recreate the player with the current config.
/// Returns null on success, or an error message on failure.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_playerRecreate(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    match block_on(player::recreate_player()) {
        Ok(()) => std::ptr::null_mut(),
        Err(e) => {
            let msg = format!("{e}");
            log::error!("playerRecreate failed: {msg}");
            string_to_jstring(&mut env, &msg)
        }
    }
}

// ---------------------------------------------------------------------------
// Audio callback registration
// ---------------------------------------------------------------------------

/// Register the audio callback object from Kotlin.
/// The object must implement: void onAudioData(byte[] data)
/// Returns null on success, or an error message string on failure.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_registerAudioCallback(
    mut env: JNIEnv,
    _class: JClass,
    callback: JObject,
) -> jstring {
    let jvm = match env.get_java_vm() {
        Ok(jvm) => jvm,
        Err(e) => {
            let msg = format!("failed to get JavaVM: {e}");
            log::error!("registerAudioCallback: {msg}");
            return string_to_jstring(&mut env, &msg);
        }
    };
    let callback_ref = match env.new_global_ref(callback) {
        Ok(r) => r,
        Err(e) => {
            let msg = format!("failed to create GlobalRef: {e}");
            log::error!("registerAudioCallback: {msg}");
            return string_to_jstring(&mut env, &msg);
        }
    };
    audio_sink::register_audio_callback(Arc::new(jvm), callback_ref);
    std::ptr::null_mut()
}

// ---------------------------------------------------------------------------
// Player control
// ---------------------------------------------------------------------------

/// Create the player. Must be called after sessionConnect succeeds and
/// registerAudioCallback has been called.
/// Returns null on success, or error message on failure.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_playerCreate(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    match block_on(player::create_player()) {
        Ok(()) => std::ptr::null_mut(),
        Err(e) => {
            let msg = format!("{e}");
            log::error!("playerCreate failed: {msg}");
            string_to_jstring(&mut env, &msg)
        }
    }
}

/// Load a track by Spotify URI and optionally start playing.
/// Returns null on success, or error message on failure.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_playerLoad(
    mut env: JNIEnv,
    _class: JClass,
    track_uri: JString,
    start_playing: jboolean,
    position_ms: jint,
) -> jstring {
    let uri = jstring_to_string(&mut env, &track_uri);
    let play = start_playing == JNI_TRUE;

    match block_on(player::load_track(&uri, play, position_ms as u32)) {
        Ok(()) => std::ptr::null_mut(),
        Err(e) => {
            let msg = format!("{e}");
            log::error!("playerLoad failed: {msg}");
            string_to_jstring(&mut env, &msg)
        }
    }
}

/// Preload the next track so it starts instantly.
/// Returns null on success, or error message on failure.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_playerPreload(
    mut env: JNIEnv,
    _class: JClass,
    track_uri: JString,
) -> jstring {
    let uri = jstring_to_string(&mut env, &track_uri);

    match block_on(player::preload_track(&uri)) {
        Ok(()) => std::ptr::null_mut(),
        Err(e) => {
            let msg = format!("{e}");
            log::error!("playerPreload failed: {msg}");
            string_to_jstring(&mut env, &msg)
        }
    }
}

/// Resume playback.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_playerPlay(
    _env: JNIEnv,
    _class: JClass,
) {
    if let Err(e) = block_on(player::play()) {
        log::error!("playerPlay failed: {e}");
    }
}

/// Pause playback.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_playerPause(
    _env: JNIEnv,
    _class: JClass,
) {
    if let Err(e) = block_on(player::pause()) {
        log::error!("playerPause failed: {e}");
    }
}

/// Seek to a position in milliseconds.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_playerSeek(
    _env: JNIEnv,
    _class: JClass,
    position_ms: jint,
) {
    if let Err(e) = block_on(player::seek(position_ms as u32)) {
        log::error!("playerSeek failed: {e}");
    }
}

/// Stop playback.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_playerStop(
    _env: JNIEnv,
    _class: JClass,
) {
    if let Err(e) = block_on(player::stop()) {
        log::error!("playerStop failed: {e}");
    }
}

/// Poll for the next player event. Returns a JSON string or null if no event.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_playerPollEvent(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    match block_on(player::poll_event()) {
        Some(json) => string_to_jstring(&mut env, &json),
        None => std::ptr::null_mut(),
    }
}

/// Set player volume (0-65535).
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_playerSetVolume(
    _env: JNIEnv,
    _class: JClass,
    volume: jint,
) {
    player::set_volume(volume as u16);
}

/// Get player volume (0-65535).
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_playerGetVolume(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    player::get_volume() as jint
}

// ---------------------------------------------------------------------------
// Metadata
// ---------------------------------------------------------------------------

/// Get track metadata by URI. Returns JSON string or error.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_metadataGetTrack(
    mut env: JNIEnv,
    _class: JClass,
    track_uri: JString,
) -> jstring {
    let uri = jstring_to_string(&mut env, &track_uri);
    match block_on(metadata::get_track_info(&uri)) {
        Ok(json) => string_to_jstring(&mut env, &json),
        Err(e) => {
            let msg = format!("{{\"error\":\"{e}\"}}");
            log::error!("metadataGetTrack failed: {e}");
            string_to_jstring(&mut env, &msg)
        }
    }
}

/// Get album metadata by URI. Returns JSON string or error.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_metadataGetAlbum(
    mut env: JNIEnv,
    _class: JClass,
    album_uri: JString,
) -> jstring {
    let uri = jstring_to_string(&mut env, &album_uri);
    match block_on(metadata::get_album_info(&uri)) {
        Ok(json) => string_to_jstring(&mut env, &json),
        Err(e) => {
            let msg = format!("{{\"error\":\"{e}\"}}");
            log::error!("metadataGetAlbum failed: {e}");
            string_to_jstring(&mut env, &msg)
        }
    }
}

/// Get artist metadata by URI (portrait, top tracks, albums, singles).
/// Returns JSON string or error.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_metadataGetArtist(
    mut env: JNIEnv,
    _class: JClass,
    artist_uri: JString,
) -> jstring {
    let uri = jstring_to_string(&mut env, &artist_uri);
    match block_on(metadata::get_artist_info(&uri)) {
        Ok(json) => string_to_jstring(&mut env, &json),
        Err(e) => {
            let msg = format!("{{\"error\":\"{e}\"}}");
            log::error!("metadataGetArtist failed: {e}");
            string_to_jstring(&mut env, &msg)
        }
    }
}

/// Get playlist metadata by URI. Returns JSON string or error.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_metadataGetPlaylist(
    mut env: JNIEnv,
    _class: JClass,
    playlist_uri: JString,
) -> jstring {
    let uri = jstring_to_string(&mut env, &playlist_uri);
    match block_on(metadata::get_playlist_info(&uri)) {
        Ok(json) => string_to_jstring(&mut env, &json),
        Err(e) => {
            let msg = format!("{{\"error\":\"{e}\"}}");
            log::error!("metadataGetPlaylist failed: {e}");
            string_to_jstring(&mut env, &msg)
        }
    }
}

/// Get user's playlists. Returns JSON array of playlist summaries.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_metadataGetUserPlaylists(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    match block_on(metadata::get_user_playlists()) {
        Ok(json) => string_to_jstring(&mut env, &json),
        Err(e) => {
            let msg = format!("{{\"error\":\"{e}\"}}");
            log::error!("metadataGetUserPlaylists failed: {e}");
            string_to_jstring(&mut env, &msg)
        }
    }
}

/// Get user's liked songs. Returns JSON playlist info.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_metadataGetLikedSongs(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    match block_on(metadata::get_liked_songs()) {
        Ok(json) => string_to_jstring(&mut env, &json),
        Err(e) => {
            let msg = format!("{{\"error\":\"{e}\"}}");
            log::error!("metadataGetLikedSongs failed: {e}");
            string_to_jstring(&mut env, &msg)
        }
    }
}

/// Search Spotify. Returns JSON search results with track URIs.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_metadataSearch(
    mut env: JNIEnv,
    _class: JClass,
    query: JString,
) -> jstring {
    let q = jstring_to_string(&mut env, &query);
    match block_on(metadata::search(&q)) {
        Ok(json) => string_to_jstring(&mut env, &json),
        Err(e) => {
            let msg = format!("{{\"error\":\"{e}\"}}");
            log::error!("metadataSearch failed: {e}");
            string_to_jstring(&mut env, &msg)
        }
    }
}

/// Get autoplay (recommended) tracks. Takes context URI and recent track URIs as JSON array.
/// Returns JSON array of track URI strings, or error JSON.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_metadataGetAutoplayTracks(
    mut env: JNIEnv,
    _class: JClass,
    context_uri: JString,
    recent_track_uris_json: JString,
) -> jstring {
    let uri = jstring_to_string(&mut env, &context_uri);
    let recent_json = jstring_to_string(&mut env, &recent_track_uris_json);

    let recent: Vec<String> = serde_json::from_str(&recent_json).unwrap_or_default();

    match block_on(metadata::get_autoplay_tracks(&uri, &recent)) {
        Ok(json) => string_to_jstring(&mut env, &json),
        Err(e) => {
            let msg = format!("{{\"error\":\"{e}\"}}");
            log::error!("metadataGetAutoplayTracks failed: {e}");
            string_to_jstring(&mut env, &msg)
        }
    }
}

// ---------------------------------------------------------------------------
// Library write operations
// ---------------------------------------------------------------------------

/// Add a track to the user's Liked Songs via collection v2.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_libraryAddToLikedSongs(
    mut env: JNIEnv,
    _class: JClass,
    track_uri: JString,
) -> jstring {
    let uri = jstring_to_string(&mut env, &track_uri);
    match block_on(library::add_to_liked_songs(&uri)) {
        Ok(json) => string_to_jstring(&mut env, &json),
        Err(e) => {
            let msg = format!("{{\"success\":false,\"error\":\"{e}\"}}");
            log::error!("libraryAddToLikedSongs failed: {e}");
            string_to_jstring(&mut env, &msg)
        }
    }
}

/// Save an album to the user's library via collection v2.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_librarySaveAlbum(
    mut env: JNIEnv,
    _class: JClass,
    album_uri: JString,
) -> jstring {
    let uri = jstring_to_string(&mut env, &album_uri);
    match block_on(library::save_album(&uri)) {
        Ok(json) => string_to_jstring(&mut env, &json),
        Err(e) => {
            let msg = format!("{{\"success\":false,\"error\":\"{e}\"}}");
            log::error!("librarySaveAlbum failed: {e}");
            string_to_jstring(&mut env, &msg)
        }
    }
}

/// Save a show (podcast) to the user's library via collection v2.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_librarySaveShow(
    mut env: JNIEnv,
    _class: JClass,
    show_uri: JString,
) -> jstring {
    let uri = jstring_to_string(&mut env, &show_uri);
    match block_on(library::save_show(&uri)) {
        Ok(json) => string_to_jstring(&mut env, &json),
        Err(e) => {
            let msg = format!("{{\"success\":false,\"error\":\"{e}\"}}");
            log::error!("librarySaveShow failed: {e}");
            string_to_jstring(&mut env, &msg)
        }
    }
}

/// Remove an album from the user's library via collection v2.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_libraryUnsaveAlbum(
    mut env: JNIEnv,
    _class: JClass,
    album_uri: JString,
) -> jstring {
    let uri = jstring_to_string(&mut env, &album_uri);
    match block_on(library::unsave_album(&uri)) {
        Ok(json) => string_to_jstring(&mut env, &json),
        Err(e) => {
            let msg = format!("{{\"success\":false,\"error\":\"{e}\"}}");
            log::error!("libraryUnsaveAlbum failed: {e}");
            string_to_jstring(&mut env, &msg)
        }
    }
}

/// Remove a show (podcast) from the user's library via collection v2.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_libraryUnsaveShow(
    mut env: JNIEnv,
    _class: JClass,
    show_uri: JString,
) -> jstring {
    let uri = jstring_to_string(&mut env, &show_uri);
    match block_on(library::unsave_show(&uri)) {
        Ok(json) => string_to_jstring(&mut env, &json),
        Err(e) => {
            let msg = format!("{{\"success\":false,\"error\":\"{e}\"}}");
            log::error!("libraryUnsaveShow failed: {e}");
            string_to_jstring(&mut env, &msg)
        }
    }
}

/// Unfollow an artist via collection v2.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_libraryUnfollowArtist(
    mut env: JNIEnv,
    _class: JClass,
    artist_uri: JString,
) -> jstring {
    let uri = jstring_to_string(&mut env, &artist_uri);
    match block_on(library::unfollow_artist(&uri)) {
        Ok(json) => string_to_jstring(&mut env, &json),
        Err(e) => {
            let msg = format!("{{\"success\":false,\"error\":\"{e}\"}}");
            log::error!("libraryUnfollowArtist failed: {e}");
            string_to_jstring(&mut env, &msg)
        }
    }
}

/// Follow an artist via collection v2.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_libraryFollowArtist(
    mut env: JNIEnv,
    _class: JClass,
    artist_uri: JString,
) -> jstring {
    let uri = jstring_to_string(&mut env, &artist_uri);
    match block_on(library::follow_artist(&uri)) {
        Ok(json) => string_to_jstring(&mut env, &json),
        Err(e) => {
            let msg = format!("{{\"success\":false,\"error\":\"{e}\"}}");
            log::error!("libraryFollowArtist failed: {e}");
            string_to_jstring(&mut env, &msg)
        }
    }
}

/// Save (follow) a playlist to the user's library via rootlist v2.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_librarySavePlaylist(
    mut env: JNIEnv,
    _class: JClass,
    playlist_uri: JString,
) -> jstring {
    let uri = jstring_to_string(&mut env, &playlist_uri);
    match block_on(library::save_playlist(&uri)) {
        Ok(json) => string_to_jstring(&mut env, &json),
        Err(e) => {
            let msg = format!("{{\"success\":false,\"error\":\"{e}\"}}");
            log::error!("librarySavePlaylist failed: {e}");
            string_to_jstring(&mut env, &msg)
        }
    }
}

/// Remove (unfollow) a playlist from the user's library via rootlist v2.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_libraryUnsavePlaylist(
    mut env: JNIEnv,
    _class: JClass,
    playlist_uri: JString,
) -> jstring {
    let uri = jstring_to_string(&mut env, &playlist_uri);
    match block_on(library::unsave_playlist(&uri)) {
        Ok(json) => string_to_jstring(&mut env, &json),
        Err(e) => {
            let msg = format!("{{\"success\":false,\"error\":\"{e}\"}}");
            log::error!("libraryUnsavePlaylist failed: {e}");
            string_to_jstring(&mut env, &msg)
        }
    }
}

/// Add a track to an existing playlist via playlist v2.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_libraryAddToPlaylist(
    mut env: JNIEnv,
    _class: JClass,
    playlist_uri: JString,
    track_uri: JString,
) -> jstring {
    let pl_uri = jstring_to_string(&mut env, &playlist_uri);
    let tr_uri = jstring_to_string(&mut env, &track_uri);
    match block_on(library::add_to_playlist(&pl_uri, &tr_uri)) {
        Ok(json) => string_to_jstring(&mut env, &json),
        Err(e) => {
            let msg = format!("{{\"success\":false,\"error\":\"{e}\"}}");
            log::error!("libraryAddToPlaylist failed: {e}");
            string_to_jstring(&mut env, &msg)
        }
    }
}

/// Create a new playlist via rootlist v2. Returns JSON with the new playlist URI.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_libraryCreatePlaylist(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
) -> jstring {
    let n = jstring_to_string(&mut env, &name);
    match block_on(library::create_playlist(&n)) {
        Ok(json) => string_to_jstring(&mut env, &json),
        Err(e) => {
            let msg = format!("{{\"success\":false,\"error\":\"{e}\"}}");
            log::error!("libraryCreatePlaylist failed: {e}");
            string_to_jstring(&mut env, &msg)
        }
    }
}

// ---------------------------------------------------------------------------
// Library read operations
// ---------------------------------------------------------------------------

/// Get user's saved albums via collection v2. Returns JSON array.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_metadataGetSavedAlbums(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    match block_on(library::get_saved_albums()) {
        Ok(json) => string_to_jstring(&mut env, &json),
        Err(e) => {
            let msg = format!("{{\"error\":\"{e}\"}}");
            log::error!("metadataGetSavedAlbums failed: {e}");
            string_to_jstring(&mut env, &msg)
        }
    }
}

/// Get user's saved shows via collection v2. Returns JSON array.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_metadataGetSavedShows(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    match block_on(library::get_saved_shows()) {
        Ok(json) => string_to_jstring(&mut env, &json),
        Err(e) => {
            let msg = format!("{{\"error\":\"{e}\"}}");
            log::error!("metadataGetSavedShows failed: {e}");
            string_to_jstring(&mut env, &msg)
        }
    }
}

/// Get user's followed artists via collection v2. Returns JSON array.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_metadataGetFollowedArtists(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    match block_on(library::get_followed_artists()) {
        Ok(json) => string_to_jstring(&mut env, &json),
        Err(e) => {
            let msg = format!("{{\"error\":\"{e}\"}}");
            log::error!("metadataGetFollowedArtists failed: {e}");
            string_to_jstring(&mut env, &msg)
        }
    }
}

/// Get episodes for a show. Returns JSON array of episode summaries.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_metadataGetShowEpisodes(
    mut env: JNIEnv,
    _class: JClass,
    show_uri: JString,
) -> jstring {
    let uri = jstring_to_string(&mut env, &show_uri);
    match block_on(library::get_show_episodes(&uri)) {
        Ok(json) => string_to_jstring(&mut env, &json),
        Err(e) => {
            let msg = format!("{{\"error\":\"{e}\"}}");
            log::error!("metadataGetShowEpisodes failed: {e}");
            string_to_jstring(&mut env, &msg)
        }
    }
}
