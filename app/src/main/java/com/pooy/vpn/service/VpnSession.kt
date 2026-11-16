package com.pooy.vpn.service

import java.util.concurrent.atomic.AtomicInteger

/** Handle to the established VPN interface (wraps the ParcelFileDescriptor in the service). */
interface TunHandle {
    val fd: Int
    fun close()
}

class PreparedConnection(
    val profileId: String,
    val profileName: String,
    val coreConfig: String,
    val socksPort: Int,
    val user: String,
    val pass: String,
)

class VpnPermissionException : RuntimeException("VPN permission missing or revoked")
class CoreStoppedException : RuntimeException("The core stopped right after starting")

/** Everything the session needs from Android, Xray and hev. Implemented by the service; faked in unit tests. */
interface SessionPorts {
    /** Loads and validates the profile (default: [lastProfileId]/active). Must not touch the running connection. */
    fun prepare(profileId: String?): PreparedConnection
    fun startCore(p: PreparedConnection)        // must clean up its own partial state on failure
    fun stopCore()
    fun isCoreRunning(): Boolean
    fun establishTun(): TunHandle?              // null: permission missing
    /** May throw after partially initialising native state; the session then still calls [stopTunnel] exactly once. */
    fun startTunnel(p: PreparedConnection, tun: TunHandle)
    /** Must be safe even if [startTunnel] threw or never completed, and must delete the generated config file. */
    fun stopTunnel()

    /**
     * Installs (or with null removes) the "core died" notification, from the core callback and a liveness check.
     * The implementation must deliver [onDead] on the same thread that calls the session's methods.
     */
    fun watchCore(onDead: (() -> Unit)?)
}

/** What the session reports. All methods are called on the session thread. */
interface SessionEvents {
    fun connecting(name: String?)
    fun connected(name: String)

    /** Fail-closed hold: the TUN is kept, no traffic flows, the service stays alive. */
    fun blocked(message: String)

    /** The connection attempt failed and nothing was ever protected; the TUN is released. */
    fun failed(message: String)

    /** Non-fatal problem; connection state is unchanged. */
    fun error(message: String)
    fun disconnected()

    /** The session is over and the service may stop. */
    fun finished()
    fun describe(t: Throwable): String
}

fun interface SessionScheduler {
    /** Runs [task] on the session thread after [delayMs]. */
    fun schedule(delayMs: Long, task: () -> Unit)
}

/**
 * The connection state machine: orders TUN, hev and Xray operations and decides what happens on failure.
 *
 * Threading: [newRequest]/[isStale] may be called from any thread; every other method must run on one single
 * "session thread" (the service's worker), so no locks are needed and operations are naturally serialised.
 * A request counter makes older start/reconnect work stale as soon as a newer start or any stop is requested.
 *
 * Traffic safety rules (fail-closed while the app process is alive):
 *  1. Switching or reconnecting keeps the old TUN open (nobody reads it, so packets are dropped) until the new one
 *     is established; only then is the old descriptor closed, so there is never a window with direct traffic and
 *     never two interfaces.
 *  2. Once a connection was established ("protecting"), core death or a failed switch does NOT close the TUN. The
 *     session enters Blocked, retries a bounded number of times, and otherwise waits for the user.
 *  3. Only an explicit stop, revoke, destroy, or a first connection that never worked releases the TUN.
 * Not covered here (needs Android's always-on + lockdown, or is impossible): process death, OS revocation.
 */
