package com.pooy.vpn.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Fail-closed behaviour, reconnect, profile switching and duplicate requests, against fake ports.
 * This tests the decision logic only; real TUN/hev/Xray behaviour needs a device.
 */
class VpnSessionTest {

    private class FakeTun(val id: Int, private val onClose: (FakeTun) -> Unit) : TunHandle {
        override val fd: Int = id
        var closed = false
        override fun close() { closed = true; onClose(this) }
    }

    private class FakePorts : SessionPorts {
        val log = ArrayList<String>()
        val openTuns = ArrayList<FakeTun>()
        var maxOpenTuns = 0
        var nextTunId = 1
        var coreRunning = false
        var tunnelRunning = false
        var deathListener: (() -> Unit)? = null
        var failPrepare: Throwable? = null
        var failStartCore: Throwable? = null
        var failEstablish = false
        var failStartTunnel: Throwable? = null
        var tunnelPartial = false       // native state left behind by a start that threw halfway
        var yamlExists = false          // the generated tun.yaml (holds credentials)
        var stopTunnelCalls = 0
        var stopTunnelThrows = false
        var coreStartCalls = 0
        var coreAliveAfterStart = true
        var onStartCore: (() -> Unit)? = null
        var profiles = mapOf("a" to "A", "b" to "B")
        var active = "a"

        override fun prepare(profileId: String?): PreparedConnection {
            failPrepare?.let { throw it }
            val id = profileId ?: active
            val name = profiles[id] ?: throw IllegalStateException("no such profile")
            return PreparedConnection(id, name, "{}", 1080, "u", "p")
        }
        override fun startCore(p: PreparedConnection) {
            log.add("core+${p.profileId}")
            coreStartCalls++
            onStartCore?.invoke()
            failStartCore?.let { throw it }
            coreRunning = coreAliveAfterStart
        }
        override fun stopCore() { if (coreRunning) log.add("core-"); coreRunning = false }
        override fun isCoreRunning() = coreRunning
        override fun establishTun(): TunHandle? {
            if (failEstablish) return null
            val t = FakeTun(nextTunId++) { openTuns.remove(it) }
            openTuns.add(t)
            maxOpenTuns = maxOf(maxOpenTuns, openTuns.size)
            log.add("tun+${t.id}")
            return t
        }
        override fun startTunnel(p: PreparedConnection, tun: TunHandle) {
            yamlExists = true; tunnelPartial = true
            failStartTunnel?.let { yamlExists = false; throw it } // contract: the port deletes the file itself on failure
            log.add("hev+"); tunnelRunning = true
        }
        override fun stopTunnel() {
            stopTunnelCalls++
            log.add("hev-"); tunnelRunning = false; tunnelPartial = false; yamlExists = false
            if (stopTunnelThrows) throw IllegalStateException("native stop failed")
        }
        override fun watchCore(onDead: (() -> Unit)?) { deathListener = onDead }
        fun killCore() { coreRunning = false; deathListener?.invoke() }
    }

    private class FakeEvents : SessionEvents {
        val log = ArrayList<String>()
        override fun connecting(name: String?) { log.add("connecting:$name") }
        override fun connected(name: String) { log.add("connected:$name") }
        override fun blocked(message: String) { log.add("blocked") }
        override fun failed(message: String) { log.add("failed") }
        override fun error(message: String) { log.add("error") }
        override fun disconnected() { log.add("disconnected") }
        override fun finished() { log.add("finished") }
        override fun describe(t: Throwable) = t.message ?: t.javaClass.simpleName
        val last get() = log.lastOrNull()
    }

    private class FakeScheduler : SessionScheduler {
        val pending = ArrayList<Pair<Long, () -> Unit>>()
        override fun schedule(delayMs: Long, task: () -> Unit) { pending.add(delayMs to task) }
        fun runNext() { val (_, t) = pending.removeAt(0); t() }
    }

    private lateinit var ports: FakePorts
    private lateinit var events: FakeEvents
    private lateinit var sched: FakeScheduler
    private lateinit var session: VpnSession

    @Before fun setUp() {
        ports = FakePorts(); events = FakeEvents(); sched = FakeScheduler()
        session = VpnSession(ports, events, sched, longArrayOf(10, 20, 30))
    }

    private fun connect(id: String? = null) { session.start(id, session.newRequest()) }

    // ---- normal flow -------------------------------------------------------------------------------

    @Test fun connectOrdersCoreThenTunThenTunnel() {
        connect()
        assertEquals(listOf("core+a", "tun+1", "hev+"), ports.log)
        assertEquals("connected:A", events.last)
        assertTrue(session.isProtecting)
    }

    @Test fun userStopReleasesEverythingAndFinishes() {
        connect(); session.newRequest(); session.stop()
        assertTrue(ports.openTuns.isEmpty()); assertFalse(ports.coreRunning); assertFalse(ports.tunnelRunning)
        assertEquals(listOf("connecting:A", "connected:A", "disconnected", "finished"), events.log)
        assertFalse(session.holdsTun)
    }

