package dev.zyriel.voicejournal.audio

import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException

/**
 * Launch-time recovery for recordings orphaned by process death (NFR-06).
 *
 * An orphan is a .wav in the recordings directory that no journal entry
 * references. Two ways one comes to exist: death mid-recording (header never
 * finalized; [WavRepair] fixes it) and death after the WAV was finalized but
 * before the DB insert landed. Both hold real audio the user spoke and
 * expects to keep, so both are handed to [onRecoverable] for transcription.
 * Files [WavRepair] deems unrecoverable carry no salvageable audio and are
 * deleted.
 *
 * Imports are not orphans. The importer writes an empty
 * `<final>.wav.importing` sidecar before moving `<final>.wav` in, inserts
 * the row, then deletes the sidecar (contract C4). A WAV with a sidecar is
 * never transcribed: if a row references it, only the sidecar is deleted;
 * if not, the import died before its insert and both are deleted (the
 * archive still holds the audio). A sidecar with no WAV is deleted.
 *
 * Failed recoveries are counted in a `<name>.wav.failures` sidecar. After
 * [MAX_ATTEMPTS] failures the WAV is moved to `quarantine/` (kept, never
 * deleted) so an oversized or poisoned file is not retried on every launch.
 *
 * Paths are compared by canonicalPath on both sides, so a writer that
 * spells the directory differently (/data/user/0 vs /data/data) cannot
 * orphan a live file.
 *
 * Pure Kotlin/JVM: knows nothing about Room, transcription, or Android.
 * The caller supplies the reference check and what "recover" means.
 */
object OrphanSweep {

    const val IMPORT_SIDECAR = ".importing"
    const val FAILURE_SIDECAR = ".failures"
    const val QUARANTINE_DIR = "quarantine"
    const val MAX_ATTEMPTS = 3

    data class Report(
        val recovered: List<File>,
        val deleted: List<File>,
        val skipped: List<File>,
        /** Orphans whose recovery threw. Files are left on disk (or quarantined). */
        val failed: List<Pair<File, Exception>> = emptyList(),
        /** Orphans moved to quarantine/ this sweep after [MAX_ATTEMPTS] failures. */
        val quarantined: List<File> = emptyList(),
    ) {
        fun oneLine(): String =
            "sweep: ${recovered.size} recovered, ${deleted.size} deleted, " +
                "${skipped.size} skipped, ${failed.size} FAILED, ${quarantined.size} quarantined" +
                if (failed.isEmpty()) "" else failed.joinToString(prefix = " [", postfix = "]") {
                    "${it.first.name}: ${it.second.message}"
                }
    }

    /**
     * Scans [dir] for orphaned WAVs and stale sidecars.
     *
     * @param isReferenced whether the database holds a row for this path.
     *   Asked per file, right before the file is touched, never snapshotted:
     *   recovery takes seconds per file, and a live recording or an import
     *   can be inserted meanwhile. Called with the absolute path and, if it
     *   differs, the canonical path. A throw here (the database itself is
     *   broken) propagates and ends the sweep; it is not a per-file failure.
     * @param activePath supplies the path of a recording in progress, if
     *   any, re-read for every candidate. Active files are skipped, never
     *   repaired.
     * @param importInFlight whether an import is running right now. While
     *   true, a WAV with an `.importing` sidecar belongs to that import
     *   (between its move and its insert), so neither the WAV nor the
     *   sidecar is touched; a later sweep resolves it. Read per sidecar.
     * @param onRecoverable called for each orphan whose audio is intact
     *   (repaired or already consistent). An exception from it, or from the
     *   repair, fails THAT file: recorded in [Report.failed], counted toward
     *   quarantine, and the loop continues. The caller must surface
     *   [Report.failed]; a failed recovery must not look like a completed
     *   sweep. Errors (OutOfMemoryError) are deliberately not caught.
     */
    suspend fun sweep(
        dir: File,
        isReferenced: suspend (path: String) -> Boolean,
        activePath: () -> String? = { null },
        importInFlight: () -> Boolean = { false },
        onRecoverable: suspend (File) -> Unit,
    ): Report {
        val recovered = mutableListOf<File>()
        val deleted = mutableListOf<File>()
        val skipped = mutableListOf<File>()
        val failed = mutableListOf<Pair<File, Exception>>()
        val quarantined = mutableListOf<File>()

        val listing = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }
            ?: return Report(recovered, deleted, skipped)

        suspend fun referenced(f: File): Boolean {
            val abs = f.absolutePath
            val canon = canonical(f)
            return isReferenced(abs) || (canon != abs && isReferenced(canon))
        }

        fun remove(f: File, into: MutableList<File>?) {
            if (f.delete() || !f.exists()) into?.add(f)
            else failed += f to IOException("could not delete ${f.name}")
        }

        // Import sidecars first, so the WAV loop below never sees a dead
        // import's audio as a recording to transcribe.
        for (side in listing.filter { it.name.endsWith(IMPORT_SIDECAR) }) {
            val wav = File(dir, side.name.removeSuffix(IMPORT_SIDECAR))
            when {
                importInFlight() -> if (wav.exists()) skipped += wav
                !wav.exists() -> remove(side, null)
                referenced(wav) -> remove(side, null)
                else -> {
                    remove(wav, deleted)
                    remove(side, null)
                }
            }
        }
        for (side in listing.filter { it.name.endsWith(FAILURE_SIDECAR) }) {
            if (!File(dir, side.name.removeSuffix(FAILURE_SIDECAR)).exists()) remove(side, null)
        }

        for (f in listing.filter { it.extension == "wav" }) {
            if (!f.exists() || f in skipped) continue // resolved in the sidecar pass
            val canon = canonical(f)
            val active = activePath()?.let { canonical(File(it)) }
            if (canon == active || sidecar(f, IMPORT_SIDECAR).exists() || referenced(f)) {
                skipped += f
                continue
            }
            try {
                when (WavRepair.inspectAndRepair(f)) {
                    is WavRepair.Outcome.Repaired,
                    WavRepair.Outcome.AlreadyConsistent -> {
                        onRecoverable(f)
                        recovered += f
                        sidecar(f, FAILURE_SIDECAR).delete()
                    }
                    is WavRepair.Outcome.Unrecoverable -> {
                        remove(f, deleted)
                        sidecar(f, FAILURE_SIDECAR).delete()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += f to e
                if (recordFailure(f) >= MAX_ATTEMPTS && quarantine(dir, f)) quarantined += f
            }
        }
        return Report(recovered, deleted, skipped, failed, quarantined)
    }

    private fun canonical(f: File): String =
        try { f.canonicalPath } catch (e: IOException) { f.absolutePath }

    private fun sidecar(wav: File, suffix: String) = File(wav.parentFile, wav.name + suffix)

    /** Increments and returns the persisted failure count for [wav]. */
    private fun recordFailure(wav: File): Int {
        val side = sidecar(wav, FAILURE_SIDECAR)
        val n = (runCatching { side.readText().trim().toInt() }.getOrDefault(0)) + 1
        runCatching { side.writeText(n.toString()) }
        return n
    }

    private fun quarantine(dir: File, wav: File): Boolean {
        val q = File(dir, QUARANTINE_DIR).apply { mkdirs() }
        val moved = wav.renameTo(File(q, wav.name))
        if (moved) sidecar(wav, FAILURE_SIDECAR).delete()
        return moved
    }
}
