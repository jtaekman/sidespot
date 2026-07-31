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

/// Tracks-only search payload; the app fills the other sections from the Web API.
#[derive(Debug, Clone, Default, Serialize)]
pub struct SearchResults {
    pub tracks: Vec<TrackInfo>,
    pub total_tracks: i32,
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

/// Search Spotify by resolving a `spotify:search:` context.
///
/// This is the offline-ish fallback the app uses when the Web API is
/// unreachable: the context only carries track URIs, so albums, artists,
/// playlists and shows come back empty.  (Spotify's internal `searchview`
/// endpoint used to serve all of those in one call, but it now rejects every
/// request from this client.)
pub async fn search(query: &str) -> Result<String> {
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

