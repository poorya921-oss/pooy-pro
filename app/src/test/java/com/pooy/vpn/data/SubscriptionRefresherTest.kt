package com.pooy.vpn.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * Deterministic: downloads are gated with CompletableDeferred and the store runs on Dispatchers.Unconfined, so every
 * interleaving ("delete while downloading", "newer finishes first", ...) is forced explicitly, with no timing.
 */
class SubscriptionRefresherTest {

    private class FakeStorage : ProfileStorage {
        @Volatile var saved: StoreData? = null
        var saves = 0
        var fail = false
        var onSave: (() -> Unit)? = null
        override fun loadResult() = LoadResult(StoreData(), LoadStatus.Empty)
        override fun save(d: StoreData): SaveResult {
            saves++
            onSave?.invoke()
            if (fail) return SaveResult.Failure(IOException("disk"))
            saved = d
            return SaveResult.Success
        }
    }

    private val s1 = Subscription("s1", "S1", "https://a.example/x")
    private val s2 = Subscription("s2", "S2", "https://b.example/y")
    private fun old(id: String, sub: String? = "s1") = Profile(id, "name-$id", "vless://$id", sub)
    private fun fresh(id: String, sub: String = "s1") = Profile(id, "name-$id", "vless://$id", sub)

    private val initial = StoreData(
        profiles = listOf(old("old1"), old("old2"), Profile("manual", "M", "vless://manual"), old("other", "s2")),
        subscriptions = listOf(s1, s2),
        activeId = "old1",
    )

    private var data = initial
    private val storage = FakeStorage()
    private val mutex = Mutex()
    private val downloads = ArrayList<String>()
    private var download: suspend (Subscription) -> List<Profile> = { emptyList() }

    private var io: kotlin.coroutines.CoroutineContext = Dispatchers.Unconfined

    private suspend fun commitWithHook(block: (StoreData) -> StoreData, onResult: (CommitResult) -> Unit) =
        StoreUpdater.update(mutex, storage, io, { data }, { data = it }, block, onResult)

    /** Simulates any other change to the store (e.g. the user deleting a subscription). */
    private suspend fun commit(block: (StoreData) -> StoreData) = commitWithHook(block) { }

    private val refresher = SubscriptionRefresher(
        currentData = { data },
        download = { sub -> downloads.add(sub.id); download(sub) },
        update = { block, onResult -> commitWithHook(block, onResult) !is CommitResult.Failed },
    )

    private fun ids(subId: String? = null) = data.profiles.filter { subId == null || it.subId == subId }.map { it.id }

    // ---- normal -------------------------------------------------------------------------------------------

    @Test fun normalRefreshReplacesOnlyThatSubscriptionsProfiles() = runBlocking {
        download = { listOf(fresh("new1"), fresh("new2")) }
        assertEquals(RefreshOutcome.Applied, refresher.refresh(s1))
        assertEquals(listOf("manual", "other", "new1", "new2"), ids())      // manual + other subscription untouched
        assertEquals(listOf(s1, s2), data.subscriptions)
        assertEquals("new1", data.activeId)                                // old active vanished -> first new one
        assertEquals(1, storage.saves)
    }

    @Test fun activeProfileStaysSelectedWhenItStillExists() = runBlocking {
        download = { listOf(fresh("x"), Profile("renamed-id", "name-old1", "vless://old1", "s1")) }
        refresher.refresh(s1)
        assertEquals("renamed-id", data.activeId)                          // matched by raw config
    }

    @Test fun sequentialRefreshesBothApply() = runBlocking {
        download = { listOf(fresh("a")) }
        assertEquals(RefreshOutcome.Applied, refresher.refresh(s1))
        download = { listOf(fresh("b")) }
        assertEquals(RefreshOutcome.Applied, refresher.refresh(s1))
        assertTrue("b" in ids("s1") && "a" !in ids("s1"))
    }

    @Test fun differentSubscriptionsRefreshIndependentlyEvenWhenOverlapping() = runBlocking {
        val g1 = CompletableDeferred<List<Profile>>(); val g2 = CompletableDeferred<List<Profile>>()
        download = { if (it.id == "s1") g1.await() else g2.await() }
        val a = async(start = CoroutineStart.UNDISPATCHED) { refresher.refresh(s1) }
        val b = async(start = CoroutineStart.UNDISPATCHED) { refresher.refresh(s2) }
        g2.complete(listOf(fresh("n2", "s2"))); g1.complete(listOf(fresh("n1")))
        assertEquals(RefreshOutcome.Applied, a.await()); assertEquals(RefreshOutcome.Applied, b.await())
        assertTrue("n1" in ids("s1") && "n2" in ids("s2"))
    }

