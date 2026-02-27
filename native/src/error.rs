use thiserror::Error;

#[derive(Error, Debug)]
pub enum SidespotError {
    #[error("Session error: {0}")]
    Session(String),

    #[error("Player error: {0}")]
    Player(String),

    #[allow(dead_code)]
    #[error("Authentication error: {0}")]
    Auth(String),

    #[error("JNI error: {0}")]
    Jni(#[from] jni::errors::Error),

    #[error("Serialization error: {0}")]
    Serde(#[from] serde_json::Error),

    #[error("No active session")]
    NoSession,

    #[error("No active player")]
    NoPlayer,

    #[error("Connect error: {0}")]
    Connect(String),
}

pub type Result<T> = std::result::Result<T, SidespotError>;
