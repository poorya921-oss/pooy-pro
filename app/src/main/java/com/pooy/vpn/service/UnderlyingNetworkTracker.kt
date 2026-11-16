package com.pooy.vpn.service

/**
 * Keeps track of which physical network is reported to the system as the VPN's underlying network, and makes the
 * connectivity callbacks safe against reordering: Android may deliver "lost" for the old network AFTER "available"
 * for the new one (Wi-Fi <-> mobile handover). A loss only clears the setting if it concerns the network that is
 * currently selected.
 *
 * Generic over the network type so it can be unit tested without android.net.Network.
 * [apply] is called with the selected network, or null to fall back to the system default; it runs under the
 * tracker's lock, so calls are serialised and never interleave with a release().
 */
class UnderlyingNetworkTracker<N : Any>(private val apply: (N?) -> Unit) {
    private val lock = Any()
    private var selected: N? = null
    private var active = true

    val current: N? get() = synchronized(lock) { selected }

    fun onAvailable(network: N) {
        synchronized(lock) {
            if (!active) return
            selected = network
            apply(network)
        }
    }

    fun onLost(network: N) {
        synchronized(lock) {
            if (!active) return
            if (selected != network) return // a stale loss of an older network: keep the current selection
            selected = null
            apply(null)
        }
    }

    /** Called when the callback is unregistered. Clears a still-selected network once; later callbacks are ignored. */
    fun release() {
        synchronized(lock) {
            if (!active) return
            active = false
            val hadSelection = selected != null
            selected = null
            if (hadSelection) apply(null)
        }
    }
}
