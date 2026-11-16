package com.pooy.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch

class LatestRunTrackerTest {
    private val active = HashMap<String, Boolean>() // only touched under the tracker's lock (and after joins)
    private val events = ArrayList<String>()
    private val tracker = LatestRunTracker<String> { k, a ->
        events.add("$k:$a")
        if (a) active[k] = true else active.remove(k)
    }

    @Test fun beginMarksActiveAndFinishClears() {
        val t = tracker.begin("a")
        assertTrue(tracker.isCurrent("a", t)); assertEquals(true, active["a"])
        assertTrue(tracker.finish("a", t))
        assertNull(active["a"]); assertEquals(0, tracker.activeCount)
    }

    @Test fun olderRunFinishingAfterANewerOneBeganTouchesNothing() {
        val old = tracker.begin("a")
        val newer = tracker.begin("a")
        assertFalse(tracker.finish("a", old))              // the old run cannot remove the newer token...
        assertTrue(tracker.isCurrent("a", newer))
        assertEquals(true, active["a"])                    // ...nor clear the newer run's loading indicator
        assertEquals(listOf("a:true", "a:true"), events)   // no "false" was emitted
        assertTrue(tracker.finish("a", newer))
        assertNull(active["a"])
    }

    @Test fun olderRunCannotPublishButNewerCan() {
        val old = tracker.begin("a"); val newer = tracker.begin("a")
        var published = ""
        assertFalse(tracker.publishIfCurrent("a", old) { published += "old" })
        assertTrue(tracker.publishIfCurrent("a", newer) { published += "new" })
        assertEquals("new", published)
    }

    @Test fun finishedRunCannotPublishAnymore() {
        val t = tracker.begin("a"); tracker.finish("a", t)
        assertFalse(tracker.publishIfCurrent("a", t) { fail("must not run") })
    }

    @Test fun keysAreIndependent() {
        val a = tracker.begin("a"); val b = tracker.begin("b")
        tracker.finish("a", a)
        assertNull(active["a"]); assertEquals(true, active["b"])
        assertTrue(tracker.isCurrent("b", b))
    }

    /** Forces the exact interleaving of the original bug: the old run finishes AFTER the newer run has begun. */
    @Test fun deterministicInterleavingWithRealThreads() {
        val oldBegan = CountDownLatch(1)
        val newerBegan = CountDownLatch(1)
        val oldFinished = CountDownLatch(1)
        var oldFinishResult: Boolean? = null
        var newerStillCurrent: Boolean? = null
        var indicatorAfterOldFinished: Boolean? = null

        val oldRun = Thread {
            val t = tracker.begin("a")
            oldBegan.countDown()
            newerBegan.await()                       // the newer test starts before the old one reaches its finally
            oldFinishResult = tracker.finish("a", t)
            oldFinished.countDown()
        }
        var newerToken: Any? = null
        val newerRun = Thread {
            oldBegan.await()
            newerToken = tracker.begin("a")
            newerBegan.countDown()
            oldFinished.await()
            newerStillCurrent = tracker.isCurrent("a", newerToken!!)
            indicatorAfterOldFinished = active["a"] // read after the old finish completed (ordered by the latch)
        }
        oldRun.start(); newerRun.start(); oldRun.join(); newerRun.join()

        assertEquals(false, oldFinishResult)
        assertEquals(true, newerStillCurrent)
        assertEquals(true, indicatorAfterOldFinished)
        assertTrue(tracker.finish("a", newerToken!!))
        assertEquals(0, tracker.activeCount)
    }

    @Test fun manyThreadsNeverLeaveStaleStateBehind() {
        val threads = List(8) {
            Thread {
                repeat(500) {
                    val t = tracker.begin("k")
                    tracker.publishIfCurrent("k", t) { }
                    tracker.finish("k", t)
                }
            }
        }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertEquals(0, tracker.activeCount)
        assertTrue(active.isEmpty())
        // every deactivation corresponds to a token that was current at that moment: never "false" while a newer run is active
        var live = false
        for (e in events) {
            if (e == "k:true") live = true else { assertTrue("deactivated while inactive", live); live = false }
        }
    }

    private fun fail(msg: String): Nothing = throw AssertionError(msg)
}