    // ---- fail-closed ---------------------------------------------------------------------------------

    @Test fun coreDeathAfterConnectKeepsTheTunAndBlocks() {
        connect()
        ports.killCore()
        assertEquals(1, ports.openTuns.size)          // TUN still open: traffic cannot leak to the normal network
        assertFalse(ports.tunnelRunning); assertFalse(ports.coreRunning)
        assertEquals("blocked", events.last)
        assertFalse(events.log.contains("finished")) // service keeps living to hold the interface
        assertEquals(1, sched.pending.size)
    }

    @Test fun reconnectAfterCoreDeathRestoresConnected() {
        connect(); ports.killCore()
        sched.runNext()
        assertEquals("connected:A", events.last)
        assertEquals(1, ports.openTuns.size)
        assertTrue(ports.coreRunning && ports.tunnelRunning)
    }

    @Test fun neverMoreThanTwoTunsAndOnlyBrieflyDuringReplacement() {
        connect(); ports.killCore(); sched.runNext()
        assertEquals(1, ports.openTuns.size)
        assertTrue(ports.maxOpenTuns <= 2)
        assertTrue(ports.openTuns.single().id == 2 && !ports.openTuns.single().closed)
    }

    @Test fun reconnectAttemptsAreBoundedThenTheSessionStaysBlocked() {
        connect(); ports.failStartCore = IllegalStateException("server down")
        ports.killCore()
        repeat(3) { assertEquals(1, sched.pending.size); sched.runNext() }
        assertTrue(sched.pending.isEmpty())           // gave up
        assertEquals(1, ports.openTuns.size)          // but still blocking, not leaking
        assertEquals("blocked", events.last)
        assertFalse(events.log.contains("finished"))
    }

    @Test fun successfulReconnectResetsTheAttemptBudget() {
        connect(); ports.killCore(); sched.runNext()
        ports.killCore()
        assertEquals(10L, sched.pending.single().first) // first delay again
    }

    @Test fun userStopCancelsPendingReconnect() {
        connect(); ports.killCore()
        session.newRequest(); session.stop()
        val coreCalls = ports.coreStartCalls
        sched.runNext()                                 // the old timer fires late
        assertEquals(coreCalls, ports.coreStartCalls)
        assertTrue(ports.openTuns.isEmpty())
        assertEquals("finished", events.last)
    }

    @Test fun newStartCancelsPendingReconnectSoThereIsNoDuplicateAttempt() {
        connect(); ports.killCore()
        connect()                                       // user picks a server meanwhile
        val coreCalls = ports.coreStartCalls
        sched.runNext()                                 // stale reconnect must do nothing
        assertEquals(coreCalls, ports.coreStartCalls)
        assertEquals("connected:A", events.last)
    }

    @Test fun duplicateCoreDeathSignalsAreHandledOnce() {
        connect()
        val listener = ports.deathListener!!
        ports.coreRunning = false
        listener(); listener()                          // callback and watchdog both fire
        assertEquals(1, events.log.count { it == "blocked" })
        assertEquals(1, sched.pending.size)
    }

    @Test fun staleCoreDeathFromAnOlderConnectionIsIgnored() {
        connect()
        val oldListener = ports.deathListener!!
        connect("b")                                    // switched
        oldListener()
        assertEquals("connected:B", events.last)
        assertTrue(ports.coreRunning)
    }

    // ---- first connection failures: nothing was protected, so nothing is held --------------------------

    @Test fun failedFirstConnectReleasesTheTunAndFinishes() {
        ports.failStartTunnel = IllegalStateException("hev missing")
        connect()
        assertTrue(ports.openTuns.isEmpty())
        assertFalse(ports.coreRunning)
        assertEquals(listOf("connecting:A", "failed", "finished"), events.log)
    }

    @Test fun missingVpnPermissionFailsCleanly() {
        ports.failEstablish = true
        connect()
        assertEquals("finished", events.last)
        assertTrue(events.log.contains("failed"))
        assertFalse(ports.coreRunning)
    }

    @Test fun coreThatDiesImmediatelyIsAFailureNotAConnection() {
        ports.coreAliveAfterStart = false
        connect()
        assertFalse(events.log.any { it.startsWith("connected") })
        assertTrue(ports.openTuns.isEmpty())
    }

    // ---- profile switching ---------------------------------------------------------------------------

    @Test fun switchingProfilesNeverLeavesTheOldCoreRunningOrTwoTuns() {
        connect("a"); connect("b")
        assertEquals("connected:B", events.last)
        assertEquals(1, ports.openTuns.size)
        assertEquals(listOf("core+a", "tun+1", "hev+", "hev-", "core-", "core+b", "tun+2", "hev+"), ports.log)
        assertTrue(ports.maxOpenTuns <= 2)
    }

    @Test fun oldTunStaysOpenUntilTheNewOneExists() {
        connect("a")
        var oldClosedWhenNewEstablished: Boolean? = null
        val first = ports.openTuns.single()
        ports.onStartCore = { oldClosedWhenNewEstablished = first.closed }
        connect("b")
        assertEquals(false, oldClosedWhenNewEstablished) // old TUN still held while the new core starts
        assertTrue(first.closed)                         // and released afterwards
    }