    // ---- deleted during refresh ---------------------------------------------------------------------------

    @Test fun subscriptionDeletedDuringRefreshIsNeverResurrected() = runBlocking {
        val gate = CompletableDeferred<List<Profile>>()
        download = { gate.await() }
        val r = async(start = CoroutineStart.UNDISPATCHED) { refresher.refresh(s1) }   // now waiting for the download
        commit { d -> d.copy(subscriptions = d.subscriptions.filter { it.id != "s1" }, profiles = d.profiles.filter { it.subId != "s1" }, activeId = "manual") }
        val savesAfterDelete = storage.saves
        gate.complete(listOf(fresh("new1")))

        assertEquals(RefreshOutcome.Stale, r.await())                      // and therefore NOT reported as success
        assertEquals(listOf(s2), data.subscriptions)
        assertFalse("no profile of the deleted subscription may return", data.profiles.any { it.subId == "s1" || it.id == "new1" })
        assertEquals(listOf("manual", "other"), ids())
        assertEquals("manual", data.activeId)
        assertEquals(savesAfterDelete, storage.saves)                      // the stale refresh wrote nothing
    }

    @Test fun subscriptionDeletedBeforeTheRefreshStartsIsNotEvenDownloaded() = runBlocking {
        commit { d -> d.copy(subscriptions = d.subscriptions.filter { it.id != "s1" }, profiles = d.profiles.filter { it.subId != "s1" }) }
        assertEquals(RefreshOutcome.Stale, refresher.refresh(s1))
        assertTrue(downloads.isEmpty())
    }

    // ---- reconfigured during refresh --------------------------------------------------------------------------

    @Test fun urlChangedDuringRefreshDiscardsTheOldContent() = runBlocking {
        val gate = CompletableDeferred<List<Profile>>()
        download = { gate.await() }
        val r = async(start = CoroutineStart.UNDISPATCHED) { refresher.refresh(s1) }
        val moved = s1.copy(url = "https://new.example/z")
        commit { d -> d.copy(subscriptions = d.subscriptions.map { if (it.id == "s1") moved else it }) }
        gate.complete(listOf(fresh("fromOldUrl")))

        assertEquals(RefreshOutcome.Stale, r.await())
        assertEquals(moved, data.subscriptions.first { it.id == "s1" })    // newer configuration kept
        assertEquals(listOf("old1", "old2"), ids("s1"))                   // existing profiles preserved
    }

    // ---- overlapping refreshes ------------------------------------------------------------------------------------

    private data class Overlap(
        val older: Deferred<RefreshOutcome>,
        val newer: Deferred<RefreshOutcome>,
        val gates: List<CompletableDeferred<List<Profile>>>,
    )

    /** Starts two refreshes of s1 that both wait in their download; gates[0] belongs to the older, gates[1] to the newer. */
    private fun CoroutineScope.overlapped(): Overlap {
        val gates = listOf(CompletableDeferred<List<Profile>>(), CompletableDeferred<List<Profile>>())
        var n = 0
        download = { gates[n++].await() }
        val older = async(start = CoroutineStart.UNDISPATCHED) { refresher.refresh(s1) }
        val newer = async(start = CoroutineStart.UNDISPATCHED) { refresher.refresh(s1) }
        return Overlap(older, newer, gates)
    }

    @Test fun olderDownloadFinishingAfterANewerOneIsDiscarded() = runBlocking {
        val (older, newer, gates) = overlapped()
        gates[1].complete(listOf(fresh("newer")))
        assertEquals(RefreshOutcome.Applied, newer.await())
        gates[0].complete(listOf(fresh("older")))
        assertEquals(RefreshOutcome.Stale, older.await())                  // must not overwrite the newer result
        assertEquals(listOf("newer"), ids("s1"))
    }

    @Test fun olderDownloadFinishingFirstIsLaterReplacedByTheNewerOne() = runBlocking {
        val (older, newer, gates) = overlapped()
        gates[0].complete(listOf(fresh("older")))
        assertEquals(RefreshOutcome.Applied, older.await())
        gates[1].complete(listOf(fresh("newer")))
        assertEquals(RefreshOutcome.Applied, newer.await())
        assertEquals(listOf("newer"), ids("s1"))
    }

