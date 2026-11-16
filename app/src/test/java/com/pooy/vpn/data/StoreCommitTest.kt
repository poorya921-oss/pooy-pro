package com.pooy.vpn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Save-failure propagation: the in-memory state may only advance, and success may only be reported, after a real save. */
class StoreCommitTest {
    private class FakeStorage(private val result: SaveResult) : ProfileStorage {
        var saved: StoreData? = null
        var saves = 0
        override fun loadResult() = LoadResult(StoreData(), LoadStatus.Empty)
        override fun save(d: StoreData): SaveResult {
            saves++
            if (result.ok) saved = d
            return result
        }
    }

    private val p = Profile("1", "n", "r")

    @Test fun successfulSaveIsCommitted() {
        val st = FakeStorage(SaveResult.Success)
        val r = StoreCommit.commit(st, StoreData()) { it.copy(profiles = it.profiles + p) }
        assertTrue(r is CommitResult.Committed)
        assertEquals(listOf(p), (r as CommitResult.Committed).data.profiles)
        assertEquals(r.data, st.saved)
    }

    @Test fun failedSaveIsReportedAsFailureWithTheCause() {
        val cause = IOException("disk full")
        val st = FakeStorage(SaveResult.Failure(cause))
        val r = StoreCommit.commit(st, StoreData()) { it.copy(profiles = it.profiles + p) }
        assertTrue(r is CommitResult.Failed)
        assertEquals(cause, (r as CommitResult.Failed).error)
    }

    @Test fun unchangedStateIsNotWritten() {
        val st = FakeStorage(SaveResult.Failure(IOException("x")))
        val r = StoreCommit.commit(st, StoreData()) { it }
        assertTrue(r is CommitResult.Unchanged)
        assertEquals(0, st.saves)
    }

    @Test fun exceptionInTheChangeBlockIsAFailureNotACrash() {
        val st = FakeStorage(SaveResult.Success)
        val r = StoreCommit.commit(st, StoreData()) { throw IllegalStateException("bad block") }
        assertTrue(r is CommitResult.Failed)
        assertEquals(0, st.saves)
    }
}
