package com.pooy.vpn.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import javax.crypto.AEADBadTagException

sealed class SaveResult {
    object Success : SaveResult()
    class Failure(val error: Throwable) : SaveResult()

    val ok: Boolean get() = this is Success
}

enum class LoadStatus {
    /** No store file exists yet. */
    Empty,

    /** The primary file was valid. */
    Ok,

    /** The primary file was missing/corrupt; data came from the last-known-good backup (or an interrupted write). */
    Recovered,

    /** Files exist but none could be decrypted/validated. Nothing was deleted; the next save keeps a copy. */
    Unreadable,
}

class LoadResult(val data: StoreData, val status: LoadStatus)

/** What the rest of the app needs from the store; lets tests substitute a failing implementation. */
interface ProfileStorage {
    fun loadResult(): LoadResult
    fun save(d: StoreData): SaveResult
}

/** A stored file decrypted fine (or is newer than we understand) but its structure is not acceptable. */
class StoreFormatException(message: String, val newerVersion: Boolean = false) : IllegalArgumentException(message)

/**
 * Persists profiles/subscriptions as one Keystore-encrypted file in app-private storage.
 *
 * Files (all in the same directory, so renames stay on one filesystem):
 *   profiles.dat            current data
 *   profiles.dat.bak        last-known-good copy of the previous version
 *   profiles.dat.tmp        in-flight write of the NEW version; a crash can leave a complete or torn copy
 *   profiles.dat.bak.tmp    staging file for the next backup (a verified copy of the then-current primary)
 *   profiles.dat*.bad-<ms>  durable copies of files that were about to be replaced and might still matter
 *
 * Save: encrypt -> probe primary/backup/temp -> durably copy anything recoverable that is about to be overwritten
 * -> write tmp + fsync -> read back -> refresh .bak (written, read back and validated before it replaces the old one)
 * -> atomic rename tmp over current. The current file is never deleted; a failure at any step leaves it intact.
 * Load/recovery priority (deterministic, first VALID candidate wins; each is decrypted and validated first):
 *   1. profiles.dat       the committed state
 *   2. profiles.dat.bak   last-known-good older state
 *   3. profiles.dat.tmp   a complete interrupted write of a newer, never-acknowledged state
 *   4. profiles.dat.bak.tmp  a complete interrupted backup staging copy
 * Nothing is repaired at load time (read-only); the next successful save persists the recovery.
 * Any existing staging file that is not provably redundant is durably copied before it is overwritten.
 *
 * Retention: a quarantine copy is only ever deleted automatically when it is *confirmed unrecoverable*, i.e. it fails
 * AES-GCM authentication or is too short to be a blob (and only beyond the newest [MAX_CORRUPT_COPIES] of those).
 * Valid copies, copies we merely could not read (I/O or Keystore trouble), and copies that decrypt but have a structure
 * or version we reject are never pruned.
 *
 * The on-disk format is unchanged (JSON "v":1, IV || ciphertext+tag).
 */
