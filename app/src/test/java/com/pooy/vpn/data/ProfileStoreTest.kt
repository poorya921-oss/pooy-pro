package com.pooy.vpn.data

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class ProfileStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = AesGcmBlobCipher { key }

    private class FailingFs(private val failFor: (File) -> Boolean) : StoreFs {
        var attempts = 0
        override fun replace(src: File, dst: File) {
            attempts++
            if (failFor(dst)) throw IOException("injected failure for ${dst.name}")
            DefaultStoreFs.replace(src, dst)
        }
    }

    private fun store(fs: StoreFs = DefaultStoreFs, c: BlobCipher = cipher) = ProfileStore(folder.root, c, fs) { }

    private fun sample(n: Int) = StoreData(
        profiles = List(n) { Profile("id$it", "name$it", "vless://x$it@h:443") },
        subscriptions = listOf(Subscription("s1", "sub", "https://example.com/s")),
        activeId = "id0",
    )

    private fun primary() = File(folder.root, "profiles.dat")
    private fun backup() = File(folder.root, "profiles.dat.bak")
    private fun bakTmp() = File(folder.root, "profiles.dat.bak.tmp")
    private fun tmpFile() = File(folder.root, "profiles.dat.tmp")
    private fun blobOf(json: String) = cipher.encrypt(json.toByteArray(Charsets.UTF_8))
    private fun bad() = folder.root.listFiles { f -> f.name.contains(".bad-") }!!.toList()

    @Test fun emptyStoreLoadsEmpty() {
        val r = store().loadResult()
        assertEquals(LoadStatus.Empty, r.status)
        assertEquals(StoreData(), r.data)
    }

    @Test fun roundTrip() {
        val s = store()
        assertTrue(s.save(sample(3)).ok)
        val r = s.loadResult()
        assertEquals(LoadStatus.Ok, r.status)
        assertEquals(sample(3), r.data)
    }

    @Test fun legacyFormatStillLoads() {
        primary().writeBytes(
            blobOf("""{"v":1,"profiles":[{"id":"a","name":"n","raw":"r","sub":"s"}],"subs":[{"id":"s","name":"x","url":"https://u"}],"active":"a"}"""),
        )
        val d = store().load()
        assertEquals(Profile("a", "n", "r", "s"), d.profiles.single())
        assertEquals("a", d.activeId)
    }

    @Test fun secondSaveKeepsPreviousVersionAsBackup() {
        val s = store()
        s.save(sample(1)); s.save(sample(2))
        primary().delete()
        val r = s.loadResult()
        assertEquals(LoadStatus.Recovered, r.status)
        assertEquals(sample(1), r.data)
    }

    @Test fun failedReplaceKeepsOriginalAndReportsFailure() {
        store().save(sample(1))
        val before = primary().readBytes()
        val fs = FailingFs { it.name == "profiles.dat" }
        val res = store(fs).save(sample(5))
        assertFalse(res.ok)
        assertTrue(res is SaveResult.Failure)
        assertArrayEquals(before, primary().readBytes())      // never deleted or touched
        assertFalse(tmpFile().exists())                        // temp cleaned up
        assertEquals(sample(1), store().load())
    }

    @Test fun failedReplaceOnFirstSaveLeavesNothingBehind() {
        val res = store(FailingFs { true }).save(sample(1))
        assertFalse(res.ok)
        assertFalse(primary().exists())
        assertFalse(tmpFile().exists()) // nothing existed before, so our own unsaved attempt is not worth keeping
        assertEquals(LoadStatus.Empty, store().loadResult().status)
    }

    private fun idsIn(f: File): List<String>? = try {
        JSONObject(String(cipher.decrypt(f.readBytes()), Charsets.UTF_8)).getJSONArray("profiles")
            .let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }
    } catch (e: Exception) { null }

    @Test fun oldTempFileThatIsTheOnlyCopySurvivesAFailedSave() {
        // Crash left the only complete copy in .tmp (primary and backup are gone).
        tmpFile().writeBytes(blobOf("""{"v":1,"profiles":[{"id":"precious","name":"n","raw":"r"}],"subs":[]}"""))
        assertEquals(LoadStatus.Recovered, store().loadResult().status)
        // A save that then fails at the final rename must not destroy it.
        assertFalse(store(FailingFs { true }).save(sample(2)).ok)
        val everything = folder.root.listFiles()!!.filter { it.isFile }
        assertTrue(everything.any { idsIn(it) == listOf("precious") })
    }

    @Test fun oldTempFileIsPreservedEvenWhenTheSaveSucceeds() {
        tmpFile().writeBytes(blobOf("""{"v":1,"profiles":[{"id":"precious","name":"n","raw":"r"}],"subs":[]}"""))
        assertTrue(store().save(sample(2)).ok)
        assertEquals(sample(2), store().load())
        assertTrue(bad().any { idsIn(it) == listOf("precious") })
    }

    @Test fun validTempFileWithDifferentDataIsPreservedEvenWhenPrimaryAndBackupAreValid() {
        val s = store()
        s.save(sample(1)); s.save(sample(2))
        tmpFile().writeBytes(blobOf("""{"v":1,"profiles":[{"id":"interrupted","name":"n","raw":"r"}],"subs":[]}"""))
        assertTrue(s.save(sample(3)).ok)
        assertTrue(bad().any { idsIn(it) == listOf("interrupted") })
    }

    @Test fun redundantTempFileIsNotKept() {
        val s = store()
        s.save(sample(2))
        // an interrupted write whose content is exactly what the primary already holds
        tmpFile().writeBytes(primary().readBytes())
        assertTrue(s.save(sample(3)).ok)
        assertTrue(bad().isEmpty())
    }

    @Test fun corruptTempFileIsNotKept() {
        val s = store()
        s.save(sample(2))
        tmpFile().writeBytes(ByteArray(40) { 9 })
        assertTrue(s.save(sample(3)).ok)
        assertTrue(bad().isEmpty())
    }

    private class FailingEncrypt(private val real: BlobCipher) : BlobCipher {
        override fun encrypt(plain: ByteArray): ByteArray = throw java.security.GeneralSecurityException("keystore down")
        override fun decrypt(blob: ByteArray): ByteArray = real.decrypt(blob)
    }

    @Test fun saveThatFailsBeforeWritingNeverTouchesAnExistingTempFile() {
        val s = store()
        s.save(sample(1))
        val interrupted = blobOf("""{"v":1,"profiles":[{"id":"interrupted","name":"n","raw":"r"}],"subs":[]}""")
        tmpFile().writeBytes(interrupted)
        val res = store(c = FailingEncrypt(cipher)).save(sample(2))
        assertFalse(res.ok)
        assertArrayEquals(interrupted, tmpFile().readBytes())   // primary is valid, but the old temp is not ours to delete
        assertEquals(sample(1), s.load())
    }

    @Test fun backupReplacementFailureKeepsTheOldBackupAndTheVerifiedStagingCopy() {
        val s = store()
        s.save(sample(1)); s.save(sample(2))
        val backupBefore = backup().readBytes()
        assertTrue(store(FailingFs { it.name == "profiles.dat.bak" }).save(sample(3)).ok)
        assertArrayEquals(backupBefore, backup().readBytes())           // previous valid backup preserved
        assertEquals(listOf("id0", "id1"), idsIn(bakTmp()))              // verified staging copy of the old primary kept
    }

    @Test fun validQuarantineCopiesAreNeverPruned() {
        val s = store()
        repeat(8) { i ->
            File(folder.root, "profiles.dat.bad-${1000 + i}").writeBytes(
                blobOf("""{"v":1,"profiles":[{"id":"keep$i","name":"n","raw":"r"}],"subs":[]}"""),
            )
        }
        primary().writeBytes(ByteArray(40) { 4 }) // forces a new quarantine copy and a prune pass
        assertTrue(s.save(sample(1)).ok)
        val keep = bad().count { idsIn(it)?.firstOrNull()?.startsWith("keep") == true }
        assertEquals(8, keep)
    }

    @Test fun onlyConfirmedCorruptCopiesArePrunedAndOnlyBeyondTheNewestFive() {
        val s = store()
        repeat(9) { i -> File(folder.root, "profiles.dat.bad-${2000 + i}").also { it.writeBytes(ByteArray(40) { (i + 1).toByte() }); it.setLastModified(1_000_000L + i) } }
        primary().writeBytes(ByteArray(40) { 77 })
        assertTrue(s.save(sample(1)).ok)
        val corrupt = bad().filter { idsIn(it) == null }
        assertTrue("corrupt copies left: ${corrupt.size}", corrupt.size <= 5)
    }

    @Test fun copiesWeCannotClassifyAreNeverPruned() {
        // Authenticated by our key but with a structure this version rejects (e.g. written by a newer version).
        val s = store()
        val newer = blobOf("""{"v":2,"profiles":[{"id":"future"}],"subs":[]}""")
        repeat(8) { i -> File(folder.root, "profiles.dat.bad-${3000 + i}").writeBytes(newer + ByteArray(0)) }
        primary().writeBytes(ByteArray(40) { 55 })
        assertTrue(s.save(sample(1)).ok)
        assertEquals(8, bad().count { it.readBytes().contentEquals(newer) })
    }

    @Test fun quarantineCopyIsVerifiedAndIdenticalCopiesAreNotDuplicated() {
        val s = store()
        primary().writeBytes(ByteArray(40) { 6 })
        s.save(sample(1))
        // corrupt the primary again with identical bytes: still only one copy of those bytes
        primary().writeBytes(ByteArray(40) { 6 })
        s.save(sample(2))
        assertEquals(1, bad().count { it.readBytes().contentEquals(ByteArray(40) { 6 }) })
    }

    // ---- strict validation ------------------------------------------------------------------------

    private fun rejects(json: String) {
        primary().writeBytes(blobOf(json))
        assertEquals("should reject: $json", LoadStatus.Unreadable, store().loadResult().status)
        primary().delete()
    }

    @Test fun missingOrMistypedArraysAreRejectedNotTurnedIntoAnEmptyStore() {
        rejects("""{"v":1,"subs":[]}""")
        rejects("""{"v":1,"profiles":[]}""")
        rejects("""{"v":1,"profiles":{},"subs":[]}""")
        rejects("""{"v":1,"profiles":[],"subs":"x"}""")
        rejects("""{"v":1,"profiles":null,"subs":[]}""")
    }

    @Test fun malformedRecordsAreRejected() {
        rejects("""{"v":1,"profiles":["x"],"subs":[]}""")
        rejects("""{"v":1,"profiles":[{"id":1,"name":"n","raw":"r"}],"subs":[]}""")
        rejects("""{"v":1,"profiles":[{"id":"a","name":null,"raw":"r"}],"subs":[]}""")
        rejects("""{"v":1,"profiles":[{"id":"a","name":"n","raw":5}],"subs":[]}""")
        rejects("""{"v":1,"profiles":[{"id":"a","name":"n","raw":"r","sub":7}],"subs":[]}""")
        rejects("""{"v":1,"profiles":[],"subs":[[]]}""")
        rejects("""{"v":1,"profiles":[],"subs":[{"id":"s","name":"n","url":null}]}""")
        rejects("""{"v":1,"profiles":[],"subs":[{"id":"","name":"n","url":"u"}]}""")
        rejects("""{"v":1,"profiles":[],"subs":[],"active":5}""")
        rejects("""{"v":"1","profiles":[],"subs":[]}""")
    }

    @Test fun duplicateIdsAreRejected() {
        rejects("""{"v":1,"profiles":[{"id":"a","name":"n","raw":"r"},{"id":"a","name":"m","raw":"r"}],"subs":[]}""")
        rejects("""{"v":1,"profiles":[],"subs":[{"id":"s","name":"n","url":"u"},{"id":"s","name":"m","url":"v"}]}""")
    }

    @Test fun duplicateIdsInPrimaryFallBackToBackup() {
        val s = store()
        s.save(sample(1)); s.save(sample(2))
        primary().writeBytes(blobOf("""{"v":1,"profiles":[{"id":"a","name":"n","raw":"r"},{"id":"a","name":"m","raw":"r"}],"subs":[]}"""))
        assertEquals(sample(1), s.load())
    }

    @Test fun danglingReferencesAreRepairedNotFatal() {
        primary().writeBytes(
            blobOf("""{"v":1,"profiles":[{"id":"a","name":"n","raw":"r","sub":"gone"},{"id":"b","name":"n","raw":"r","sub":"s"}],"subs":[{"id":"s","name":"x","url":"https://u"}],"active":"missing"}"""),
        )
        val d = store().load()
        assertNull(d.activeId)
        assertNull(d.profiles.first { it.id == "a" }.subId)
        assertEquals("s", d.profiles.first { it.id == "b" }.subId)
        assertEquals(2, d.profiles.size)
    }

    @Test fun validVersionOneDataWithoutOptionalFieldsStillLoads() {
        primary().writeBytes(blobOf("""{"v":1,"profiles":[{"id":"a","name":"","raw":"r"}],"subs":[]}"""))
        val r = store().loadResult()
        assertEquals(LoadStatus.Ok, r.status)
        assertEquals("", r.data.profiles.single().name)
        assertNull(r.data.activeId)
    }

    @Test fun unknownExtraFieldsAreTolerated() {
        primary().writeBytes(blobOf("""{"v":1,"profiles":[],"subs":[],"extra":{"x":1}}"""))
        assertEquals(LoadStatus.Ok, store().loadResult().status)
    }

    @Test fun emptyStoreRoundTrips() {
        val s = store()
        assertTrue(s.save(StoreData()).ok)
        assertEquals(LoadStatus.Ok, s.loadResult().status)
    }
    @Test fun failedSaveKeepsTempWhenPrimaryIsCorruptButBackupDoesNotGetOverwritten() {
        val s = store()
        s.save(sample(1)); s.save(sample(2))
        val backupBefore = backup().readBytes()
        primary().writeBytes(ByteArray(40) { 5 })
        assertFalse(store(FailingFs { it.name == "profiles.dat" }).save(sample(3)).ok)
        assertArrayEquals(backupBefore, backup().readBytes())     // valid backup untouched
        assertEquals(sample(1), s.load())                         // priority: backup before the unacknowledged temp
        assertTrue(tmpFile().exists())                            // still available as a last resort
    }

    @Test fun corruptTempAndCorruptBackupWithValidPrimaryAreHarmless() {
        val s = store()
        s.save(sample(2))
        backup().writeBytes(ByteArray(30) { 1 })
        tmpFile().writeBytes(ByteArray(30) { 2 })
        assertEquals(LoadStatus.Ok, s.loadResult().status)
        assertTrue(s.save(sample(3)).ok)
        assertEquals(sample(3), s.load())
        // backup was repaired from the valid primary rather than staying corrupt
        primary().delete()
        assertEquals(sample(2), s.load())
    }

    @Test fun allThreeCorruptIsUnreadableAndSurvivesAFailedSave() {
        primary().writeBytes(ByteArray(30) { 1 }); backup().writeBytes(ByteArray(30) { 2 }); tmpFile().writeBytes(ByteArray(30) { 3 })
        assertEquals(LoadStatus.Unreadable, store().loadResult().status)
        assertFalse(store(FailingFs { true }).save(sample(1)).ok)
        assertArrayEquals(ByteArray(30) { 1 }, primary().readBytes())
        assertArrayEquals(ByteArray(30) { 2 }, backup().readBytes())
    }

    @Test fun interruptedWriteLeavesPreviousVersion() {
        // Simulates a crash after the temp file was written but before the rename: temp is torn.
        val s = store()
        s.save(sample(1))
        tmpFile().writeBytes(blobOf("""{"v":1,"profiles":[],"subs":[]}""").copyOf(25))
        assertEquals(sample(1), s.load())
    }

    @Test fun backupRefreshFailureDoesNotBlockSave() {
        store().save(sample(1))
        val res = store(FailingFs { it.name == "profiles.dat.bak" }).save(sample(2))
        assertTrue(res.ok)
        assertEquals(sample(2), store().load())
    }

    @Test fun bitFlippedPrimaryRecoversFromBackup() {
        val s = store()
        s.save(sample(1)); s.save(sample(2))
        val b = primary().readBytes(); b[b.size / 2] = (b[b.size / 2].toInt() xor 0x01).toByte(); primary().writeBytes(b)
        val r = s.loadResult()
        assertEquals(LoadStatus.Recovered, r.status)
        assertEquals(sample(1), r.data)
    }

    @Test fun truncatedPrimaryRecoversFromBackup() {
        val s = store()
        s.save(sample(1)); s.save(sample(2))
        primary().writeBytes(primary().readBytes().copyOf(20))
        assertEquals(sample(1), s.load())
    }

    @Test fun leftoverTempFileIsIgnoredWhenPrimaryIsValid() {
        val s = store()
        s.save(sample(2))
        tmpFile().writeBytes(blobOf("""{"v":1,"profiles":[],"subs":[]}"""))
        assertEquals(sample(2), s.load())
    }

    @Test fun tornTempFileIsIgnored() {
        val s = store()
        s.save(sample(2))
        tmpFile().writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(sample(2), s.load())
    }

    @Test fun completeTempFileIsLastResort() {
        tmpFile().writeBytes(blobOf("""{"v":1,"profiles":[{"id":"t","name":"n","raw":"r"}],"subs":[]}"""))
        val r = store().loadResult()
        assertEquals(LoadStatus.Recovered, r.status)
        assertEquals("t", r.data.profiles.single().id)
    }

    @Test fun everythingCorruptIsReportedAndNothingIsDeleted() {
        val s = store()
        s.save(sample(1)); s.save(sample(2))
        primary().writeBytes(ByteArray(64) { 7 })
        backup().writeBytes(ByteArray(64) { 9 })
        val r = s.loadResult()
        assertEquals(LoadStatus.Unreadable, r.status)
        assertTrue(primary().exists() && backup().exists())
        assertArrayEquals(ByteArray(64) { 7 }, primary().readBytes())
    }

    @Test fun savingAfterUnreadableKeepsACopyOfTheOldBytes() {
        val s = store()
        val junk = ByteArray(64) { 7 }
        primary().writeBytes(junk)
        assertEquals(LoadStatus.Unreadable, s.loadResult().status)
        assertTrue(s.save(sample(1)).ok)
        assertEquals(LoadStatus.Ok, s.loadResult().status)
        assertTrue(bad().any { it.readBytes().contentEquals(junk) })
    }

    @Test fun wrongKeyIsUnreadableNotDeleted() {
        store().save(sample(2))
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val r = store(c = AesGcmBlobCipher { other }).loadResult()
        assertEquals(LoadStatus.Unreadable, r.status)
        assertTrue(primary().exists())
    }

    @Test fun structurallyInvalidFilesAreRejected() {
        val bads = listOf(
            """{"v":2,"profiles":[],"subs":[]}""",
            """{"profiles":[],"subs":[]}""",
            """{"v":1,"profiles":[{"name":"n","raw":"r"}],"subs":[]}""",
            """{"v":1,"profiles":[{"id":"","name":"n","raw":"r"}],"subs":[]}""",
            """{"v":1,"profiles":[],"subs":[{"id":"s","name":"n"}]}""",
            """not json""",
        )
        for (j in bads) {
            primary().writeBytes(blobOf(j))
            assertEquals("should reject: $j", LoadStatus.Unreadable, store().loadResult().status)
        }
    }

    @Test fun invalidPrimaryFallsBackToValidBackup() {
        val s = store()
        s.save(sample(1)); s.save(sample(2))
        primary().writeBytes(blobOf("""{"v":9}"""))
        assertEquals(sample(1), s.load())
    }

    @Test fun corruptPrimaryIsNeverCopiedOverTheGoodBackup() {
        val s = store()
        s.save(sample(1)); s.save(sample(2))
        primary().writeBytes(ByteArray(40) { 3 })
        assertTrue(s.save(sample(3)).ok)
        assertEquals(sample(3), s.load())
        primary().delete()
        assertEquals(sample(1), s.load()) // backup still the last good version, not the corrupt bytes
        assertTrue(bad().isNotEmpty())
    }

    @Test fun manyCorruptionsKeepOnlyAFewQuarantineCopies() {
        val s = store()
        repeat(6) { i ->
            primary().writeBytes(ByteArray(40) { (i + 1).toByte() })
            Thread.sleep(2)
            assertTrue(s.save(sample(1)).ok)
        }
        assertTrue(bad().size <= 5)
    }

    // ---- backup.tmp integration ---------------------------------------------------------------------------

    private fun ids(vararg id: String) =
        blobOf("""{"v":1,"profiles":[${id.joinToString(",") { """{"id":"$it","name":"n","raw":"r"}""" }}],"subs":[]}""")

    @Test fun onlyBackupTmpExistingIsNotConsideredAnEmptyStore() {
        bakTmp().writeBytes(ByteArray(40) { 1 })
        assertEquals(LoadStatus.Unreadable, store().loadResult().status)   // not Empty: something is there
        assertTrue(bakTmp().exists())                                       // and it is untouched
    }

    @Test fun backupTmpIsARecoverySourceOfLastResort() {
        bakTmp().writeBytes(ids("from-bak-tmp"))
        val r = store().loadResult()
        assertEquals(LoadStatus.Recovered, r.status)
        assertEquals("from-bak-tmp", r.data.profiles.single().id)
    }

    @Test fun recoveryPriorityIsPrimaryThenBackupThenTmpThenBackupTmp() {
        primary().writeBytes(ids("P")); backup().writeBytes(ids("B")); tmpFile().writeBytes(ids("T")); bakTmp().writeBytes(ids("BT"))
        val s = store()
        assertEquals("P", s.load().profiles.single().id)
        primary().delete()
        assertEquals("B", s.load().profiles.single().id)
        backup().delete()
        assertEquals("T", s.load().profiles.single().id)
        tmpFile().delete()
        assertEquals("BT", s.load().profiles.single().id)
    }

    @Test fun invalidCandidatesAreSkippedInPriorityOrder() {
        primary().writeBytes(ByteArray(40) { 1 }); backup().writeBytes(ByteArray(40) { 2 })
        tmpFile().writeBytes(blobOf("""{"v":1,"profiles":[{"id":"","name":"n","raw":"r"}],"subs":[]}"""))
        bakTmp().writeBytes(ids("BT"))
        assertEquals("BT", store().load().profiles.single().id)
    }

    @Test fun crashDuringBackupCreationLeavesPrimaryAndBackupIntactAndTheTornStagingFileIsHarmless() {
        val s = store()
        s.save(sample(1)); s.save(sample(2))
        bakTmp().writeBytes(blobOf("""{"v":1,"profiles":[],"subs":[]}""").copyOf(25)) // torn staging file
        assertEquals(sample(2), s.load())                       // load unaffected
        assertTrue(s.save(sample(3)).ok)                        // next save replaces the torn scratch
        assertTrue(bad().isEmpty())                             // a torn file is confirmed unrecoverable, nothing to keep
        assertFalse(bakTmp().exists())
        primary().delete()
        assertEquals(sample(2), s.load())                       // backup refreshed from the previous primary
    }

    @Test fun crashAfterBackupStagingWasVerifiedButBeforeRenameIsHandledWithoutLoss() {
        val s = store()
        s.save(sample(1)); s.save(sample(2))
        bakTmp().writeBytes(primary().readBytes())              // staged copy of the current primary, rename never happened
        assertEquals(sample(2), s.load())
        assertTrue(s.save(sample(3)).ok)
        assertTrue(bad().isEmpty())                             // redundant with the primary, so not kept
        primary().delete()
        assertEquals(sample(2), s.load())
    }

    @Test fun existingNonRedundantBackupTmpIsPreservedBeforeBeingOverwritten() {
        val s = store()
        s.save(sample(1)); s.save(sample(2))
        bakTmp().writeBytes(ids("precious"))
        assertTrue(s.save(sample(3)).ok)
        assertTrue(bad().any { idsIn(it) == listOf("precious") })
        assertEquals(sample(3), s.load())
    }

    @Test fun backupTmpWeCannotClassifyIsPreserved() {
        val s = store()
        s.save(sample(1))
        val newer = blobOf("""{"v":2,"profiles":[],"subs":[]}""")
        bakTmp().writeBytes(newer)
        assertTrue(s.save(sample(2)).ok)
        assertTrue(bad().any { it.readBytes().contentEquals(newer) })
    }

    @Test fun recoveringFromBackupTmpAndSavingKeepsACopyOfItAndPersistsTheRecovery() {
        bakTmp().writeBytes(ids("rescued"))
        val s = store()
        val recovered = s.loadResult().data
        assertTrue(s.save(recovered).ok)
        assertEquals(LoadStatus.Ok, s.loadResult().status)
        assertEquals("rescued", s.load().profiles.single().id)
        assertTrue(bad().any { idsIn(it) == listOf("rescued") })
    }

    @Test fun failedSaveBeforeWritingNeverTouchesAnExistingBackupTmp() {
        val s = store()
        s.save(sample(1))
        val staged = ids("keep-me")
        bakTmp().writeBytes(staged)
        assertFalse(store(c = FailingEncrypt(cipher)).save(sample(2)).ok)
        assertArrayEquals(staged, bakTmp().readBytes())
    }

    @Test fun backupIsOnlyEverReplacedByAVerifiedCopy() {
        // The previous valid backup survives every kind of failed refresh.
        val s = store()
        s.save(sample(1)); s.save(sample(2))
        val before = backup().readBytes()
        store(FailingFs { it.name == "profiles.dat.bak" }).save(sample(3))
        store(FailingFs { it.name == "profiles.dat.bak" }).save(sample(4))
        assertArrayEquals(before, backup().readBytes())
    }

    // ---- empty required values -----------------------------------------------------------------------------

    @Test fun emptyProfileRawAndEmptySubscriptionUrlAreRejected() {
        rejects("""{"v":1,"profiles":[{"id":"a","name":"n","raw":""}],"subs":[]}""")
        rejects("""{"v":1,"profiles":[],"subs":[{"id":"s","name":"n","url":""}]}""")
    }

    @Test fun rejectedFileIsNeverDiscardedAndItsBytesSurviveASubsequentSave() {
        val rejected = blobOf("""{"v":1,"profiles":[{"id":"a","name":"n","raw":"vless://x"},{"id":"b","name":"n","raw":""}],"subs":[]}""")
        primary().writeBytes(rejected)
        val s = store()
        assertEquals(LoadStatus.Unreadable, s.loadResult().status)
        assertArrayEquals(rejected, primary().readBytes())      // load changed nothing
        assertTrue(s.save(sample(1)).ok)
        assertTrue(bad().any { it.readBytes().contentEquals(rejected) })   // user data still on disk
    }

    @Test fun validExistingProfilesWithNonEmptyRawStillLoad() {
        primary().writeBytes(blobOf("""{"v":1,"profiles":[{"id":"a","name":"","raw":"vless://u@h:443"}],"subs":[{"id":"s","name":"","url":"https://u"}]}"""))
        val r = store().loadResult()
        assertEquals(LoadStatus.Ok, r.status)
        assertEquals("vless://u@h:443", r.data.profiles.single().raw)
    }
}
