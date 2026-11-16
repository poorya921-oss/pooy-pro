package com.pooy.vpn.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException

class FallbackStoreFsTest {
    @get:Rule val folder = TemporaryFolder()

    private fun file(name: String, content: String) = File(folder.root, name).also { it.writeText(content) }

    /** Records which primitive was used; each can be told to fail. */
    private class ScriptedMover(
        private val atomic: () -> Unit = { },
        private val replacing: () -> Unit = { },
        private val rename: () -> Boolean = { true },
    ) : FileMover {
        val calls = ArrayList<String>()
        override fun atomicMove(src: File, dst: File) { calls.add("atomic"); atomic(); NioMover.atomicMove(src, dst) }
        override fun replacingMove(src: File, dst: File) { calls.add("replacing"); replacing(); NioMover.replacingMove(src, dst) }
        override fun rename(src: File, dst: File): Boolean { calls.add("rename"); return rename() && NioMover.rename(src, dst) }
    }

    @Test fun atomicMoveReplacesAnExistingDestination() {
        val src = file("src", "new"); val dst = file("dst", "old")
        FallbackStoreFs(NioMover).replace(src, dst)
        assertEquals("new", dst.readText()); assertFalse(src.exists())
    }

    @Test fun defaultStoreFsReplacesAnExistingFile() {
        val src = file("src", "new"); val dst = file("dst", "old")
        DefaultStoreFs.replace(src, dst)
        assertEquals("new", dst.readText())
    }

    @Test fun worksWhenTheDestinationDoesNotExist() {
        val src = file("src", "new"); val dst = File(folder.root, "dst")
        FallbackStoreFs(NioMover).replace(src, dst)
        assertEquals("new", dst.readText())
    }

    @Test fun unsupportedAtomicMoveFallsBackToReplacingMove() {
        val m = ScriptedMover(atomic = { throw AtomicMoveNotSupportedException("a", "b", "no") })
        val src = file("src", "new"); val dst = file("dst", "old")
        FallbackStoreFs(m).replace(src, dst)
        assertEquals(listOf("atomic", "replacing"), m.calls)
        assertEquals("new", dst.readText())
    }

    @Test fun platformThatRefusesToReplaceFallsBack() {
        val m = ScriptedMover(atomic = { throw FileAlreadyExistsException("dst") })
        val src = file("src", "new"); val dst = file("dst", "old")
        FallbackStoreFs(m).replace(src, dst)
        assertEquals(listOf("atomic", "replacing"), m.calls)
        assertEquals("new", dst.readText())
    }

    @Test fun lastResortIsJavaIoRename() {
        val m = ScriptedMover(
            atomic = { throw IOException("x") },
            replacing = { throw IOException("y") },
        )
        val src = file("src", "new"); val dst = file("dst", "old")
        FallbackStoreFs(m).replace(src, dst)
        assertEquals(listOf("atomic", "replacing", "rename"), m.calls)
        assertEquals("new", dst.readText())
    }

    @Test fun whenEverythingFailsTheDestinationIsUntouchedAndTheErrorExplainsWhy() {
        val m = ScriptedMover(
            atomic = { throw IOException("a") },
            replacing = { throw IOException("b") },
            rename = { false },
        )
        val src = file("src", "new"); val dst = file("dst", "old")
        try {
            FallbackStoreFs(m).replace(src, dst)
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.suppressed.size >= 3)
        }
        assertEquals("old", dst.readText())        // never deleted
        assertEquals("new", src.readText())        // and the new data is still available
    }

    @Test fun missingSourceFailsWithoutTouchingTheDestination() {
        val dst = file("dst", "old")
        try {
            FallbackStoreFs(NioMover).replace(File(folder.root, "nope"), dst)
            fail("expected IOException")
        } catch (e: IOException) { /* expected */ }
        assertArrayEquals("old".toByteArray(), dst.readBytes())
    }

    @Test fun profileStoreSavesThroughTheFallbackPath() {
        val key = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val m = ScriptedMover(atomic = { throw AtomicMoveNotSupportedException("a", "b", "no") })
        val store = ProfileStore(folder.root, AesGcmBlobCipher { key }, FallbackStoreFs(m)) { }
        val d1 = StoreData(listOf(Profile("a", "n", "r")), emptyList(), "a")
        val d2 = StoreData(listOf(Profile("a", "n", "r"), Profile("b", "n2", "r2")), emptyList(), "a")
        assertTrue(store.save(d1).ok)
        assertTrue(store.save(d2).ok)
        assertEquals(d2, store.load())
        assertTrue(m.calls.contains("replacing"))
    }
}
