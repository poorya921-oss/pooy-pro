package com.pooy.vpn.core

/**
 * Tracks the newest run per key (e.g. latency test per profile) so that an older run can never clobber a newer one.
 *
 * Every transition happens under ONE lock together with the matching UI-state update ([setActive]), so a run
 * "finishing" and another one "beginning" cannot interleave between the token check and the state change:
 *  - [begin] installs a new token and marks the key active, atomically;
 *  - [finish] removes the token and clears the active flag only if the token is still the newest, atomically;
 *  - [publishIfCurrent] runs [block] only if the token is still the newest, atomically with that check.
 * [setActive] and the publish blocks run while the lock is held, so they must be short and must not call back in.
 */
class LatestRunTracker<K : Any>(private val setActive: (K, Boolean) -> Unit) {
    private val lock = Any()
    private val tokens = HashMap<K, Any>()

    fun begin(key: K): Any {
        val token = Any()
        synchronized(lock) {
            tokens[key] = token
            setActive(key, true)
        }
        return token
    }

    fun isCurrent(key: K, token: Any): Boolean = synchronized(lock) { tokens[key] === token }

    /** Returns true if [block] ran (the token was the newest at that moment, and still is while it runs). */
    fun publishIfCurrent(key: K, token: Any, block: () -> Unit): Boolean =
        synchronized(lock) {
            if (tokens[key] !== token) return@synchronized false
            block()
            true
        }

    /** Returns true if this call ended the key's activity (false: a newer run owns it, nothing was touched). */
    fun finish(key: K, token: Any): Boolean =
        synchronized(lock) {
            if (tokens[key] !== token) return@synchronized false
            tokens.remove(key)
            setActive(key, false)
            true
        }

    val activeCount: Int get() = synchronized(lock) { tokens.size }
}
