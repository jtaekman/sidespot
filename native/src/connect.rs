use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Mutex, OnceLock};

use librespot_connect::{ConnectConfig, LoadRequest, LoadRequestOptions, PlayingTrack, Spirc};
use librespot_core::authentication::Credentials;
use librespot_core::config::DeviceType;
use librespot_core::SpotifyUri;
use librespot_playback::mixer::Mixer;
use tokio::task::JoinHandle;

use crate::error::{Result, SidespotError};
use crate::session;

static SPIRC: OnceLock<Mutex<Option<Spirc>>> = OnceLock::new();
static SPIRC_TASK: OnceLock<Mutex<Option<JoinHandle<()>>>> = OnceLock::new();
static CONNECT_ACTIVE: AtomicBool = AtomicBool::new(false);

fn spirc_slot() -> &'static Mutex<Option<Spirc>> {
    SPIRC.get_or_init(|| Mutex::new(None))
}

fn task_slot() -> &'static Mutex<Option<JoinHandle<()>>> {
    SPIRC_TASK.get_or_init(|| Mutex::new(None))
}

/// Start Spotify Connect (Spirc). Creates session, player, mixer, and Spirc
/// in one step. The device will appear in Spotify's device picker.
pub async fn start(access_token: &str, device_name: &str) -> Result<()> {
    // Initialize event channel (shared with non-Connect path)
    crate::player::init_event_channel();

    let sess = session::create_session_unconnected();
    let credentials = Credentials::with_access_token(access_token);

    let (player, mixer) = crate::player::build_player(&sess)?;

    // Spawn event forwarder so player events flow to Kotlin
    crate::player::spawn_event_forwarder(&player);

    let config = ConnectConfig {
        name: device_name.to_string(),
        device_type: DeviceType::Smartphone,
        emit_set_queue_events: true,
        ..ConnectConfig::default()
    };

    let mixer_dyn: std::sync::Arc<dyn Mixer> = mixer;

    let (spirc, spirc_task) = Spirc::new(config, sess.clone(), credentials, player, mixer_dyn)
        .await
        .map_err(|e| SidespotError::Connect(format!("Spirc::new failed: {e}")))?;

    // Store the session globally so metadata queries work
    session::store_session(sess).await;

    // Spawn the SpircTask event loop
    let handle = session::runtime().spawn(spirc_task);

    *spirc_slot().lock().unwrap() = Some(spirc);
    *task_slot().lock().unwrap() = Some(handle);
    CONNECT_ACTIVE.store(true, Ordering::SeqCst);

    // Activate this device so it appears in the picker
    if let Some(spirc) = spirc_slot().lock().unwrap().as_ref() {
        spirc.activate().map_err(|e| SidespotError::Connect(format!("activate failed: {e}")))?;
    }

    log::info!("Spotify Connect started as '{device_name}'");
    Ok(())
}

/// Stop Spotify Connect. Shuts down Spirc and the SpircTask.
pub async fn stop() -> Result<()> {
    let spirc = spirc_slot().lock().unwrap().take();
    if let Some(spirc) = spirc {
        let _ = spirc.shutdown();
    }

    let handle = task_slot().lock().unwrap().take();
    if let Some(handle) = handle {
        let _ = handle.await;
    }

    CONNECT_ACTIVE.store(false, Ordering::SeqCst);
    log::info!("Spotify Connect stopped");
    Ok(())
}

/// Check if Connect mode is active.
pub fn is_active() -> bool {
    CONNECT_ACTIVE.load(Ordering::SeqCst)
}

// -- Forwarding functions that delegate to Spirc --

pub fn play() -> Result<()> {
    let lock = spirc_slot().lock().unwrap();
    let spirc = lock.as_ref().ok_or(SidespotError::Connect("not started".into()))?;
    spirc.play().map_err(|e| SidespotError::Connect(format!("{e}")))
}

