package com.sidespot.offline

import android.content.Context
import android.util.Log
import com.sidespot.bridge.AlbumInfo
import com.sidespot.bridge.NativeBridge
import com.sidespot.bridge.PlaylistInfo
import com.sidespot.bridge.TrackInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A downloaded album, playlist or Liked Songs, or the set of downloaded podcast episodes. */
@Serializable
data class DownloadedCollection(
    val uri: String,
    val name: String,
    val subtitle: String = "",
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("track_uris") val trackUris: List<String> = emptyList(),
    @SerialName("added_at_ms") val addedAtMs: Long = 0L,
)

@Serializable
private data class StoredDownloads(
    val collections: List<DownloadedCollection> = emptyList(),
    val unavailable: List<String> = emptyList(),
)

enum class ConnectionState { CONNECTING, ONLINE, OFFLINE }

enum class DownloadStatus { IDLE, DOWNLOADING, WAITING_FOR_CONNECTION, WAITING_FOR_WIFI, RETRYING }

data class DownloadsState(
    val collections: List<DownloadedCollection> = emptyList(),
    /** Tracks and episodes whose audio is stored on the device. */
    val downloaded: Set<String> = emptySet(),
    /** Tracks that can't be downloaded, e.g. because they are no longer on Spotify. */
    val unavailable: Set<String> = emptySet(),
    val status: DownloadStatus = DownloadStatus.IDLE,
    /** Tracks still to download across all collections. */
    val remaining: Int = 0,
    val storageBytes: Long = 0L,
) {
    fun collection(uri: String): DownloadedCollection? = collections.find { it.uri == uri }

    /** Downloaded and downloadable track counts for [collection]. */
    fun progress(collection: DownloadedCollection): Pair<Int, Int> =
        collection.trackUris.count { it in downloaded } to
            collection.trackUris.count { it !in unavailable }
}

/**
 * Keeps downloaded collections on the device and in sync with Spotify.
 *
 * Collections and their track lists are persisted here; the audio, keys and track
 * metadata live in the native offline store. A single worker downloads whatever
 * the collections want but the device doesn't have yet, one track at a time,
 * whenever the session is online and the network is allowed.
 *
 * Also the app's source of truth for whether it is offline, since that decides
 * both what the UI shows and when downloads may run.
 */
class DownloadManager private constructor(context: Context) {

    companion object {
        /** Pseudo-collection holding individually downloaded podcast episodes. */
        const val EPISODES_URI = "downloaded_episodes"
        const val LIKED_SONGS_URI = "liked_songs"

        const val KEY_DOWNLOAD_OVER_CELLULAR = "download_over_cellular"
        private const val PREFS_NAME = "sidespot_settings"
        private const val TAG = "SidespotDownloads"

        /** Consecutive failed downloads after which the worker pauses before retrying. */
        private const val MAX_CONSECUTIVE_FAILURES = 3
        private const val RETRY_DELAY_MS = 60_000L

        @Volatile
        private var instance: DownloadManager? = null

        fun init(context: Context): DownloadManager =
            instance ?: synchronized(this) {
                instance ?: DownloadManager(context.applicationContext).also { instance = it }
            }

        /** The instance created by [init] in Application.onCreate(). */
        fun get(): DownloadManager = checkNotNull(instance) { "DownloadManager.init() not called" }
    }

    private enum class Outcome { DOWNLOADED, UNAVAILABLE, FAILED, SESSION_LOST }

    /** Everything that decides whether the worker can make progress. */
    private data class WorkerInputs(
        val connection: ConnectionState,
        val network: NetworkState,
        val allowCellular: Boolean,
        val queueVersion: Int,
    )

    private val appContext = context.applicationContext
    private val stateFile = File(context.filesDir, "offline/collections.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Guards changes to collections against the worker recording a finished download. */
    private val mutex = Mutex()
    private val loaded = CompletableDeferred<Unit>()

    /** Collection changes, applied in the order they were requested. */
    private val changes = Channel<(DownloadsState) -> DownloadsState>(Channel.UNLIMITED)

    val network = NetworkMonitor(appContext)

    private val _connection = MutableStateFlow(ConnectionState.CONNECTING)

    /** True when Spotify can't be reached: the session started offline, or the network dropped. */
    val isOffline: StateFlow<Boolean> = combine(_connection, network.state) { connection, net ->
        connection == ConnectionState.OFFLINE ||
            (connection == ConnectionState.ONLINE && !net.isOnline)
    }.stateIn(scope, SharingStarted.Eagerly, false)

    private val allowCellular = MutableStateFlow(
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DOWNLOAD_OVER_CELLULAR, false)
    )

