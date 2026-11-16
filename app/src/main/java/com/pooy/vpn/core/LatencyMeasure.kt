package com.pooy.vpn.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.CancellationException

/**
 * Runs one blocking latency measurement with correct coroutine semantics:
 *  - cancellation is never swallowed (it is rethrown, also when it arrives while the uninterruptible native call runs);
 *  - ordinary operational failures (bad config, unreachable server, missing core classes) give -1;
 *  - a result produced after the coroutine was cancelled is never returned, so callers cannot publish stale values.
 * Errors that are not operational (e.g. OutOfMemoryError) propagate.
 */
object LatencyMeasure {
    suspend fun measure(block: () -> Long): Long {
        val ms = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            -1L
        } catch (e: LinkageError) {
            -1L // NoClassDefFoundError / UnsatisfiedLinkError: the core or its native library is missing
        }
        currentCoroutineContext().ensureActive()
        return if (ms >= 1) ms else -1L
    }
}