pub fn pause() -> Result<()> {
    let lock = spirc_slot().lock().unwrap();
    let spirc = lock.as_ref().ok_or(SidespotError::Connect("not started".into()))?;
    spirc.pause().map_err(|e| SidespotError::Connect(format!("{e}")))
}

pub fn next() -> Result<()> {
    let lock = spirc_slot().lock().unwrap();
    let spirc = lock.as_ref().ok_or(SidespotError::Connect("not started".into()))?;
    spirc.next().map_err(|e| SidespotError::Connect(format!("{e}")))
}

pub fn prev() -> Result<()> {
    let lock = spirc_slot().lock().unwrap();
    let spirc = lock.as_ref().ok_or(SidespotError::Connect("not started".into()))?;
    spirc.prev().map_err(|e| SidespotError::Connect(format!("{e}")))
}

pub fn seek(position_ms: u32) -> Result<()> {
    let lock = spirc_slot().lock().unwrap();
    let spirc = lock.as_ref().ok_or(SidespotError::Connect("not started".into()))?;
    spirc.set_position_ms(position_ms).map_err(|e| SidespotError::Connect(format!("{e}")))
}

pub fn set_volume(volume: u16) -> Result<()> {
    let lock = spirc_slot().lock().unwrap();
    let spirc = lock.as_ref().ok_or(SidespotError::Connect("not started".into()))?;
    spirc.set_volume(volume).map_err(|e| SidespotError::Connect(format!("{e}")))
}

#[allow(dead_code)]
pub fn shuffle(enabled: bool) -> Result<()> {
    let lock = spirc_slot().lock().unwrap();
    let spirc = lock.as_ref().ok_or(SidespotError::Connect("not started".into()))?;
    spirc.shuffle(enabled).map_err(|e| SidespotError::Connect(format!("{e}")))
}

#[allow(dead_code)]
pub fn repeat(enabled: bool) -> Result<()> {
    let lock = spirc_slot().lock().unwrap();
    let spirc = lock.as_ref().ok_or(SidespotError::Connect("not started".into()))?;
    spirc.repeat(enabled).map_err(|e| SidespotError::Connect(format!("{e}")))
}

pub fn load_context(context_uri: &str, start_playing: bool, track_index: u32) -> Result<()> {
    let lock = spirc_slot().lock().unwrap();
    let spirc = lock.as_ref().ok_or(SidespotError::Connect("not started".into()))?;
    let options = LoadRequestOptions {
        start_playing,
        playing_track: Some(PlayingTrack::Index(track_index)),
        ..Default::default()
    };
    let request = LoadRequest::from_context_uri(context_uri.to_string(), options);
    spirc.load(request).map_err(|e| SidespotError::Connect(format!("{e}")))
}

pub fn load_tracks(track_uris: Vec<String>, start_playing: bool, track_index: u32) -> Result<()> {
    let lock = spirc_slot().lock().unwrap();
    let spirc = lock.as_ref().ok_or(SidespotError::Connect("not started".into()))?;
    let options = LoadRequestOptions {
        start_playing,
        playing_track: Some(PlayingTrack::Index(track_index)),
        ..Default::default()
    };
    let request = LoadRequest::from_tracks(track_uris, options);
    spirc.load(request).map_err(|e| SidespotError::Connect(format!("{e}")))
}

#[allow(dead_code)]
pub fn add_to_queue(uri: &str) -> Result<()> {
    let lock = spirc_slot().lock().unwrap();
    let spirc = lock.as_ref().ok_or(SidespotError::Connect("not started".into()))?;
    let spotify_uri = SpotifyUri::from_uri(uri)
        .map_err(|e| SidespotError::Connect(format!("invalid URI: {e}")))?;
    spirc.add_to_queue(spotify_uri).map_err(|e| SidespotError::Connect(format!("{e}")))
}

pub fn activate() -> Result<()> {
    let lock = spirc_slot().lock().unwrap();
    let spirc = lock.as_ref().ok_or(SidespotError::Connect("not started".into()))?;
    spirc.activate().map_err(|e| SidespotError::Connect(format!("{e}")))
}
