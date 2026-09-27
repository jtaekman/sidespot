//! Offline downloads.
//!
//! A download keeps the audio file exactly as the CDN serves it (still
//! encrypted), together with its decryption key and the track's metadata, so
//! the player can play it without a network connection. Layout under the
//! directory set by [`set_dir`]:
//!
//! ```text
//! items/<id>.json   OfflineEntry: format, key and TrackInfo
//! items/<id>.audio  encrypted audio file
//! images/<file id>  cover art referenced by the stored TrackInfo
//! tmp/              partial downloads, cleared on startup
//! ```

use std::collections::{HashMap, HashSet};
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex as StdMutex, OnceLock};
use std::time::Duration;

use bytes::Bytes;
use http_body_util::BodyExt;
use hyper::{Method, Request, StatusCode};
use librespot_core::audio_key::AudioKey;
use librespot_core::cdn_url::CdnUrl;
use librespot_core::date::Date;
use librespot_core::{FileId, Session, SpotifyId, SpotifyUri};
use librespot_metadata::artist::ArtistsWithRole;
use librespot_metadata::audio::{AudioFileFormat, AudioFiles, AudioItem, UniqueFields};
use librespot_metadata::{Episode, Metadata};
use librespot_playback::offline::{OfflineAudio, OfflineAudioSource};
use serde::{Deserialize, Serialize};

use crate::error::SidespotError;
use crate::metadata::{self, ArtistSummary, TrackInfo};
use crate::session;

static STORE_DIR: OnceLock<StdMutex<Option<PathBuf>>> = OnceLock::new();

/// Held while a download is committed to the store and while downloads are
/// removed, so removal never mistakes a download's cover art for unused.
static STORE_LOCK: StdMutex<()> = StdMutex::new(());

/// How long any single request of a download, or a pause between chunks of
/// audio, may take before the download is given up on and retried later.
const REQUEST_TIMEOUT: Duration = Duration::from_secs(30);

/// Formats a download may use, in the player's order of preference for each
/// bitrate setting. Podcasts are often only available at 96 kbps, hence the
/// fallbacks.
const FORMATS_96: [AudioFileFormat; 7] = [
    AudioFileFormat::OGG_VORBIS_96,
    AudioFileFormat::MP3_96,
    AudioFileFormat::OGG_VORBIS_160,
    AudioFileFormat::MP3_160,
    AudioFileFormat::MP3_256,
    AudioFileFormat::OGG_VORBIS_320,
    AudioFileFormat::MP3_320,
];
const FORMATS_160: [AudioFileFormat; 7] = [
    AudioFileFormat::OGG_VORBIS_160,
    AudioFileFormat::MP3_160,
    AudioFileFormat::OGG_VORBIS_96,
    AudioFileFormat::MP3_96,
    AudioFileFormat::MP3_256,
    AudioFileFormat::OGG_VORBIS_320,
    AudioFileFormat::MP3_320,
];
const FORMATS_320: [AudioFileFormat; 7] = [
    AudioFileFormat::OGG_VORBIS_320,
    AudioFileFormat::MP3_320,
    AudioFileFormat::MP3_256,
    AudioFileFormat::OGG_VORBIS_160,
    AudioFileFormat::MP3_160,
    AudioFileFormat::OGG_VORBIS_96,
    AudioFileFormat::MP3_96,
];

/// What is stored alongside each downloaded audio file.
#[derive(Serialize, Deserialize)]
struct OfflineEntry {
    uri: String,
    file_id: String,
    format: String,
    key: Option<String>,
    size: u64,
    info: TrackInfo,
}

/// Why a download failed. Permanent failures won't succeed on a retry, so the
/// caller should stop trying that item.
pub enum DownloadError {
    Permanent(String),
    Transient(String),
    /// The session was shut down (e.g. the connection dropped for too long), so
    /// nothing can download until it is replaced.
    SessionLost,
}

type DownloadResult<T> = std::result::Result<T, DownloadError>;

