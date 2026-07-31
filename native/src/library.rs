//! Library write/read operations via librespot SpClient + internal APIs.
//!
//! Save/unsave operations use the collection v2 API (protobuf) routed through
//! the SpClient proxy. Read operations for albums/shows also use collection v2.
//! Playlist operations use librespot's internal protobuf APIs.

use std::collections::HashSet;

use librespot_core::{Session, SpotifyUri};
use librespot_metadata::image::ImageSize;
use librespot_metadata::{Album, Artist, Episode, Metadata, Show};
use librespot_protocol::collection2v2::{CollectionItem, PageResponse};
use protobuf::Message;
use serde::Serialize;

use librespot_protocol::playlist4_external::{
    Add, CreateListReply, Delta, Item, ListAttributes, ListChanges, Op, SelectedListContent,
    op::Kind as OpKind,
};

use crate::error::{Result, SidespotError};
use crate::session;

// ---------------------------------------------------------------------------
// JSON response types
// ---------------------------------------------------------------------------

#[derive(Debug, Serialize)]
pub struct LibraryResult {
    pub success: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub uri: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct AlbumSummary {
    pub uri: String,
    pub name: String,
    pub artist_name: String,
    pub image_url: Option<String>,
    pub added_at: i32,
}

#[derive(Debug, Serialize)]
pub struct ShowSummary {
    pub uri: String,
    pub name: String,
    pub publisher: String,
    pub image_url: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct SavedArtist {
    pub uri: String,
    pub name: String,
    pub image_url: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct EpisodeInfo {
    pub uri: String,
    pub name: String,
    pub description: String,
    pub duration_ms: i32,
    pub release_date: String,
    pub image_url: Option<String>,
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/// Collection v2 set holding followed artists.  Spotify does not document its
/// set names; this is the only place the string lives so it is cheap to change
/// if the read tiers in `get_followed_artists` show it is wrong.
const ARTIST_COLLECTION_SET: &str = "artist";

/// Items requested per collection v2 page.
const COLLECTION_PAGE_LIMIT: i32 = 100;

/// Upper bound on collection pages, so a server that ignores the pagination
/// token can never spin us forever.
const MAX_COLLECTION_PAGES: usize = 50;

/// Walk a collection v2 set to exhaustion, following `next_page_token`.
///
/// The older readers (`get_saved_albums`, `get_saved_shows`) request a single
/// page of 50 and silently drop the tail; this follows every page.
async fn collect_collection_items(
    session: &Session,
    username: &str,
    set: &str,
) -> Result<Vec<CollectionItem>> {
    let mut items: Vec<CollectionItem> = Vec::new();
    let mut token = String::new();
    let mut seen_tokens: HashSet<String> = HashSet::new();

    for _ in 0..MAX_COLLECTION_PAGES {
        let resp = session
            .spclient()
            .collection_page(username, set, &token, COLLECTION_PAGE_LIMIT)
            .await
            .map_err(|e| SidespotError::Player(format!("collection_page({set}) failed: {e}")))?;

        let page = PageResponse::parse_from_bytes(&resp).map_err(|e| {
            SidespotError::Player(format!("parse page response({set}) failed: {e}"))
        })?;

        let got = page.items.len();
        items.extend(page.items);

        let next = page.next_page_token;
        // Stop on the last page, an empty page, or a server that replays a token.
        if next.is_empty() || got == 0 || !seen_tokens.insert(next.clone()) {
            break;
        }
        token = next;
    }

    Ok(items)
}

/// Recursively collect every `spotify:artist:` string in an arbitrary JSON value.
///
/// The user-profile-view "following" response shape is undocumented, so this
/// stays deliberately shape-agnostic.
fn walk_artist_uris(value: &serde_json::Value, out: &mut Vec<String>) {
    match value {
        serde_json::Value::String(s) if s.starts_with("spotify:artist:") => out.push(s.clone()),
        serde_json::Value::Array(a) => a.iter().for_each(|v| walk_artist_uris(v, out)),
        serde_json::Value::Object(o) => o.values().for_each(|v| walk_artist_uris(v, out)),
        _ => {}
    }
}

/// Last-resort source of followed artists: the public profile "following" list.
async fn following_artist_uris(session: &Session, username: &str) -> Vec<String> {
    let body = match session.spclient().get_user_following(username).await {
        Ok(b) => b,
        Err(e) => {
            log::warn!("get_user_following failed: {e}");
            return Vec::new();
        }
    };
    let value: serde_json::Value = match serde_json::from_slice(&body) {
        Ok(v) => v,
        Err(e) => {
            log::warn!("parse following response failed: {e}");
            return Vec::new();
        }
    };
    let mut uris = Vec::new();
    walk_artist_uris(&value, &mut uris);
    uris
}

fn image_url_from_images(images: &librespot_metadata::image::Images) -> Option<String> {
    let preferred_order = [ImageSize::LARGE, ImageSize::DEFAULT, ImageSize::SMALL];
    for &size in &preferred_order {
        if let Some(img) = images.iter().find(|i| i.size == size) {
            return Some(format!("https://i.scdn.co/image/{}", img.id.to_base16()));
        }
    }
    images
        .first()
        .map(|img| format!("https://i.scdn.co/image/{}", img.id.to_base16()))
}

fn ok_result() -> Result<String> {
    Ok(serde_json::to_string(&LibraryResult {
        success: true,
        error: None,
        uri: None,
    })?)
}

/// Build a CollectionItem from a URI for write operations.
fn make_collection_item(uri: &str) -> CollectionItem {
    let mut item = CollectionItem::new();
    item.uri = uri.to_string();
    item.added_at = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs() as i32;
    item
}

/// Build a CollectionItem that marks a URI as removed.
fn make_removal_item(uri: &str) -> CollectionItem {
    let mut item = make_collection_item(uri);
    item.is_removed = true;
    item
}

// ---------------------------------------------------------------------------
// Write operations (via collection v2 API)
// ---------------------------------------------------------------------------

/// Add a track to the user's Liked Songs.
pub async fn add_to_liked_songs(track_uri: &str) -> Result<String> {
    let session = session::get_session().await?;
    let username = session.username();

    let item = make_collection_item(track_uri);
    session
        .spclient()
        .collection_write(&username, "collection", &[item])
        .await
        .map_err(|e| SidespotError::Player(format!("save track failed: {e}")))?;

    ok_result()
}

/// Save an album to the user's library.
pub async fn save_album(album_uri: &str) -> Result<String> {
    let session = session::get_session().await?;
    let username = session.username();

    let item = make_collection_item(album_uri);
    session
        .spclient()
        .collection_write(&username, "collection", &[item])
        .await
        .map_err(|e| SidespotError::Player(format!("save album failed: {e}")))?;

    ok_result()
}

/// Save a show (podcast) to the user's library.
pub async fn save_show(show_uri: &str) -> Result<String> {
    let session = session::get_session().await?;
    let username = session.username();

    let item = make_collection_item(show_uri);
    session
        .spclient()
        .collection_write(&username, "show", &[item])
        .await
        .map_err(|e| SidespotError::Player(format!("save show failed: {e}")))?;

    ok_result()
}

/// Remove an album from the user's library.
pub async fn unsave_album(album_uri: &str) -> Result<String> {
    let session = session::get_session().await?;
    let username = session.username();

    let item = make_removal_item(album_uri);
    session
        .spclient()
        .collection_write(&username, "collection", &[item])
        .await
        .map_err(|e| SidespotError::Player(format!("unsave album failed: {e}")))?;

    ok_result()
}

/// Remove a show (podcast) from the user's library.
pub async fn unsave_show(show_uri: &str) -> Result<String> {
    let session = session::get_session().await?;
    let username = session.username();

    let item = make_removal_item(show_uri);
    session
        .spclient()
        .collection_write(&username, "show", &[item])
        .await
        .map_err(|e| SidespotError::Player(format!("unsave show failed: {e}")))?;

    ok_result()
}

/// Add a track to an existing playlist (via internal protobuf API).
pub async fn add_to_playlist(playlist_uri: &str, track_uri: &str) -> Result<String> {
    let session = session::get_session().await?;

    let spotify_uri = SpotifyUri::from_uri(playlist_uri)
        .map_err(|e| SidespotError::Player(format!("invalid playlist URI: {e}")))?;
    let playlist_id = match &spotify_uri {
        SpotifyUri::Playlist { id, .. } => id.clone(),
        _ => return Err(SidespotError::Player("not a playlist URI".into())),
    };
    let playlist_base62 = playlist_id.to_base62();

    let playlist_bytes = session
        .spclient()
        .get_playlist(&playlist_id)
        .await
        .map_err(|e| SidespotError::Player(format!("get_playlist failed: {e}")))?;

    let content = SelectedListContent::parse_from_bytes(&playlist_bytes)
        .map_err(|e| SidespotError::Player(format!("parse playlist failed: {e}")))?;

    let revision = content.revision().to_vec();

    let mut item = Item::new();
    item.set_uri(track_uri.to_string());

    let mut add = Add::new();
    add.items.push(item);
    add.set_add_last(true);

    let mut op = Op::new();
    op.set_kind(OpKind::ADD);
    op.add = Some(add).into();

    let mut delta = Delta::new();
    delta.set_base_version(revision);
    delta.ops.push(op);

    let mut changes = ListChanges::new();
    changes.deltas.push(delta);
    changes.set_want_resulting_revisions(true);

    session
        .spclient()
        .playlist_modify(&playlist_base62, &changes)
        .await
        .map_err(|e| SidespotError::Player(format!("playlist_modify failed: {e}")))?;

    ok_result()
}

/// Create a new playlist and return its URI (via internal protobuf API).
pub async fn create_playlist(name: &str) -> Result<String> {
    let session = session::get_session().await?;
    let username = session.username();

    let rootlist_bytes = session
        .spclient()
        .get_rootlist(0, None)
        .await
        .map_err(|e| SidespotError::Player(format!("get_rootlist failed: {e}")))?;

    let rootlist = SelectedListContent::parse_from_bytes(&rootlist_bytes)
        .map_err(|e| SidespotError::Player(format!("parse rootlist failed: {e}")))?;

    let revision = rootlist.revision().to_vec();

    let mut item = Item::new();
    item.set_uri(String::new());

    let mut item_attrs = librespot_protocol::playlist4_external::ItemAttributes::new();
    item_attrs.set_added_by(username.clone());
    item.attributes = Some(item_attrs).into();

    let mut add = Add::new();
    add.items.push(item);
    add.set_add_last(true);

    let mut op = Op::new();
    op.set_kind(OpKind::ADD);
    op.add = Some(add).into();

    let mut attrs = ListAttributes::new();
    attrs.set_name(name.to_string());

    let mut partial = librespot_protocol::playlist4_external::ListAttributesPartialState::new();
    partial.values = protobuf::MessageField::some(attrs);

    let mut ula = librespot_protocol::playlist4_external::UpdateListAttributes::new();
    ula.new_attributes = protobuf::MessageField::some(partial);

    let mut update_op = Op::new();
    update_op.set_kind(OpKind::UPDATE_LIST_ATTRIBUTES);
    update_op.update_list_attributes = Some(ula).into();

    let mut delta = Delta::new();
    delta.set_base_version(revision);
    delta.ops.push(op);
    delta.ops.push(update_op);

    let mut changes = ListChanges::new();
    changes.deltas.push(delta);
    changes.set_want_resulting_revisions(true);

    let response = session
        .spclient()
        .rootlist_modify(&changes)
        .await
        .map_err(|e| SidespotError::Player(format!("rootlist_modify failed: {e}")))?;

    let new_uri = if let Ok(reply) = CreateListReply::parse_from_bytes(&response) {
        Some(reply.uri().to_string())
    } else {
        log::warn!("Could not parse CreateListReply, trying SelectedListContent");
        None
    };

    let result = LibraryResult {
        success: true,
        error: None,
        uri: new_uri,
    };
    Ok(serde_json::to_string(&result)?)
}

/// Save (follow) an existing playlist to the user's rootlist.
pub async fn save_playlist(playlist_uri: &str) -> Result<String> {
    let session = session::get_session().await?;

    let rootlist_bytes = session
        .spclient()
        .get_rootlist(0, None)
        .await
        .map_err(|e| SidespotError::Player(format!("get_rootlist failed: {e}")))?;

    let rootlist = SelectedListContent::parse_from_bytes(&rootlist_bytes)
        .map_err(|e| SidespotError::Player(format!("parse rootlist failed: {e}")))?;

    let revision = rootlist.revision().to_vec();

    let mut item = Item::new();
    item.set_uri(playlist_uri.to_string());

    let mut add = Add::new();
    add.items.push(item);
    add.set_add_last(true);

    let mut op = Op::new();
    op.set_kind(OpKind::ADD);
    op.add = Some(add).into();

    let mut delta = Delta::new();
    delta.set_base_version(revision);
    delta.ops.push(op);

    let mut changes = ListChanges::new();
    changes.deltas.push(delta);
    changes.set_want_resulting_revisions(true);

    session
        .spclient()
        .rootlist_modify(&changes)
        .await
        .map_err(|e| SidespotError::Player(format!("rootlist_modify failed: {e}")))?;

    ok_result()
}

/// Remove (unfollow) a playlist from the user's rootlist.
pub async fn unsave_playlist(playlist_uri: &str) -> Result<String> {
    let session = session::get_session().await?;
    let username = session.username();

    let item = make_removal_item(playlist_uri);
    session
        .spclient()
        .collection_write(&username, "rootlist", &[item])
        .await
        .map_err(|e| SidespotError::Player(format!("unsave playlist failed: {e}")))?;

    ok_result()
}

/// Unfollow an artist.
pub async fn unfollow_artist(artist_uri: &str) -> Result<String> {
    let session = session::get_session().await?;
    let username = session.username();

    let item = make_removal_item(artist_uri);
    session
        .spclient()
        .collection_write(&username, ARTIST_COLLECTION_SET, &[item])
        .await
        .map_err(|e| SidespotError::Player(format!("unfollow artist failed: {e}")))?;

    ok_result()
}

/// Follow an artist.
pub async fn follow_artist(artist_uri: &str) -> Result<String> {
    let session = session::get_session().await?;
    let username = session.username();

    let item = make_collection_item(artist_uri);
    session
        .spclient()
        .collection_write(&username, ARTIST_COLLECTION_SET, &[item])
        .await
        .map_err(|e| SidespotError::Player(format!("follow artist failed: {e}")))?;

    ok_result()
}

// ---------------------------------------------------------------------------
// Read operations
// ---------------------------------------------------------------------------

/// Get the user's followed artists, then fetch name + portrait for each.
///
/// Spotify's set names are undocumented, so this tries the dedicated "artist"
/// set, then the main "collection" set, then the profile "following" list, and
/// logs which one produced the result.
pub async fn get_followed_artists() -> Result<String> {
    let session = session::get_session().await?;
    let username = session.username();

    fn artist_uris(items: Vec<CollectionItem>) -> Vec<(String, i32)> {
        items
            .into_iter()
            .filter(|i| !i.is_removed && i.uri.starts_with("spotify:artist:"))
            .map(|i| (i.uri, i.added_at))
            .collect()
    }

    let mut source = ARTIST_COLLECTION_SET;
    let mut ordered = artist_uris(
        collect_collection_items(&session, &username, ARTIST_COLLECTION_SET)
            .await
            .unwrap_or_else(|e| {
                log::warn!("read '{ARTIST_COLLECTION_SET}' set failed: {e}");
                Vec::new()
            }),
    );

    if ordered.is_empty() {
        source = "collection";
        ordered = artist_uris(
            collect_collection_items(&session, &username, "collection")
                .await
                .unwrap_or_else(|e| {
                    log::warn!("read 'collection' set failed: {e}");
                    Vec::new()
                }),
        );
    }

    if ordered.is_empty() {
        source = "profile-following";
        ordered = following_artist_uris(&session, &username)
            .await
            .into_iter()
            .map(|uri| (uri, 0))
            .collect();
    }

    // Most recently followed first, de-duplicated.
    ordered.sort_by(|a, b| b.1.cmp(&a.1));
    let mut seen = HashSet::new();
    ordered.retain(|(uri, _)| seen.insert(uri.clone()));

    log::info!(
        "followed artists: {} item(s) via '{source}'",
        ordered.len()
    );

    let mut artists = Vec::with_capacity(ordered.len());
    for chunk in ordered.chunks(10) {
        let mut handles = Vec::new();
        for (uri_str, _) in chunk {
            let uri_str = uri_str.clone();
            let sess = session.clone();
            handles.push(tokio::spawn(async move {
                let uri = SpotifyUri::from_uri(&uri_str).ok()?;
                let artist = Artist::get(&sess, &uri).await.ok()?;
                let image_url = image_url_from_images(&artist.portraits)
                    .or_else(|| image_url_from_images(&artist.portrait_group));
                Some(SavedArtist {
                    uri: uri_str,
                    name: artist.name,
                    image_url,
                })
            }));
        }
        for handle in handles {
            if let Ok(Some(a)) = handle.await {
                artists.push(a);
            }
        }
    }

    Ok(serde_json::to_string(&artists)?)
}

/// Get the user's saved albums via collection v2 paging, then fetch metadata.
pub async fn get_saved_albums() -> Result<String> {
    let session = session::get_session().await?;
    let username = session.username();

    let resp = session
        .spclient()
        .collection_page(&username, "collection", "", 50)
        .await
        .map_err(|e| SidespotError::Player(format!("get saved albums failed: {e}")))?;

    let page = librespot_protocol::collection2v2::PageResponse::parse_from_bytes(&resp)
        .map_err(|e| SidespotError::Player(format!("parse page response failed: {e}")))?;

    // Filter to album URIs only and fetch metadata
    let album_items: Vec<_> = page.items.iter()
        .filter(|item| item.uri.starts_with("spotify:album:"))
        .collect();

    let mut albums = Vec::new();
    for chunk in album_items.chunks(10) {
        let mut handles = Vec::new();
        for item in chunk {
            let uri_str = item.uri.clone();
            let saved_at = item.added_at;
            let sess = session.clone();
            handles.push(tokio::spawn(async move {
                let uri = SpotifyUri::from_uri(&uri_str).ok()?;
                let album = Album::get(&sess, &uri).await.ok()?;
                let art_url = image_url_from_images(&album.covers);
                let artist_name = album
                    .artists
                    .iter()
                    .map(|a| a.name.as_str())
                    .collect::<Vec<_>>()
                    .join(", ");
                Some(AlbumSummary {
                    uri: uri_str,
                    name: album.name,
                    artist_name,
                    image_url: art_url,
                    added_at: saved_at,
                })
            }));
        }
        for handle in handles {
            if let Ok(Some(a)) = handle.await {
                albums.push(a);
            }
        }
    }

    // Sort by added_at descending (most recently saved first)
    albums.sort_by(|a, b| b.added_at.cmp(&a.added_at));

    Ok(serde_json::to_string(&albums)?)
}

/// Get the user's saved shows via collection v2 paging, then fetch metadata.
pub async fn get_saved_shows() -> Result<String> {
    let session = session::get_session().await?;
    let username = session.username();

    let resp = session
        .spclient()
        .collection_page(&username, "show", "", 50)
        .await
        .map_err(|e| SidespotError::Player(format!("get saved shows failed: {e}")))?;

    let page = librespot_protocol::collection2v2::PageResponse::parse_from_bytes(&resp)
        .map_err(|e| SidespotError::Player(format!("parse page response failed: {e}")))?;

    // Fetch show metadata for each item
    let mut shows = Vec::new();
    for chunk in page.items.chunks(10) {
        let mut handles = Vec::new();
        for item in chunk {
            let uri_str = item.uri.clone();
            let sess = session.clone();
            handles.push(tokio::spawn(async move {
                let uri = SpotifyUri::from_uri(&uri_str).ok()?;
                let show = Show::get(&sess, &uri).await.ok()?;
                let art_url = image_url_from_images(&show.covers);
                Some(ShowSummary {
                    uri: uri_str,
                    name: show.name,
                    publisher: show.publisher,
                    image_url: art_url,
                })
            }));
        }
        for handle in handles {
            if let Ok(Some(s)) = handle.await {
                shows.push(s);
            }
        }
    }

    Ok(serde_json::to_string(&shows)?)
}

/// Get episodes for a show (via librespot metadata).
pub async fn get_show_episodes(show_uri: &str) -> Result<String> {
    let session = session::get_session().await?;
    let uri = SpotifyUri::from_uri(show_uri)
        .map_err(|e| SidespotError::Player(format!("invalid show URI: {e}")))?;

    let show = Show::get(&session, &uri)
        .await
        .map_err(|e| SidespotError::Player(format!("failed to get show: {e}")))?;

    let mut episodes = Vec::new();
    for chunk in show.episodes.chunks(10) {
        let mut handles = Vec::new();
        for ep_uri in chunk {
            let sess = session.clone();
            let eu = ep_uri.clone();
            handles.push(tokio::spawn(async move {
                let episode = Episode::get(&sess, &eu).await.ok()?;
                let art_url = image_url_from_images(&episode.covers);
                Some(EpisodeInfo {
                    uri: episode.id.to_uri(),
                    name: episode.name,
                    description: episode.description,
                    duration_ms: episode.duration,
                    release_date: episode.publish_time.to_string(),
                    image_url: art_url,
                })
            }));
        }
        for handle in handles {
            if let Ok(Some(ep)) = handle.await {
                episodes.push(ep);
            }
        }
    }

    Ok(serde_json::to_string(&episodes)?)
}
