package com.pooy.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class SubscriptionsTest {

    private class FakeConn(
        url: URL,
        private val code: Int,
        private val headers: Map<String, String> = emptyMap(),
        private val body: ByteArray = ByteArray(0),
        private val length: Long = -1L,
    ) : HttpURLConnection(url) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode(): Int = code
        override fun getHeaderField(name: String?): String? = headers[name ?: ""]
        override fun getInputStream(): InputStream = ByteArrayInputStream(body)
        override fun getContentLengthLong(): Long = length
    }

    private class Net(private val routes: Map<String, (URL) -> HttpURLConnection>) {
        val requested = ArrayList<String>()
        val opener = Subscriptions.Opener { url ->
            requested.add(url.toString())
            (routes[url.toString()] ?: throw IOException("no route to $url")).invoke(url)
        }
    }

    private fun ok(body: String) = { u: URL -> FakeConn(u, 200, body = body.toByteArray()) }
    private fun redirect(to: String, code: Int = 302) = { u: URL -> FakeConn(u, code, mapOf("Location" to to)) }

    // ---- URL validation -------------------------------------------------------------

    @Test fun acceptsHttps() {
        assertEquals("https://example.com/sub?token=1", Subscriptions.parseHttpsUrl(" https://example.com/sub?token=1 ").toString())
        Subscriptions.parseHttpsUrl("HTTPS://Example.com/x")
    }

    @Test fun rejectsEverythingElse() {
        val bad = listOf(
            "http://example.com/sub", "ftp://example.com/x", "file:///etc/passwd", "javascript:alert(1)",
            "", "   ", "example.com/sub", "https://", "https:///path", "https://exa mple.com", "https://[::1", "//example.com",
            "https://" + "a".repeat(5000),
        )
        for (u in bad) {
            assertThrows("should reject '$u'", SubscriptionException::class.java) { Subscriptions.parseHttpsUrl(u) }
        }
    }

    @Test fun fetchNeverContactsTheNetworkForHttp() {
        val net = Net(emptyMap())
        assertThrows(SubscriptionException::class.java) { Subscriptions.fetch("http://example.com/s", net.opener) }
        assertTrue(net.requested.isEmpty())
    }

    // ---- redirects --------------------------------------------------------------------

    @Test fun downgradeRedirectIsRefused() {
        val net = Net(mapOf("https://a.example/s" to redirect("http://a.example/s")))
        val e = assertThrows(SubscriptionException::class.java) { Subscriptions.fetch("https://a.example/s", net.opener) }
        assertTrue(e.message!!.contains("non-HTTPS"))
        assertEquals(listOf("https://a.example/s"), net.requested) // the http:// URL was never requested
    }

    @Test fun httpsRedirectChainIsFollowed() {
        val net = Net(
            mapOf(
                "https://a.example/s" to redirect("https://b.example/x", 301),
                "https://b.example/x" to redirect("/final", 307),
                "https://b.example/final" to ok("payload"),
            ),
        )
        assertEquals("payload", Subscriptions.fetch("https://a.example/s", net.opener))
        assertEquals(3, net.requested.size)
    }

    @Test fun redirectLoopIsStopped() {
        val net = Net(mapOf("https://a.example/s" to redirect("https://a.example/s")))
        val e = assertThrows(SubscriptionException::class.java) { Subscriptions.fetch("https://a.example/s", net.opener) }
        assertTrue(e.message!!.contains("redirects"))
        assertEquals(6, net.requested.size) // first request + 5 redirects
    }

    @Test fun redirectWithoutLocationIsRejected() {
        val net = Net(mapOf("https://a.example/s" to { u: URL -> FakeConn(u, 302) }))
        assertThrows(SubscriptionException::class.java) { Subscriptions.fetch("https://a.example/s", net.opener) }
    }

    @Test fun malformedRedirectTargetIsRejectedNotCrashing() {
        val net = Net(mapOf("https://a.example/s" to redirect("https://[bad")))
        assertThrows(SubscriptionException::class.java) { Subscriptions.fetch("https://a.example/s", net.opener) }
    }

    @Test fun redirectToOtherSchemesIsRefused() {
        for (t in listOf("ftp://x/y", "file:///etc/passwd", "javascript:alert(1)")) {
            val net = Net(mapOf("https://a.example/s" to redirect(t)))
            assertThrows("redirect to $t", SubscriptionException::class.java) { Subscriptions.fetch("https://a.example/s", net.opener) }
        }
    }

    // ---- responses ----------------------------------------------------------------------

    @Test fun nonOkStatusIsAnError() {
        for (code in listOf(204, 400, 403, 404, 500, 503)) {
            val net = Net(mapOf("https://a.example/s" to { u: URL -> FakeConn(u, code) }))
            val e = assertThrows(SubscriptionException::class.java) { Subscriptions.fetch("https://a.example/s", net.opener) }
            assertEquals("HTTP $code", e.message)
        }
    }

    @Test fun oversizeContentLengthIsRejected() {
        val net = Net(mapOf("https://a.example/s" to { u: URL -> FakeConn(u, 200, length = Subscriptions.MAX_BYTES + 1L) }))
        assertThrows(SubscriptionException::class.java) { Subscriptions.fetch("https://a.example/s", net.opener) }
    }

    @Test fun oversizeBodyWithoutLengthIsRejected() {
        val big = ByteArray(Subscriptions.MAX_BYTES + 1)
        val net = Net(mapOf("https://a.example/s" to { u: URL -> FakeConn(u, 200, body = big) }))
        val e = assertThrows(SubscriptionException::class.java) { Subscriptions.fetch("https://a.example/s", net.opener) }
        assertEquals("Content too large", e.message)
    }

    @Test fun bodyAtTheLimitIsAccepted() {
        val body = ByteArray(Subscriptions.MAX_BYTES) { 'a'.code.toByte() }
        val net = Net(mapOf("https://a.example/s" to { u: URL -> FakeConn(u, 200, body = body) }))
        assertEquals(Subscriptions.MAX_BYTES, Subscriptions.fetch("https://a.example/s", net.opener).length)
    }

    @Test fun networkFailuresBecomeSubscriptionExceptions() {
        val failing = Subscriptions.Opener { throw IOException("boom") }
        assertThrows(SubscriptionException::class.java) { Subscriptions.fetch("https://a.example/s", failing) }
        val runtime = Subscriptions.Opener { throw IllegalStateException("platform bug") }
        assertThrows(SubscriptionException::class.java) { Subscriptions.fetch("https://a.example/s", runtime) }
    }

    @Test fun emptyBodyIsReturnedAndLeftToTheImporter() {
        val net = Net(mapOf("https://a.example/s" to ok("")))
        assertEquals("", Subscriptions.fetch("https://a.example/s", net.opener))
        assertEquals(0, Importer.fromText("").profiles.size)
    }

    @Test fun garbageBodyYieldsNoProfilesNotACrash() {
        val r = Importer.fromText("<html>Service unavailable</html>\n\u0000\u0001")
        assertEquals(0, r.profiles.size)
    }
}