    /** Bumped whenever the wanted tracks change, to wake the worker. */
    private val queueVersion = MutableStateFlow(0)

    /**
     * False if the saved collections couldn't be read. Downloads are then kept even
     * though no collection wants them, rather than deleting everything on the
     * strength of a bad file; only Remove All Downloads deletes them.
     */
    private var removeUnwantedDownloads = true

    /** Called when downloads find the session shut down, so it can be replaced. */
    @Volatile
    private var sessionLostListener: (() -> Unit)? = null

    private val _state = MutableStateFlow(DownloadsState())
    val state: StateFlow<DownloadsState> = _state.asStateFlow()

    init {
        scope.launch {
            val stored = readStoredState()
            _state.value = DownloadsState(
                collections = stored.collections,
                downloaded = parseUris(NativeBridge.offlineList()),
                unavailable = stored.unavailable.toSet(),
                storageBytes = NativeBridge.offlineStorageBytes(),
            )
            loaded.complete(Unit)
            // A removal is finished here if the app was killed partway through one.
            mutex.withLock { removeUnwanted() }
            launch { for (change in changes) applyChange(change) }
            runWorker()
        }
        scope.launch {
            _connection.collect { if (it == ConnectionState.ONLINE) syncAll() }
        }
    }

    // -- Connection --

    fun setConnectionState(state: ConnectionState) {
        _connection.value = state
    }

    fun setAllowCellular(allow: Boolean) {
        allowCellular.value = allow
    }

    fun setSessionLostListener(listener: (() -> Unit)?) {
        sessionLostListener = listener
    }

    fun isDownloaded(uri: String): Boolean = uri in _state.value.downloaded

    // -- Collections --

    /** Download every track of an album, playlist or Liked Songs, and keep it in sync. */
    fun addCollection(
        uri: String,
        name: String,
        subtitle: String,
        imageUrl: String?,
        trackUris: List<String>,
    ) = mutate { state ->
        val collection = DownloadedCollection(
            uri = uri,
            name = name,
            subtitle = subtitle,
            imageUrl = imageUrl,
            trackUris = trackUris.distinct(),
            addedAtMs = state.collection(uri)?.addedAtMs ?: System.currentTimeMillis(),
        )
        state.copy(collections = state.collections.filterNot { it.uri == uri } + collection)
    }

    fun removeCollection(uri: String) = mutate { state ->
        state.copy(collections = state.collections.filterNot { it.uri == uri })
    }

    fun removeAll() = mutate { state ->
        // Everything goes, including downloads kept because the saved state was unreadable.
        NativeBridge.offlineRemove(JSONArray(state.downloaded.toList()).toString())
        state.copy(
            collections = emptyList(),
            downloaded = emptySet(),
            unavailable = emptySet(),
            storageBytes = NativeBridge.offlineStorageBytes(),
        )
    }

    /**
     * Bring a downloaded collection's tracks up to date with a fresh listing: new
     * tracks get downloaded, removed ones deleted. Does nothing for collections
     * that aren't downloaded.
     */
    fun syncCollection(uri: String, name: String, trackUris: List<String>) = mutate { state ->
        val existing = state.collection(uri) ?: return@mutate state
        // An empty listing is more likely a glitch than a playlist that was emptied;
        // don't throw away every download on the strength of it.
        if (trackUris.isEmpty()) return@mutate state
        val updated = existing.copy(
            name = name.ifEmpty { existing.name },
            trackUris = trackUris.distinct(),
        )
        state.copy(collections = state.collections.map { if (it.uri == uri) updated else it })
    }

    fun addEpisode(uri: String) = mutate { state ->
        val episodes = state.collection(EPISODES_URI)
            ?: DownloadedCollection(
                uri = EPISODES_URI,
                name = "Podcast Episodes",
                addedAtMs = System.currentTimeMillis(),
            )
        if (uri in episodes.trackUris) return@mutate state
        val updated = episodes.copy(trackUris = listOf(uri) + episodes.trackUris)
        state.copy(collections = state.collections.filterNot { it.uri == EPISODES_URI } + updated)
    }

    fun removeEpisode(uri: String) = mutate { state ->
        val episodes = state.collection(EPISODES_URI) ?: return@mutate state
        val remaining = episodes.trackUris - uri
        val others = state.collections.filterNot { it.uri == EPISODES_URI }
        state.copy(
            collections = if (remaining.isEmpty()) others
            else others + episodes.copy(trackUris = remaining),
        )
    }

