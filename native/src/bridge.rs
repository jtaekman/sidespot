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

use crate::{connect, metadata, player, session};
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
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_registerAudioCallback(
    env: JNIEnv,
    _class: JClass,
    callback: JObject,
) {
    let jvm = env.get_java_vm().expect("failed to get JavaVM");
    let callback_ref = env.new_global_ref(callback).expect("failed to create GlobalRef");
    audio_sink::register_audio_callback(Arc::new(jvm), callback_ref);
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
) -> jstring {
    let uri = jstring_to_string(&mut env, &track_uri);
    let play = start_playing == JNI_TRUE;

    match block_on(player::load_track(&uri, play)) {
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

/// Resume playback. Routes through Spirc when Connect is active.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_playerPlay(
    _env: JNIEnv,
    _class: JClass,
) {
    let result = if connect::is_active() {
        connect::play()
    } else {
        block_on(player::play())
    };
    if let Err(e) = result {
        log::error!("playerPlay failed: {e}");
    }
}

/// Pause playback. Routes through Spirc when Connect is active.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_playerPause(
    _env: JNIEnv,
    _class: JClass,
) {
    let result = if connect::is_active() {
        connect::pause()
    } else {
        block_on(player::pause())
    };
    if let Err(e) = result {
        log::error!("playerPause failed: {e}");
    }
}

/// Seek to a position in milliseconds. Routes through Spirc when Connect is active.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_playerSeek(
    _env: JNIEnv,
    _class: JClass,
    position_ms: jint,
) {
    let result = if connect::is_active() {
        connect::seek(position_ms as u32)
    } else {
        block_on(player::seek(position_ms as u32))
    };
    if let Err(e) = result {
        log::error!("playerSeek failed: {e}");
    }
}

/// Stop playback. Routes through Spirc when Connect is active.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_playerStop(
    _env: JNIEnv,
    _class: JClass,
) {
    let result = if connect::is_active() {
        connect::pause() // Spirc has no stop, use pause
    } else {
        block_on(player::stop())
    };
    if let Err(e) = result {
        log::error!("playerStop failed: {e}");
    }
}

/// Skip to next track. Only effective in Connect mode.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_playerNext(
    _env: JNIEnv,
    _class: JClass,
) {
    if connect::is_active() {
        if let Err(e) = connect::next() {
            log::error!("playerNext failed: {e}");
        }
    }
}

/// Skip to previous track. Only effective in Connect mode.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_playerPrev(
    _env: JNIEnv,
    _class: JClass,
) {
    if connect::is_active() {
        if let Err(e) = connect::prev() {
            log::error!("playerPrev failed: {e}");
        }
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

/// Set player volume (0-65535). Routes through Spirc when Connect is active.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_playerSetVolume(
    _env: JNIEnv,
    _class: JClass,
    volume: jint,
) {
    if connect::is_active() {
        if let Err(e) = connect::set_volume(volume as u16) {
            log::error!("playerSetVolume (Connect) failed: {e}");
        }
    } else {
        player::set_volume(volume as u16);
    }
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

// ---------------------------------------------------------------------------
// Spotify Connect
// ---------------------------------------------------------------------------

/// Start Spotify Connect mode.
/// Returns null on success, or an error message on failure.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_connectStart(
    mut env: JNIEnv,
    _class: JClass,
    access_token: JString,
    device_name: JString,
) -> jstring {
    let token = jstring_to_string(&mut env, &access_token);
    let name = jstring_to_string(&mut env, &device_name);

    match block_on(connect::start(&token, &name)) {
        Ok(()) => std::ptr::null_mut(),
        Err(e) => {
            let msg = format!("{e}");
            log::error!("connectStart failed: {msg}");
            string_to_jstring(&mut env, &msg)
        }
    }
}

/// Stop Spotify Connect mode.
/// Returns null on success, or an error message on failure.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_connectStop(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    match block_on(connect::stop()) {
        Ok(()) => std::ptr::null_mut(),
        Err(e) => {
            let msg = format!("{e}");
            log::error!("connectStop failed: {msg}");
            string_to_jstring(&mut env, &msg)
        }
    }
}

/// Check if Spotify Connect mode is active.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_connectIsActive(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    if connect::is_active() { JNI_TRUE } else { JNI_FALSE }
}

/// Load a context (playlist/album URI) via Spirc.
/// Returns null on success, or an error message on failure.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_connectLoadContext(
    mut env: JNIEnv,
    _class: JClass,
    context_uri: JString,
    start_playing: jboolean,
    track_index: jint,
) -> jstring {
    let uri = jstring_to_string(&mut env, &context_uri);
    let play = start_playing == JNI_TRUE;

    match connect::load_context(&uri, play, track_index as u32) {
        Ok(()) => std::ptr::null_mut(),
        Err(e) => {
            let msg = format!("{e}");
            log::error!("connectLoadContext failed: {msg}");
            string_to_jstring(&mut env, &msg)
        }
    }
}

/// Load a list of track URIs via Spirc.
/// Returns null on success, or an error message on failure.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_connectLoadTracks(
    mut env: JNIEnv,
    _class: JClass,
    tracks_json: JString,
    start_playing: jboolean,
    track_index: jint,
) -> jstring {
    let json = jstring_to_string(&mut env, &tracks_json);
    let play = start_playing == JNI_TRUE;

    let uris: Vec<String> = match serde_json::from_str(&json) {
        Ok(v) => v,
        Err(e) => {
            let msg = format!("invalid tracks JSON: {e}");
            log::error!("connectLoadTracks: {msg}");
            return string_to_jstring(&mut env, &msg);
        }
    };

    match connect::load_tracks(uris, play, track_index as u32) {
        Ok(()) => std::ptr::null_mut(),
        Err(e) => {
            let msg = format!("{e}");
            log::error!("connectLoadTracks failed: {msg}");
            string_to_jstring(&mut env, &msg)
        }
    }
}

/// Activate this device as the Connect target.
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_sidespot_bridge_NativeBridge_connectActivate(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    match connect::activate() {
        Ok(()) => std::ptr::null_mut(),
        Err(e) => {
            let msg = format!("{e}");
            log::error!("connectActivate failed: {msg}");
            string_to_jstring(&mut env, &msg)
        }
    }
}
