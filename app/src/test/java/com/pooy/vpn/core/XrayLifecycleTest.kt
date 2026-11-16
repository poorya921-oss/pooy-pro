package com.pooy.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

/** Lifecycle rules, tested against a fake core. The real AAR is NOT exercised here (it is not in the repository). */
class XrayLifecycleTest {

    private class FakeController(
        private val failStart: Boolean = false,
        private val notRunningAfterStart: Boolean = false,
        private val failStop: Boolean = false,
    ) : XrayController {
        var running = false
        var startCalls = 0
        var stopCalls = 0
        var onStop: (() -> Unit)? = null
        var onStart: (() -> Unit)? = null
        var live: java.util.concurrent.atomic.AtomicInteger? = null
        var liveMax: java.util.concurrent.atomic.AtomicInteger? = null
        override val isRunning: Boolean get() = running
        override fun start(config: String) {
            startCalls++
            onStart?.invoke()
            if (failStart) throw IllegalStateException("boom")
            running = !notRunningAfterStart
            live?.let { n -> val v = n.incrementAndGet(); liveMax?.updateAndGet { m -> if (v > m) v else m } }
        }
        override fun stop() {
            stopCalls++
            if (running) live?.decrementAndGet()
            running = false
            onStop?.invoke()
            if (failStop) throw RuntimeException("stop failed")
        }
        override fun queryStats(tag: String, direction: String) = 42L
    }

    private class FakeBackend : XrayBackend {
        var initCalls = 0
        var failNew = false
        var next: () -> FakeController = { FakeController() }
        val controllers = ArrayList<FakeController>()
        val shutdownCallbacks = ArrayList<() -> Unit>()
        override fun initEnv(basePath: String, xudpKey: String) { initCalls++ }
        override fun newController(onCoreShutdown: () -> Unit): XrayController {
            if (failNew) throw IllegalStateException("no core")
            val c = next()
            controllers.add(c)
            shutdownCallbacks.add(onCoreShutdown)
            return c
        }
        override fun measureDelay(configJson: String, url: String) = 7L
    }

    private lateinit var backend: FakeBackend
    private lateinit var lc: XrayLifecycle

    @Before fun setUp() {
        backend = FakeBackend()
        lc = XrayLifecycle(backend)
        lc.init("/data", "key")
    }

    @Test fun initIsIdempotent() {
        lc.init("/data", "key")
        assertEquals(1, backend.initCalls)
    }

    @Test fun startRequiresInit() {
        val fresh = XrayLifecycle(FakeBackend())
        assertThrows(IllegalStateException::class.java) { fresh.start("{}") }
    }

    @Test fun startRunsAndStopStops() {
        lc.start("{}")
        assertTrue(lc.isRunning)
        assertEquals(XrayLifecycle.State.Running, lc.state)
        lc.stop()
        assertFalse(lc.isRunning)
        assertEquals(XrayLifecycle.State.Stopped, lc.state)
        assertEquals(1, backend.controllers[0].stopCalls)
    }

    @Test fun failedStartCleansUpAndAllowsRetry() {
        backend.next = { FakeController(failStart = true) }
        assertThrows(IllegalStateException::class.java) { lc.start("{}") }
        assertEquals(1, backend.controllers[0].stopCalls)      // half-started controller was stopped
        assertFalse(lc.isRunning)
        assertEquals(XrayLifecycle.State.Stopped, lc.state)

        backend.next = { FakeController() }
        lc.start("{}")                                         // no stale state blocks the retry
        assertTrue(lc.isRunning)
    }

    @Test fun coreThatIsNotRunningAfterStartCountsAsFailure() {
        backend.next = { FakeController(notRunningAfterStart = true) }
        assertThrows(IllegalStateException::class.java) { lc.start("{}") }
        assertEquals(1, backend.controllers[0].stopCalls)
        assertEquals(XrayLifecycle.State.Stopped, lc.state)
    }

    @Test fun controllerCreationFailureLeavesLifecycleStopped() {
        backend.failNew = true
        assertThrows(IllegalStateException::class.java) { lc.start("{}") }
        assertEquals(XrayLifecycle.State.Stopped, lc.state)
        backend.failNew = false
        lc.start("{}")
        assertTrue(lc.isRunning)
    }

    @Test fun failingStopDuringCleanupDoesNotMaskTheStartError() {
        backend.next = { FakeController(failStart = true, failStop = true) }
        val e = assertThrows(IllegalStateException::class.java) { lc.start("{}") }
        assertEquals("boom", e.message)
        assertEquals(XrayLifecycle.State.Stopped, lc.state)
    }

    @Test fun duplicateStartIsRejectedWhileRunning() {
        lc.start("{}")
        assertThrows(IllegalStateException::class.java) { lc.start("{}") }
        assertEquals(1, backend.controllers.size)
        assertTrue(lc.isRunning)
    }

