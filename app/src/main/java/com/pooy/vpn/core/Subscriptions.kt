package com.pooy.vpn.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Any failure while validating or downloading a subscription; the message is safe to show to the user. */
class SubscriptionException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Minimal HTTPS-only fetcher (no third-party HTTP library). Only https:// URLs are accepted, redirects are
 * followed manually and every hop (including the final URL) must be https, so a downgrade to http is refused.
 * TLS certificate and hostname validation are the platform defaults and are not touched.
 */
object Subscriptions {
    const val MAX_BYTES = 5_000_000
    private const val MAX_REDIRECTS = 5
    private const val MAX_URL_LENGTH = 4096

    fun interface Opener {
        fun open(url: URL): HttpURLConnection
    }

    private val defaultOpener = Opener { it.openConnection() as HttpURLConnection }

    /** Returns a validated https URL or throws [SubscriptionException]. Never throws anything else. */
    fun parseHttpsUrl(raw: String): URL {
        val t = raw.trim()
        if (t.isEmpty() || t.length > MAX_URL_LENGTH) throw SubscriptionException("Invalid URL")
        val uri = try {
            URI(t)
        } catch (e: Exception) {
            throw SubscriptionException("Invalid URL")
        }
        val scheme = uri.scheme ?: throw SubscriptionException("Invalid URL")
        if (!scheme.equals("https", ignoreCase = true)) {
            throw SubscriptionException("Only https:// subscription URLs are supported")
        }
        if (uri.host.isNullOrEmpty()) throw SubscriptionException("Invalid URL")
        return try {
            uri.toURL()
        } catch (e: Exception) {
            throw SubscriptionException("Invalid URL")
        }
    }

    fun fetch(url: String, opener: Opener = defaultOpener): String {
        var current = parseHttpsUrl(url)
        var redirects = 0
        while (true) {
            val c = try {
                opener.open(current)
            } catch (e: Exception) {
                throw SubscriptionException("Connection failed", e)
            }
            try {
                c.connectTimeout = 10_000
                c.readTimeout = 20_000
                c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", "POOOY/1.0")
                when (val code = c.responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        if (!c.url.protocol.equals("https", ignoreCase = true)) {
                            throw SubscriptionException("Refusing non-HTTPS response")
                        }
                        if (c.contentLengthLong > MAX_BYTES) throw SubscriptionException("Content too large")
                        return c.inputStream.use { readLimited(it, MAX_BYTES) }
                    }
                    301, 302, 303, 307, 308 -> {
                        if (++redirects > MAX_REDIRECTS) throw SubscriptionException("Too many redirects")
                        val loc = c.getHeaderField("Location")?.trim()
                        if (loc.isNullOrEmpty()) throw SubscriptionException("Redirect without a target")
                        current = resolveRedirect(current, loc)
                    }
                    else -> throw SubscriptionException("HTTP $code")
                }
            } catch (e: SubscriptionException) {
                throw e
            } catch (e: Exception) {
                // IOException, runtime failures from the platform stack, malformed responses: never crash the app.
                throw SubscriptionException("Network error", e)
            } finally {
                try {
                    c.disconnect()
                } catch (ignored: Exception) {
                    // ignore
                }
            }
        }
    }

    private fun resolveRedirect(base: URL, location: String): URL {
        val next = try {
            URL(base, location)
        } catch (e: Exception) {
            throw SubscriptionException("Invalid redirect target")
        }
        if (!next.protocol.equals("https", ignoreCase = true)) {
            throw SubscriptionException("Refusing redirect to a non-HTTPS URL")
        }
        return parseHttpsUrl(next.toString())
    }

    fun readLimited(s: InputStream, max: Int): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw SubscriptionException("Content too large")
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }
}
