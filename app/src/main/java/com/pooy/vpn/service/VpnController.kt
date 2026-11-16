package com.pooy.vpn.service

import android.os.SystemClock
import com.pooy.vpn.core.XrayCore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Process-wide VPN state shared between the service (writer) and the UI (reader). */
object VpnController {
    /** Blocked = the tunnel interface is held open but no core is running: no traffic leaves the device (fail-closed). */
    enum class State { Disconnected, Connecting, Connected, Blocked }

    data class Status(
        val state: State = State.Disconnected,
        val serverName: String? = null,
        val connectedAtMs: Long = 0L,   // SystemClock.elapsedRealtime() when connected
        val error: String? = null,
    )

    data class Traffic(
        val up: Long = 0,
        val down: Long = 0,
        val upSpeed: Long = 0,     // bytes/s over the last poll interval
        val downSpeed: Long = 0,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private val _traffic = MutableStateFlow(Traffic())
    val traffic: StateFlow<Traffic> = _traffic

    @Volatile private var lastPoll = 0L

    /** Time source; replaced in unit tests (SystemClock is not available on the JVM). */
    internal var clock: () -> Long = { SystemClock.elapsedRealtime() }

    @Synchronized
    fun connecting(serverName: String?) {
        _traffic.value = Traffic()
        _status.value = Status(State.Connecting, serverName)
    }

    /** Only valid after [connecting]; a late "connected" from a start that was cancelled meanwhile is ignored. */
    @Synchronized
    fun connected(serverName: String?) {
        if (_status.value.state != State.Connecting) return
        lastPoll = clock()
        _status.value = Status(State.Connected, serverName, lastPoll)
    }

    @Synchronized
    fun disconnected() {
        _status.update { Status(State.Disconnected, null, 0L, it.error) }
    }

    /** Fail-closed hold. The UI offers Disconnect; the service keeps trying to reconnect for a while. */
    @Synchronized
    fun blocked(message: String) {
        _status.update { Status(State.Blocked, it.serverName, 0L, message) }
    }

    /** Non-fatal problem; state is left as it is. */
    @Synchronized
    fun reportError(message: String) {
        _status.update { it.copy(error = message) }
    }

    @Synchronized
    fun failed(message: String) {
        _status.value = Status(State.Disconnected, null, 0L, message)
    }

    fun clearError() {
        _status.update { it.copy(error = null) }
    }

    /**
     * Pulls new byte counts from the core. Only called by the UI while it is visible,
     * so there is no background polling; the core keeps counting in between.
     */
    fun pollTraffic() {
        if (_status.value.state != State.Connected || !XrayCore.isRunning) return
        val now = clock()
        val up = XrayCore.queryTraffic("proxy", "uplink")
        val down = XrayCore.queryTraffic("proxy", "downlink")
        val dt = (now - lastPoll).coerceAtLeast(1L)
        lastPoll = now
        _traffic.update { Traffic(it.up + up, it.down + down, up * 1000 / dt, down * 1000 / dt) }
    }
}