    /** Stored metadata for whichever of [uris] are downloaded, in order. */
    fun trackInfos(uris: List<String>): List<TrackInfo> {
        val result = NativeBridge.offlineTrackInfos(JSONArray(uris).toString()) ?: return emptyList()
        return try {
            json.decodeFromString<List<TrackInfo>>(result)
        } catch (e: Exception) {
            Log.w(TAG, "trackInfos: failed to parse", e)
            emptyList()
        }
    }

    fun refreshStorageUsed() {
        scope.launch {
            _state.update { it.copy(storageBytes = NativeBridge.offlineStorageBytes()) }
        }
    }

    private fun mutate(change: (DownloadsState) -> DownloadsState) {
        changes.trySend(change)
    }

    private suspend fun applyChange(change: (DownloadsState) -> DownloadsState) {
        mutex.withLock {
            val before = _state.value
            val after = change(before)
            if (after == before) return
            // Changes only touch what the worker doesn't, so nothing it did
            // meanwhile is lost by setting the result directly.
            _state.update { it.copy(collections = after.collections, unavailable = after.unavailable) }
            if (after.downloaded != before.downloaded) {
                _state.update {
                    it.copy(downloaded = after.downloaded, storageBytes = after.storageBytes)
                }
            }
            persist()
            removeUnwanted()
            queueVersion.update { it + 1 }
        }
    }

    /** Delete downloads that no collection wants any more. Call with [mutex] held. */
    private fun removeUnwanted() {
        if (!removeUnwantedDownloads) return
        val state = _state.value
        val wanted = state.collections.flatMapTo(HashSet()) { it.trackUris }
        val unwanted = state.downloaded - wanted
        val staleUnavailable = state.unavailable - wanted
        if (unwanted.isEmpty() && staleUnavailable.isEmpty()) return
        if (unwanted.isNotEmpty()) {
            NativeBridge.offlineRemove(JSONArray(unwanted.toList()).toString())
            Log.i(TAG, "removed ${unwanted.size} downloads")
        }
        _state.update {
            it.copy(
                downloaded = it.downloaded - unwanted,
                unavailable = it.unavailable - staleUnavailable,
                storageBytes = NativeBridge.offlineStorageBytes(),
            )
        }
        if (staleUnavailable.isNotEmpty()) persist()
    }

    /** Re-fetch every downloaded collection so changes made elsewhere are picked up. */
    private suspend fun syncAll() {
        loaded.await()
        // Tracks that couldn't be downloaded before get another chance.
        mutate { it.copy(unavailable = emptySet()) }
        for (collection in _state.value.collections) {
            if (_connection.value != ConnectionState.ONLINE) return
            val (name, trackUris) = fetchListing(collection.uri) ?: continue
            syncCollection(collection.uri, name, trackUris)
        }
    }

    private fun fetchListing(uri: String): Pair<String, List<String>>? = when {
        uri == LIKED_SONGS_URI -> NativeBridge.metadataGetLikedSongs()
            ?.let { PlaylistInfo.fromJson(it) }
            ?.let { "Liked Songs" to it.trackUris }
        uri.startsWith("spotify:playlist:") -> NativeBridge.metadataGetPlaylist(uri)
            ?.let { PlaylistInfo.fromJson(it) }
            ?.let { it.name to it.trackUris }
        uri.startsWith("spotify:album:") -> NativeBridge.metadataGetAlbum(uri)
            ?.let { AlbumInfo.fromJson(it) }
            ?.let { album -> album.name to album.tracks.map { it.uri } }
        else -> null
    }

    // -- Worker --

    private fun workerInputs() = WorkerInputs(
        connection = _connection.value,
        network = network.state.value,
        allowCellular = allowCellular.value,
        queueVersion = queueVersion.value,
    )

    /** Suspend until anything in [WorkerInputs] differs from [snapshot], or [timeoutMs] passes. */
    private suspend fun awaitInputsChange(snapshot: WorkerInputs, timeoutMs: Long? = null) {
        val changed = combine(_connection, network.state, allowCellular, queueVersion, ::WorkerInputs)
        if (timeoutMs == null) {
            changed.first { it != snapshot }
        } else {
            withTimeoutOrNull(timeoutMs) { changed.first { it != snapshot } }
        }
    }

    /** Why downloads can't run right now, or null if they can. */
    private fun blocker(): DownloadStatus? {
        val net = network.state.value
        return when {
            _connection.value != ConnectionState.ONLINE || !net.isOnline ->
                DownloadStatus.WAITING_FOR_CONNECTION
            !allowCellular.value && !net.isUnmetered -> DownloadStatus.WAITING_FOR_WIFI
            else -> null
        }
    }

