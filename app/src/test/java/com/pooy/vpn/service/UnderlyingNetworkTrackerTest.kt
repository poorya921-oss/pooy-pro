package com.pooy.vpn.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Collections

class UnderlyingNetworkTrackerTest {
    private val applied = ArrayList<String?>()
    private val tracker = UnderlyingNetworkTracker<String> { applied.add(it) }

    @Test fun selectsTheAvailableNetwork() {
        tracker.onAvailable("wifi")
        assertEquals("wifi", tracker.current)
        assertEquals(listOf<String?>("wifi"), applied)
    }

    @Test fun lossOfTheSelectedNetworkClearsIt() {
        tracker.onAvailable("wifi"); tracker.onLost("wifi")
        assertNull(tracker.current)
        assertEquals(listOf<String?>("wifi", null), applied)
    }

    @Test fun lateLossOfTheOldNetworkDoesNotClearTheNewOne() {
        // handover: new network becomes available first, the old one's "lost" arrives afterwards
        tracker.onAvailable("wifi")
        tracker.onAvailable("mobile")
        tracker.onLost("wifi")
        assertEquals("mobile", tracker.current)
        assertEquals(listOf<String?>("wifi", "mobile"), applied)   // no null in between
    }

    @Test fun normalOrderHandoverClearsThenSelects() {
        tracker.onAvailable("wifi"); tracker.onLost("wifi"); tracker.onAvailable("mobile")
        assertEquals("mobile", tracker.current)
        assertEquals(listOf<String?>("wifi", null, "mobile"), applied)
    }

    @Test fun lossOfANetworkThatWasNeverSelectedIsIgnored() {
        tracker.onAvailable("wifi"); tracker.onLost("other")
        assertEquals("wifi", tracker.current)
        assertEquals(1, applied.size)
    }

    @Test fun duplicateLossIsAppliedOnce() {
        tracker.onAvailable("wifi"); tracker.onLost("wifi"); tracker.onLost("wifi")
        assertEquals(listOf<String?>("wifi", null), applied)
    }

    @Test fun repeatedAvailableForTheSameNetworkIsHarmless() {
        tracker.onAvailable("wifi"); tracker.onAvailable("wifi"); tracker.onLost("wifi")
        assertNull(tracker.current)
        assertEquals("wifi", applied.first()); assertNull(applied.last())
    }

    @Test fun releaseClearsOnceAndIgnoresLaterCallbacks() {
        tracker.onAvailable("wifi")
        tracker.release()
        assertEquals(listOf<String?>("wifi", null), applied)
        tracker.onAvailable("mobile"); tracker.onLost("mobile"); tracker.release()
        assertEquals(2, applied.size)
        assertNull(tracker.current)
    }

    @Test fun releaseWithoutSelectionDoesNotTouchTheSystem() {
        tracker.release()
        assertEquals(0, applied.size)
    }

    @Test fun concurrentCallbacksKeepTheStateConsistent() {
        val log = Collections.synchronizedList(ArrayList<String?>())
        val t = UnderlyingNetworkTracker<Int> { log.add(it?.toString()) }
        val threads = List(4) { id ->
            Thread { repeat(500) { i -> if (i % 2 == 0) t.onAvailable(id * 1000 + i) else t.onLost(id * 1000 + i - 1) } }
        }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        // The last applied value always matches the tracker's selection.
        assertEquals(t.current?.toString(), log.lastOrNull())
    }
}
