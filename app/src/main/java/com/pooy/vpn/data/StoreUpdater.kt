package com.pooy.vpn.data

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Applies a change to the store as ONE consistency-critical step.
 *
 * Under [mutex], on [io], and inside [NonCancellable]: read the current state, compute the new state, persist it
 * and - only if it was really saved - publish it through [apply]. Because persistence and publication share the same
 * non-cancellable block, a cancellation can never land between "written to disk" and "visible in memory".
 *
 * Cancellation semantics:
 *  - cancelled while waiting for the lock: nothing happens, CancellationException propagates;
 *  - cancelled during the commit: the commit and [apply] still complete, then CancellationException is thrown
 *    (so callers cannot go on to show a success message);
 *  - not cancelled: the [CommitResult] is returned.
 * [onResult] is the transactional hook: it runs once per executed commit (Committed, Unchanged or Failed), after
 * [apply], inside the same non-cancellable critical section and while the lock is still held. State that must only
 * advance after a SUCCESSFUL save (and must be visible to the very next update) belongs there, never in [block]
 * (which runs before the write) and never after this function returns (the lock is released and a cancellation may
 * have intervened). It is not called if the call was cancelled while waiting for the lock.
 * [read], [block], [apply] and [onResult] run on [io] with the lock held; keep them short and non-suspending.
 */
object StoreUpdater {
    suspend fun update(
        mutex: Mutex,
        storage: ProfileStorage,
        io: CoroutineContext,
        read: () -> StoreData,
        apply: (StoreData) -> Unit,
        block: (StoreData) -> StoreData,
        onResult: (CommitResult) -> Unit = {},
    ): CommitResult {
        val result = mutex.withLock {
            withContext(io + NonCancellable) {
                val r = StoreCommit.commit(storage, read(), block)
                if (r is CommitResult.Committed) apply(r.data)
                onResult(r) // same critical section: bookkeeping that must follow a successful save is atomic with it
                r
            }
        }
        // Propagate a cancellation that arrived during the critical section, now that it is finished.
        currentCoroutineContext().ensureActive()
        return result
    }
}