    @Test fun concurrentStartsYieldExactlyOneCore() {
        val outcomes = Collections.synchronizedList(ArrayList<String>())
        val threads = List(4) {
            Thread {
                try {
                    lc.start("{}"); outcomes.add("ok")
                } catch (e: IllegalStateException) {
                    outcomes.add("rejected")
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(1, outcomes.count { it == "ok" })
        assertEquals(3, outcomes.count { it == "rejected" })
        assertEquals(1, backend.controllers.size)
    }

    @Test fun stopIsIdempotentAndSwallowsErrors() {
        lc.stop()
        lc.start("{}")
        lc.stop()
        lc.stop()
        assertEquals(XrayLifecycle.State.Stopped, lc.state)

        backend.next = { FakeController(failStop = true) }
        lc.start("{}")
        lc.stop() // controller.stop() throws; must not propagate
        assertEquals(XrayLifecycle.State.Stopped, lc.state)
        lc.start("{}") // and we can start again
        assertTrue(lc.isRunning)
    }

    @Test fun restartAfterStopWorks() {
        lc.start("{}"); lc.stop(); lc.start("{}")
        assertEquals(2, backend.controllers.size)
        assertTrue(lc.isRunning)
    }

    @Test fun unexpectedShutdownIsReportedOnce() {
        var calls = 0
        lc.onUnexpectedStop = { calls++ }
        lc.start("{}")
        backend.shutdownCallbacks[0]()
        backend.shutdownCallbacks[0]()
        assertEquals(1, calls)
    }

    @Test fun shutdownCausedByStopIsNotUnexpected() {
        var calls = 0
        lc.onUnexpectedStop = { calls++ }
        val c = FakeController()
        backend.next = { c }
        lc.start("{}")
        c.onStop = { backend.shutdownCallbacks[0]() } // core calls back from inside stopLoop()
        lc.stop()
        backend.shutdownCallbacks[0]()                // and late, after stop
        assertEquals(0, calls)
    }

    @Test fun shutdownDuringFailedStartIsNotReported() {
        var calls = 0
        lc.onUnexpectedStop = { calls++ }
        val c = FakeController(failStart = true)
        c.onStop = { backend.shutdownCallbacks[0]() }
        backend.next = { c }
        assertThrows(IllegalStateException::class.java) { lc.start("{}") }
        assertEquals(0, calls)
    }

    @Test fun staleCallbackFromAnOlderCoreIsIgnored() {
        var calls = 0
        lc.onUnexpectedStop = { calls++ }
        lc.start("{}"); lc.stop(); lc.start("{}")
        backend.shutdownCallbacks[0]()
        assertEquals(0, calls)
        backend.shutdownCallbacks[1]()
        assertEquals(1, calls)
    }

    @Test fun trafficAndDelayPassThrough() {
        assertEquals(0L, lc.queryTraffic("proxy", "uplink")) // not running
        lc.start("{}")
        assertEquals(42L, lc.queryTraffic("proxy", "uplink"))
        assertEquals(7L, lc.measureDelay("{}", "https://x"))
        lc.stop()
        assertNull(lc.onUnexpectedStop)
    }

    // ---- second pass: races, exactly-once cleanup, cancellation --------------------------------------------

    @Test fun shutdownCallbackDuringStartIsNotLostAndFailsTheStart() {
        var calls = 0
        lc.onUnexpectedStop = { calls++ }
        val c = FakeController()
        c.onStart = { backend.shutdownCallbacks[0]() } // core reports shutdown from inside startLoop()
        backend.next = { c }
        assertThrows(XrayStartAbortedException::class.java) { lc.start("{}") }
        assertEquals(1, c.stopCalls)                  // cleaned up exactly once
        assertEquals(XrayLifecycle.State.Stopped, lc.state)
        assertFalse(lc.isRunning)
        assertEquals(0, calls)                        // the caller already gets the failure through start()
        backend.next = { FakeController() }
        lc.start("{}")                                // and a retry works
        assertTrue(lc.isRunning)
    }

    @Test fun stopDuringStartCancelsThatStartAndCleansUpOnce() {
        val inNative = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val c = FakeController()
        c.onStart = { inNative.countDown(); release.await() }
        backend.next = { c }
        var startError: Throwable? = null
        val starter = Thread { try { lc.start("{}") } catch (t: Throwable) { startError = t } }
        starter.start()
        inNative.await()
        val stopper = Thread { lc.stop() }
        stopper.start()
        while (!lc.startCancelRequested) Thread.yield() // stop() has registered its request; no sleeping involved
        release.countDown()
        starter.join(); stopper.join()
        assertTrue(startError is XrayStartCancelledException)
        assertEquals(1, c.stopCalls)
        assertEquals(XrayLifecycle.State.Stopped, lc.state)
        assertFalse(lc.isRunning)
        backend.next = { FakeController() }
        lc.start("{}")                                // not wedged
        assertTrue(lc.isRunning)
    }

    @Test fun failedStartThenStopStopsTheControllerExactlyOnce() {
        val c = FakeController(failStart = true)
        backend.next = { c }
        assertThrows(IllegalStateException::class.java) { lc.start("{}") }
        lc.stop(); lc.stop()
        assertEquals(1, c.stopCalls)
    }

    @Test fun unexpectedTerminationThenStopStopsTheControllerExactlyOnce() {
        lc.onUnexpectedStop = { }
        val c = FakeController()
        backend.next = { c }
        lc.start("{}")
        backend.shutdownCallbacks[0]()
        assertTrue(lc.isTerminated)
        assertFalse(lc.isRunning)          // even if the native object still claims to run
        lc.stop(); lc.stop()
        assertEquals(1, c.stopCalls)
        assertFalse(lc.isTerminated)
    }

    @Test fun reconnectAfterUnexpectedTerminationReleasesTheDeadCoreFirst() {
        lc.onUnexpectedStop = { }
        val first = FakeController()
        backend.next = { first }
        lc.start("{}")
        backend.shutdownCallbacks[0]()
        val second = FakeController()
        backend.next = { second }
        lc.start("{}")                     // must not fail with "already running"
        assertEquals(1, first.stopCalls)
        assertTrue(lc.isRunning)
        // the old core's late callbacks cannot affect the new connection
        var calls = 0
        lc.onUnexpectedStop = { calls++ }
        backend.shutdownCallbacks[0]()
        assertEquals(0, calls)
        assertTrue(lc.isRunning)
    }

    @Test fun listenerMayCallStopFromTheCallbackWithoutDeadlock() {
        lc.start("{}")
        lc.onUnexpectedStop = { lc.stop() }
        backend.shutdownCallbacks[0]()
        assertEquals(XrayLifecycle.State.Stopped, lc.state)
        assertEquals(1, backend.controllers[0].stopCalls)
    }

    @Test fun neverMoreThanOneCoreUnderConcurrentStartAndStop() {
        val live = java.util.concurrent.atomic.AtomicInteger(0)
        val liveMax = java.util.concurrent.atomic.AtomicInteger(0)
        backend.next = { FakeController().also { it.live = live; it.liveMax = liveMax } }
        val errors = Collections.synchronizedList(ArrayList<Throwable>())
        val threads = List(6) { id ->
            Thread {
                repeat(300) { i ->
                    try {
                        if ((i + id) % 3 == 0) lc.stop() else lc.start("{}")
                    } catch (e: IllegalStateException) {
                        // "already running" is the expected rejection
                    } catch (e: XrayStartCancelledException) {
                        // also fine
                    } catch (t: Throwable) {
                        errors.add(t)
                    }
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertTrue("unexpected: $errors", errors.isEmpty())
        assertTrue("two cores were alive at once", liveMax.get() <= 1)
        assertTrue(live.get() <= 1)
        lc.stop()
        assertEquals(0, live.get())
        assertTrue(backend.controllers.all { it.stopCalls <= 1 }) // every controller stopped at most once
        assertTrue(backend.controllers.none { it.running })
    }

    // ---- listener safety ---------------------------------------------------------------------------------

    @Test fun throwingListenerDoesNotEscapeIntoTheNativeCallbackThread() {
        val errors = ArrayList<Throwable>()
        val lc2 = XrayLifecycle(backend) { errors.add(it) }
        lc2.init("/d", "k")
        lc2.onUnexpectedStop = { throw IllegalStateException("app bug") }
        lc2.start("{}")
        backend.shutdownCallbacks[0]()          // must not throw
        assertEquals(1, errors.size)
        assertTrue(errors[0] is IllegalStateException)
    }

    @Test fun throwingListenerStillCountsAsReportedExactlyOnce() {
        var calls = 0
        val lc2 = XrayLifecycle(backend)
        lc2.init("/d", "k")
        lc2.onUnexpectedStop = { calls++; throw RuntimeException("boom") }
        lc2.start("{}")
        backend.shutdownCallbacks[0]()
        backend.shutdownCallbacks[0]()
        assertEquals(1, calls)
        assertTrue(lc2.isTerminated)
        assertFalse(lc2.isRunning)
        lc2.stop()                               // cleanup still works afterwards
        assertEquals(1, backend.controllers[0].stopCalls)
    }

    @Test fun throwingErrorHandlerIsContainedToo() {
        val lc2 = XrayLifecycle(backend) { throw RuntimeException("logger broke") }
        lc2.init("/d", "k")
        lc2.onUnexpectedStop = { throw RuntimeException("x") }
        lc2.start("{}")
        backend.shutdownCallbacks[0]()          // still must not throw
    }

    @Test fun listenerRunsOutsideLifecycleLocksSoItMayCallBackIn() {
        lc.start("{}")
        lc.onUnexpectedStop = { lc.stop(); lc.isRunning; lc.state }
        backend.shutdownCallbacks[0]()
        assertEquals(XrayLifecycle.State.Stopped, lc.state)
    }

    @Test fun staleGenerationCallbackDoesNotReachTheListener() {
        var calls = 0
        lc.onUnexpectedStop = { calls++ }
        lc.start("{}"); lc.stop(); lc.start("{}")
        backend.shutdownCallbacks[0]()
        assertEquals(0, calls)
    }
}
