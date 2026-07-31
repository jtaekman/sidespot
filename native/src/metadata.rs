//! Metadata retrieval module.
//!
//! Wraps librespot's `Metadata` trait and `SpClient` to fetch track, album,
//! playlist, and search information, returning JSON to the Kotlin layer.

use hyper::Method;
use librespot_core::SpotifyUri;
use librespot_metadata::image::ImageSize;
use librespot_core::Session;
use librespot_metadata::{Album, Artist, Metadata, Playlist, Track};
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
pub struct ArtistAlbum {
    pub uri: String,
    pub name: String,
    pub image_url: Option<String>,
    pub year: i32,
    pub track_count: i32,
}

#[derive(Debug, Clone, Serialize)]
pub struct ArtistInfo {
    pub uri: String,
    pub name: String,
    pub image_url: Option<String>,
    pub top_tracks: Vec<TrackInfo>,
    pub albums: Vec<ArtistAlbum>,
    pub singles: Vec<ArtistAlbum>,
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
pub struct SearchArtistResult {
    pub uri: String,
    pub name: String,
    pub image_url: Option<String>,
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

#[derive(Debug, Clone, Default, Serialize)]
pub struct SearchResults {
    pub tracks: Vec<TrackInfo>,
    pub artists: Vec<SearchArtistResult>,
    pub albums: Vec<SearchAlbumResult>,
    pub playlists: Vec<SearchPlaylistResult>,
    pub shows: Vec<SearchShowResult>,
    pub total_tracks: i32,
    pub total_artists: i32,
    pub total_albums: i32,
    pub total_playlists: i32,
    pub total_shows: i32,
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

/// Top tracks shown on the artist page.
const TOP_TRACK_LIMIT: usize = 10;

/// How many search hits the context fallback resolves to full metadata.
const SEARCH_FALLBACK_LIMIT: usize = 10;

/// Cap on albums (and separately, singles) shown on the artist page.  Each one
/// costs an `Album::get`, so prolific artists would otherwise be very slow.
const ARTIST_ALBUM_LIMIT: usize = 50;

/// Resolve track URIs to full `TrackInfo`, preserving the input order.
async fn fetch_track_infos(session: &Session, uris: &[SpotifyUri]) -> Vec<TrackInfo> {
    let mut tracks = Vec::with_capacity(uris.len());
    for chunk in uris.chunks(10) {
        let mut handles = Vec::new();
        for track_uri in chunk {
            let sess = session.clone();
            let tu = track_uri.clone();
            handles.push(tokio::spawn(async move {
                let track = Track::get(&sess, &tu).await.ok()?;
                let artists: Vec<ArtistSummary> = track
                    .artists
                    .iter()
                    .map(|a| ArtistSummary {
                        uri: a.id.to_uri(),
                        name: a.name.clone(),
                    })
                    .collect();
                Some(TrackInfo {
                    uri: track.id.to_uri(),
                    name: track.name.clone(),
                    artists,
                    album_name: track.album.name.clone(),
                    album_uri: track.album.id.to_uri(),
                    album_art_url: image_url_from_images(&track.album.covers),
                    duration_ms: track.duration,
                    track_number: track.number,
                    disc_number: track.disc_number,
                    is_explicit: track.is_explicit,
                })
            }));
        }
        for handle in handles {
            if let Ok(Some(t)) = handle.await {
                tracks.push(t);
            }
        }
    }
    tracks
}

/// Resolve album URIs to the lightweight shape the artist page renders.
async fn fetch_artist_albums(session: &Session, uris: &[SpotifyUri]) -> Vec<ArtistAlbum> {
    let mut albums = Vec::with_capacity(uris.len());
    for chunk in uris.chunks(10) {
        let mut handles = Vec::new();
        for album_uri in chunk {
            let sess = session.clone();
            let au = album_uri.clone();
            handles.push(tokio::spawn(async move {
                let album = Album::get(&sess, &au).await.ok()?;
                Some(ArtistAlbum {
                    uri: album.id.to_uri(),
                    name: album.name.clone(),
                    image_url: image_url_from_images(&album.covers),
                    year: album.date.year(),
                    track_count: album.tracks().count() as i32,
                })
            }));
        }
        for handle in handles {
            if let Ok(Some(a)) = handle.await {
                albums.push(a);
            }
        }
    }
    albums
}

/// Fetch artist metadata: portrait, top tracks, albums and singles.
pub async fn get_artist_info(uri: &str) -> Result<String> {
    let session = session::get_session().await?;
    let spotify_uri = SpotifyUri::from_uri(uri)
        .map_err(|e| SidespotError::Player(format!("invalid URI '{uri}': {e}")))?;
    let SpotifyUri::Artist { .. } = spotify_uri else {
        return Err(SidespotError::Player(format!("not an artist URI: {uri}")));
    };

    let artist = Artist::get(&session, &spotify_uri)
        .await
        .map_err(|e| SidespotError::Player(format!("failed to get artist metadata: {e}")))?;

    let image_url = image_url_from_images(&artist.portraits)
        .or_else(|| image_url_from_images(&artist.portrait_group));

    // Top tracks come back in popularity order; keep it.
    let country = session.country();
    let top_uris: Vec<SpotifyUri> = artist
        .top_tracks
        .for_country(&country)
        .iter()
        .take(TOP_TRACK_LIMIT)
        .cloned()
        .collect();
    let top_tracks = fetch_track_infos(&session, &top_uris).await;

    let album_uris: Vec<SpotifyUri> = artist
        .albums_current()
        .take(ARTIST_ALBUM_LIMIT)
        .cloned()
        .collect();
    let single_uris: Vec<SpotifyUri> = artist
        .singles_current()
        .take(ARTIST_ALBUM_LIMIT)
        .cloned()
        .collect();

    let mut albums = fetch_artist_albums(&session, &album_uris).await;
    let mut singles = fetch_artist_albums(&session, &single_uris).await;

    // Catalogue group order is not reliably newest-first.
    albums.sort_by(|a, b| b.year.cmp(&a.year).then_with(|| a.name.cmp(&b.name)));
    singles.sort_by(|a, b| b.year.cmp(&a.year).then_with(|| a.name.cmp(&b.name)));

    let info = ArtistInfo {
        uri: spotify_uri.to_uri(),
        name: artist.name.clone(),
        image_url,
        top_tracks,
        albums,
        singles,
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
// Search
// ---------------------------------------------------------------------------

/// Pathfinder is the GraphQL gateway the first-party clients search through.
/// It accepts our session token, unlike the Web API, so it works for every
/// signed-in user rather than only those on a developer-dashboard allowlist.
const PATHFINDER_HOST: &str = "https://api-partner.spotify.com";

/// Pathfinder only serves *persisted* queries — an inline query string is
/// rejected outright — so we address the web client's `searchDesktop`
/// operation by hash.  Spotify rotates these when the web player ships, and a
/// stale hash comes back as 400; [`search`] then falls back to the context
/// search until this constant is refreshed.
const SEARCH_QUERY_HASH: &str = "d9f785900f0710b31c07818d617f4f7600c1e21217e80f5b043d1e78d74e6026";

/// Smallest image we consider big enough for a search row.
const IMAGE_MIN_WIDTH: i64 = 200;

/// Percent-encode a query-string value.
fn percent_encode(input: &str) -> String {
    let mut out = String::with_capacity(input.len() * 2);
    for b in input.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => out.push(b as char),
            _ => {
                out.push('%');
                out.push(char::from(b"0123456789ABCDEF"[(b >> 4) as usize]));
                out.push(char::from(b"0123456789ABCDEF"[(b & 0x0F) as usize]));
            }
        }
    }
    out
}

/// Run `searchDesktop` against pathfinder and return the decoded response.
async fn pathfinder_search(
    session: &Session,
    query: &str,
    limit: i32,
    offset: i32,
) -> Result<serde_json::Value> {
    use hyper::header::{ACCEPT, HeaderMap, HeaderValue};
    use librespot_core::spclient::RequestOptions;

    let variables = serde_json::json!({
        "searchTerm": query,
        "offset": offset,
        "limit": limit,
        "numberOfTopResults": 5,
        "includeAudiobooks": false,
    });
    let extensions = serde_json::json!({
        "persistedQuery": { "version": 1, "sha256Hash": SEARCH_QUERY_HASH },
    });
    let endpoint = format!(
        "/pathfinder/v1/query?operationName=searchDesktop&variables={}&extensions={}",
        percent_encode(&variables.to_string()),
        percent_encode(&extensions.to_string()),
    );

    let mut headers = HeaderMap::new();
    headers.insert(ACCEPT, HeaderValue::from_static("application/json"));
    headers.insert("app-platform", HeaderValue::from_static("WebPlayer"));

    // `metrics` and `salt` would append params of their own; pathfinder wants
    // the query string exactly as built above.
    let options = RequestOptions {
        metrics: false,
        salt: false,
        base_url: Some(PATHFINDER_HOST),
    };

    let body = session
        .spclient()
        .request_with_options(&Method::GET, &endpoint, Some(headers), None, &options)
        .await
        .map_err(|e| SidespotError::Player(format!("pathfinder request failed: {e}")))?;

    let val: serde_json::Value = serde_json::from_slice(&body)
        .map_err(|e| SidespotError::Player(format!("failed to parse pathfinder response: {e}")))?;

    // GraphQL reports trouble in-band: a rotated hash comes back as HTTP 200
    // carrying `errors` and no data, which must not read as "no results".
    if let Some(errors) = val.get("errors") {
        return Err(SidespotError::Player(format!("pathfinder error: {errors}")));
    }
    if val.pointer("/data/searchV2").is_none() {
        return Err(SidespotError::Player("pathfinder returned no searchV2 data".into()));
    }

    Ok(val)
}

/// Pick a list-row-sized image out of a pathfinder `sources` array.
fn pf_image(node: Option<&serde_json::Value>) -> Option<String> {
    let sources = node?.as_array()?;
    let mut smallest_usable: Option<(i64, &str)> = None;
    let mut largest: Option<(i64, &str)> = None;
    let mut any: Option<&str> = None;

    for source in sources {
        let Some(url) = source.get("url").and_then(|v| v.as_str()) else {
            continue;
        };
        any.get_or_insert(url);
        let Some(width) = source.get("width").and_then(|v| v.as_i64()) else {
            continue;
        };
        if width >= IMAGE_MIN_WIDTH && smallest_usable.is_none_or(|(w, _)| width < w) {
            smallest_usable = Some((width, url));
        }
        if largest.is_none_or(|(w, _)| width > w) {
            largest = Some((width, url));
        }
    }

    smallest_usable
        .or(largest)
        .map(|(_, url)| url)
        .or(any)
        .map(str::to_string)
}

fn pf_str(node: &serde_json::Value, pointer: &str) -> String {
    node.pointer(pointer)
        .and_then(|v| v.as_str())
        .unwrap_or_default()
        .to_string()
}

/// Items and `totalCount` of one `searchV2` section.
fn pf_section<'a>(
    root: &'a serde_json::Value,
    name: &str,
) -> (&'a [serde_json::Value], i32) {
    let Some(section) = root.pointer(&format!("/data/searchV2/{name}")) else {
        return (&[], 0);
    };
    let total = section
        .get("totalCount")
        .and_then(|v| v.as_i64())
        .unwrap_or(0) as i32;
    let items = section
        .get("items")
        .and_then(|v| v.as_array())
        .map(Vec::as_slice)
        .unwrap_or(&[]);
    (items, total)
}

/// Artist credits attached to a track or album.
fn pf_artists(data: &serde_json::Value) -> Vec<ArtistSummary> {
    data.pointer("/artists/items")
        .and_then(|v| v.as_array())
        .map(|items| {
            items
                .iter()
                .map(|a| ArtistSummary {
                    uri: pf_str(a, "/uri"),
                    name: pf_str(a, "/profile/name"),
                })
                .collect()
        })
        .unwrap_or_default()
}

fn parse_pf_tracks(root: &serde_json::Value) -> (Vec<TrackInfo>, i32) {
    let (items, total) = pf_section(root, "tracksV2");
    let tracks = items
        .iter()
        .filter_map(|item| {
            let data = item.pointer("/item/data")?;
            let uri = data.get("uri")?.as_str()?.to_string();
            Some(TrackInfo {
                uri,
                name: pf_str(data, "/name"),
                artists: pf_artists(data),
                album_name: pf_str(data, "/albumOfTrack/name"),
                album_uri: pf_str(data, "/albumOfTrack/uri"),
                album_art_url: pf_image(data.pointer("/albumOfTrack/coverArt/sources")),
                duration_ms: data
                    .pointer("/duration/totalMilliseconds")
                    .and_then(|v| v.as_i64())
                    .unwrap_or(0) as i32,
                // Search hits carry no position within the album.
                track_number: 0,
                disc_number: 0,
                is_explicit: pf_str(data, "/contentRating/label") == "EXPLICIT",
            })
        })
        .collect();
    (tracks, total)
}

fn parse_pf_artists(root: &serde_json::Value) -> (Vec<SearchArtistResult>, i32) {
    let (items, total) = pf_section(root, "artists");
    let artists = items
        .iter()
        .filter_map(|item| {
            let data = item.get("data")?;
            Some(SearchArtistResult {
                uri: data.get("uri")?.as_str()?.to_string(),
                name: pf_str(data, "/profile/name"),
                image_url: pf_image(data.pointer("/visuals/avatarImage/sources")),
            })
        })
        .collect();
    (artists, total)
}

fn parse_pf_albums(root: &serde_json::Value) -> (Vec<SearchAlbumResult>, i32) {
    let (items, total) = pf_section(root, "albumsV2");
    let albums = items
        .iter()
        .filter_map(|item| {
            // The section can also carry pre-releases, which have no album data.
            let data = item.get("data")?;
            Some(SearchAlbumResult {
                uri: data.get("uri")?.as_str()?.to_string(),
                name: pf_str(data, "/name"),
                artist_name: pf_artists(data)
                    .iter()
                    .map(|a| a.name.as_str())
                    .collect::<Vec<_>>()
                    .join(", "),
                album_art_url: pf_image(data.pointer("/coverArt/sources")),
            })
        })
        .collect();
    (albums, total)
}

fn parse_pf_playlists(root: &serde_json::Value) -> (Vec<SearchPlaylistResult>, i32) {
    let (items, total) = pf_section(root, "playlists");
    let playlists = items
        .iter()
        .filter_map(|item| {
            let data = item.get("data")?;
            Some(SearchPlaylistResult {
                uri: data.get("uri")?.as_str()?.to_string(),
                name: pf_str(data, "/name"),
                owner_name: pf_str(data, "/ownerV2/data/name"),
                image_url: pf_image(data.pointer("/images/items/0/sources")),
            })
        })
        .collect();
    (playlists, total)
}

fn parse_pf_shows(root: &serde_json::Value) -> (Vec<SearchShowResult>, i32) {
    let (items, total) = pf_section(root, "podcasts");
    let shows = items
        .iter()
        .filter_map(|item| {
            let data = item.get("data")?;
            Some(SearchShowResult {
                uri: data.get("uri")?.as_str()?.to_string(),
                name: pf_str(data, "/name"),
                publisher: pf_str(data, "/publisher/name"),
                image_url: pf_image(data.pointer("/coverArt/sources")),
            })
        })
        .collect();
    (shows, total)
}

/// Search Spotify's catalogue for one page of every entity type.
///
/// Goes through pathfinder, which authenticates with the session token and so
/// works for any signed-in user.  If that fails — most likely a rotated
/// [`SEARCH_QUERY_HASH`] — falls back to resolving a `spotify:search:` context,
/// which only yields tracks.
pub async fn search(query: &str, limit: i32, offset: i32) -> Result<String> {
    let session = session::get_session().await?;

    match pathfinder_search(&session, query, limit, offset).await {
        Ok(val) => {
            let (tracks, total_tracks) = parse_pf_tracks(&val);
            let (artists, total_artists) = parse_pf_artists(&val);
            let (albums, total_albums) = parse_pf_albums(&val);
            let (playlists, total_playlists) = parse_pf_playlists(&val);
            let (shows, total_shows) = parse_pf_shows(&val);

            let results = SearchResults {
                tracks,
                artists,
                albums,
                playlists,
                shows,
                total_tracks,
                total_artists,
                total_albums,
                total_playlists,
                total_shows,
            };
            Ok(serde_json::to_string(&results)?)
        }
        Err(e) => {
            log::warn!("Pathfinder search failed, falling back to context search: {e}");
            // The fallback can only serve the first page; paging past it would
            // repeat the same tracks under keys the UI has already used.
            if offset > 0 {
                return Ok(serde_json::to_string(&SearchResults::default())?);
            }
            context_search(&session, query).await
        }
    }
}

/// Search by resolving a `spotify:search:` context.  The context carries track
/// URIs only, so every other section comes back empty.
async fn context_search(session: &Session, query: &str) -> Result<String> {
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
    let mut tracks = Vec::new();
    for uri in track_uris.iter().take(SEARCH_FALLBACK_LIMIT) {
        match get_track_info(uri).await {
            Ok(json) => {
                if let Ok(info) = serde_json::from_str::<TrackInfo>(&json) {
                    tracks.push(info);
                }
            }
            Err(e) => log::warn!("Failed to fetch track metadata for {uri}: {e}"),
        }
    }

    let total_tracks = tracks.len() as i32;
    let results = SearchResults {
        tracks,
        total_tracks,
        ..Default::default()
    };
    Ok(serde_json::to_string(&results)?)
}