impl From<SidespotError> for DownloadError {
    fn from(e: SidespotError) -> Self {
        DownloadError::Transient(e.to_string())
    }
}

impl From<librespot_core::Error> for DownloadError {
    fn from(e: librespot_core::Error) -> Self {
        DownloadError::Transient(e.to_string())
    }
}

impl From<std::io::Error> for DownloadError {
    fn from(e: std::io::Error) -> Self {
        DownloadError::Transient(format!("storage error: {e}"))
    }
}

/// Set the directory downloads are stored in, and clear out what a previous
/// run left behind when it was killed partway through a download or removal.
pub fn set_dir(path: &str) {
    let dir = PathBuf::from(path);
    let _ = fs::remove_dir_all(dir.join("tmp"));
    for sub in ["items", "images", "tmp"] {
        if let Err(e) = fs::create_dir_all(dir.join(sub)) {
            log::error!("offline: failed to create {sub} dir: {e}");
        }
    }
    remove_orphaned_audio(&dir);
    let slot = STORE_DIR.get_or_init(|| StdMutex::new(None));
    *slot.lock().unwrap() = Some(dir);
    log::info!("offline dir set to: {path}");
}

fn store_dir() -> Option<PathBuf> {
    STORE_DIR.get().and_then(|m| m.lock().unwrap().clone())
}

fn item_id(uri: &str) -> String {
    uri.replace(':', "_")
}

fn entry_path(dir: &Path, uri: &str) -> PathBuf {
    dir.join("items").join(format!("{}.json", item_id(uri)))
}

fn audio_path(dir: &Path, uri: &str) -> PathBuf {
    dir.join("items").join(format!("{}.audio", item_id(uri)))
}

/// Write `contents` to `path` via a temporary file so readers never see a
/// partially written file.
fn write_atomic(dir: &Path, path: &Path, contents: &[u8]) -> std::io::Result<()> {
    let name = path.file_name().and_then(|n| n.to_str()).unwrap_or("file");
    let tmp = dir.join("tmp").join(format!("{name}.part"));
    fs::write(&tmp, contents)?;
    fs::rename(&tmp, path)
}

/// Load the entry for `uri` if its audio file is also present.
fn load_entry(dir: &Path, uri: &str) -> Option<OfflineEntry> {
    let json = fs::read(entry_path(dir, uri)).ok()?;
    let entry: OfflineEntry = serde_json::from_slice(&json).ok()?;
    audio_path(dir, uri).is_file().then_some(entry)
}

