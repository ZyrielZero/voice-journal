package dev.zyriel.voicejournal.audio

import java.io.File

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
 * Pure Kotlin/JVM: knows nothing about Room, transcription, or Android.
 * The caller supplies the referenced paths and what "recover" means.
 */
object OrphanSweep {

    data class Report(
        val recovered: List<File>,
        val deleted: List<File>,
        val skipped: List<File>,
        /** Orphans whose recovery callback threw. Files are left on disk untouched. */
        val failed: List<Pair<File, Exception>> = emptyList(),
    ) {
        fun oneLine(): String =
            "sweep: ${recovered.size} recovered, ${deleted.size} deleted, " +
                "${skipped.size} skipped, ${failed.size} FAILED" +
                if (failed.isEmpty()) "" else failed.joinToString(prefix = " [", postfix = "]") {
                    "${it.first.name}: ${it.second.message}"
                }
    }

    /**
     * Scans [dir] for orphaned WAVs.
     *
     * @param referencedPaths absolute paths of every audio file the database
     *   knows about. Files listed here are never touched.
     * @param activePath supplies the absolute path of a recording in
     *   progress, if any, and is re-read for every candidate: recovery can
     *   take seconds per file (whisper), which is plenty of time for the
     *   user to start a new recording mid-sweep. A single snapshot taken at
     *   call time would let the sweep repair — or delete — a file that
     *   became active after the snapshot. Active files are skipped, never
     *   repaired.
     * @param onRecoverable called for each orphan whose audio is intact
     *   (repaired or already consistent). A callback exception fails THAT
     *   file — recorded in [Report.failed], file left on disk for the next
     *   sweep — and the loop continues, so one bad orphan can't shadow the
     *   rest. The caller must surface [Report.failed]; a failed recovery
     *   must not look like a completed sweep.
     */
    fun sweep(
        dir: File,
        referencedPaths: Set<String>,
        activePath: () -> String? = { null },
        onRecoverable: (File) -> Unit,
    ): Report {
        val recovered = mutableListOf<File>()
        val deleted = mutableListOf<File>()
        val skipped = mutableListOf<File>()
        val failed = mutableListOf<Pair<File, Exception>>()

        val candidates = dir.listFiles { f -> f.isFile && f.extension == "wav" }
            ?: return Report(recovered, deleted, skipped)

        for (f in candidates.sortedBy { it.name }) {
            val path = f.absolutePath
            if (path in referencedPaths || path == activePath()) {
                skipped += f
                continue
            }
            when (WavRepair.inspectAndRepair(f)) {
                is WavRepair.Outcome.Repaired,
                WavRepair.Outcome.AlreadyConsistent -> {
                    try {
                        onRecoverable(f)
                        recovered += f
                    } catch (e: Exception) {
                        failed += f to e
                    }
                }
                is WavRepair.Outcome.Unrecoverable -> {
                    f.delete()
                    deleted += f
                }
            }
        }
        return Report(recovered, deleted, skipped, failed)
    }
}
