package com.pooy.vpn.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.CancellationException

/** Counts concurrent operations so one finishing cannot clear the "busy" indicator of another that still runs. */
class BusyTracker(private val onChange: (Boolean) -> Unit) {
    private val lock = Any()
    private var count = 0

    /** Runs [block] with busy set; cleanup happens in `finally`, also on cancellation. */
    suspend fun <T> track(block: suspend () -> T): T {
        synchronized(lock) {
            count++
            if (count == 1) onChange(true)
        }
        try {
            return block()
        } finally {
            synchronized(lock) {
                if (count > 0) count--
                if (count == 0) onChange(false)
            }
        }
    }

    val isBusy: Boolean get() = synchronized(lock) { count > 0 }
}

class BatchCounts(val ok: Int, val failed: Int, val skipped: Int = 0)

enum class BatchOutcome { Ok, Failed, Skipped }

/** Coroutine-correct helpers for ViewModel operations. */
object Operations {
    /**
     * Runs [block]. Cancellation is rethrown immediately and never reported; ordinary exceptions go to [onError]
     * (result null). If the coroutine was cancelled while the (possibly uninterruptible) block was failing, the
     * failure is treated as the cancellation it really is, so no misleading error is shown.
     */
    suspend fun <T> attempt(onError: (Exception) -> Unit, block: suspend () -> T): T? {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            onError(e)
            return null
        }
    }

    /** Shows [message] only if the coroutine is still active; after cancellation it throws instead. */
    suspend fun announce(message: () -> Unit) {
        currentCoroutineContext().ensureActive()
        message()
    }

    /** Runs [op] for every item; counts true as ok, false or an ordinary exception as failed; rethrows cancellation. */
    suspend fun <T> countResults(items: List<T>, op: suspend (T) -> Boolean): BatchCounts {
        var ok = 0
        var failed = 0
        for (item in items) {
            val r = attempt(onError = { failed++ }) { op(item) } ?: continue
            if (r) ok++ else failed++
        }
        return BatchCounts(ok, failed)
    }

    /**
     * Like [countResults] with a third outcome: [BatchOutcome.Skipped] (e.g. a stale refresh that was discarded) is
     * neither a success nor a failure. Cancellation is rethrown; ordinary exceptions count as failed.
     */
    suspend fun <T> countOutcomes(items: List<T>, op: suspend (T) -> BatchOutcome): BatchCounts {
        var ok = 0
        var failed = 0
        var skipped = 0
        for (item in items) {
            val r = attempt(onError = { failed++ }) { op(item) } ?: continue
            when (r) {
                BatchOutcome.Ok -> ok++
                BatchOutcome.Failed -> failed++
                BatchOutcome.Skipped -> skipped++
            }
        }
        return BatchCounts(ok, failed, skipped)
    }
}