fn hex_encode(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn hex_decode(s: &str) -> Option<Vec<u8>> {
    if s.len() % 2 != 0 {
        return None;
    }
    (0..s.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(s.get(i..i + 2)?, 16).ok())
        .collect()
}

fn format_name(format: AudioFileFormat) -> String {
    format!("{format:?}")
}

fn parse_format(name: &str) -> Option<AudioFileFormat> {
    FORMATS_320.into_iter().find(|f| format_name(*f) == name)
}

fn preferred_formats() -> [AudioFileFormat; 7] {
    let bitrate = crate::player::config_slot().lock().unwrap().bitrate;
    match bitrate {
        Some(96) => FORMATS_96,
        Some(320) => FORMATS_320,
        _ => FORMATS_160,
    }
}

// ---------------------------------------------------------------------------
// Queries
// ---------------------------------------------------------------------------

/// Stored metadata for a downloaded track or episode.
pub fn stored_track_info(uri: &str) -> Option<TrackInfo> {
    load_entry(&store_dir()?, uri).map(|entry| entry.info)
}

/// Stored metadata for whichever of `uris` are downloaded, in the given order.
pub fn stored_track_infos(uris: &[String]) -> Vec<TrackInfo> {
    let Some(dir) = store_dir() else {
        return Vec::new();
    };
    uris.iter()
        .filter_map(|uri| load_entry(&dir, uri).map(|entry| entry.info))
        .collect()
}

/// URIs of every downloaded track and episode.
pub fn list() -> Vec<String> {
    let Some(dir) = store_dir() else {
        return Vec::new();
    };
    let Ok(entries) = fs::read_dir(dir.join("items")) else {
        return Vec::new();
    };
    entries
        .filter_map(|e| e.ok())
        .filter_map(|e| {
            let path = e.path();
            if path.extension()? != "json" {
                return None;
            }
            let json = fs::read(&path).ok()?;
            let entry: OfflineEntry = serde_json::from_slice(&json).ok()?;
            audio_path(&dir, &entry.uri).is_file().then_some(entry.uri)
        })
        .collect()
}

/// Bytes used by downloaded audio and cover art.
pub fn storage_bytes() -> u64 {
    let Some(dir) = store_dir() else {
        return 0;
    };
    ["items", "images"]
        .iter()
        .filter_map(|sub| fs::read_dir(dir.join(sub)).ok())
        .flatten()
        .filter_map(|e| e.ok()?.metadata().ok())
        .map(|m| m.len())
        .sum()
}

// ---------------------------------------------------------------------------
// Removal
// ---------------------------------------------------------------------------

/// Delete the downloads for `uris`, then any cover art no longer used.
pub fn remove(uris: &[String]) {
    let Some(dir) = store_dir() else {
        return;
    };
    let _guard = STORE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
    for uri in uris {
        // Audio first: an entry without audio is ignored, audio without an
        // entry is only found again by the cleanup on the next start.
        let _ = fs::remove_file(audio_path(&dir, uri));
        let _ = fs::remove_file(entry_path(&dir, uri));
    }
    remove_unused_images(&dir);
}

/// Delete audio files whose entry was never written or already removed.
fn remove_orphaned_audio(dir: &Path) {
    let Ok(items) = fs::read_dir(dir.join("items")) else {
        return;
    };
    for item in items.filter_map(|e| e.ok()) {
        let path = item.path();
        if path.extension().is_some_and(|ext| ext == "audio")
            && !path.with_extension("json").is_file()
        {
            let _ = fs::remove_file(&path);
        }
    }
}

fn remove_unused_images(dir: &Path) {
    let Ok(items) = fs::read_dir(dir.join("items")) else {
        return;
    };
    let referenced: HashSet<String> = items
        .filter_map(|e| e.ok())
        .filter(|e| e.path().extension().is_some_and(|ext| ext == "json"))
        .filter_map(|e| {
            let json = fs::read(e.path()).ok()?;
            let entry: OfflineEntry = serde_json::from_slice(&json).ok()?;
            entry.info.album_art_url
        })
        .collect();

    let Ok(images) = fs::read_dir(dir.join("images")) else {
        return;
    };
    for image in images.filter_map(|e| e.ok()) {
        let url = format!("file://{}", image.path().display());
        if !referenced.contains(&url) {
            let _ = fs::remove_file(image.path());
        }
    }
}

// ---------------------------------------------------------------------------
// Download
// ---------------------------------------------------------------------------

/// Download a track or episode for offline playback, returning its metadata.
/// Does nothing but return the stored metadata if it is already downloaded.
pub async fn download(uri: &str) -> DownloadResult<TrackInfo> {
    let dir = store_dir().ok_or_else(|| DownloadError::Transient("no offline dir".into()))?;
    if let Some(entry) = load_entry(&dir, uri) {
        return Ok(entry.info);
    }
    if session::is_offline() {
        return Err(DownloadError::Transient("offline".into()));
    }
    let session = session::get_session().await?;
    if session.is_invalid() {
        return Err(DownloadError::SessionLost);
    }
    // Availability depends on the account's country, which arrives shortly
    // after connecting. Without it every track would look unavailable.
    if session.country().is_empty() {
        return Err(DownloadError::Transient("account details not loaded yet".into()));
    }

    match fetch_and_store(&session, &dir, uri).await {
        Err(DownloadError::Transient(_)) if session.is_invalid() => Err(DownloadError::SessionLost),
        result => result,
    }
}

/// Run one step of a download, giving up if it takes longer than [`REQUEST_TIMEOUT`].
async fn within<T, E>(
    step: &str,
    future: impl std::future::Future<Output = std::result::Result<T, E>>,
) -> DownloadResult<T>
where
    DownloadError: From<E>,
{
    match tokio::time::timeout(REQUEST_TIMEOUT, future).await {
        Ok(result) => result.map_err(DownloadError::from),
        Err(_) => Err(DownloadError::Transient(format!("{step} timed out"))),
    }
}

async fn fetch_and_store(session: &Session, dir: &Path, uri: &str) -> DownloadResult<TrackInfo> {
    let spotify_uri = SpotifyUri::from_uri(uri)
        .map_err(|e| DownloadError::Permanent(format!("invalid URI '{uri}': {e}")))?;
    let track_id = SpotifyId::try_from(&spotify_uri)
        .map_err(|e| DownloadError::Permanent(format!("invalid URI '{uri}': {e}")))?;

    let audio_item = within("metadata", AudioItem::get_file(session, spotify_uri.clone())).await?;
    let audio_item = within("alternatives", async {
        Ok::<_, DownloadError>(playable_item(session, audio_item).await)
    })
    .await?
    .ok_or_else(|| DownloadError::Permanent("not available".into()))?;

    let (format, file_id) = preferred_formats()
        .into_iter()
        .find_map(|f| audio_item.files.get(&f).map(|id| (f, *id)))
        .ok_or_else(|| DownloadError::Permanent("no downloadable format".into()))?;

    let mut info = match spotify_uri {
        SpotifyUri::Episode { .. } => within("metadata", episode_info(session, &spotify_uri)).await?,
        _ => within("metadata", metadata::fetch_track_info(session, &spotify_uri)).await?,
    };

    // As when streaming, the key is requested for the track that was asked for,
    // even if the file belongs to an alternative.
    let key = within("audio key", session.audio_key().request(track_id, file_id)).await?;

    let size = download_audio(session, dir, file_id, &audio_path(dir, uri)).await?;
    let cover = fetch_cover(session, dir, &info).await;

    // Save the cover art and the entry together, so a removal running meanwhile
    // can't see the art as unused and delete it.
    let _guard = STORE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
    if let Some((path, bytes)) = cover {
        match bytes.map_or(Ok(()), |bytes| write_atomic(dir, &path, &bytes)) {
            Ok(()) => info.album_art_url = Some(format!("file://{}", path.display())),
            Err(e) => log::warn!("offline: failed to save cover art: {e}"),
        }
    }
    let entry = OfflineEntry {
        uri: uri.to_string(),
        file_id: file_id.to_base16(),
        format: format_name(format),
        key: Some(hex_encode(&key.0)),
        size,
        info,
    };
    let json = serde_json::to_vec(&entry).map_err(SidespotError::from)?;
    write_atomic(dir, &entry_path(dir, uri), &json)?;

    log::info!("offline: downloaded {uri} ({size} bytes, {:?})", format);
    Ok(entry.info)
}

/// The item itself if it can be played, otherwise its first playable alternative.
async fn playable_item(session: &Session, item: AudioItem) -> Option<AudioItem> {
    if item.availability.is_err() {
        return None;
    }
    if !item.files.is_empty() {
        return Some(item);
    }
    for alt in item.alternatives.iter().flat_map(|alts| alts.0.iter()) {
        if let Ok(alt_item) = AudioItem::get_file(session, alt.clone()).await {
            if alt_item.availability.is_ok() && !alt_item.files.is_empty() {
                return Some(alt_item);
            }
        }
    }
    None
}

/// Episodes aren't tracks, so present them the way the app shows episodes
/// elsewhere: the show stands in for both artist and album.
async fn episode_info(
    session: &Session,
    uri: &SpotifyUri,
) -> DownloadResult<TrackInfo> {
    let episode = Episode::get(session, uri).await?;
    Ok(TrackInfo {
        uri: uri.to_uri(),
        name: episode.name,
        artists: vec![ArtistSummary {
            uri: String::new(),
            name: episode.show_name.clone(),
        }],
        album_name: episode.show_name,
        album_uri: String::new(),
        album_art_url: metadata::image_url_from_images(&episode.covers),
        duration_ms: episode.duration,
        track_number: 0,
        disc_number: 0,
        is_explicit: episode.is_explicit,
    })
}

/// Fetch an audio file from the CDN into `dest`, returning its size.
async fn download_audio(
    session: &Session,
    dir: &Path,
    file_id: FileId,
    dest: &Path,
) -> DownloadResult<u64> {
    let cdn_url = within("CDN lookup", CdnUrl::new(file_id).resolve_audio(session)).await?;
    let urls = cdn_url.try_get_urls()?;
    let tmp = dir.join("tmp").join(format!(
        "{}.part",
        dest.file_name().and_then(|n| n.to_str()).unwrap_or("audio")
    ));

    let mut last_error = DownloadError::Transient("no CDN URLs".into());
    for url in urls {
        match fetch_to_file(session, url, &tmp).await {
            Ok(size) => {
                fs::rename(&tmp, dest)?;
                return Ok(size);
            }
            Err(e) => {
                log::warn!("offline: fetching {file_id} from CDN failed: {e}");
                last_error = DownloadError::Transient(e);
            }
        }
    }
    let _ = fs::remove_file(&tmp);
    Err(last_error)
}

async fn fetch_to_file(
    session: &Session,
    url: &str,
    path: &Path,
) -> std::result::Result<u64, String> {
    let req = Request::builder()
        .method(Method::GET)
        .uri(url)
        .body(Bytes::new())
        .map_err(|e| e.to_string())?;
    let response = tokio::time::timeout(REQUEST_TIMEOUT, session.http_client().request(req))
        .await
        .map_err(|_| "request timed out".to_string())?
        .map_err(|e| e.to_string())?;
    if response.status() != StatusCode::OK {
        return Err(format!("unexpected status {}", response.status()));
    }
    let expected = response
        .headers()
        .get(hyper::header::CONTENT_LENGTH)
        .and_then(|v| v.to_str().ok()?.parse::<u64>().ok());

    let mut file = fs::File::create(path).map_err(|e| e.to_string())?;
    let mut body = response.into_body();
    let mut size = 0u64;
    loop {
        let Some(frame) = tokio::time::timeout(REQUEST_TIMEOUT, body.frame())
            .await
            .map_err(|_| "download stalled".to_string())?
        else {
            break;
        };
        let frame = frame.map_err(|e| e.to_string())?;
        if let Some(chunk) = frame.data_ref() {
            file.write_all(chunk).map_err(|e| e.to_string())?;
            size += chunk.len() as u64;
        }
    }
    file.sync_all().map_err(|e| e.to_string())?;

    if size == 0 || expected.is_some_and(|len| len != size) {
        return Err(format!("incomplete download ({size} of {expected:?} bytes)"));
    }
    Ok(size)
}

/// Where to keep the track's cover art locally, and its bytes if they still
/// need saving there. Art is shared between the tracks of an album, so it is
/// stored once per image. None if there is no art or it can't be fetched, in
/// which case the metadata keeps the remote URL.
async fn fetch_cover(
    session: &Session,
    dir: &Path,
    info: &TrackInfo,
) -> Option<(PathBuf, Option<Bytes>)> {
    let url = info.album_art_url.as_deref()?;
    let image_id = url.rsplit('/').next().filter(|id| !id.is_empty())?;
    let path = dir.join("images").join(image_id);
    if path.is_file() {
        return Some((path, None));
    }
    match within("cover art", session.spclient().request_url(url)).await {
        Ok(bytes) => Some((path, Some(bytes))),
        Err(_) => {
            log::warn!("offline: failed to fetch cover art {url}");
            None
        }
    }
}

// ---------------------------------------------------------------------------
// Player integration
// ---------------------------------------------------------------------------

/// Hands downloaded copies to the player.
pub struct OfflineStore;

impl OfflineStore {
    pub fn source() -> Arc<dyn OfflineAudioSource> {
        Arc::new(OfflineStore)
    }
}

impl OfflineAudioSource for OfflineStore {
    fn lookup(&self, uri: &SpotifyUri) -> Option<OfflineAudio> {
        let dir = store_dir()?;
        let uri_string = uri.to_uri();
        let entry = load_entry(&dir, &uri_string)?;

        let format = parse_format(&entry.format)?;
        let key = match entry.key {
            Some(hex) => {
                let bytes: [u8; 16] = hex_decode(&hex)?.try_into().ok()?;
                Some(AudioKey(bytes))
            }
            None => None,
        };

        let info = entry.info;
        let unique_fields = match uri {
            SpotifyUri::Episode { .. } => UniqueFields::Episode {
                description: String::new(),
                publish_time: Date::now_utc(),
                show_name: info.album_name,
            },
            _ => UniqueFields::Track {
                artists: ArtistsWithRole(Vec::new()),
                album: info.album_name,
                album_artists: Vec::new(),
                popularity: 0,
                number: info.track_number.max(0) as u32,
                disc_number: info.disc_number.max(0) as u32,
            },
        };
        let audio_item = AudioItem {
            track_id: uri.clone(),
            uri: uri_string.clone(),
            files: AudioFiles(HashMap::new()),
            name: info.name,
            covers: Vec::new(),
            language: Vec::new(),
            duration_ms: info.duration_ms.max(0) as u32,
            is_explicit: info.is_explicit,
            availability: Ok(()),
            alternatives: None,
            unique_fields,
        };

        Some(OfflineAudio {
            path: audio_path(&dir, &uri_string),
            format,
            key,
            audio_item,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hex_round_trip() {
        let bytes = [0u8, 1, 0x7f, 0x80, 0xff];
        assert_eq!(hex_encode(&bytes), "00017f80ff");
        assert_eq!(hex_decode("00017f80ff").unwrap(), bytes);
        assert!(hex_decode("abc").is_none());
        assert!(hex_decode("zz").is_none());
    }

    #[test]
    fn format_names_round_trip() {
        for format in FORMATS_320 {
            assert_eq!(parse_format(&format_name(format)), Some(format));
        }
        assert_eq!(parse_format("FLAC_FLAC"), None);
    }

    fn track_info(uri: &str, art: Option<String>) -> TrackInfo {
        TrackInfo {
            uri: uri.to_string(),
            name: "Song".to_string(),
            artists: vec![ArtistSummary {
                uri: "spotify:artist:0".to_string(),
                name: "Artist".to_string(),
            }],
            album_name: "Album".to_string(),
            album_uri: "spotify:album:0".to_string(),
            album_art_url: art,
            duration_ms: 185_000,
            track_number: 3,
            disc_number: 1,
            is_explicit: false,
        }
    }

    fn store(dir: &Path, uri: &str, info: TrackInfo, with_audio: bool) {
        let entry = OfflineEntry {
            uri: uri.to_string(),
            file_id: "00".repeat(20),
            format: format_name(AudioFileFormat::OGG_VORBIS_320),
            key: Some(hex_encode(&[7u8; 16])),
            size: 4,
            info,
        };
        write_atomic(dir, &entry_path(dir, uri), &serde_json::to_vec(&entry).unwrap()).unwrap();
        if with_audio {
            fs::write(audio_path(dir, uri), b"ogg!").unwrap();
        }
    }

    /// The store's directory is process-wide, so tests using it take turns.
    static TEST_DIR_LOCK: StdMutex<()> = StdMutex::new(());

    fn fresh_store_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir()
            .join(format!("sidespot-offline-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        set_dir(dir.to_str().unwrap());
        dir
    }

    #[test]
    fn store_lookup_list_and_remove() {
        let _lock = TEST_DIR_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let dir = fresh_store_dir("store");

        let track = "spotify:track:4uLU6hMCjMI75M1A2tKUQC";
        let episode = "spotify:episode:0Q86acNRm6V9GYx55SXKwf";
        let partial = "spotify:track:1301WleyT98MSxVHPZCA6M";
        let art = dir.join("images").join("ab67616d0000b273");
        let art_url = format!("file://{}", art.display());
        fs::write(&art, b"jpg").unwrap();
        fs::write(dir.join("images").join("unused"), b"jpg").unwrap();

        store(&dir, track, track_info(track, Some(art_url.clone())), true);
        store(&dir, episode, track_info(episode, None), true);
        // Metadata without audio, as left by an interrupted removal.
        store(&dir, partial, track_info(partial, None), false);

        let mut listed = list();
        listed.sort();
        assert_eq!(listed, vec![episode.to_string(), track.to_string()]);
        assert!(stored_track_info(partial).is_none());
        let infos = stored_track_infos(&[partial.into(), track.into(), episode.into()]);
        assert_eq!(
            infos.iter().map(|i| i.uri.as_str()).collect::<Vec<_>>(),
            vec![track, episode]
        );
        assert!(storage_bytes() >= 8);

        let offline = OfflineStore
            .lookup(&SpotifyUri::from_uri(track).unwrap())
            .expect("downloaded track should be found");
        assert_eq!(offline.path, audio_path(&dir, track));
        assert_eq!(offline.format, AudioFileFormat::OGG_VORBIS_320);
        assert_eq!(offline.key.map(|k| k.0), Some([7u8; 16]));
        assert_eq!(offline.audio_item.duration_ms, 185_000);
        assert_eq!(offline.audio_item.uri, track);
        assert!(matches!(
            offline.audio_item.unique_fields,
            UniqueFields::Track { number: 3, disc_number: 1, .. }
        ));

        let offline = OfflineStore
            .lookup(&SpotifyUri::from_uri(episode).unwrap())
            .expect("downloaded episode should be found");
        assert!(matches!(offline.audio_item.unique_fields, UniqueFields::Episode { .. }));
        assert!(OfflineStore.lookup(&SpotifyUri::from_uri(partial).unwrap()).is_none());

        // Removing nothing still clears out art no download refers to.
        remove(&[]);
        assert!(art.is_file());
        assert!(!dir.join("images").join("unused").exists());

        remove(&[track.to_string(), partial.to_string()]);
        assert_eq!(list(), vec![episode.to_string()]);
        assert!(!audio_path(&dir, track).exists());
        assert!(!entry_path(&dir, partial).exists());
        assert!(!art.exists());

        // Audio whose entry never got written is cleaned up on the next start.
        let orphan = dir.join("items").join("spotify_track_orphan.audio");
        fs::write(&orphan, b"ogg!").unwrap();
        set_dir(dir.to_str().unwrap());
        assert!(!orphan.exists());
        assert!(audio_path(&dir, episode).is_file());

        let _ = fs::remove_dir_all(&dir);
    }

    /// Plays a downloaded track through librespot's player on a session that never
    /// connected, as happens offline: the file must be found, decrypted and decoded
    /// without touching the network. Needs ffmpeg to make the audio.
    #[test]
    fn player_plays_download_without_network() {
        use librespot_audio::AudioDecrypt;
        use librespot_core::SessionConfig;
        use librespot_playback::audio_backend::{Sink, SinkResult};
        use librespot_playback::config::PlayerConfig;
        use librespot_playback::convert::Converter;
        use librespot_playback::decoder::AudioPacket;
        use librespot_playback::mixer::NoOpVolume;
        use librespot_playback::player::{Player, PlayerEvent};
        use std::io::Read;
        use std::sync::atomic::{AtomicUsize, Ordering};

        let _lock = TEST_DIR_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let dir = fresh_store_dir("player");

        let ogg = dir.join("tmp").join("fixture.ogg");
        // ffmpeg's own Vorbis encoder is experimental, so prefer libvorbis when built in.
        let made = [&["-c:a", "libvorbis"][..], &["-c:a", "vorbis", "-strict", "-2"][..]]
            .iter()
            .any(|codec| {
                std::process::Command::new("ffmpeg")
                    .args(["-y", "-loglevel", "quiet", "-f", "lavfi"])
                    .args(["-i", "sine=frequency=440:duration=2", "-ar", "44100", "-ac", "2"])
                    .args(*codec)
                    .arg(&ogg)
                    .status()
                    .is_ok_and(|s| s.success())
            });
        if !made {
            eprintln!("skipping: ffmpeg with a Vorbis encoder is needed to make the test audio");
            return;
        }

        // Spotify's files start with a 0xa7-byte header of their own, and are
        // encrypted with AES-CTR, so decrypting the plain file encrypts it.
        let mut plain = vec![0u8; 0xa7];
        plain.extend(fs::read(&ogg).unwrap());
        let key = AudioKey([42u8; 16]);
        let mut encrypted = Vec::new();
        AudioDecrypt::new(Some(key), std::io::Cursor::new(plain))
            .read_to_end(&mut encrypted)
            .unwrap();

        let uri = "spotify:track:4uLU6hMCjMI75M1A2tKUQC";
        let entry = OfflineEntry {
            uri: uri.to_string(),
            file_id: "00".repeat(20),
            format: format_name(AudioFileFormat::OGG_VORBIS_320),
            key: Some(hex_encode(&key.0)),
            size: encrypted.len() as u64,
            info: track_info(uri, None),
        };
        fs::write(audio_path(&dir, uri), &encrypted).unwrap();
        write_atomic(&dir, &entry_path(&dir, uri), &serde_json::to_vec(&entry).unwrap())
            .unwrap();

        struct CountingSink(Arc<AtomicUsize>);
        impl Sink for CountingSink {
            fn write(&mut self, packet: AudioPacket, _: &mut Converter) -> SinkResult<()> {
                if let AudioPacket::Samples(samples) = packet {
                    self.0.fetch_add(samples.len(), Ordering::Relaxed);
                }
                Ok(())
            }
        }
        let samples = Arc::new(AtomicUsize::new(0));

        let runtime = tokio::runtime::Runtime::new().unwrap();
        let outcome = runtime.block_on(async {
            let session = Session::new(SessionConfig::default(), None);
            let config = PlayerConfig {
                offline_source: Some(OfflineStore::source()),
                ..PlayerConfig::default()
            };
            let sink_samples = samples.clone();
            let player = Player::new(config, session, Box::new(NoOpVolume), move || {
                Box::new(CountingSink(sink_samples)) as Box<dyn Sink>
            });
            let mut events = player.get_player_event_channel();
            player.load(SpotifyUri::from_uri(uri).unwrap(), true, 0);

            tokio::time::timeout(std::time::Duration::from_secs(20), async {
                while let Some(event) = events.recv().await {
                    match event {
                        PlayerEvent::EndOfTrack { .. } => return Ok(()),
                        PlayerEvent::Unavailable { .. } => return Err("unavailable"),
                        PlayerEvent::Timeout { .. } => return Err("timed out loading"),
                        _ => {}
                    }
                }
                Err("player stopped sending events")
            })
            .await
            .unwrap_or(Err("no end of track within 20s"))
        });

        let _ = fs::remove_dir_all(&dir);
        assert_eq!(outcome, Ok(()));
        // Two seconds of stereo at 44.1 kHz, give or take the codec's padding.
        let played = samples.load(Ordering::Relaxed);
        assert!(played > 150_000, "only {played} samples were played");
    }
}