    @Test fun aNewerRefreshThatFailsDoesNotBlockTheOlderOne() = runBlocking {
        val (older, newer, gates) = overlapped()
        gates[1].completeExceptionally(IOException("server down"))
        try { newer.await(); fail("expected failure") } catch (e: IOException) { /* expected */ }
        gates[0].complete(listOf(fresh("older")))
        assertEquals(RefreshOutcome.Applied, older.await())
        assertEquals(listOf("older"), ids("s1"))
    }

    // ---- failures keep the data ----------------------------------------------------------------------------------------

    @Test fun failedDownloadChangesNothing() = runBlocking {
        download = { throw IOException("HTTP 503") }
        try { refresher.refresh(s1); fail("expected IOException") } catch (e: IOException) { assertEquals("HTTP 503", e.message) }
        assertEquals(initial, data)
        assertEquals(0, storage.saves)
    }

    @Test fun emptyDownloadFailsWithoutDataLoss() = runBlocking {
        download = { emptyList() }
        try { refresher.refresh(s1); fail("expected IOException") } catch (e: IOException) { /* expected */ }
        assertEquals(initial, data)
    }

    @Test fun saveFailureIsReportedAsSuchAndKeepsTheOldProfiles() = runBlocking {
        storage.fail = true
        download = { listOf(fresh("new1")) }
        assertEquals(RefreshOutcome.SaveFailed, refresher.refresh(s1))
        assertEquals(initial, data)
    }

    // ---- cancellation ------------------------------------------------------------------------------------------------------

    @Test fun cancellationDuringTheDownloadPropagatesAndLeavesEverythingAlone() = runBlocking {
        val gate = CompletableDeferred<List<Profile>>()
        download = { gate.await() }
        val job = launch(start = CoroutineStart.UNDISPATCHED) { refresher.refresh(s1) }
        job.cancel(); job.join()
        assertTrue(job.isCancelled)
        assertEquals(initial, data); assertEquals(0, storage.saves)
        download = { listOf(fresh("again")) }                              // and a later refresh still works
        assertEquals(RefreshOutcome.Applied, refresher.refresh(s1))
    }

    // ---- merge ---------------------------------------------------------------------------------------------------------------

    @Test fun mergeKeepsAnActiveProfileThatBelongsToAnotherSource() {
        val d = initial.copy(activeId = "manual")
        val merged = SubscriptionRefresher.merge(d, s1, listOf(fresh("n")))
        assertEquals("manual", merged.activeId)
        assertEquals(listOf("manual", "other", "n"), merged.profiles.map { it.id })
    }

    // ---- transaction safety: the sequence only advances together with a successful save -------------------------

    @Test fun aNewerRefreshThatFailsToSaveDoesNotSupersedeTheOlderOne() = runBlocking {
        val (older, newer, gates) = overlapped()
        gates[1].complete(listOf(fresh("newer")))
        storage.fail = true                                                // the newer refresh's write fails
        assertEquals(RefreshOutcome.SaveFailed, newer.await())
        assertEquals(initial, data)                                        // nothing published by the failed one
        storage.fail = false
        gates[0].complete(listOf(fresh("older")))                          // the older download finishes afterwards
        assertEquals(RefreshOutcome.Applied, older.await())                // and is still eligible
        assertEquals(listOf("older"), ids("s1"))
    }

    @Test fun afterAFailedSaveALaterRefreshOfTheSameSubscriptionStillWorks() = runBlocking {
        download = { listOf(fresh("a")) }
        storage.fail = true
        assertEquals(RefreshOutcome.SaveFailed, refresher.refresh(s1))
        storage.fail = false
        download = { listOf(fresh("b")) }
        assertEquals(RefreshOutcome.Applied, refresher.refresh(s1))
        assertEquals(listOf("b"), ids("s1"))
    }

    @Test fun aSuccessfullySavedNewerRefreshRejectsTheOlderResult() = runBlocking {
        val (older, newer, gates) = overlapped()
        gates[1].complete(listOf(fresh("newer")))
        assertEquals(RefreshOutcome.Applied, newer.await())
        val savesAfterNewer = storage.saves
        gates[0].complete(listOf(fresh("older")))
        assertEquals(RefreshOutcome.Stale, older.await())
        assertEquals(listOf("newer"), ids("s1"))
        assertEquals(savesAfterNewer, storage.saves)                       // the stale one wrote nothing
    }