    @Test fun switchToBrokenProfileKeepsTheWorkingConnection() {
        connect("a")
        ports.failPrepare = IllegalStateException("invalid config")
        connect("b")
        assertEquals(listOf("connecting:A", "connected:A", "error", "connected:A"), events.log)
        assertTrue(ports.coreRunning && ports.tunnelRunning)
        assertEquals(1, ports.openTuns.size)
        assertEquals(1, ports.coreStartCalls)
    }

    @Test fun switchWhoseNewServerFailsToStartBlocksInsteadOfLeaking() {
        connect("a")
        ports.failStartCore = IllegalStateException("cannot start")
        connect("b")
        assertEquals(1, ports.openTuns.size)
        assertFalse(ports.coreRunning)
        assertEquals("blocked", events.last)
    }

    // ---- duplicate and superseded requests -------------------------------------------------------------

    @Test fun supersededStartDoesNothing() {
        val g1 = session.newRequest()
        val g2 = session.newRequest()
        session.start("a", g1)                          // stale before it runs
        assertTrue(ports.log.isEmpty()); assertTrue(events.log.isEmpty())
        session.start("b", g2)
        assertEquals("connected:B", events.last)
    }

    @Test fun repeatedConnectRequestsEndWithExactlyOneCoreAndOneTun() {
        repeat(5) { connect() }
        assertEquals(1, ports.openTuns.size)
        assertTrue(ports.maxOpenTuns <= 2)
        assertEquals(5, ports.coreStartCalls)
        assertEquals(4, ports.log.count { it == "core-" })  // each restart stopped the previous core first
        assertTrue(ports.coreRunning)
    }

    @Test fun stopRequestedWhileStartingAbortsAndLeavesNothingBehind() {
        val g = session.newRequest()
        ports.onStartCore = { session.newRequest() }    // the user presses stop while the core is starting
        session.start("a", g)
        session.stop()
        assertTrue(ports.openTuns.isEmpty())
        assertFalse(ports.coreRunning)
        assertFalse(events.log.any { it.startsWith("connected") })
        assertEquals("finished", events.last)
    }

    @Test fun destroyReleasesEverythingWithoutAskingTheServiceToStop() {
        connect(); session.newRequest(); session.destroy()
        assertTrue(ports.openTuns.isEmpty()); assertFalse(ports.coreRunning)
        assertFalse(events.log.contains("finished"))
        assertNull(ports.deathListener)
    }

    @Test fun stopWithoutAnyConnectionIsHarmless() {
        session.newRequest(); session.stop(); session.stop()
        assertTrue(ports.openTuns.isEmpty())
    }

    // ---- hev partial startup ------------------------------------------------------------------------------

    @Test fun failedNativeStartStillTriggersExactlyOneNativeCleanup() {
        ports.failStartTunnel = IllegalStateException("native init failed halfway")
        connect()
        assertEquals(1, ports.stopTunnelCalls)
        assertFalse(ports.tunnelPartial)
        assertFalse(ports.yamlExists)
        assertFalse(ports.coreRunning)
        assertTrue(ports.openTuns.isEmpty()) // first connection never worked: nothing is held
    }

    @Test fun noDuplicateNativeStopAfterAFailedStartFollowedByStopOrDestroy() {
        ports.failStartTunnel = IllegalStateException("x")
        connect()
        session.newRequest(); session.stop()
        session.newRequest(); session.destroy()
        assertEquals(1, ports.stopTunnelCalls)
    }

    @Test fun nativeStopHappensOncePerSuccessfulStartToo() {
        connect(); session.newRequest(); session.stop(); session.stop()
        assertEquals(1, ports.stopTunnelCalls)
    }

    @Test fun failedNativeStartDuringASwitchCleansUpAndStaysFailClosed() {
        connect("a")
        ports.failStartTunnel = IllegalStateException("x")
        connect("b")
        assertEquals(2, ports.stopTunnelCalls)   // once for the old tunnel, once for the failed attempt
        assertFalse(ports.tunnelPartial); assertFalse(ports.yamlExists)
        assertEquals(1, ports.openTuns.size)     // fail-closed preserved
        assertEquals("blocked", events.last)
    }

    @Test fun throwingNativeStopDoesNotSkipCoreCleanupAndIsNotRepeated() {
        connect()
        ports.stopTunnelThrows = true
        session.newRequest(); session.stop(); session.stop()
        assertEquals(1, ports.stopTunnelCalls)
        assertFalse(ports.coreRunning)
        assertTrue(ports.openTuns.isEmpty())
    }

    @Test fun cleanupIsSafeWhenHevNeverStarted() {
        ports.failStartCore = IllegalStateException("core failed before hev")
        connect()
        assertEquals(0, ports.stopTunnelCalls)   // hev was never attempted, so no native stop
        assertEquals("finished", events.last)
    }
}
