use std::sync::{Arc, OnceLock, Mutex as StdMutex};

use librespot_core::SpotifyUri;
use librespot_playback::config::{AudioFormat, Bitrate, PlayerConfig};
use librespot_playback::mixer::{Mixer, MixerConfig, softmixer::SoftMixer};
use librespot_playback::player::{Player, PlayerEvent};
use serde::{Deserialize, Serialize};
use tokio::sync::{Mutex, mpsc};

use crate::error::{SidespotError, Result};
use crate::session;

/// Global player instance.
static PLAYER: OnceLock<Mutex<Option<Arc<Player>>>> = OnceLock::new();

/// Global mixer instance for volume control (wrapped in StdMutex for replacement on recreate).
static MIXER: OnceLock<StdMutex<Arc<SoftMixer>>> = OnceLock::new();

/// Channel for player events flowing to Kotlin.
static EVENT_TX: OnceLock<mpsc::UnboundedSender<PlayerEventInfo>> = OnceLock::new();
static EVENT_RX: OnceLock<Mutex<mpsc::UnboundedReceiver<PlayerEventInfo>>> = OnceLock::new();

/// Application configuration stored from Kotlin side.
static APP_CONFIG: OnceLock<StdMutex<AppConfig>> = OnceLock::new();

#[derive(Debug, Clone, Deserialize, Default)]
pub struct AppConfig {
    pub bitrate: Option<u32>,
    pub normalisation: Option<bool>,
    pub gapless: Option<bool>,
    pub autoplay: Option<bool>,
}

pub(crate) fn config_slot() -> &'static StdMutex<AppConfig> {
    APP_CONFIG.get_or_init(|| StdMutex::new(AppConfig::default()))
}

/// Update the stored app config from a JSON string.
pub fn set_config(json: &str) -> Result<()> {
    let new_config: AppConfig = serde_json::from_str(json)
        .map_err(|e| SidespotError::Player(format!("invalid config JSON: {e}")))?;
    let mut slot = config_slot().lock().unwrap();
    *slot = new_config;
    log::info!("Config updated: {:?}", *slot);
    Ok(())
}

fn player_slot() -> &'static Mutex<Option<Arc<Player>>> {
    PLAYER.get_or_init(|| Mutex::new(None))
}

fn mixer_slot() -> &'static StdMutex<Arc<SoftMixer>> {
    MIXER.get_or_init(|| {
        StdMutex::new(Arc::new(
            SoftMixer::open(MixerConfig::default())
                .expect("failed to create initial mixer"),
        ))
    })
}

/// Simplified player event for the Kotlin layer.
#[derive(Debug, Clone, Serialize)]
#[serde(tag = "type")]
pub enum PlayerEventInfo {
    #[serde(rename = "playing")]
    Playing {
        track_id: String,
        position_ms: u32,
    },
    #[serde(rename = "paused")]
    Paused {
        track_id: String,
        position_ms: u32,
    },
    #[serde(rename = "stopped")]
    Stopped {
        track_id: String,
    },
    #[serde(rename = "loading")]
    Loading {
        track_id: String,
        position_ms: u32,
    },
    #[serde(rename = "end_of_track")]
    EndOfTrack {
        track_id: String,
    },
    #[serde(rename = "error")]
    Error {
        message: String,
    },
    #[serde(rename = "timeout")]
    Timeout {
        message: String,
    },
}

/// Helper to convert a SpotifyUri to its string representation.
fn uri_to_string(uri: &SpotifyUri) -> String {
    uri.to_uri()
}

/// Initialize the event channel (call once).
fn init_event_channel() {
    let (tx, rx) = mpsc::unbounded_channel();
    let _ = EVENT_TX.set(tx);
    let _ = EVENT_RX.set(Mutex::new(rx));
}

