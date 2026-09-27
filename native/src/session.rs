use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
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

/// Whether the stored session was created by [`start_offline`] and has not
/// connected yet.
static OFFLINE: AtomicBool = AtomicBool::new(false);

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

fn session_config() -> SessionConfig {
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

    config
}

/// Connect to Spotify with the given access token.
/// Returns Ok(()) on success, stores the session globally.
pub async fn connect_with_token(access_token: &str) -> Result<()> {
    let credentials = Credentials::with_access_token(access_token);

    // The player holds on to the offline session, so connect that one in place
    // instead of replacing it. Downloaded tracks keep playing throughout.
    if OFFLINE.load(Ordering::Acquire) {
        let session = get_session().await?;
        session
            .connect(credentials, true)
            .await
            .map_err(|e| SidespotError::Session(format!("connect failed: {e}")))?;
        OFFLINE.store(false, Ordering::Release);
        log::info!("Offline session connected to Spotify");
        return Ok(());
    }

    let session = Session::new(session_config(), None);

    session
        .connect(credentials, true)
        .await
        .map_err(|e| SidespotError::Session(format!("connect failed: {e}")))?;

    log::info!("Spotify session connected successfully");

    let mut slot = session_slot().lock().await;
    *slot = Some(session);

    Ok(())
}

/// Create a session without connecting it, so the player can be created and
/// play downloaded tracks while there is no network. A later
/// [`connect_with_token`] connects this same session.
pub async fn start_offline() -> Result<()> {
    let mut slot = session_slot().lock().await;
    if slot.is_some() {
        return Ok(());
    }
    *slot = Some(Session::new(session_config(), None));
    OFFLINE.store(true, Ordering::Release);
    log::info!("Started offline session");
    Ok(())
}

/// Whether the current session was started offline and has not connected yet.
pub fn is_offline() -> bool {
    OFFLINE.load(Ordering::Acquire)
}

/// Disconnect the current session.
pub async fn disconnect() {
    let mut slot = session_slot().lock().await;
    if let Some(session) = slot.take() {
        session.shutdown();
        log::info!("Spotify session disconnected");
    }
    OFFLINE.store(false, Ordering::Release);
}

/// Get a clone of the current session, if connected.
pub async fn get_session() -> Result<Session> {
    let slot = session_slot().lock().await;
    slot.clone().ok_or(SidespotError::NoSession)
}

/// Check if a session is currently active.
pub async fn is_connected() -> bool {
    let slot = session_slot().lock().await;
    slot.as_ref().is_some_and(|session| !session.is_invalid()) && !is_offline()
}
