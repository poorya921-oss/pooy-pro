package com.pooy.vpn.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the DNS/routing properties documented in docs/HARDENING_REPORT.md. */
class XrayConfigBuilderTest {
    private val server = ConfigParser.parse("vless://11111111-1111-1111-1111-111111111111@example.com:443?security=tls&sni=example.com#s")
    private val cfg = JSONObject(XrayConfigBuilder.runtime(server, 12345, "user1", "pass1"))

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }

    @Test fun onlyOneInboundBoundToLoopbackWithPasswordAuth() {
        val inbounds = cfg.getJSONArray("inbounds")
        assertEquals(1, inbounds.length())
        val i = inbounds.getJSONObject(0)
        assertEquals("127.0.0.1", i.getString("listen"))
        assertEquals(12345, i.getInt("port"))
        assertEquals("socks", i.getString("protocol"))
        val st = i.getJSONObject("settings")
        assertEquals("password", st.getString("auth"))
        assertEquals("user1", st.getJSONArray("accounts").getJSONObject(0).getString("user"))
    }

    @Test fun dnsUsesOnlyTheSystemResolverForBootstrap() {
        // Xray's own lookups (the server's hostname) use the system resolver of POOOY's excluded process.
        // All application DNS never reaches Xray as a name: apps query 1.1.1.1/8.8.8.8 through the TUN as UDP.
        assertEquals(listOf("localhost"), cfg.getJSONObject("dns").getJSONArray("servers").strings())
    }

    @Test fun routingIsIpBasedWithoutDomainResolution() {
        val routing = cfg.getJSONObject("routing")
        assertEquals("AsIs", routing.getString("domainStrategy"))
        val rule = routing.getJSONArray("rules").getJSONObject(0)
        assertEquals("direct", rule.getString("outboundTag"))
        val ranges = rule.getJSONArray("ip").strings()
        assertTrue(ranges.containsAll(listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7", "fe80::/10")))
    }

    @Test fun proxyOutboundComesFirstSoItIsTheDefault() {
        val outs = cfg.getJSONArray("outbounds")
        assertEquals("proxy", outs.getJSONObject(0).getString("tag"))
        assertEquals("direct", outs.getJSONObject(1).getString("tag"))
    }

    @Test fun speedTestConfigOpensNoListeningPorts() {
        val t = JSONObject(XrayConfigBuilder.speedTest(server))
        assertFalse(t.has("inbounds"))
        assertEquals(1, t.getJSONArray("outbounds").length())
    }

    @Test fun testUrlIsHttps() {
        assertTrue(XrayConfigBuilder.TEST_URL.startsWith("https://"))
    }
}
