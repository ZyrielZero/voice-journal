package dev.zyriel.voicejournal.data

import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/**
 * Orchestrates an archive import so that recordings/ only ever contains
 * audio that a database entry references, or audio the orphan sweep knows
 * to discard.
 *
 * Why staging exists: audio members stream out of the zip before the
 * manifest can be validated (import is member-order-independent), so a
 * rejected or interrupted archive used to leave WAVs sitting in
 * recordings/ unreferenced, where the next launch's OrphanSweep would
 * faithfully re-transcribe them into phantom entries with fresh
 * timestamps. All audio now lands in [stagingDir] first and is moved into
 * [recordingsDir] one entry at a time, immediately before that entry's
 * insert. [stagingDir] is private to one run (the caller passes a fresh
 * subdirectory), so a second import can never wipe the first one's files.
 *
 * Process death between the move and the insert is covered by the
 * sidecar protocol shared with OrphanSweep: before the move, an empty
 * `<final>.wav.importing` is created next to the destination; after the
 * insert commits, it is deleted. A WAV that still has its sidecar at the
 * next launch is therefore an import in an unknown state: the sweep keeps
 * it (dropping only the sidecar) if a row references it, and deletes both
 * if none does, instead of transcribing it into a phantom entry.
 *
 * Duplicate semantics (deliberate, see below): an entry is a duplicate
 * iff its (timestampMs, transcript) pair already exists. Editing a
 * transcript in-app then re-importing an old archive therefore imports
 * the pre-edit version as a second entry at the same timestamp: the old
 * transcript is genuinely different data, and silently dropping it on a
 * timestamp match would be the importer deciding which version the user
 * meant to keep. Skipped duplicates never touch recordings/.
 *
 * Pure JVM: dirs and a suspend insert callback in, no Android or Room
 * types, so every failure path is unit-tested.
 */
class JournalImporter(
    private val stagingDir: File,
    private val recordingsDir: File,
    /** Bytes free on the staging volume; Android passes StatFs.availableBytes. */
    private val freeBytes: () -> Long = { stagingDir.parentFile?.usableSpace ?: 0L },
) {

    data class Result(val added: Int, val total: Int)

    companion object {
        const val SIDECAR_SUFFIX = ".importing"
        /** Headroom left free on the volume so the import cannot fill it. */
        const val FREE_SPACE_RESERVE_BYTES = 64L * 1024 * 1024

        fun sidecarFor(wav: File): File = File(wav.path + SIDECAR_SUFFIX)
    }

    /**
     * Imports [archive]. For each non-duplicate entry, its audio (if any)
     * is moved from staging into recordings under a sidecar and [insert]
     * is called with the entry carrying the final absolute path; the
     * sidecar is removed once [insert] returns. If [insert] throws, the
     * just-moved audio and its sidecar are removed before the exception
     * propagates; entries inserted earlier in the same import remain,
     * each fully consistent.
     *
     * The staged-audio ceiling is [maxTotalAudioBytes] clamped to the free
     * space on the staging volume minus [FREE_SPACE_RESERVE_BYTES]. Staging
     * may live in the cache dir, which the OS can evict mid-import; a
     * staged file that vanished fails the import at that entry.
     *
     * Blocking file IO: call it on an IO dispatcher.
     */
    suspend fun import(
        archive: InputStream,
        existingKeys: Set<Pair<Long, String>>,
        maxAudioMemberBytes: Long = JournalArchive.DEFAULT_MAX_AUDIO_MEMBER_BYTES,
        maxTotalAudioBytes: Long = JournalArchive.DEFAULT_MAX_TOTAL_AUDIO_BYTES,
        insert: suspend (JournalArchive.ImportedEntry) -> Unit,
    ): Result {
        resetStaging()
        try {
            val budget = minOf(maxTotalAudioBytes, freeBytes() - FREE_SPACE_RESERVE_BYTES)
            if (budget <= 0) {
                throw IOException("not enough free storage to import (need more than ${FREE_SPACE_RESERVE_BYTES / (1024 * 1024)} MB free)")
            }
            val staged = JournalArchive.import(
                archive, maxAudioMemberBytes, budget,
            ) { suggested, data ->
                val f = uniqueIn(stagingDir, suggested)
                f.outputStream().use { data.copyTo(it) }
                f.absolutePath
            }

            recordingsDir.mkdirs()
            var added = 0
            for (e in staged) {
                coroutineContext.ensureActive()
                if (e.timestampMs to e.transcript in existingKeys) continue

                val finalWav = e.audioPath?.let { stagedPath -> moveIntoRecordings(File(stagedPath)) }
                try {
                    insert(e.copy(audioPath = finalWav?.absolutePath))
                } catch (ex: Throwable) {
                    // The entry never landed (or its fate is unknown, on
                    // cancellation); its audio must not stay in recordings/
                    // to be resurrected by the sweep. The archive still
                    // holds the bytes; a retry re-imports it.
                    finalWav?.let { it.delete(); sidecarFor(it).delete() }
                    throw ex
                }
                finalWav?.let { sidecarFor(it).delete() }
                added++
            }
            return Result(added = added, total = staged.size)
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    private fun resetStaging() {
        // Leftovers from a previous import that died mid-flight.
        stagingDir.deleteRecursively()
        check(stagingDir.mkdirs() || stagingDir.isDirectory) {
            "could not create staging dir ${stagingDir.absolutePath}"
        }
    }

    /** Sidecar first, then the move: a death between them leaves a lone sidecar, which the sweep drops. */
    private fun moveIntoRecordings(staged: File): File {
        if (!staged.isFile) throw IOException("staged audio ${staged.name} disappeared (cache evicted?)")
        val dest = uniqueIn(recordingsDir, "imported_${staged.name}")
        val sidecar = sidecarFor(dest)
        if (!sidecar.createNewFile() && !sidecar.isFile) {
            throw IOException("could not create ${sidecar.name}")
        }
        try {
            if (!staged.renameTo(dest)) {
                // Cross-volume fallback; staging and recordings share a volume
                // in app storage, so this path is belt-and-suspenders.
                staged.copyTo(dest, overwrite = false)
                staged.delete()
            }
        } catch (ex: Exception) {
            dest.delete()
            sidecar.delete()
            throw ex
        }
        return dest
    }

    /** A name is free only if neither the WAV nor its sidecar exists. */
    private fun uniqueIn(dir: File, name: String): File {
        var f = File(dir, name)
        var i = 1
        while (f.exists() || sidecarFor(f).exists()) f = File(dir, "${i++}_$name")
        return f
    }
}
