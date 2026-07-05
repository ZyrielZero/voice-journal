package dev.zyriel.voicejournal.data

import java.io.File
import java.io.InputStream

/**
 * Orchestrates an archive import so that recordings/ only ever contains
 * audio that a database entry references.
 *
 * Why staging exists: audio members stream out of the zip before the
 * manifest can be validated (import is member-order-independent), so a
 * rejected or interrupted archive used to leave WAVs sitting in
 * recordings/ unreferenced — where the next launch's OrphanSweep would
 * faithfully re-transcribe them into phantom entries with fresh
 * timestamps. All audio now lands in [stagingDir] first and is moved into
 * [recordingsDir] one entry at a time, immediately before that entry's
 * insert. Any failure — bad version, malformed manifest, insert error,
 * process death — leaves recordings/ exactly as the sweep expects:
 * nothing unreferenced. Staging is wiped on entry and exit; a death
 * mid-import leaves only staging-dir leftovers, which live under the
 * cache dir at the call site and are cleared by the next import (or the
 * OS).
 *
 * Duplicate semantics (deliberate, see below): an entry is a duplicate
 * iff its (timestampMs, transcript) pair already exists. Editing a
 * transcript in-app then re-importing an old archive therefore imports
 * the pre-edit version as a second entry at the same timestamp — the old
 * transcript is genuinely different data, and silently dropping it on a
 * timestamp match would be the importer deciding which version the user
 * meant to keep. Skipped duplicates never touch recordings/.
 *
 * Pure JVM: dirs and an insert callback in, no Android or Room types, so
 * every failure path is unit-tested.
 */
class JournalImporter(
    private val stagingDir: File,
    private val recordingsDir: File,
) {

    data class Result(val added: Int, val total: Int)

    /**
     * Imports [archive]. For each non-duplicate entry, its audio (if any)
     * is moved from staging into recordings and [insert] is called with
     * the entry carrying the final absolute path. If [insert] throws, the
     * just-moved audio is removed from recordings before the exception
     * propagates — entries inserted earlier in the same import remain,
     * each fully consistent.
     */
    fun import(
        archive: InputStream,
        existingKeys: Set<Pair<Long, String>>,
        maxAudioMemberBytes: Long = JournalArchive.DEFAULT_MAX_AUDIO_MEMBER_BYTES,
        maxTotalAudioBytes: Long = JournalArchive.DEFAULT_MAX_TOTAL_AUDIO_BYTES,
        insert: (JournalArchive.ImportedEntry) -> Unit,
    ): Result {
        resetStaging()
        try {
            val staged = JournalArchive.import(
                archive, maxAudioMemberBytes, maxTotalAudioBytes,
            ) { suggested, data ->
                val f = uniqueIn(stagingDir, suggested)
                f.outputStream().use { data.copyTo(it) }
                f.absolutePath
            }

            recordingsDir.mkdirs()
            var added = 0
            for (e in staged) {
                if (e.timestampMs to e.transcript in existingKeys) continue

                val finalPath = e.audioPath?.let { stagedPath ->
                    moveIntoRecordings(File(stagedPath)).absolutePath
                }
                try {
                    insert(e.copy(audioPath = finalPath))
                } catch (ex: Exception) {
                    // The entry never landed; its audio must not stay in
                    // recordings/ to be resurrected by the sweep. The
                    // archive still holds the bytes; a retry re-imports it.
                    finalPath?.let { File(it).delete() }
                    throw ex
                }
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

    private fun moveIntoRecordings(staged: File): File {
        val dest = uniqueIn(recordingsDir, "imported_${staged.name}")
        if (!staged.renameTo(dest)) {
            // Cross-volume fallback; staging and recordings share a volume
            // in app storage, so this path is belt-and-suspenders.
            staged.copyTo(dest, overwrite = false)
            staged.delete()
        }
        return dest
    }

    private fun uniqueIn(dir: File, name: String): File {
        var f = File(dir, name)
        var i = 1
        while (f.exists()) f = File(dir, "${i++}_$name")
        return f
    }
}
