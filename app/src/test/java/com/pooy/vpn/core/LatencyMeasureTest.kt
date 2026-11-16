package com.pooy.vpn.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch

class LatencyMeasureTest {
    @Test fun returnsTheMeasuredMilliseconds() = runBlocking {
        assertEquals(120L, LatencyMeasure.measure { 120L })
    }

    @Test fun nonPositiveResultsMeanFailure() = runBlocking {
        assertEquals(-1L, LatencyMeasure.measure { 0L })
        assertEquals(-1L, LatencyMeasure.measure { -5L })
    }

    @Test fun operationalExceptionsGiveMinusOne() = runBlocking {
        assertEquals(-1L, LatencyMeasure.measure { throw IllegalStateException("unreachable") })
        assertEquals(-1L, LatencyMeasure.measure { throw java.io.IOException("io") })
        assertEquals(-1L, LatencyMeasure.measure { throw ConfigException("bad config") })
    }

    @Test fun missingCoreClassesGiveMinusOne() = runBlocking {
        assertEquals(-1L, LatencyMeasure.measure { throw NoClassDefFoundError("libv2ray/Libv2ray") })
        assertEquals(-1L, LatencyMeasure.measure { throw UnsatisfiedLinkError("libgojni.so") })
    }

    @Test fun cancellationThrownByTheBlockIsRethrownNotSwallowed() {
        assertThrows(CancellationException::class.java) {
            runBlocking { LatencyMeasure.measure { throw CancellationException("cancelled") } }
        }
    }

    @Test fun nonOperationalErrorsPropagate() {
        assertThrows(OutOfMemoryError::class.java) {
            runBlocking { LatencyMeasure.measure { throw OutOfMemoryError("test") } }
        }
    }

    @Test fun resultProducedAfterCancellationIsNeverDelivered() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var delivered: Long? = null
        val job = launch(Dispatchers.Default) {
            delivered = LatencyMeasure.measure { started.countDown(); release.await(); 120L } // uninterruptible native call
        }
        started.await()
        job.cancel()
        release.countDown()
        job.join()
        assertTrue(job.isCancelled)
        assertNull(delivered)           // the caller can never publish this stale value
    }

    @Test fun failureAfterCancellationIsStillACancellation() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var delivered: Long? = null
        val job = launch(Dispatchers.Default) {
            delivered = LatencyMeasure.measure { started.countDown(); release.await(); throw IllegalStateException("late failure") }
        }
        started.await(); job.cancel(); release.countDown(); job.join()
        assertTrue(job.isCancelled)
        assertNull(delivered)
    }
}
