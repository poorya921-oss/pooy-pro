package com.pooy.vpn

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pooy.vpn.core.ConfigException
import com.pooy.vpn.core.BatchOutcome
import com.pooy.vpn.core.BusyTracker
import com.pooy.vpn.core.ConfigParser
import com.pooy.vpn.core.LatencyMeasure
import com.pooy.vpn.core.LatestRunTracker
import com.pooy.vpn.core.Operations
import com.pooy.vpn.core.Importer
import com.pooy.vpn.core.Subscriptions
import com.pooy.vpn.core.XrayConfigBuilder
import com.pooy.vpn.core.XrayCore
import com.pooy.vpn.data.CommitResult
import com.pooy.vpn.data.LoadStatus
import com.pooy.vpn.data.Profile
import com.pooy.vpn.data.RefreshOutcome
import com.pooy.vpn.data.ProfileStore
import com.pooy.vpn.data.StoreUpdater
import com.pooy.vpn.data.SubscriptionRefresher
import com.pooy.vpn.data.StoreData
import com.pooy.vpn.data.Subscription
import com.pooy.vpn.service.PooyVpnService
import com.pooy.vpn.service.VpnController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.net.URI

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ProfileStore(app)
    private val mutex = Mutex()
    private val testGate = Semaphore(3)

    var data by mutableStateOf(StoreData())
        private set
    var loaded by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    private val busyTracker = BusyTracker { busy = it }

    /** profile id -> latency in ms (-1 = failed). Absent = not tested. */
    val latency = mutableStateMapOf<String, Long>()
    val testing = mutableStateMapOf<String, Boolean>()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val r = mutex.withLock { store.loadResult().also { data = it.data } }
            loaded = true
            val notice = when (r.status) {
                LoadStatus.Recovered -> str(R.string.msg_store_recovered)
                LoadStatus.Unreadable -> str(R.string.msg_store_unreadable)
                else -> null
            }
            if (notice != null) {
                // Nobody collects before the UI exists; wait for it so the notice is not dropped.
                _messages.subscriptionCount.first { it > 0 }
                _messages.emit(notice)
            }
        }
    }

    fun say(text: String) {
        _messages.tryEmit(text)
    }

    private fun str(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    /**
     * Applies [block] and persists the result. Returns true only if the new state is actually on disk (or nothing
     * changed). On failure the in-memory state is left untouched, the user is told why, and false is returned, so
     * callers must show success messages only when this returns true.
     *
     * Persisting and publishing to [data] are one non-cancellable step ([StoreUpdater]); a cancellation that arrives
     * meanwhile is thrown only afterwards, so callers never get to show a message for cancelled work and memory
     * never lags behind disk.
     */
    private suspend fun update(block: (StoreData) -> StoreData): Boolean = updateWithHook(block) { }

    /** [onResult] runs inside the same non-cancellable critical section as the write (see [StoreUpdater]). */
    private suspend fun updateWithHook(block: (StoreData) -> StoreData, onResult: (CommitResult) -> Unit): Boolean {
        val r = StoreUpdater.update(mutex, store, Dispatchers.IO, { data }, { data = it }, block, onResult)
        return when (r) {
            is CommitResult.Committed, is CommitResult.Unchanged -> true
            is CommitResult.Failed -> {
                Operations.announce { say(str(R.string.err_save, r.error.message ?: r.error.javaClass.simpleName)) }
                false
            }
        }
    }

    // ------------------------------------------------------------------ import

    /** Clipboard / QR text: a lone http(s) URL is treated as a subscription, anything else as links or JSON. */
    fun importAuto(text: String?) {
        if (text.isNullOrBlank()) {
            say(str(R.string.msg_clipboard_empty))
            return
        }
        if (ConfigParser.isSubscriptionUrl(text)) importUrl(text.trim(), "", true) else importText(text)
    }

    private fun importText(text: String) {
        viewModelScope.launch(Dispatchers.IO) { addImported(Importer.fromText(text)) }
    }

    private suspend fun addImported(r: Importer.Result) {
        if (r.profiles.isEmpty()) {
            Operations.announce { say(str(R.string.msg_nothing_imported, r.skipped)) }
            return
        }
        val saved = update { d -> d.copy(profiles = d.profiles + r.profiles, activeId = d.activeId ?: r.profiles.first().id) }
        if (!saved) return // the error has already been shown; never report an import that was not persisted
        Operations.announce {
            say(
                if (r.skipped > 0) str(R.string.msg_imported_skipped, r.profiles.size, r.skipped)
                else str(R.string.msg_imported, r.profiles.size),
            )
        }
    }

    fun importUrl(url: String, name: String, asSubscription: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            busyTracker.track {
                Operations.attempt({ e -> say(str(R.string.err_fetch, e.message ?: e.javaClass.simpleName)) }) {
                    val body = Subscriptions.fetch(url)
                    if (asSubscription) {
                        val sub = Subscription(Importer.newId(), name.ifBlank { hostOf(url) }, url.trim())
                        val r = Importer.fromText(body, sub.id)
                        if (r.profiles.isEmpty()) {
                            Operations.announce { say(str(R.string.msg_nothing_imported, r.skipped)) }
                        } else {
                            val saved = update { d ->
                                d.copy(
                                    subscriptions = d.subscriptions + sub,
                                    profiles = d.profiles + r.profiles,
                                    activeId = d.activeId ?: r.profiles.first().id,
                                )
                            }
                            if (saved) Operations.announce { say(str(R.string.msg_imported, r.profiles.size)) }
                        }
                    } else {
                        addImported(Importer.fromText(body))
                    }
                }
            }
        }
    }

    fun importFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            Operations.attempt({ e -> say(str(R.string.msg_file_error, e.message ?: e.javaClass.simpleName)) }) {
                val text = getApplication<Application>().contentResolver.openInputStream(uri)
                    ?.use { Subscriptions.readLimited(it, 2_000_000) }
                    ?: throw IOException("cannot open file")
                addImported(Importer.fromText(text))
            }
        }
    }

    fun addManual(name: String, raw: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val trimmed = raw.trim()
            val parsed = try {
                ConfigParser.parse(trimmed)
            } catch (e: ConfigException) {
                say(str(R.string.msg_invalid_config, e.message ?: ""))
                return@launch
            }
            val p = Profile(Importer.newId(), name.ifBlank { parsed.name }, trimmed)
            update { d -> d.copy(profiles = d.profiles + p, activeId = d.activeId ?: p.id) }
        }
    }

    private fun hostOf(url: String): String =
        try {
            URI(url.trim()).host ?: url
        } catch (e: Exception) {
            url
        }

    // ------------------------------------------------------------------ subscriptions

    /**
     * Downloads a subscription and applies it only if, at the moment of applying, the subscription still exists with
     * the same URL and no newer refresh has already applied (see [SubscriptionRefresher]).
     */
    private val refresher = SubscriptionRefresher(
        currentData = { data },
        download = { sub ->
            val body = Subscriptions.fetch(sub.url)
            val r = Importer.fromText(body, sub.id)
            if (r.profiles.isEmpty()) throw IOException(str(R.string.err_sub_empty))
            r.profiles
        },
        update = { block, onResult -> updateWithHook(block, onResult) },
    )

    fun updateSubscription(id: String) {
        val sub = data.subscriptions.firstOrNull { it.id == id } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            busyTracker.track {
                Operations.attempt({ e -> say(str(R.string.err_fetch, e.message ?: e.javaClass.simpleName)) }) {
                    // Stale = deleted/reconfigured/superseded meanwhile: discarded silently, never reported as success.
                    if (refresher.refresh(sub) == RefreshOutcome.Applied) {
                        Operations.announce { say(str(R.string.msg_sub_updated)) }
                    }
                }
            }
        }
    }

    fun updateAllSubscriptions() {
        val subs = data.subscriptions
        if (subs.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            // Cancellation propagates out of the loop; only ordinary failures count as "failed", discarded stale
            // refreshes count as neither.
            val counts = busyTracker.track {
                Operations.countOutcomes(subs) { s ->
                    when (refresher.refresh(s)) {
                        RefreshOutcome.Applied -> BatchOutcome.Ok
                        RefreshOutcome.SaveFailed -> BatchOutcome.Failed
                        RefreshOutcome.Stale -> BatchOutcome.Skipped
                    }
                }
            }
            if (counts.ok + counts.failed > 0) {
                Operations.announce { say(str(R.string.msg_subs_updated, counts.ok, counts.failed)) }
            }
        }
    }

    fun deleteSubscription(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            update { d ->
                val remaining = d.profiles.filter { it.subId != id }
                d.copy(
                    subscriptions = d.subscriptions.filter { it.id != id },
                    profiles = remaining,
                    activeId = if (remaining.any { it.id == d.activeId }) d.activeId else remaining.firstOrNull()?.id,
                )
            }
        }
    }

    // ------------------------------------------------------------------ profile management

    fun select(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val saved = update { d -> d.copy(activeId = id) }
            // Connected or connecting: switch the live tunnel to the newly selected server (only if the choice was saved).
            if (saved && VpnController.status.value.state != VpnController.State.Disconnected) {
                val app = getApplication<Application>()
                app.startService(
                    Intent(app, PooyVpnService::class.java)
                        .setAction(PooyVpnService.ACTION_START)
                        .putExtra(PooyVpnService.EXTRA_PROFILE_ID, id),
                )
            }
        }
    }

    fun rename(id: String, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            update { d -> d.copy(profiles = d.profiles.map { if (it.id == id) it.copy(name = name.trim()) else it }) }
        }
    }

    fun edit(id: String, name: String, raw: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val trimmed = raw.trim()
            val parsed = try {
                ConfigParser.parse(trimmed)
            } catch (e: ConfigException) {
                say(str(R.string.msg_invalid_config, e.message ?: ""))
                return@launch
            }
            val saved = update { d ->
                d.copy(profiles = d.profiles.map {
                    if (it.id == id) it.copy(name = name.ifBlank { parsed.name }, raw = trimmed) else it
                })
            }
            if (saved) latency.remove(id)
        }
    }

    fun duplicate(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            update { d ->
                val i = d.profiles.indexOfFirst { it.id == id }
                if (i < 0) return@update d
                val src = d.profiles[i]
                // The copy is detached from any subscription so a subscription refresh does not delete it.
                val copy = Profile(Importer.newId(), src.name + " (copy)", src.raw, null)
                d.copy(profiles = d.profiles.toMutableList().apply { add(i + 1, copy) })
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val saved = update { d ->
                val remaining = d.profiles.filter { it.id != id }
                d.copy(
                    profiles = remaining,
                    activeId = if (d.activeId == id) remaining.firstOrNull()?.id else d.activeId,
                )
            }
            if (saved) latency.remove(id)
        }
    }

    // ------------------------------------------------------------------ latency

    fun test(id: String) {
        viewModelScope.launch(Dispatchers.IO) { runTest(id) }
    }

    fun testAll() {
        viewModelScope.launch(Dispatchers.IO) {
            val ids = data.profiles.map { it.id }
            coroutineScope { ids.map { async { runTest(it) } }.awaitAll() }
        }
    }

    /**
     * Per-profile "newest test wins" bookkeeping. Token check + removal and the matching loading-indicator update
     * are atomic, so an older (e.g. cancelled) test can neither remove a newer test's token nor clear its indicator.
     */
    private val runs = LatestRunTracker<String> { id, active -> if (active) testing[id] = true else testing.remove(id) }

    private suspend fun runTest(id: String) {
        val p = data.profiles.firstOrNull { it.id == id } ?: return
        val token = runs.begin(id)
        try {
            testGate.withPermit {
                // Rethrows cancellation, maps operational failures to -1, never returns a result after cancellation.
                val ms = LatencyMeasure.measure {
                    val server = ConfigParser.parse(p.raw)
                    XrayCore.init(getApplication<Application>())
                    XrayCore.measureDelay(XrayConfigBuilder.speedTest(server), XrayConfigBuilder.TEST_URL)
                }
                // The native call cannot be interrupted, so the profile may have been edited/deleted, or a newer
                // test started, in the meantime: publish only for the same profile content and the newest test.
                runs.publishIfCurrent(id, token) {
                    if (data.profiles.firstOrNull { it.id == id }?.raw == p.raw) latency[id] = ms
                }
            }
        } finally {
            runs.finish(id, token)
        }
    }
}