    @Test fun aNewerRefreshWhoseResultIsIdenticalToTheStoreStillSupersedesTheOlderOne() = runBlocking {
        // Nothing needs writing (Unchanged), but the newer result IS what is stored, so older content must not replace it.
        data = StoreData(
            profiles = listOf(Profile("manual", "M", "vless://manual"), old("other", "s2"), old("old1"), old("old2")),
            subscriptions = listOf(s1, s2),
            activeId = "old1",
        )
        val before = data
        val (older, newer, gates) = overlapped()
        gates[1].complete(listOf(old("old1"), old("old2")))
        assertEquals(RefreshOutcome.Applied, newer.await())
        assertEquals(0, storage.saves)
        gates[0].complete(listOf(fresh("older")))
        assertEquals(RefreshOutcome.Stale, older.await())
        assertEquals(before, data)
    }

    @Test fun anOlderRefreshThatCommittedFirstDoesNotBlockTheNewerOne() = runBlocking {
        val (older, newer, gates) = overlapped()
        gates[0].complete(listOf(fresh("older")))
        assertEquals(RefreshOutcome.Applied, older.await())
        gates[1].complete(listOf(fresh("newer")))
        assertEquals(RefreshOutcome.Applied, newer.await())
        assertEquals(listOf("newer"), ids("s1"))
    }

    @Test fun aReconfiguredSubscriptionOnlyAcceptsContentDownloadedFromItsNewUrl() = runBlocking {
        val gates = listOf(CompletableDeferred<List<Profile>>(), CompletableDeferred<List<Profile>>())
        var n = 0
        download = { gates[n++].await() }
        val olderSub = s1
        val older = async(start = CoroutineStart.UNDISPATCHED) { refresher.refresh(olderSub) }       // seq 1, old URL
        val moved = s1.copy(url = "https://moved.example/z")
        commit { d -> d.copy(subscriptions = d.subscriptions.map { if (it.id == "s1") moved else it }) }
        val newer = async(start = CoroutineStart.UNDISPATCHED) { refresher.refresh(moved) }          // seq 2, new URL
        gates[1].complete(listOf(fresh("fromNewUrl")))
        assertEquals(RefreshOutcome.Applied, newer.await())
        gates[0].complete(listOf(fresh("fromOldUrl")))
        assertEquals(RefreshOutcome.Stale, older.await())                                            // wrong URL
        assertEquals(listOf("fromNewUrl"), ids("s1"))
    }

    // ---- cancellation during persistence ---------------------------------------------------------------------------

    @Test fun cancellationDuringASuccessfulWriteKeepsDiskMemoryAndTheSequenceConsistent() = runBlocking {
        io = Dispatchers.IO
        val gate0 = CompletableDeferred<List<Profile>>()
        val calls = AtomicInteger(0)
        download = { if (calls.getAndIncrement() == 0) gate0.await() else listOf(fresh("newer")) }
        val older = async(start = CoroutineStart.UNDISPATCHED) { refresher.refresh(s1) }              // seq 1 waits
        val saveStarted = CountDownLatch(1); val release = CountDownLatch(1)
        storage.onSave = { saveStarted.countDown(); release.await() }
        val newer = launch(Dispatchers.Default) { refresher.refresh(s1) }                              // seq 2 persists
        saveStarted.await()
        newer.cancel()                                                                                 // cancelled mid-write
        release.countDown()
        newer.join()

        assertTrue(newer.isCancelled)
        assertEquals(listOf("newer"), ids("s1"))                                                       // memory is not stale
        assertEquals(data, storage.saved)                                                              // and equals disk
        storage.onSave = null
        gate0.complete(listOf(fresh("older")))
        assertEquals("the saved newer refresh must still supersede the older one", RefreshOutcome.Stale, older.await())
        assertEquals(listOf("newer"), ids("s1"))
    }

    @Test fun cancellationDuringAFailingWriteRegistersNothingAndPublishesNothing() = runBlocking {
        io = Dispatchers.IO
        val gate0 = CompletableDeferred<List<Profile>>()
        val calls = AtomicInteger(0)
        download = { if (calls.getAndIncrement() == 0) gate0.await() else listOf(fresh("newer")) }
        val older = async(start = CoroutineStart.UNDISPATCHED) { refresher.refresh(s1) }
        val saveStarted = CountDownLatch(1); val release = CountDownLatch(1)
        storage.fail = true
        storage.onSave = { saveStarted.countDown(); release.await() }
        val newer = launch(Dispatchers.Default) { refresher.refresh(s1) }
        saveStarted.await(); newer.cancel(); release.countDown(); newer.join()

        assertTrue(newer.isCancelled)
        assertEquals(initial, data)                                                                    // nothing published
        storage.fail = false; storage.onSave = null
        gate0.complete(listOf(fresh("older")))
        assertEquals(RefreshOutcome.Applied, older.await())                                            // not superseded
        assertEquals(listOf("older"), ids("s1"))
    }
}
