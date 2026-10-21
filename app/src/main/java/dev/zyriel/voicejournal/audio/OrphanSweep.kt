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
 * Recovery attempts are counted in a `<name>.wav.failures` sidecar, written
 * before [onRecoverable] runs and deleted when it succeeds, so an attempt
 * that crashes the process still counts. After [MAX_ATTEMPTS] failed
 * attempts the WAV is moved to `quarantine/` (kept, never deleted) so an
 * oversized or poisoned file is not retried on every launch.
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

    /**
     * Thrown from onRecoverable when the failure is not the file's fault
     * (the transcription model cannot be loaded): the sweep stops for this
     * launch and the attempt is not counted, so a broken model can never
     * quarantine the user's audio.
     */
    class Abort(cause: Throwable) : Exception(cause.message, cause)

    data class Report(
        val recovered: List<File>,
        val deleted: List<File>,
        val skipped: List<File>,
        /** Orphans whose recovery threw. Files are left on disk (or quarantined). */
        val failed: List<Pair<File, Exception>> = emptyList(),
        /** Orphans moved to quarantine/ this sweep after [MAX_ATTEMPTS] failures. */
        val quarantined: List<File> = emptyList(),
        /** Set when onRecoverable threw [Abort]; files after it were not visited. */
        val aborted: Throwable? = null,
    ) {
        fun oneLine(): String =
            "sweep: ${recovered.size} recovered, ${deleted.size} deleted, " +
                "${skipped.size} skipped, ${failed.size} FAILED, ${quarantined.size} quarantined" +
                (if (aborted != null) ", ABORTED: ${aborted.message}" else "") +
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
     *   sweep. Throwing [Abort] instead ends the sweep without counting the
     *   attempt ([Report.aborted]). Errors (OutOfMemoryError) are
     *   deliberately not caught.
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
        var aborted: Throwable? = null

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
            // Earlier attempts that never came back (a native crash or a
            // process kill inside onRecoverable) were already counted, so a
            // file that has used up its attempts is quarantined untried.
            if (failureCount(f) >= MAX_ATTEMPTS) {
                if (quarantine(dir, f)) quarantined += f
                continue
            }
            var attempts = -1 // the count after this attempt, once recorded
            try {
                when (WavRepair.inspectAndRepair(f)) {
                    is WavRepair.Outcome.Repaired,
                    WavRepair.Outcome.AlreadyConsistent -> {
                        // Counted BEFORE the attempt: a SIGSEGV or kill in
                        // native transcription never reaches the catch below.
                        attempts = recordFailure(f)
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
            } catch (e: Abort) {
                // Not this file's failure: undo its count and stop.
                if (attempts > 0) unrecordFailure(f, attempts)
                aborted = e.cause ?: e
                break
            } catch (e: Exception) {
                failed += f to e
                if (attempts < 0) attempts = recordFailure(f) // the repair itself threw
                if (attempts >= MAX_ATTEMPTS && quarantine(dir, f)) quarantined += f
            }
        }
        return Report(recovered, deleted, skipped, failed, quarantined, aborted)
    }

    /** WAVs currently held in `quarantine/` under [dir]. */
    fun quarantinedCount(dir: File): Int =
        File(dir, QUARANTINE_DIR).listFiles()?.count { it.isFile && it.extension == "wav" } ?: 0

    private fun canonical(f: File): String =
        try { f.canonicalPath } catch (e: IOException) { f.absolutePath }

    private fun sidecar(wav: File, suffix: String) = File(wav.parentFile, wav.name + suffix)

    private fun failureCount(wav: File): Int =
        runCatching { sidecar(wav, FAILURE_SIDECAR).readText().trim().toInt() }.getOrDefault(0)

    /** Increments and returns the persisted failure count for [wav]. */
    private fun recordFailure(wav: File): Int {
        val n = failureCount(wav) + 1
        runCatching { sidecar(wav, FAILURE_SIDECAR).writeText(n.toString()) }
        return n
    }

    /** Rolls the count back from [counted] to the value before this attempt. */
    private fun unrecordFailure(wav: File, counted: Int) {
        val side = sidecar(wav, FAILURE_SIDECAR)
        if (counted <= 1) side.delete() else runCatching { side.writeText((counted - 1).toString()) }
    }

    private fun quarantine(dir: File, wav: File): Boolean {
        val q = File(dir, QUARANTINE_DIR).apply { mkdirs() }
        val moved = wav.renameTo(File(q, wav.name))
        if (moved) sidecar(wav, FAILURE_SIDECAR).delete()
        return moved
    }
}
