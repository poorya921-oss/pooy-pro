package com.pooy.vpn.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch

class OperationsTest {

    // ---- attempt -------------------------------------------------------------------------------------

    @Test fun attemptReturnsTheValue() = runBlocking {
        assertEquals(5, Operations.attempt({ throw AssertionError() }) { 5 })
    }

    @Test fun ordinaryExceptionsAreReportedAndReturnNull() = runBlocking {
        var reported: Exception? = null
        val r = Operations.attempt({ reported = it }) { throw IOException("network") }
        assertNull(r)
        assertEquals("network", reported?.message)
    }

    @Test fun cancellationIsRethrownImmediatelyAndNeverReported() {
        var reported = false
        assertThrows(CancellationException::class.java) {
            runBlocking { Operations.attempt({ reported = true }) { throw CancellationException("c") } }
        }
        assertFalse(reported)
    }

    @Test fun failureThatHappensAfterCancellationIsACancellationNotAnError() = runBlocking {
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        var reported = false
        val job = launch(Dispatchers.Default) {
            Operations.attempt({ reported = true }) { started.countDown(); release.await(); throw IOException("socket closed") }
        }
        started.await(); job.cancel(); release.countDown(); job.join()
        assertTrue(job.isCancelled)
        assertFalse("no misleading failure message after cancellation", reported)
    }

    // ---- announce -------------------------------------------------------------------------------------

    @Test fun announceShowsTheMessageWhileActive() = runBlocking {
        var shown = false
        Operations.announce { shown = true }
        assertTrue(shown)
    }

    @Test fun announceIsSuppressedAfterCancellation() = runBlocking {
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        var shown = false
        val job = launch(Dispatchers.Default) {
            started.countDown(); release.await()
            Operations.announce { shown = true }       // e.g. "import succeeded" arriving after the user left
        }
        started.await(); job.cancel(); release.countDown(); job.join()
        assertTrue(job.isCancelled); assertFalse(shown)
    }

    // ---- busy tracking ----------------------------------------------------------------------------------

    @Test fun busyIsSetDuringAndClearedAfter() = runBlocking {
        val states = ArrayList<Boolean>()
        val busy = BusyTracker { states.add(it) }
        busy.track { assertTrue(busy.isBusy) }
        assertEquals(listOf(true, false), states)
        assertFalse(busy.isBusy)
    }

    @Test fun busyIsClearedWhenTheOperationFails() {
        val busy = BusyTracker { }
        assertThrows(IllegalStateException::class.java) { runBlocking { busy.track { throw IllegalStateException("x") } } }
        assertFalse(busy.isBusy)
    }

    @Test fun busyIsClearedWhenTheOperationIsCancelled() = runBlocking {
        val states = ArrayList<Boolean>()
        val busy = BusyTracker { states.add(it) }
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val job = launch(Dispatchers.Default) { busy.track { started.countDown(); release.await(); Operations.announce { } } }
        started.await(); job.cancel(); release.countDown(); job.join()
        assertTrue(job.isCancelled)
        assertFalse(busy.isBusy)
        assertEquals(listOf(true, false), states)
    }

    @Test fun oneOperationFinishingDoesNotClearTheBusyFlagOfAnotherThatStillRuns() = runBlocking {
        val states = ArrayList<Boolean>()
        val busy = BusyTracker { synchronized(states) { states.add(it) } }
        val aIn = CountDownLatch(1); val aOut = CountDownLatch(1); val bIn = CountDownLatch(1); val bOut = CountDownLatch(1)
        val a = launch(Dispatchers.Default) { busy.track { aIn.countDown(); aOut.await() } }
        val b = launch(Dispatchers.Default) { busy.track { bIn.countDown(); bOut.await() } }
        aIn.await(); bIn.await()
        aOut.countDown(); a.join()
        assertTrue("B still runs", busy.isBusy)
        bOut.countDown(); b.join()
        assertFalse(busy.isBusy)
        assertEquals(listOf(true, false), synchronized(states) { states.toList() }) // one on, one off
    }

    // ---- batch (updateAllSubscriptions) ----------------------------------------------------------------------

    @Test fun countResultsCountsOkFalseAndExceptions() = runBlocking {
        val c = Operations.countResults(listOf(1, 2, 3, 4)) { n ->
            when (n) { 1 -> true; 2 -> false; 3 -> throw IOException("down"); else -> true }
        }
        assertEquals(2, c.ok); assertEquals(2, c.failed)
    }

    @Test fun cancellationInsideTheBatchStopsItAndIsNotCountedAsAFailure() {
        val processed = ArrayList<Int>()
        assertThrows(CancellationException::class.java) {
            runBlocking {
                Operations.countResults(listOf(1, 2, 3, 4, 5)) { n ->
                    processed.add(n)
                    if (n == 3) throw CancellationException("cancelled")
                    true
                }
            }
        }
        assertEquals(listOf(1, 2, 3), processed)           // items 4 and 5 are never started
    }

    @Test fun externalCancellationDuringABlockedItemStopsTheBatchWithoutCountingAFailure() = runBlocking {
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val processed = ArrayList<Int>()
        var result: BatchCounts? = null
        val job = launch(Dispatchers.Default) {
            result = Operations.countResults(listOf(1, 2, 3)) { n ->
                synchronized(processed) { processed.add(n) }
                if (n == 2) { started.countDown(); release.await(); throw IOException("aborted by cancel") }
                true
            }
        }
        started.await(); job.cancel(); release.countDown(); job.join()
        assertTrue(job.isCancelled)
        assertNull(result)                                  // no "x updated, y failed" summary is produced
        assertEquals(listOf(1, 2), synchronized(processed) { processed.toList() })
    }

    // ---- three-way outcomes ----------------------------------------------------------------------------------

    @Test fun staleOutcomesAreNeitherSuccessNorFailure() = runBlocking {
        val c = Operations.countOutcomes(listOf(1, 2, 3, 4, 5)) { n ->
            when (n) {
                1 -> BatchOutcome.Ok
                2 -> BatchOutcome.Skipped
                3 -> BatchOutcome.Failed
                4 -> throw IOException("down")
                else -> BatchOutcome.Ok
            }
        }
        assertEquals(2, c.ok); assertEquals(2, c.failed); assertEquals(1, c.skipped)
    }

    @Test fun cancellationStopsTheThreeWayBatch() {
        val processed = ArrayList<Int>()
        assertThrows(CancellationException::class.java) {
            runBlocking {
                Operations.countOutcomes(listOf(1, 2, 3, 4)) { n ->
                    processed.add(n)
                    if (n == 2) throw CancellationException("cancelled")
                    BatchOutcome.Ok
                }
            }
        }
        assertEquals(listOf(1, 2), processed)
    }
}
