//! Metadata retrieval module.
//!
//! Wraps librespot's `Metadata` trait and `SpClient` to fetch track, album,
//! playlist, and search information, returning JSON to the Kotlin layer.

use bytes::Bytes;
use hyper::Method;
use librespot_core::SpotifyUri;
use librespot_metadata::image::ImageSize;
use librespot_metadata::{Album, Metadata, Playlist, Track};
use serde::{Deserialize, Serialize};

use crate::error::{Result, SidespotError};
use crate::session;

// ---------------------------------------------------------------------------
// Serde structs returned as JSON to Kotlin
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TrackInfo {
    pub uri: String,
    pub name: String,
    pub artists: Vec<ArtistSummary>,
    pub album_name: String,
    pub album_uri: String,
    pub album_art_url: Option<String>,
    pub duration_ms: i32,
    pub track_number: i32,
    pub disc_number: i32,
    pub is_explicit: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ArtistSummary {
    pub uri: String,
    pub name: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct AlbumInfo {
    pub uri: String,
    pub name: String,
    pub artists: Vec<ArtistSummary>,
    pub album_art_url: Option<String>,
    pub tracks: Vec<TrackSummary>,
    pub album_type: String,
    pub label: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct TrackSummary {
    pub uri: String,
    pub name: String,
    pub artists: Vec<ArtistSummary>,
    pub duration_ms: i32,
    pub track_number: i32,
    pub disc_number: i32,
    pub is_explicit: bool,
}

#[derive(Debug, Clone, Serialize)]
pub struct PlaylistInfo {
    pub uri: String,
    pub name: String,
    pub track_uris: Vec<String>,
    pub track_count: i32,
}

#[derive(Debug, Clone, Serialize)]
pub struct PlaylistSummary {
    pub uri: String,
    pub name: String,
    pub is_writable: bool,
}

#[derive(Debug, Clone, Serialize)]
pub struct SearchAlbumResult {
    pub uri: String,
    pub name: String,
    pub artist_name: String,
    pub album_art_url: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
pub struct SearchPlaylistResult {
    pub uri: String,
    pub name: String,
    pub owner_name: String,
    pub image_url: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
pub struct SearchShowResult {
    pub uri: String,
    pub name: String,
    pub publisher: String,
    pub image_url: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
pub struct SearchResults {
    pub tracks: Vec<TrackInfo>,
    pub albums: Vec<SearchAlbumResult>,
    pub playlists: Vec<SearchPlaylistResult>,
    pub shows: Vec<SearchShowResult>,
    pub total_tracks: i32,
    pub total_albums: i32,
    pub total_playlists: i32,
    pub total_shows: i32,
}

#[derive(Debug, Clone, Serialize)]
pub struct SearchPageResult<T: Serialize> {
    pub items: Vec<T>,
    pub total: i32,
}

// ---------------------------------------------------------------------------
// Image URL helper
// ---------------------------------------------------------------------------

/// Construct a Spotify CDN image URL from a librespot FileId.
/// Prefers the largest available image.
fn image_url_from_images(images: &librespot_metadata::image::Images) -> Option<String> {
    // Prefer larger images: Large > Medium > Small > XLarge (XLarge is sometimes a different format)
    let preferred_order = [ImageSize::LARGE, ImageSize::DEFAULT, ImageSize::SMALL];

    for &size in &preferred_order {
        if let Some(img) = images.iter().find(|i| i.size == size) {
            return Some(format!("https://i.scdn.co/image/{}", img.id.to_base16()));
        }
    }
    // Fall back to first available
    images
        .first()
        .map(|img| format!("https://i.scdn.co/image/{}", img.id.to_base16()))
}

// ---------------------------------------------------------------------------
// Public async functions
// ---------------------------------------------------------------------------

/// Fetch full track metadata.
pub async fn get_track_info(uri: &str) -> Result<String> {
    let session = session::get_session().await?;
    let spotify_uri = SpotifyUri::from_uri(uri)
        .map_err(|e| SidespotError::Player(format!("invalid URI '{uri}': {e}")))?;

    let track = Track::get(&session, &spotify_uri)
        .await
        .map_err(|e| SidespotError::Player(format!("failed to get track metadata: {e}")))?;

    let artists: Vec<ArtistSummary> = track
        .artists
        .iter()
        .map(|a| ArtistSummary {
            uri: a.id.to_uri(),
            name: a.name.clone(),
        })
        .collect();

    let album_art_url = image_url_from_images(&track.album.covers);

    let info = TrackInfo {
        uri: track.id.to_uri(),
        name: track.name.clone(),
        artists,
        album_name: track.album.name.clone(),
        album_uri: track.album.id.to_uri(),
        album_art_url,
        duration_ms: track.duration,
        track_number: track.number,
        disc_number: track.disc_number,
        is_explicit: track.is_explicit,
    };

    Ok(serde_json::to_string(&info)?)
}

/// Fetch album metadata with all track details.
pub async fn get_album_info(uri: &str) -> Result<String> {
    let session = session::get_session().await?;
    let spotify_uri = SpotifyUri::from_uri(uri)
        .map_err(|e| SidespotError::Player(format!("invalid URI '{uri}': {e}")))?;

    let album = Album::get(&session, &spotify_uri)
        .await
        .map_err(|e| SidespotError::Player(format!("failed to get album metadata: {e}")))?;

    let album_art_url = image_url_from_images(&album.covers);
    let album_artists: Vec<ArtistSummary> = album
        .artists
        .iter()
        .map(|a| ArtistSummary {
            uri: a.id.to_uri(),
            name: a.name.clone(),
        })
        .collect();

    // Fetch individual track metadata concurrently (capped to avoid overload)
    let track_uris: Vec<SpotifyUri> = album.tracks().cloned().collect();
    let mut tracks = Vec::with_capacity(track_uris.len());

    // Fetch in batches of 10
    for chunk in track_uris.chunks(10) {
        let mut handles = Vec::new();
        for track_uri in chunk {
            let sess = session.clone();
            let tu = track_uri.clone();
            handles.push(tokio::spawn(async move { Track::get(&sess, &tu).await }));
        }
        for handle in handles {
            match handle.await {
                Ok(Ok(track)) => {
                    let track_artists: Vec<ArtistSummary> = track
                        .artists
                        .iter()
                        .map(|a| ArtistSummary {
                            uri: a.id.to_uri(),
                            name: a.name.clone(),
                        })
                        .collect();
                    tracks.push(TrackSummary {
                        uri: track.id.to_uri(),
                        name: track.name.clone(),
                        artists: track_artists,
                        duration_ms: track.duration,
                        track_number: track.number,
                        disc_number: track.disc_number,
                        is_explicit: track.is_explicit,
                    });
                }
                Ok(Err(e)) => {
                    log::warn!("Failed to fetch track in album: {e}");
                }
                Err(e) => {
                    log::warn!("Task join error fetching track: {e}");
                }
            }
        }
    }

    let info = AlbumInfo {
        uri: album.id.to_uri(),
        name: album.name.clone(),
        artists: album_artists,
        album_art_url,
        tracks,
        album_type: album.type_str.clone(),
        label: album.label.clone(),
    };

    Ok(serde_json::to_string(&info)?)
}

/// Number of items requested per page when walking a truncated list.
const LIST_PAGE_SIZE: usize = 500;

/// Upper bound on pagination requests, so a server that ignores `from` can
/// never spin us forever.
const MAX_LIST_PAGES: usize = 100;

/// Fetch playlist metadata (track URIs only, metadata fetched lazily).
pub async fn get_playlist_info(uri: &str) -> Result<String> {
    let session = session::get_session().await?;
    let spotify_uri = SpotifyUri::from_uri(uri)
        .map_err(|e| SidespotError::Player(format!("invalid URI '{uri}': {e}")))?;

    let playlist = Playlist::get(&session, &spotify_uri)
        .await
        .map_err(|e| SidespotError::Player(format!("failed to get playlist metadata: {e}")))?;

    let mut track_uris: Vec<String> = playlist.tracks().map(|u| u.to_uri()).collect();
    let expected = playlist.length.max(0) as usize;

    // The playlist endpoint truncates long playlists; walk the remainder with
    // explicit from/length windows.
    if track_uris.len() < expected {
        use librespot_protocol::playlist4_external::SelectedListContent;
        use protobuf::Message;

        let SpotifyUri::Playlist { id, .. } = &spotify_uri else {
            return Err(SidespotError::Player(format!("not a playlist URI: {uri}")));
        };
        let id62 = id.to_base62();

        for _ in 0..MAX_LIST_PAGES {
            if track_uris.len() >= expected {
                break;
            }
            let from = track_uris.len();
            let endpoint =
                format!("/playlist/v2/playlist/{id62}?from={from}&length={LIST_PAGE_SIZE}");

            let response = match session
                .spclient()
                .request(&Method::GET, &endpoint, None, None)
                .await
            {
                Ok(r) => r,
                Err(e) => {
                    log::warn!("playlist page from={from} failed: {e}");
                    break;
                }
            };

            let content = match SelectedListContent::parse_from_bytes(&response) {
                Ok(c) => c,
                Err(e) => {
                    log::warn!("failed to parse playlist page from={from}: {e}");
                    break;
                }
            };

            // If the server ignored `from` it would replay items we already
            // have, so only accept a page that starts where we asked.
            let pos = content.contents.pos().max(0) as usize;
            if pos != from {
                log::warn!("playlist page returned pos {pos}, expected {from}; stopping");
                break;
            }

            let before = track_uris.len();
            for item in content.contents.items.iter() {
                let item_uri = item.uri();
                if !item_uri.is_empty() {
                    track_uris.push(item_uri.to_string());
                }
            }
            if track_uris.len() == before {
                break;
            }
        }
    }

    if track_uris.len() < expected {
        log::warn!(
            "playlist {uri}: resolved {} of {expected} tracks",
            track_uris.len()
        );
    }

    let info = PlaylistInfo {
        uri: spotify_uri.to_uri(),
        name: playlist.name().to_string(),
        track_count: track_uris.len() as i32,
        track_uris,
    };

    Ok(serde_json::to_string(&info)?)
}

/// Fetch the user's root playlist list.
pub async fn get_user_playlists() -> Result<String> {
    let session = session::get_session().await?;

    // The rootlist returns a protobuf SelectedListContent.
    // Parse it to extract playlist URIs and names.
    use librespot_protocol::playlist4_external::SelectedListContent;
    use protobuf::Message;

    let username = session.username();

    let mut playlists = Vec::new();
    // The rootlist is windowed, so page until we've seen every entry. Folders
    // count toward the total but are filtered out below.
    let mut seen = 0usize;
    for _ in 0..MAX_LIST_PAGES {
        let response = session
            .spclient()
            .get_rootlist(seen, Some(LIST_PAGE_SIZE))
            .await
            .map_err(|e| SidespotError::Player(format!("failed to get rootlist: {e}")))?;

        let content = SelectedListContent::parse_from_bytes(&response)
            .map_err(|e| SidespotError::Player(format!("failed to parse rootlist: {e}")))?;

        let items = &content.contents.items;
        let meta_items = &content.contents.meta_items;
        if items.is_empty() {
            break;
        }

        // Guard against a server that ignores `from` and replays page one.
        let pos = content.contents.pos().max(0) as usize;
        if pos != seen {
            log::warn!("rootlist page returned pos {pos}, expected {seen}; stopping");
            if seen > 0 {
                break;
            }
        }

        for (i, item) in items.iter().enumerate() {
            let uri = item.uri();
            // Only include playlists (skip folders, etc.)
            if uri.starts_with("spotify:playlist:") {
                let name = meta_items
                    .get(i)
                    .and_then(|m| m.attributes.as_ref())
                    .map(|a| a.name().to_string())
                    .unwrap_or_default();

                let owner = meta_items
                    .get(i)
                    .map(|m| m.owner_username().to_string())
                    .unwrap_or_default();

                let collaborative = meta_items
                    .get(i)
                    .and_then(|m| m.attributes.as_ref())
                    .map(|a| a.collaborative())
                    .unwrap_or(false);

                let is_writable = owner.is_empty() || owner == username || collaborative;

                playlists.push(PlaylistSummary {
                    uri: uri.to_string(),
                    name,
                    is_writable,
                });
            }
        }

        seen += items.len();
        if seen >= content.length().max(0) as usize {
            break;
        }
    }

    Ok(serde_json::to_string(&playlists)?)
}

/// Fetch the user's liked songs via context resolve.
pub async fn get_liked_songs() -> Result<String> {
    let session = session::get_session().await?;
    let username = session.username();
    let context_uri = format!("spotify:user:{username}:collection");

    let context = session
        .spclient()
        .get_context(&context_uri)
        .await
        .map_err(|e| SidespotError::Player(format!("failed to get liked songs: {e}")))?;

    let mut track_uris = Vec::new();
    for page in context.pages.iter() {
        for track in page.tracks.iter() {
            let uri = track.uri();
            if !uri.is_empty() {
                track_uris.push(uri.to_string());
            }
        }
    }

    // Return as PlaylistInfo with a fixed name
    let info = PlaylistInfo {
        uri: context_uri,
        name: "Liked Songs".to_string(),
        track_count: track_uris.len() as i32,
        track_uris,
    };

    Ok(serde_json::to_string(&info)?)
}

/// Fetch autoplay (recommended) tracks based on a context URI and recent tracks.
pub async fn get_autoplay_tracks(context_uri: &str, recent_track_uris: &[String]) -> Result<String> {
    use librespot_protocol::autoplay_context_request::AutoplayContextRequest;

    let session = session::get_session().await?;

    let request = AutoplayContextRequest {
        context_uri: Some(context_uri.to_string()),
        recent_track_uri: recent_track_uris.to_vec(),
        ..Default::default()
    };

    let context = session
        .spclient()
        .get_autoplay_context(&request)
        .await
        .map_err(|e| SidespotError::Player(format!("failed to get autoplay context: {e}")))?;

    let mut track_uris = Vec::new();
    for page in context.pages.iter() {
        for track in page.tracks.iter() {
            let uri = track.uri();
            if !uri.is_empty() && uri.starts_with("spotify:track:") {
                track_uris.push(uri.to_string());
            }
        }
    }

    Ok(serde_json::to_string(&track_uris)?)
}

// ---------------------------------------------------------------------------
// Searchview helpers
// ---------------------------------------------------------------------------

/// Simple percent-encoding for query strings.
fn percent_encode(input: &str) -> String {
    let mut out = String::with_capacity(input.len() * 2);
    for b in input.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(b as char);
            }
            b' ' => out.push_str("%20"),
            _ => {
                out.push('%');
                out.push(char::from(b"0123456789ABCDEF"[(b >> 4) as usize]));
                out.push(char::from(b"0123456789ABCDEF"[(b & 0x0F) as usize]));
            }
        }
    }
    out
}

/// Execute a search request using Spotify's internal searchview API via spclient.
/// This bypasses the public Web API and its rate limits entirely.
async fn spclient_search_raw(query: &str, limit: u32, offset: u32) -> Result<Bytes> {
    let session = session::get_session().await?;
    let encoded = percent_encode(query);
    let country = session.country();
    let endpoint = format!(
        "/searchview/km/v4/search/{encoded}?limit={limit}&offset={offset}&catalogue=premium&country={country}&locale=en&platform=android&entityVersion=2"
    );

    session
        .spclient()
        .request_as_json(&Method::GET, &endpoint, None, None)
        .await
        .map_err(|e| SidespotError::Player(format!("searchview request failed: {e}")))
}

/// Parse searchview response dynamically using serde_json::Value.
/// The searchview API format differs from the Web API — fields are discovered at runtime.
fn parse_searchview_response(val: &serde_json::Value) -> SearchResults {
    // The searchview response may nest results under "results" or be flat
    let root = val.get("results").unwrap_or(val);

    let (tracks, total_tracks) = parse_sv_tracks(root);
    let (albums, total_albums) = parse_sv_albums(root);
    let (playlists, total_playlists) = parse_sv_playlists(root);
    let (shows, total_shows) = parse_sv_shows(root);

    SearchResults {
        tracks,
        albums,
        playlists,
        shows,
        total_tracks,
        total_albums,
        total_playlists,
        total_shows,
    }
}

/// Helper to get a string from a Value, trying multiple field paths.
fn sv_str(val: &serde_json::Value, keys: &[&str]) -> String {
    for key in keys {
        if let Some(s) = val.get(key).and_then(|v| v.as_str()) {
            return s.to_string();
        }
    }
    String::new()
}

/// Helper to get image URL from various possible locations.
fn sv_image(val: &serde_json::Value) -> Option<String> {
    // Try "image" object with nested URL fields
    if let Some(img) = val.get("image") {
        for key in ["largeImageUrl", "smallImageUrl", "url", "imageUrl"] {
            if let Some(url) = img.get(key).and_then(|v| v.as_str()) {
                if !url.is_empty() {
                    return Some(url.to_string());
                }
            }
        }
        // Image might be a string directly
        if let Some(url) = img.as_str() {
            if !url.is_empty() {
                return Some(url.to_string());
            }
        }
    }
    // Try "imageUrl" or "images" array at top level
    if let Some(url) = val.get("imageUrl").and_then(|v| v.as_str()) {
        if !url.is_empty() {
            return Some(url.to_string());
        }
    }
    if let Some(images) = val.get("images").and_then(|v| v.as_array()) {
        if let Some(first) = images.first() {
            if let Some(url) = first.get("url").and_then(|v| v.as_str()) {
                return Some(url.to_string());
            }
            if let Some(url) = first.as_str() {
                return Some(url.to_string());
            }
        }
    }
    None
}

/// Extract hits array and total from a section, trying various field names.
fn sv_section<'a>(root: &'a serde_json::Value, section: &str) -> (Vec<&'a serde_json::Value>, i32) {
    if let Some(sec) = root.get(section) {
        let total = sec.get("total")
            .or_else(|| sec.get("totalCount"))
            .and_then(|v| v.as_i64())
            .unwrap_or(0) as i32;
        let items = sec.get("hits")
            .or_else(|| sec.get("items"))
            .and_then(|v| v.as_array())
            .map(|arr| arr.iter().collect::<Vec<_>>())
            .unwrap_or_default();
        (items, total)
    } else {
        (vec![], 0)
    }
}

fn parse_sv_tracks(root: &serde_json::Value) -> (Vec<TrackInfo>, i32) {
    let (items, total) = sv_section(root, "tracks");
    let tracks = items.iter().filter_map(|hit| {
        let uri = sv_str(hit, &["uri"]);
        if uri.is_empty() { return None; }
        let name = sv_str(hit, &["name"]);

        // Artists: could be in "artists" array, or nested under "info"
        let info = hit.get("info").unwrap_or(hit);
        let artists_val = info.get("artists")
            .and_then(|v| v.as_array());
        let artists: Vec<ArtistSummary> = artists_val
            .map(|arr| arr.iter().map(|a| ArtistSummary {
                uri: sv_str(a, &["uri"]),
                name: sv_str(a, &["name"]),
            }).collect())
            .unwrap_or_default();

        // Album info
        let album = info.get("album").or_else(|| hit.get("album"));
        let album_name = album.map(|a| sv_str(a, &["name"])).unwrap_or_default();
        let album_uri = album.map(|a| sv_str(a, &["uri"])).unwrap_or_default();
        let album_art_url = album.and_then(|a| sv_image(a)).or_else(|| sv_image(hit));

        let duration_ms = hit.get("duration")
            .or_else(|| hit.get("duration_ms"))
            .or_else(|| info.get("duration"))
            .and_then(|v| v.as_i64())
            .unwrap_or(0) as i32;

        Some(TrackInfo {
            uri,
            name,
            artists,
            album_name,
            album_uri,
            album_art_url,
            duration_ms,
            track_number: hit.get("trackNumber")
                .or_else(|| hit.get("track_number"))
                .and_then(|v| v.as_i64())
                .unwrap_or(0) as i32,
            disc_number: hit.get("discNumber")
                .or_else(|| hit.get("disc_number"))
                .and_then(|v| v.as_i64())
                .unwrap_or(0) as i32,
            is_explicit: hit.get("explicit")
                .and_then(|v| v.as_bool())
                .unwrap_or(false),
        })
    }).collect();
    (tracks, total)
}

fn parse_sv_albums(root: &serde_json::Value) -> (Vec<SearchAlbumResult>, i32) {
    let (items, total) = sv_section(root, "albums");
    let albums = items.iter().filter_map(|hit| {
        let uri = sv_str(hit, &["uri"]);
        if uri.is_empty() { return None; }
        let name = sv_str(hit, &["name"]);
        let info = hit.get("info").unwrap_or(hit);
        let artists = info.get("artists")
            .and_then(|v| v.as_array())
            .map(|arr| arr.iter()
                .filter_map(|a| a.get("name").and_then(|n| n.as_str()))
                .collect::<Vec<_>>()
                .join(", "))
            .unwrap_or_default();
        Some(SearchAlbumResult {
            uri,
            name,
            artist_name: artists,
            album_art_url: sv_image(hit),
        })
    }).collect();
    (albums, total)
}

fn parse_sv_playlists(root: &serde_json::Value) -> (Vec<SearchPlaylistResult>, i32) {
    let (items, total) = sv_section(root, "playlists");
    let playlists = items.iter().filter_map(|hit| {
        let uri = sv_str(hit, &["uri"]);
        if uri.is_empty() { return None; }
        Some(SearchPlaylistResult {
            uri,
            name: sv_str(hit, &["name"]),
            owner_name: sv_str(hit, &["owner", "ownerName", "owner_name"]),
            image_url: sv_image(hit),
        })
    }).collect();
    (playlists, total)
}

fn parse_sv_shows(root: &serde_json::Value) -> (Vec<SearchShowResult>, i32) {
    // Try both "shows" and "podcasts" as the section key
    let (items, total) = {
        let (items, total) = sv_section(root, "shows");
        if items.is_empty() {
            sv_section(root, "podcasts")
        } else {
            (items, total)
        }
    };
    let shows = items.iter().filter_map(|hit| {
        let uri = sv_str(hit, &["uri"]);
        if uri.is_empty() { return None; }
        Some(SearchShowResult {
            uri,
            name: sv_str(hit, &["name"]),
            publisher: sv_str(hit, &["publisher", "publisherName"]),
            image_url: sv_image(hit),
        })
    }).collect();
    (shows, total)
}

/// Search Spotify via internal searchview API (all entity types in one request).
/// Falls back to get_context search (tracks only) on failure.
pub async fn search(query: &str) -> Result<String> {
    match spclient_search_raw(query, 10, 0).await {
        Ok(body) => {
            // Parse the searchview response using flexible Value-based parsing
            let val: serde_json::Value = serde_json::from_slice(&body)
                .map_err(|e| SidespotError::Player(format!("failed to parse search response: {e}")))?;

            let results = parse_searchview_response(&val);
            let json = serde_json::to_string(&results)?;
            Ok(json)
        }
        Err(e) => {
            log::warn!("Searchview search failed, falling back to get_context: {e}");

            // Fallback: get_context search returns track URIs only (no metadata).
            let session = session::get_session().await?;
            let encoded_query = query.replace(' ', "+");
            let context_uri = format!("spotify:search:{encoded_query}");

            let context = session
                .spclient()
                .get_context(&context_uri)
                .await
                .map_err(|e| SidespotError::Player(format!("search failed: {e}")))?;

            let mut track_uris = Vec::new();
            for page in context.pages.iter() {
                for track in page.tracks.iter() {
                    let uri = track.uri();
                    if !uri.is_empty() && uri.starts_with("spotify:track:") {
                        track_uris.push(uri.to_string());
                    }
                }
            }

            // Fetch metadata for top 10 tracks
            let mut track_infos = Vec::new();
            for uri in track_uris.iter().take(10) {
                match get_track_info(uri).await {
                    Ok(json) => {
                        if let Ok(info) = serde_json::from_str::<TrackInfo>(&json) {
                            track_infos.push(info);
                        }
                    }
                    Err(e) => log::warn!("Failed to fetch track metadata for {uri}: {e}"),
                }
            }

            let results = SearchResults {
                tracks: track_infos,
                albums: vec![],
                playlists: vec![],
                shows: vec![],
                total_tracks: 0,
                total_albums: 0,
                total_playlists: 0,
                total_shows: 0,
            };
            Ok(serde_json::to_string(&results)?)
        }
    }
}

/// Paginated search for a single entity type ("track", "album", "playlist", or "show").
pub async fn search_more(query: &str, search_type: &str, offset: u32) -> Result<String> {
    let body = spclient_search_raw(query, 10, offset).await?;

    let val: serde_json::Value = serde_json::from_slice(&body)
        .map_err(|e| SidespotError::Player(format!("parse error: {e}")))?;

    let results = parse_searchview_response(&val);

    match search_type {
        "track" => {
            let result = SearchPageResult {
                items: results.tracks,
                total: results.total_tracks,
            };
            Ok(serde_json::to_string(&result)?)
        }
        "album" => {
            let result = SearchPageResult {
                items: results.albums,
                total: results.total_albums,
            };
            Ok(serde_json::to_string(&result)?)
        }
        "playlist" => {
            let result = SearchPageResult {
                items: results.playlists,
                total: results.total_playlists,
            };
            Ok(serde_json::to_string(&result)?)
        }
        "show" => {
            let result = SearchPageResult {
                items: results.shows,
                total: results.total_shows,
            };
            Ok(serde_json::to_string(&result)?)
        }
        _ => Err(SidespotError::Player(format!("unknown search type: {search_type}"))),
    }
}
