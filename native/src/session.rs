use std::path::PathBuf;
use std::sync::{Arc, OnceLock, Mutex as StdMutex};

use librespot_core::SessionConfig;
use librespot_core::authentication::Credentials;
use librespot_core::Session;
use tokio::runtime::Runtime;
use tokio::sync::Mutex;

use crate::error::{SidespotError, Result};

/// Global tokio runtime shared across the native library.
static RUNTIME: OnceLock<Runtime> = OnceLock::new();

/// Global session state.
static SESSION: OnceLock<Arc<Mutex<Option<Session>>>> = OnceLock::new();

/// Custom tmp dir set from Android (app cache dir).
static TMP_DIR: OnceLock<StdMutex<Option<PathBuf>>> = OnceLock::new();

/// Set the temporary directory for librespot file downloads.
pub fn set_tmp_dir(path: &str) {
    let slot = TMP_DIR.get_or_init(|| StdMutex::new(None));
    *slot.lock().unwrap() = Some(PathBuf::from(path));
    log::info!("tmp_dir set to: {path}");
}

fn get_tmp_dir() -> Option<PathBuf> {
    TMP_DIR.get().and_then(|m| m.lock().unwrap().clone())
}

pub fn runtime() -> &'static Runtime {
    RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .thread_name("sidespot-rt")
            .build()
            .expect("failed to create tokio runtime")
    })
}

fn session_slot() -> &'static Arc<Mutex<Option<Session>>> {
    SESSION.get_or_init(|| Arc::new(Mutex::new(None)))
}

/// Connect to Spotify with the given access token.
/// Returns Ok(()) on success, stores the session globally.
pub async fn connect_with_token(access_token: &str) -> Result<()> {
    let mut config = SessionConfig::default();
    if let Some(tmp) = get_tmp_dir() {
        config.tmp_dir = tmp;
    }

    // Apply autoplay setting from stored config
    {
        let app_cfg = crate::player::config_slot().lock().unwrap();
        if let Some(autoplay) = app_cfg.autoplay {
            config.autoplay = Some(autoplay);
        }
    }

    let credentials = Credentials::with_access_token(access_token);

    let session = Session::new(config, None);

    session
        .connect(credentials, true)
        .await
        .map_err(|e| SidespotError::Session(format!("connect failed: {e}")))?;

    log::info!("Spotify session connected successfully");

    let mut slot = session_slot().lock().await;
    *slot = Some(session);

    Ok(())
}

/// Disconnect the current session.
pub async fn disconnect() {
    let mut slot = session_slot().lock().await;
    if let Some(session) = slot.take() {
        session.shutdown();
        log::info!("Spotify session disconnected");
    }
}

/// Get a clone of the current session, if connected.
pub async fn get_session() -> Result<Session> {
    let slot = session_slot().lock().await;
    slot.clone().ok_or(SidespotError::NoSession)
}

/// Check if a session is currently active.
pub async fn is_connected() -> bool {
    let slot = session_slot().lock().await;
    slot.is_some()
}

/// Create a Session with config but do NOT call session.connect().
/// Spirc handles connecting internally.
pub fn create_session_unconnected() -> Session {
    let mut config = SessionConfig::default();
    if let Some(tmp) = get_tmp_dir() {
        config.tmp_dir = tmp;
    }

    // Apply autoplay setting from stored config
    {
        let app_cfg = crate::player::config_slot().lock().unwrap();
        if let Some(autoplay) = app_cfg.autoplay {
            config.autoplay = Some(autoplay);
        }
    }

    Session::new(config, None)
}

/// Store an externally-connected session in the global.
/// Used after Spirc connects the session so metadata queries work.
pub async fn store_session(session: Session) {
    let mut slot = session_slot().lock().await;
    *slot = Some(session);
    log::info!("Session stored from external connection");
}