    /** Tracks wanted but not yet downloaded, oldest collection first. */
    private fun pendingTracks(): List<String> {
        val state = _state.value
        return state.collections
            .sortedBy { it.addedAtMs }
            .flatMap { it.trackUris }
            .distinct()
            .filter { it !in state.downloaded && it !in state.unavailable }
    }

    private suspend fun runWorker() {
        var failures = 0
        // Tracks that just failed; the rest of the queue is tried before them again.
        val skipped = HashSet<String>()
        while (true) {
            // Taken before deciding anything, so a change while deciding still wakes us.
            val inputs = workerInputs()
            val pending = pendingTracks()
            _state.update { it.copy(remaining = pending.size) }
            val blocker = blocker()
            val next = pending.firstOrNull { it !in skipped }
            when {
                pending.isEmpty() -> {
                    setStatus(DownloadStatus.IDLE)
                    skipped.clear()
                    awaitInputsChange(inputs)
                }
                blocker != null -> {
                    setStatus(blocker)
                    awaitInputsChange(inputs)
                }
                next == null || failures >= MAX_CONSECUTIVE_FAILURES -> {
                    setStatus(DownloadStatus.RETRYING)
                    awaitInputsChange(inputs, RETRY_DELAY_MS)
                    failures = 0
                    skipped.clear()
                }
                else -> {
                    setStatus(DownloadStatus.DOWNLOADING)
                    when (download(next)) {
                        Outcome.DOWNLOADED, Outcome.UNAVAILABLE -> failures = 0
                        Outcome.FAILED -> {
                            failures++
                            skipped += next
                        }
                        Outcome.SESSION_LOST -> {
                            // Wait for the session to be replaced before trying again.
                            _connection.value = ConnectionState.CONNECTING
                            sessionLostListener?.invoke()
                        }
                    }
                }
            }
        }
    }

    private suspend fun download(uri: String): Outcome {
        val response = NativeBridge.offlineDownload(uri)
        val result = response?.let { runCatching { JSONObject(it) }.getOrNull() }
        return mutex.withLock {
            when {
                result != null && !result.has("error") -> {
                    val stillWanted = _state.value.collections.any { uri in it.trackUris }
                    if (stillWanted) {
                        _state.update {
                            it.copy(
                                downloaded = it.downloaded + uri,
                                storageBytes = NativeBridge.offlineStorageBytes(),
                            )
                        }
                    } else {
                        // Its collection was removed while it downloaded.
                        NativeBridge.offlineRemove(JSONArray(listOf(uri)).toString())
                    }
                    Outcome.DOWNLOADED
                }
                result?.optBoolean("session_lost") == true -> Outcome.SESSION_LOST
                result?.optBoolean("permanent") == true -> {
                    Log.w(TAG, "$uri can't be downloaded: ${result.optString("error")}")
                    _state.update { it.copy(unavailable = it.unavailable + uri) }
                    persist()
                    Outcome.UNAVAILABLE
                }
                else -> {
                    Log.w(TAG, "downloading $uri failed: ${result?.optString("error") ?: response}")
                    Outcome.FAILED
                }
            }
        }
    }

    private fun setStatus(status: DownloadStatus) {
        val previous = _state.value.status
        if (previous == status) return
        _state.update { it.copy(status = status) }
        if (status == DownloadStatus.DOWNLOADING) DownloadService.start(appContext)
    }

    // -- Persistence --

    private fun readStoredState(): StoredDownloads = try {
        if (stateFile.exists()) json.decodeFromString<StoredDownloads>(stateFile.readText())
        else StoredDownloads()
    } catch (e: Exception) {
        Log.e(TAG, "failed to read downloads state, keeping all downloads", e)
        removeUnwantedDownloads = false
        StoredDownloads()
    }

    private fun persist() {
        val state = _state.value
        val stored = StoredDownloads(state.collections, state.unavailable.toList())
        try {
            stateFile.parentFile?.mkdirs()
            val tmp = File(stateFile.path + ".tmp")
            tmp.writeText(json.encodeToString(StoredDownloads.serializer(), stored))
            if (!tmp.renameTo(stateFile)) Log.e(TAG, "failed to save downloads state")
        } catch (e: Exception) {
            Log.e(TAG, "failed to save downloads state", e)
        }
    }

    private fun parseUris(json: String?): Set<String> = try {
        val array = JSONArray(json ?: "[]")
        (0 until array.length()).mapTo(HashSet()) { array.getString(it) }
    } catch (_: Exception) {
        emptySet()
    }
}
