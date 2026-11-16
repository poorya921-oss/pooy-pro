package com.pooy.vpn.data

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch

/** Persist + publish is one non-cancellable step; cancellation surfaces only afterwards. */
class StoreUpdaterTest {

    private class FakeStorage : ProfileStorage {
        var saved: StoreData? = null
        var saves = 0
        var fail = false
        var onSave: (() -> Unit)? = null
        override fun loadResult() = LoadResult(StoreData(), LoadStatus.Empty)
        override fun save(d: StoreData): SaveResult {
            saves++
            onSave?.invoke()
            if (fail) return SaveResult.Failure(IOException("disk full"))
            saved = d
            return SaveResult.Success
        }
    }

    private val p1 = Profile("1", "one", "vless://1")
    private val p2 = Profile("2", "two", "vless://2")

    private val storage = FakeStorage()
    private val mutex = Mutex()
    private var memory = StoreData()
    private var applyCalls = 0

    private suspend fun update(io: kotlin.coroutines.CoroutineContext = Dispatchers.Unconfined, block: (StoreData) -> StoreData) =
        StoreUpdater.update(mutex, storage, io, { memory }, { memory = it; applyCalls++ }, block)

    @Test fun successfulCommitIsSavedThenPublishedAndReturned() = runBlocking {
        val r = update { it.copy(profiles = it.profiles + p1) }
        assertTrue(r is CommitResult.Committed)
        assertEquals(listOf(p1), memory.profiles)
        assertEquals(memory, storage.saved)
    }

    @Test fun unchangedStateIsNeitherSavedNorPublished() = runBlocking {
        val r = update { it }
        assertTrue(r is CommitResult.Unchanged)
        assertEquals(0, storage.saves); assertEquals(0, applyCalls)
    }

    @Test fun failedSaveIsNeverPublished() = runBlocking {
        storage.fail = true
        val r = update { it.copy(profiles = it.profiles + p1) }
        assertTrue(r is CommitResult.Failed)
        assertEquals(StoreData(), memory); assertEquals(0, applyCalls)
    }

    @Test fun exceptionInTheChangeBlockIsAFailureWithNothingPublished() = runBlocking {
        val r = update { throw IllegalStateException("bad block") }
        assertTrue(r is CommitResult.Failed)
        assertEquals(0, storage.saves); assertEquals(0, applyCalls)
    }

    @Test fun cancellationDuringTheCommitStillPublishesTheSavedStateAndThenPropagates() = runBlocking {
        val saveStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        storage.onSave = { saveStarted.countDown(); release.await() }   // blocks on the IO thread, mid-commit
        var afterUpdate = false
        val job = launch(Dispatchers.Default) {
            update(Dispatchers.IO) { it.copy(profiles = it.profiles + p1) }
            afterUpdate = true                                          // where a success message would be shown
        }
        saveStarted.await()
        job.cancel()                                                   // cancel exactly while the data is being written
        release.countDown()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(listOf(p1), storage.saved?.profiles)              // it did reach the disk...
        assertEquals(listOf(p1), memory.profiles)                      // ...and memory is NOT stale
        assertEquals(1, applyCalls)
        assertFalse("no code after the update (e.g. a success message) may run", afterUpdate)
    }

    @Test fun cancellationDuringAFailingCommitPublishesNothingAndStillPropagates() = runBlocking {
        val saveStarted = CountDownLatch(1); val release = CountDownLatch(1)
        storage.fail = true
        storage.onSave = { saveStarted.countDown(); release.await() }
        val job = launch(Dispatchers.Default) { update(Dispatchers.IO) { it.copy(profiles = it.profiles + p1) } }
        saveStarted.await(); job.cancel(); release.countDown(); job.join()
        assertTrue(job.isCancelled)
        assertEquals(StoreData(), memory); assertEquals(0, applyCalls)
    }