class VpnSession(
    private val ports: SessionPorts,
    private val events: SessionEvents,
    private val scheduler: SessionScheduler,
    private val reconnectDelaysMs: LongArray = longArrayOf(2_000L, 5_000L, 15_000L),
) {
    private class Stale : RuntimeException()

    private val generation = AtomicInteger(0)

    private var tun: TunHandle? = null
    /** hev startup was attempted and has not been stopped yet (true even if the native start threw halfway). */
    private var tunnelStartAttempted = false
    private var watching = false
    private var protecting = false
    private var currentName: String? = null
    private var lastProfileId: String? = null
    private var attempts = 0

    /** Call for every start AND stop request, before queueing the work. Returns the request's generation. */
    fun newRequest(): Int = generation.incrementAndGet()
    fun isStale(gen: Int): Boolean = gen != generation.get()

    val holdsTun: Boolean get() = tun != null
    val isProtecting: Boolean get() = protecting

    private fun checkpoint(gen: Int) {
        if (isStale(gen)) throw Stale()
    }

    fun start(profileId: String?, gen: Int) {
        try {
            checkpoint(gen)
            // Validate first: a bad profile must not disturb a working connection.
            val p = try {
                ports.prepare(profileId ?: lastProfileId)
            } catch (t: Throwable) {
                if (isStale(gen)) return
                rejectStart(t)
                return
            }
            checkpoint(gen)
            lastProfileId = p.profileId
            events.connecting(p.profileName)
            haltRunning() // hev + core down; the TUN deliberately stays

            ports.startCore(p)
            checkpoint(gen)
            val fresh = ports.establishTun() ?: throw VpnPermissionException()
            val old = tun
            tun = fresh
            if (old != null) closeQuietly(old) // replaced by the new interface; never two at once
            checkpoint(gen)

            // Set BEFORE the native call: a start that throws halfway may still have left threads/sockets behind, and
            // a failed native start is never assumed to clean up after itself.
            tunnelStartAttempted = true
            ports.startTunnel(p, fresh)
            if (!ports.isCoreRunning()) throw CoreStoppedException()
            checkpoint(gen)

            watching = true
            ports.watchCore { coreDied(gen) }
            protecting = true
            attempts = 0
            currentName = p.profileName
            events.connected(p.profileName)
        } catch (t: Throwable) {
            if (t is Stale || isStale(gen)) return // whoever superseded this request owns cleanup and state
            failure(events.describe(t))
        }
    }

    /** User stop, system revoke. Always ends with no TUN and no core. */
    fun stop() {
        haltRunning()
        releaseTun()
        protecting = false
        attempts = 0
        events.disconnected()
        events.finished()
    }

    /** Service is being destroyed: release everything, report disconnected, do not ask the service to stop again. */
    fun destroy() {
        haltRunning()
        releaseTun()
        protecting = false
        events.disconnected()
    }

    /** The core stopped without being asked to. Idempotent (the callback and the watchdog may both report it). */
    fun coreDied(gen: Int) {
        if (isStale(gen) || !watching) return
        haltRunning()
        failure(events.describe(CoreStoppedException()))
    }

    private fun rejectStart(t: Throwable) {
        val msg = events.describe(t)
        when {
            protecting && tun != null && ports.isCoreRunning() -> {
                events.error(msg)               // keep the working connection exactly as it was
                events.connected(currentName ?: "")
            }
            else -> failure(msg)
        }
    }

    private fun failure(message: String) {
        haltRunning()
        if (protecting && tun != null) {
            events.blocked(message)
            scheduleReconnect()
        } else {
            releaseTun()
            protecting = false
            events.failed(message)
            events.finished()
        }
    }

    private fun scheduleReconnect() {
        if (attempts >= reconnectDelaysMs.size) return // gave up: stay Blocked until the user acts
        val delay = reconnectDelaysMs[attempts++]
        val gen = generation.get()
        scheduler.schedule(delay) {
            // Any start/stop requested meanwhile makes this stale; so does having left the blocked state.
            if (!isStale(gen) && protecting && tun != null) start(null, newRequest())
        }
    }

    private fun haltRunning() {
        if (watching) {
            watching = false
            try {
                ports.watchCore(null)
            } catch (ignored: Throwable) {
                // ignore
            }
        }
        if (tunnelStartAttempted) {
            tunnelStartAttempted = false // cleared first: at most one native stop per start attempt
            try {
                ports.stopTunnel()
            } catch (ignored: Throwable) {
                // native library gone
            }
        }
        try {
            ports.stopCore()
        } catch (ignored: Throwable) {
            // core missing or already stopped
        }
    }

    private fun releaseTun() {
        tun?.let { closeQuietly(it) }
        tun = null
    }

    private fun closeQuietly(t: TunHandle) {
        try {
            t.close()
        } catch (ignored: Throwable) {
            // ignore
        }
    }
}
