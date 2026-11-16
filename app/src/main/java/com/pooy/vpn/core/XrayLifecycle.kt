package com.pooy.vpn.core

import java.util.concurrent.atomic.AtomicBoolean

/** One running (or startable) Xray instance. Implemented by the AAR adapter in [XrayCore] and by fakes in tests. */
interface XrayController {
    val isRunning: Boolean
    fun start(config: String)
    fun stop()
    fun queryStats(tag: String, direction: String): Long
}

/** Factory side of the AAR. Contains no state; [XrayLifecycle] owns all lifecycle decisions. */
interface XrayBackend {
    fun initEnv(basePath: String, xudpKey: String)

    /** [onCoreShutdown] is invoked by the core's shutdown callback, on any thread, possibly while start() is still running. */
    fun newController(onCoreShutdown: () -> Unit): XrayController
    fun measureDelay(configJson: String, url: String): Long
}

/** start() was cancelled by a stop() that arrived while the core was still starting. */
class XrayStartCancelledException : RuntimeException("Xray start was cancelled")

/** The core reported shutdown while it was still starting. */
class XrayStartAbortedException : RuntimeException("The Xray core stopped while starting")

/**
 * Start/stop state machine around the core, free of Android and AAR types so it can be unit tested.
 *
 * Locking model (no lock is ever held while calling out to the listener, and callbacks never take [lock]):
 *  - [lock]      serialises start()/stop(); held across the (possibly slow) native start/stop calls.
 *  - [cbLock]    tiny monitor guarding the generation bookkeeping shared with the core's shutdown callback. It is held
 *                only for a few field reads/writes, so a callback arriving while stop() sits in native code cannot deadlock.
 *
 * Guarantees:
 *  - At most one core exists. start() is rejected while one is running and concurrent calls are serialised.
 *  - A shutdown callback that arrives *during* start() is not lost: the start fails and is cleaned up.
 *  - stop() during start() cancels that start (it is stopped as soon as the native start call returns).
 *  - Each controller is stopped exactly once ([Managed.stopOnce]); failed start, stop() and a restart cannot double-stop.
 *  - A shutdown callback not caused by us is reported once via [onUnexpectedStop] (exceptions from the listener are
 *    contained, never propagated into the native thread); stale callbacks from older controllers are ignored, and the dead core is released by the next start() or stop().
 */