class ProfileStore internal constructor(
    private val dir: File,
    private val cipher: BlobCipher,
    private val fs: StoreFs,
    private val log: (String) -> Unit,
) : ProfileStorage {

    constructor(context: Context) : this(
        context.applicationContext.filesDir,
        Crypto,
        DefaultStoreFs,
        { Log.w(TAG, it) },
    )

    private val primary = File(dir, NAME)
    private val backup = File(dir, "$NAME.bak")
    private val backupTmp = File(dir, "$NAME.bak.tmp")
    private val tmp = File(dir, "$NAME.tmp")

    /** What we know about one file on disk. */
    private sealed class Probe {
        class Valid(val data: StoreData, val bytes: ByteArray) : Probe()
        object Missing : Probe()

        /** Confirmed unrecoverable: fails authentication or is not even a well-formed blob. */
        object Corrupt : Probe()

        /** Could not tell, or readable but rejected by structure/version: must be treated as possibly recoverable. */
        object Unknown : Probe()
    }

    fun load(): StoreData = loadResult().data

    override fun loadResult(): LoadResult = synchronized(LOCK) {
        if (!primary.exists() && !backup.exists() && !tmp.exists() && !backupTmp.exists()) {
            return LoadResult(StoreData(), LoadStatus.Empty)
        }
        (probe(primary) as? Probe.Valid)?.let { return LoadResult(it.data, LoadStatus.Ok) }
        (probe(backup) as? Probe.Valid)?.let {
            log("Profile store recovered from backup")
            return LoadResult(it.data, LoadStatus.Recovered)
        }
        (probe(tmp) as? Probe.Valid)?.let {
            log("Profile store recovered from interrupted write")
            return LoadResult(it.data, LoadStatus.Recovered)
        }
        (probe(backupTmp) as? Probe.Valid)?.let {
            log("Profile store recovered from interrupted backup staging file")
            return LoadResult(it.data, LoadStatus.Recovered)
        }
        log("Profile store unreadable; files kept untouched")
        LoadResult(StoreData(), LoadStatus.Unreadable)
    }

    override fun save(d: StoreData): SaveResult = synchronized(LOCK) {
        val hadData = primary.exists() || backup.exists() || tmp.exists() || backupTmp.exists()
        wroteTmp = false
        try {
            saveLocked(d)
            SaveResult.Success
        } catch (e: Exception) {
            log("Save failed: ${e.javaClass.simpleName}")
            cleanupTmpAfterFailure(hadData)
            SaveResult.Failure(e)
        }
    }

    /** True once this save call started writing the temp file (guarded by [LOCK]). */
    private var wroteTmp = false

    /**
     * Only a temp file THIS call wrote can be removed, and only when that costs nothing: the primary is verified
     * valid (it holds the previous version) or nothing existed before. A temp file that was there before this save
     * (an interrupted write) is never touched here.
     */
    private fun cleanupTmpAfterFailure(hadData: Boolean) {
        if (!wroteTmp) return
        try {
            if (!hadData || probe(primary) is Probe.Valid) tmp.delete()
        } catch (ignored: Exception) {
            // leave it
        }
    }

    private fun saveLocked(d: StoreData) {
        val blob = cipher.encrypt(toJson(d).toString().toByteArray(Charsets.UTF_8))
        // Refuse to commit anything we could not read back ourselves.
        fromJson(JSONObject(String(cipher.decrypt(blob), Charsets.UTF_8)))
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Cannot create storage directory")

        // 1) Validate what is on disk BEFORE touching any file.
        val primaryProbe = probe(primary)
        val backupProbe = probe(backup)
        val tmpProbe = probe(tmp)
        val backupTmpProbe = probe(backupTmp)

        // 2) Durably preserve everything that might still be recovered, before it can be overwritten. If any copy
        //    fails the save aborts here with every original file untouched.
        if (primaryProbe !is Probe.Valid && primary.exists()) quarantine(primary)
        if (backupProbe !is Probe.Valid && backup.exists()) quarantine(backup)
        if (tmp.exists() && !isDisposable(tmpProbe, primaryProbe, backupProbe)) quarantine(tmp)
        if (backupTmp.exists() && !isDisposable(backupTmpProbe, primaryProbe, backupProbe)) quarantine(backupTmp)

        // 3) Write and verify the new version next to the primary.
        wroteTmp = true
        writeDurably(tmp, blob)
        if (!tmp.readBytes().contentEquals(blob)) throw IOException("Write verification failed")

        // 4) Only a verified-good primary may become the backup, and only after the new backup file itself has been
        //    written, read back and validated. A failure here leaves the old backup untouched and must not block the save.
        if (primaryProbe is Probe.Valid) refreshBackup(primaryProbe.bytes)

        // 5) Commit. Throws on failure; the primary is untouched in that case.
        fs.replace(tmp, primary)
        syncDir()
    }

    /**
     * Stages the new backup in [backupTmp] (any previous content was preserved by the caller), verifies it, and only
     * then replaces [backup]; the previous valid backup stays in place until that replacement succeeds. Failure never
     * blocks the save. After a failure the staging file is deleted only if it never verified (our own torn scratch);
     * a verified copy is kept, since it may be the newest recoverable version of the previous state.
     */
    private fun refreshBackup(bytes: ByteArray) {
        var verified = false
        try {
            writeDurably(backupTmp, bytes)
            if (!backupTmp.readBytes().contentEquals(bytes)) throw IOException("Backup write verification failed")
            if (probe(backupTmp) !is Probe.Valid) throw IOException("Backup copy does not validate")
            verified = true
            fs.replace(backupTmp, backup)
            syncDir()
        } catch (e: Exception) {
            log("Backup refresh failed: ${e.javaClass.simpleName}")
            if (!verified) backupTmp.delete()
        }
    }

    /**
     * A staging file (tmp or bak.tmp) is disposable only if it is confirmed unrecoverable, or it is a valid copy of exactly what the
     * (valid) primary or backup already holds. Anything else, including files we could not classify, is kept.
     */
    private fun isDisposable(t: Probe, p: Probe, b: Probe): Boolean = when (t) {
        is Probe.Corrupt, is Probe.Missing -> true
        is Probe.Unknown -> false
        is Probe.Valid -> (p is Probe.Valid && p.data == t.data) || (b is Probe.Valid && b.data == t.data)
    }

    private fun writeDurably(f: File, bytes: ByteArray) {
        FileOutputStream(f).use {
            it.write(bytes)
            it.flush()
            it.fd.sync()
        }
    }

    /** Best effort: persist directory entries (new files, renames). Not available on every platform. */
    private fun syncDir() {
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (ignored: Exception) {
            // unsupported
        }
    }

    /**
     * Makes a durable, verified copy of [f] next to it BEFORE the caller modifies [f]. Skipped if an identical copy
     * already exists. Throws on failure so the caller aborts without having changed anything.
     */
    private fun quarantine(f: File) {
        val bytes = f.readBytes()
        val existing = quarantined()
        if (existing.any { it.length() == bytes.size.toLong() && it.readBytes().contentEquals(bytes) }) return
        val stamp = System.currentTimeMillis()
        var copy = File(dir, "${f.name}.bad-$stamp")
        var n = 1
        while (copy.exists()) copy = File(dir, "${f.name}.bad-$stamp-${n++}")
        try {
            writeDurably(copy, bytes)
            if (!copy.readBytes().contentEquals(bytes)) throw IOException("Quarantine verification failed")
        } catch (e: Exception) {
            copy.delete()
            throw e
        }
        syncDir()
        pruneConfirmedCorrupt()
    }

    private fun quarantined(): List<File> =
        dir.listFiles { x -> x.name.startsWith(NAME) && x.name.contains(".bad-") }?.toList() ?: emptyList()

    /** Deletes only copies confirmed unrecoverable, beyond the newest [MAX_CORRUPT_COPIES] of them. */
    private fun pruneConfirmedCorrupt() {
        val corrupt = quarantined().filter { probe(it) is Probe.Corrupt }.sortedByDescending { it.lastModified() }
        corrupt.drop(MAX_CORRUPT_COPIES).forEach { it.delete() }
    }

    private fun probe(f: File): Probe {
        if (!f.exists()) return Probe.Missing
        val bytes = try {
            f.readBytes()
        } catch (e: IOException) {
            log("${f.name} unreadable: ${e.javaClass.simpleName}")
            return Probe.Unknown
        }
        val plain = try {
            cipher.decrypt(bytes)
        } catch (e: AEADBadTagException) {
            log("${f.name} failed authentication")
            return Probe.Corrupt
        } catch (e: IllegalArgumentException) {
            log("${f.name} is not a valid blob")
            return Probe.Corrupt
        } catch (e: Exception) {
            log("${f.name} could not be decrypted: ${e.javaClass.simpleName}")
            return Probe.Unknown
        }
        return try {
            Probe.Valid(fromJson(JSONObject(String(plain, Charsets.UTF_8))), bytes)
        } catch (e: Exception) {
            log("${f.name} rejected: ${e.javaClass.simpleName}")
            Probe.Unknown // authenticated but not acceptable to this version: never auto-deleted
        }
    }

    private fun toJson(d: StoreData): JSONObject {
        val profiles = JSONArray()
        for (p in d.profiles) {
            val o = JSONObject().put("id", p.id).put("name", p.name).put("raw", p.raw)
            if (p.subId != null) o.put("sub", p.subId)
            profiles.put(o)
        }
        val subs = JSONArray()
        for (s in d.subscriptions) {
            subs.put(JSONObject().put("id", s.id).put("name", s.name).put("url", s.url))
        }
        val root = JSONObject().put("v", FORMAT_VERSION).put("profiles", profiles).put("subs", subs)
        if (d.activeId != null) root.put("active", d.activeId)
        return root
    }

    private fun str(o: JSONObject, key: String, what: String, nonEmpty: Boolean = false): String {
        val v = o.opt(key)
        if (v !is String) throw StoreFormatException("$what.$key missing or not a string")
        if (nonEmpty && v.isEmpty()) throw StoreFormatException("$what.$key is empty")
        return v
    }

    /**
     * Strict structural validation. Rejects (never silently empties): wrong/missing version, missing or mistyped
     * arrays, records that are not objects, missing or mistyped fields, empty or duplicate ids, empty profile `raw`
     * and empty subscription `url`. A rejected file is never deleted or overwritten without a durable copy.
     * Dangling references (active profile, a profile's subscription) are repaired to null and logged: they cannot
     * come from valid saves of this app, and rejecting the whole file would cost the user every profile.
     */
    private fun fromJson(o: JSONObject): StoreData {
        val v = o.opt("v")
        if (v !is Number) throw StoreFormatException("Missing store version")
        if (v.toDouble() > FORMAT_VERSION) throw StoreFormatException("Store version is newer than supported", newerVersion = true)
        if (v.toDouble() != FORMAT_VERSION.toDouble()) throw StoreFormatException("Unsupported store version")

        val pa = o.opt("profiles") as? JSONArray ?: throw StoreFormatException("profiles missing or not an array")
        val sa = o.opt("subs") as? JSONArray ?: throw StoreFormatException("subs missing or not an array")

        val subs = ArrayList<Subscription>()
        val subIds = HashSet<String>()
        for (i in 0 until sa.length()) {
            val s = sa.opt(i) as? JSONObject ?: throw StoreFormatException("subs[$i] is not an object")
            val id = str(s, "id", "sub", nonEmpty = true)
            if (!subIds.add(id)) throw StoreFormatException("Duplicate subscription id")
            subs.add(Subscription(id, str(s, "name", "sub"), str(s, "url", "sub", nonEmpty = true)))
        }

        val profiles = ArrayList<Profile>()
        val profileIds = HashSet<String>()
        var repaired = 0
        for (i in 0 until pa.length()) {
            val p = pa.opt(i) as? JSONObject ?: throw StoreFormatException("profiles[$i] is not an object")
            val id = str(p, "id", "profile", nonEmpty = true)
            if (!profileIds.add(id)) throw StoreFormatException("Duplicate profile id")
            var sub: String? = if (p.has("sub")) str(p, "sub", "profile", nonEmpty = true) else null
            if (sub != null && sub !in subIds) {
                sub = null
                repaired++
            }
            profiles.add(Profile(id, str(p, "name", "profile"), str(p, "raw", "profile", nonEmpty = true), sub))
        }

        var active: String? = null
        if (o.has("active")) {
            active = str(o, "active", "store")
            if (active !in profileIds) {
                active = null
                repaired++
            }
        }
        if (repaired > 0) log("Repaired $repaired dangling reference(s) while loading")
        return StoreData(profiles, subs, active)
    }

    private companion object {
        const val TAG = "ProfileStore"
        const val NAME = "profiles.dat"
        const val FORMAT_VERSION = 1
        const val MAX_CORRUPT_COPIES = 5
        val LOCK = Any()
    }
}

/** Applies a change and persists it; the in-memory state may only advance when persistence succeeded. */
sealed class CommitResult {
    class Committed(val data: StoreData) : CommitResult()
    object Unchanged : CommitResult()
    class Failed(val error: Throwable) : CommitResult()
}

object StoreCommit {
    fun commit(storage: ProfileStorage, current: StoreData, block: (StoreData) -> StoreData): CommitResult {
        val next = try {
            block(current)
        } catch (e: Exception) {
            return CommitResult.Failed(e)
        }
        if (next == current) return CommitResult.Unchanged
        return when (val r = storage.save(next)) {
            is SaveResult.Success -> CommitResult.Committed(next)
            is SaveResult.Failure -> CommitResult.Failed(r.error)
        }
    }
}