    @Test fun cancellationWhileWaitingForTheLockDoesNothingAndDoesNotLeakTheLock() = runBlocking {
        mutex.lock()                                                   // another update is in progress
        val job = launch(start = CoroutineStart.UNDISPATCHED) { update { it.copy(profiles = it.profiles + p1) } }
        job.cancel(); job.join()                                       // suspended on the lock; cancelled there
        assertTrue(job.isCancelled)
        assertEquals(0, storage.saves); assertEquals(0, applyCalls)
        mutex.unlock()
        assertTrue(update { it.copy(profiles = it.profiles + p2) } is CommitResult.Committed)   // lock is usable
        assertEquals(listOf(p2), memory.profiles)
    }

    @Test fun anUpdateAfterACancelledCommitBuildsOnTheCommittedState() = runBlocking {
        val saveStarted = CountDownLatch(1); val release = CountDownLatch(1)
        storage.onSave = { saveStarted.countDown(); release.await() }
        val job = launch(Dispatchers.Default) { update(Dispatchers.IO) { it.copy(profiles = it.profiles + p1) } }
        saveStarted.await(); job.cancel(); release.countDown(); job.join()
        storage.onSave = null
        update { it.copy(profiles = it.profiles + p2) }
        assertEquals(listOf(p1, p2), memory.profiles)                  // p1 is not lost by the next write
        assertEquals(listOf(p1, p2), storage.saved?.profiles)
    }

    @Test fun nothingIsPublishedBeforeTheSaveSucceeded() = runBlocking {
        var memoryDuringSave: StoreData? = null
        storage.onSave = { memoryDuringSave = memory }
        update { it.copy(profiles = it.profiles + p1) }
        assertEquals(StoreData(), memoryDuringSave)
    }

    // ---- transactional hook ---------------------------------------------------------------------------------

    private suspend fun updateWithHook(
        io: kotlin.coroutines.CoroutineContext = Dispatchers.Unconfined,
        onResult: (CommitResult) -> Unit,
        block: (StoreData) -> StoreData,
    ) = StoreUpdater.update(mutex, storage, io, { memory }, { memory = it; applyCalls++ }, block, onResult)

    @Test fun hookRunsAfterApplyAndWhileTheLockIsStillHeld() = runBlocking {
        val order = ArrayList<String>()
        var lockedInHook: Boolean? = null
        StoreUpdater.update(
            mutex, storage, Dispatchers.Unconfined, { memory }, { memory = it; order.add("apply") },
            { it.copy(profiles = it.profiles + p1) },
            { r -> order.add("hook:" + r.javaClass.simpleName); lockedInHook = mutex.isLocked },
        )
        assertEquals(listOf("apply", "hook:Committed"), order)
        assertEquals(true, lockedInHook)
        assertFalse(mutex.isLocked)
    }

    @Test fun hookSeesFailedAndUnchangedResultsToo() = runBlocking {
        val seen = ArrayList<String>()
        storage.fail = true
        updateWithHook(onResult = { seen.add(it.javaClass.simpleName) }) { it.copy(profiles = it.profiles + p1) }
        storage.fail = false
        updateWithHook(onResult = { seen.add(it.javaClass.simpleName) }) { it }
        assertEquals(listOf("Failed", "Unchanged"), seen)
    }

    @Test fun hookStillRunsWhenCancelledDuringTheCommit() = runBlocking {
        val saveStarted = CountDownLatch(1); val release = CountDownLatch(1)
        storage.onSave = { saveStarted.countDown(); release.await() }
        var hookRan = false
        val job = launch(Dispatchers.Default) {
            updateWithHook(Dispatchers.IO, { hookRan = it is CommitResult.Committed }) { it.copy(profiles = it.profiles + p1) }
        }
        saveStarted.await(); job.cancel(); release.countDown(); job.join()
        assertTrue(job.isCancelled)
        assertTrue("bookkeeping must be atomic with the save, even if cancelled", hookRan)
    }

    @Test fun hookDoesNotRunWhenCancelledWhileWaitingForTheLock() = runBlocking {
        mutex.lock()
        var hookRan = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) { updateWithHook(onResult = { hookRan = true }) { it.copy(profiles = it.profiles + p1) } }
        job.cancel(); job.join()
        assertTrue(job.isCancelled); assertFalse(hookRan)
        mutex.unlock()
    }
}