class XrayLifecycle(
    private val backend: XrayBackend,
    /** Called (never throws) if the application's [onUnexpectedStop] listener throws; for logging. */
    private val onListenerError: (Throwable) -> Unit = {},
) {
    enum class State { Stopped, Starting, Running, Stopping }

    private class Managed(val gen: Long, val controller: XrayController) {
        private val stopped = AtomicBoolean(false)
        fun stopOnce() {
            if (!stopped.compareAndSet(false, true)) return
            try {
                controller.stop()
            } catch (ignored: Throwable) {
                // already stopped / core gone
            }
        }
    }

    private val lock = Any()
    private val cbLock = Any()

    // guarded by lock
    private var envReady = false
    private var nextGen = 0L
    private var current: Managed? = null

    // guarded by cbLock
    private var startingGen = 0L
    private var activeGen = -1L
    private var shutdownSeenDuringStart = -1L
    private var cancelledGen = -1L
    private var terminatedGen = -1L

    @Volatile private var controllerForReads: XrayController? = null
    @Volatile var state: State = State.Stopped
        private set
    @Volatile var onUnexpectedStop: (() -> Unit)? = null

    val isRunning: Boolean
        get() {
            if (terminated()) return false
            return try {
                controllerForReads?.isRunning == true
            } catch (ignored: Exception) {
                false
            }
        }

    /** True while the core has reported shutdown but its resources have not been released yet. */
    val isTerminated: Boolean get() = terminated()

    /** True between a stop() that arrived during start() and the end of that start(). */
    internal val startCancelRequested: Boolean get() = synchronized(cbLock) { startingGen != 0L && cancelledGen == startingGen }

    private fun terminated() = synchronized(cbLock) { terminatedGen != -1L && terminatedGen == currentGenSnapshot }

    @Volatile private var currentGenSnapshot = -2L

    fun init(basePath: String, xudpKey: String) {
        synchronized(lock) {
            if (envReady) return
            backend.initEnv(basePath, xudpKey)
            envReady = true
        }
    }

    val isInitialised: Boolean get() = synchronized(lock) { envReady }

    fun start(config: String) {
        synchronized(lock) {
            check(envReady) { "Xray environment is not initialised" }
            // A core that died on its own is still registered; release it so a reconnect can proceed.
            if (current != null && terminated()) releaseLocked()
            check(state == State.Stopped) { "Xray is already running or busy" }

            state = State.Starting
            val gen = ++nextGen
            synchronized(cbLock) {
                startingGen = gen
                shutdownSeenDuringStart = -1L
                cancelledGen = -1L
            }
            val managed = try {
                Managed(gen, backend.newController { onShutdownCallback(gen) })
            } catch (t: Throwable) {
                finishStartAttempt(gen)
                state = State.Stopped
                throw t
            }
            var failure: Throwable? = null
            try {
                managed.controller.start(config)
                if (!managed.controller.isRunning) failure = IllegalStateException("The Xray core did not start")
            } catch (t: Throwable) {
                failure = t
            }
            // Atomically decide the outcome together with any callback/stop that raced with the native start call.
            val outcome = synchronized(cbLock) {
                when {
                    failure != null -> failure
                    shutdownSeenDuringStart == gen -> XrayStartAbortedException()
                    cancelledGen == gen -> XrayStartCancelledException()
                    else -> {
                        activeGen = gen
                        null
                    }
                }.also {
                    startingGen = 0L
                    shutdownSeenDuringStart = -1L
                    cancelledGen = -1L
                }
            }
            if (outcome != null) {
                managed.stopOnce() // exactly one cleanup, and nothing is registered
                state = State.Stopped
                throw outcome
            }
            current = managed
            currentGenSnapshot = gen
            controllerForReads = managed.controller
            state = State.Running
        }
    }

    /** Idempotent; safe to call from any thread. If a start() is in progress it is cancelled and this call waits for it. */
    fun stop() {
        synchronized(cbLock) {
            if (startingGen != 0L) cancelledGen = startingGen
            activeGen = -1L // callbacks caused by this stop are not "unexpected"
        }
        synchronized(lock) { releaseLocked() }
    }

    private fun releaseLocked() {
        val m = current
        current = null
        controllerForReads = null
        synchronized(cbLock) {
            activeGen = -1L
            terminatedGen = -1L
        }
        currentGenSnapshot = -2L
        if (m == null) {
            state = State.Stopped
            return
        }
        state = State.Stopping
        m.stopOnce()
        state = State.Stopped
    }

    fun queryTraffic(tag: String, direction: String): Long =
        try {
            controllerForReads?.queryStats(tag, direction) ?: 0L
        } catch (ignored: Exception) {
            0L
        }

    fun measureDelay(configJson: String, url: String): Long = backend.measureDelay(configJson, url)

    private fun finishStartAttempt(gen: Long) {
        synchronized(cbLock) {
            if (startingGen == gen) startingGen = 0L
            shutdownSeenDuringStart = -1L
            cancelledGen = -1L
        }
    }

    /** May run on any thread at any time, including while start() or stop() hold [lock]. Never takes [lock]. */
    private fun onShutdownCallback(gen: Long) {
        val notify = synchronized(cbLock) {
            when {
                gen == startingGen -> {
                    shutdownSeenDuringStart = gen // start() will observe this and fail
                    false
                }
                gen == activeGen -> {
                    activeGen = -1L // report once
                    terminatedGen = gen
                    true
                }
                else -> false // stale (older controller) or caused by stop()
            }
        }
        if (notify) notifyListener()
    }

    /**
     * Runs outside every lock. Whatever the application's listener does, nothing may escape into the native
     * callback thread (that would crash the process), and the exactly-once bookkeeping above has already happened.
     */
    private fun notifyListener() {
        val listener = onUnexpectedStop ?: return
        try {
            listener()
        } catch (t: Throwable) {
            try {
                onListenerError(t)
            } catch (ignored: Throwable) {
                // logging must not throw either
            }
        }
    }
}
