package com.pooy.vpn.data

import java.io.IOException

sealed class RefreshOutcome {
    /** The downloaded profiles were saved and applied. */
    object Applied : RefreshOutcome()

    /** The result was discarded because the subscription changed, was deleted, or a newer refresh was already saved. Nothing was written. */
    object Stale : RefreshOutcome()

    /** The data was valid and current, but saving failed (the error has already been reported by the update function). */
    object SaveFailed : RefreshOutcome()
}

/**
 * Refreshes one subscription without ever resurrecting or overwriting something that changed while the download ran.
 *
 * The download runs outside any lock. Applying it is one transaction on the store, with two phases that both happen
 * under the store mutex and inside the same non-cancellable critical section ([StoreUpdater]):
 *
 *  1. DECIDE (in the update block, on the latest data, before the write; strictly read-only for this class): the
 *     refresh is eligible only if the subscription still exists, its URL equals the downloaded URL, and no refresh
 *     that started LATER has already been successfully saved ([lastSaved]).
 *  2. REGISTER (in the result hook, right after the write): only if the write succeeded (saved, or already identical
 *     to what is on disk) is the subscription's last-saved sequence advanced.
 *
 * Because registration happens in the same critical section as persistence, "saved" and "sequence advanced" are
 * logically atomic: no other refresh can decide between the two, and a cancellation cannot separate them. A refresh
 * whose save failed registers nothing, so it supersedes nobody: an older refresh stays eligible. A successfully saved
 * newer refresh makes every older result stale. Sequence numbers are handed out at refresh START (start order defines
 * "newer"); handing one out changes no shared decision state.
 *
 * Failures propagate as exceptions (nothing touched); cancellation propagates untouched.
 */
class SubscriptionRefresher(
    private val currentData: () -> StoreData,
    /** Downloads and parses the subscription; profiles must carry `subId = sub.id`. Throws on any failure. */
    private val download: suspend (Subscription) -> List<Profile>,
    /**
     * Runs the transaction: `block` computes the new state from the latest data; `onResult` is invoked inside the
     * same critical section after the write was attempted. Returns false if saving failed.
     */
    private val update: suspend (block: (StoreData) -> StoreData, onResult: (CommitResult) -> Unit) -> Boolean,
) {
    private val lock = Any()
    private var nextSeq = 0L
    private val lastSaved = HashMap<String, Long>() // subscription id -> sequence of the newest SAVED refresh

    suspend fun refresh(sub: Subscription): RefreshOutcome {
        if (!isCurrent(currentData(), sub)) return RefreshOutcome.Stale // already gone/changed: do not even download
        val seq = synchronized(lock) { ++nextSeq }
        val profiles = download(sub)
        if (profiles.isEmpty()) throw IOException("Subscription contains no usable profiles")

        // Written only inside the transaction (under the store mutex), read after it completed normally.
        var proposed = false
        val saved = update(
            { d ->
                if (isCurrent(d, sub) && isEligible(sub.id, seq)) {
                    proposed = true
                    merge(d, sub, profiles)
                } else {
                    proposed = false
                    d
                }
            },
            { result ->
                // Phase 2: only a write that really succeeded (or was a no-op because disk already equals the new
                // state) advances the sequence. Failed => nothing registered => older refreshes stay eligible.
                if (proposed && result !is CommitResult.Failed) registerSaved(sub.id, seq)
            },
        )
        return when {
            !proposed -> RefreshOutcome.Stale
            saved -> RefreshOutcome.Applied
            else -> RefreshOutcome.SaveFailed
        }
    }

    private fun isCurrent(d: StoreData, sub: Subscription): Boolean =
        d.subscriptions.any { it.id == sub.id && it.url == sub.url }

    private fun isEligible(subId: String, seq: Long): Boolean =
        synchronized(lock) { seq > (lastSaved[subId] ?: 0L) }

    private fun registerSaved(subId: String, seq: Long) {
        synchronized(lock) {
            val last = lastSaved[subId] ?: 0L
            if (seq > last) lastSaved[subId] = seq
        }
    }

    companion object {
        /** Replaces the subscription's profiles, keeping the active profile selected when it still exists. */
        fun merge(d: StoreData, sub: Subscription, fresh: List<Profile>): StoreData {
            val oldActive = d.profiles.firstOrNull { it.id == d.activeId && it.subId == sub.id }
            val newActive = when {
                oldActive != null ->
                    (fresh.firstOrNull { it.raw == oldActive.raw }
                        ?: fresh.firstOrNull { it.name == oldActive.name }
                        ?: fresh.first()).id
                d.activeId == null -> fresh.first().id
                else -> d.activeId
            }
            return d.copy(profiles = d.profiles.filter { it.subId != sub.id } + fresh, activeId = newActive)
        }
    }
}