/// Create and store the player. Must be called after session is connected.
pub async fn create_player() -> Result<()> {
    init_event_channel();

    let session = session::get_session().await?;
    let mut config = PlayerConfig::default();
    // Enable position updates every second for the seek bar
    config.position_update_interval = Some(std::time::Duration::from_secs(1));
    // Play downloaded copies whenever they exist, online or not
    config.offline_source = Some(crate::offline::OfflineStore::source());

    // Apply stored app config
    {
        let app_cfg = config_slot().lock().unwrap();
        if let Some(bitrate) = app_cfg.bitrate {
            config.bitrate = match bitrate {
                96 => Bitrate::Bitrate96,
                160 => Bitrate::Bitrate160,
                _ => Bitrate::Bitrate320,
            };
        }
        if let Some(norm) = app_cfg.normalisation {
            config.normalisation = norm;
        }
        if let Some(gapless) = app_cfg.gapless {
            config.gapless = gapless;
        }
    }

    let format = AudioFormat::default(); // S16

    // Create a new SoftMixer and store it (replaces previous on recreate)
    let mixer = Arc::new(
        SoftMixer::open(MixerConfig::default())
            .map_err(|e| SidespotError::Player(format!("failed to create mixer: {e}")))?,
    );
    *mixer_slot().lock().unwrap() = mixer.clone();
    let soft_volume = mixer.get_soft_volume();

    let player = Player::new(
        config,
        session,
        soft_volume,
        move || {
            // Create our JNI audio sink
            Box::new(crate::audio_sink::JniAudioSink::new(format))
        },
    );

    // Get an event channel and spawn a task to forward events
    let mut event_rx = player.get_player_event_channel();
    let event_tx = EVENT_TX.get().cloned();

    session::runtime().spawn(async move {
        while let Some(event) = event_rx.recv().await {
            let info = match event {
                PlayerEvent::Playing {
                    track_id,
                    position_ms,
                    ..
                } => PlayerEventInfo::Playing {
                    track_id: uri_to_string(&track_id),
                    position_ms,
                },
                PlayerEvent::Paused {
                    track_id,
                    position_ms,
                    ..
                } => PlayerEventInfo::Paused {
                    track_id: uri_to_string(&track_id),
                    position_ms,
                },
                PlayerEvent::Stopped { track_id, .. } => PlayerEventInfo::Stopped {
                    track_id: uri_to_string(&track_id),
                },
                PlayerEvent::Loading {
                    track_id,
                    position_ms,
                    ..
                } => PlayerEventInfo::Loading {
                    track_id: uri_to_string(&track_id),
                    position_ms,
                },
                PlayerEvent::EndOfTrack { track_id, .. } => PlayerEventInfo::EndOfTrack {
                    track_id: uri_to_string(&track_id),
                },
                PlayerEvent::PositionChanged {
                    track_id,
                    position_ms,
                    ..
                } => PlayerEventInfo::Playing {
                    track_id: uri_to_string(&track_id),
                    position_ms,
                },
                PlayerEvent::Unavailable { track_id, .. } => PlayerEventInfo::Error {
                    message: format!("Track unavailable: {}", uri_to_string(&track_id)),
                },
                PlayerEvent::Timeout { track_id, .. } => PlayerEventInfo::Timeout {
                    message: format!("Track load timed out: {}", uri_to_string(&track_id)),
                },
                _ => continue, // Skip other events for now
            };

            if let Some(tx) = &event_tx {
                let _ = tx.send(info);
            }
        }
    });

    let mut slot = player_slot().lock().await;
    *slot = Some(player);

    log::info!("Player created successfully");
    Ok(())
}

/// Load and play a track by Spotify URI (e.g., "spotify:track:4uLU6hMCjMI75M1A2tKUQC").
pub async fn load_track(uri: &str, start_playing: bool, position_ms: u32) -> Result<()> {
    let slot = player_slot().lock().await;
    let player = slot.as_ref().ok_or(SidespotError::NoPlayer)?;

    let spotify_uri = SpotifyUri::from_uri(uri)
        .map_err(|e| SidespotError::Player(format!("invalid URI '{uri}': {e}")))?;

    player.load(spotify_uri, start_playing, position_ms);
    log::info!("Loaded track: {uri}, start_playing={start_playing}, position_ms={position_ms}");
    Ok(())
}

/// Preload a track so it starts instantly when loaded next.
pub async fn preload_track(uri: &str) -> Result<()> {
    let slot = player_slot().lock().await;
    let player = slot.as_ref().ok_or(SidespotError::NoPlayer)?;

    let spotify_uri = SpotifyUri::from_uri(uri)
        .map_err(|e| SidespotError::Player(format!("invalid preload URI '{uri}': {e}")))?;

    player.preload(spotify_uri);
    log::info!("Preloading track: {uri}");
    Ok(())
}

/// Resume playback.
pub async fn play() -> Result<()> {
    let slot = player_slot().lock().await;
    let player = slot.as_ref().ok_or(SidespotError::NoPlayer)?;
    player.play();
    Ok(())
}

/// Pause playback.
pub async fn pause() -> Result<()> {
    let slot = player_slot().lock().await;
    let player = slot.as_ref().ok_or(SidespotError::NoPlayer)?;
    player.pause();
    Ok(())
}

/// Seek to position in milliseconds.
pub async fn seek(position_ms: u32) -> Result<()> {
    let slot = player_slot().lock().await;
    let player = slot.as_ref().ok_or(SidespotError::NoPlayer)?;
    player.seek(position_ms);
    Ok(())
}

/// Stop playback.
pub async fn stop() -> Result<()> {
    let slot = player_slot().lock().await;
    let player = slot.as_ref().ok_or(SidespotError::NoPlayer)?;
    player.stop();
    Ok(())
}

/// Poll for the next player event (non-blocking).
/// Returns JSON-serialized event or None.
pub async fn poll_event() -> Option<String> {
    let rx = EVENT_RX.get()?;
    let mut rx = rx.lock().await;
    match rx.try_recv() {
        Ok(event) => serde_json::to_string(&event).ok(),
        Err(_) => None,
    }
}

/// Set the playback volume (0-65535).
pub fn set_volume(volume: u16) {
    if let Some(slot) = MIXER.get() {
        slot.lock().unwrap().set_volume(volume);
        log::debug!("Volume set to {volume}");
    }
}

/// Get the current playback volume (0-65535).
pub fn get_volume() -> u16 {
    MIXER.get()
        .map(|slot| slot.lock().unwrap().volume())
        .unwrap_or(0)
}

/// Recreate the player with the current config.
/// Stops the old player, creates a new one. Caller is responsible for
/// resuming playback (load + seek) after this returns.
pub async fn recreate_player() -> Result<()> {
    // Stop and drop the old player
    {
        let mut slot = player_slot().lock().await;
        if let Some(player) = slot.take() {
            player.stop();
            // Drop the old player
        }
    }

    // Create a new player with current config
    create_player().await?;
    log::info!("Player recreated with current config");
    Ok(())
}
