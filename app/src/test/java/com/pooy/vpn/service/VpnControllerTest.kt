package com.pooy.vpn.service

import com.pooy.vpn.service.VpnController.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** State transitions of the process-wide VPN status shared by the service and the UI. */
class VpnControllerTest {
    @Before fun reset() {
        VpnController.clock = { 1000L }
        VpnController.failed("")
        VpnController.clearError()
        VpnController.disconnected()
    }

    private val s get() = VpnController.status.value

    @Test fun normalConnectCycle() {
        VpnController.connecting(null)
        assertEquals(State.Connecting, s.state)
        VpnController.connecting("srv")
        VpnController.connected("srv")
        assertEquals(State.Connected, s.state)
        assertEquals("srv", s.serverName)
        assertEquals(1000L, s.connectedAtMs)
        VpnController.disconnected()
        assertEquals(State.Disconnected, s.state)
        assertNull(s.serverName)
    }

    @Test fun lateConnectedAfterDisconnectIsIgnored() {
        VpnController.connecting("srv")
        VpnController.disconnected()      // user pressed stop while the start was still running
        VpnController.connected("srv")    // the cancelled start reports in late
        assertEquals(State.Disconnected, s.state)
    }

    @Test fun connectedWithoutConnectingIsIgnored() {
        VpnController.connected("srv")
        assertEquals(State.Disconnected, s.state)
    }

    @Test fun failureIsRecordedAndSurvivesDisconnect() {
        VpnController.connecting("srv")
        VpnController.failed("core missing")
        assertEquals(State.Disconnected, s.state)
        assertEquals("core missing", s.error)
        VpnController.disconnected()
        assertEquals("core missing", s.error)
        VpnController.clearError()
        assertNull(s.error)
    }

    @Test fun newConnectionAttemptClearsOldError() {
        VpnController.failed("old")
        VpnController.connecting("srv")
        assertNull(s.error)
    }

    @Test fun switchingProfilesWhileConnectedGoesBackThroughConnecting() {
        VpnController.connecting("a"); VpnController.connected("a")
        VpnController.connecting(null)
        assertEquals(State.Connecting, s.state)
        VpnController.connecting("b"); VpnController.connected("b")
        assertEquals("b", s.serverName)
        assertEquals(State.Connected, s.state)
    }

    @Test fun connectingResetsTrafficCounters() {
        VpnController.connecting("a")
        assertEquals(0L, VpnController.traffic.value.up)
    }

    @Test fun blockedKeepsTheServerNameAndCarriesTheError() {
        VpnController.connecting("srv"); VpnController.connected("srv")
        VpnController.blocked("core died")
        assertEquals(State.Blocked, s.state)
        assertEquals("srv", s.serverName)
        assertEquals("core died", s.error)
    }

    @Test fun blockedCanReconnectOrBeDisconnected() {
        VpnController.connecting("srv"); VpnController.connected("srv"); VpnController.blocked("x")
        VpnController.connecting("srv"); VpnController.connected("srv")
        assertEquals(State.Connected, s.state)
        VpnController.blocked("x")
        VpnController.disconnected()
        assertEquals(State.Disconnected, s.state)
    }

    @Test fun connectedIsIgnoredWhileBlocked() {
        VpnController.connecting("srv"); VpnController.connected("srv"); VpnController.blocked("x")
        VpnController.connected("srv")
        assertEquals(State.Blocked, s.state)
    }

    @Test fun reportErrorDoesNotChangeTheState() {
        VpnController.connecting("a"); VpnController.connected("a")
        VpnController.reportError("bad profile")
        assertEquals(State.Connected, s.state)
        assertEquals("bad profile", s.error)
    }
}
