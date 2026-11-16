package com.pooy.vpn.data

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** File replacement primitive. Must never delete [dst] before [src] has been committed. */
interface StoreFs {
    fun replace(src: File, dst: File)
}

/**
 * Low-level move primitives. Each must either replace [dst] completely or leave it untouched; none may delete
 * [dst] first. Faked in tests.
 */
interface FileMover {
    /** rename(2)-style atomic replace; throws [AtomicMoveNotSupportedException] if the filesystem cannot do it. */
    fun atomicMove(src: File, dst: File)

    /** Replacing move without the atomicity promise (Java ignores REPLACE_EXISTING when ATOMIC_MOVE is given, so it is separate). */
    fun replacingMove(src: File, dst: File)

    /** java.io rename; false on failure. */
    fun rename(src: File, dst: File): Boolean
}

object NioMover : FileMover {
    override fun atomicMove(src: File, dst: File) {
        Files.move(src.toPath(), dst.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    override fun replacingMove(src: File, dst: File) {
        Files.move(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    override fun rename(src: File, dst: File): Boolean = src.renameTo(dst)
}

/**
 * Replacement with a strict fallback ladder; the destination is never deleted by us, so if every step fails the
 * previous destination is still in place:
 *  1. ATOMIC_MOVE (replaces an existing destination atomically on POSIX filesystems)
 *  2. if unsupported, or the platform refused to replace: REPLACE_EXISTING move
 *  3. java.io rename
 *  4. IOException (with the earlier failures attached)
 */
class FallbackStoreFs(private val mover: FileMover) : StoreFs {
    override fun replace(src: File, dst: File) {
        if (!src.exists()) throw IOException("Source file missing: ${src.name}")
        val failures = ArrayList<Throwable>()

        try {
            mover.atomicMove(src, dst)
            return
        } catch (e: AtomicMoveNotSupportedException) {
            failures.add(e)
        } catch (e: FileAlreadyExistsException) {
            failures.add(e) // platform's atomic move does not replace
        } catch (e: IOException) {
            failures.add(e)
        } catch (e: UnsupportedOperationException) {
            failures.add(e)
        }

        try {
            mover.replacingMove(src, dst)
            return
        } catch (e: IOException) {
            failures.add(e)
        } catch (e: UnsupportedOperationException) {
            failures.add(e)
        }

        try {
            if (mover.rename(src, dst)) return
            failures.add(IOException("rename failed"))
        } catch (e: SecurityException) {
            failures.add(e)
        }

        val error = IOException("Could not replace ${dst.name}")
        failures.forEach { error.addSuppressed(it) }
        throw error
    }
}

object DefaultStoreFs : StoreFs {
    private val impl = FallbackStoreFs(NioMover)
    override fun replace(src: File, dst: File) = impl.replace(src, dst)
}
