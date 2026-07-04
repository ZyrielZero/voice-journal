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
    )

    /**
     * Scans [dir] for orphaned WAVs.
     *
     * @param referencedPaths absolute paths of every audio file the database
     *   knows about. Files listed here are never touched.
     * @param activePath absolute path of a recording in progress, if any.
     *   It is mid-write and legitimately headerless; skipped, never repaired.
     * @param onRecoverable called for each orphan whose audio is intact
     *   (repaired or already consistent). Exceptions from the callback are
     *   not caught: a failed recovery must not look like a completed sweep.
     */
    fun sweep(
        dir: File,
        referencedPaths: Set<String>,
        activePath: String? = null,
        onRecoverable: (File) -> Unit,
    ): Report {
        val recovered = mutableListOf<File>()
        val deleted = mutableListOf<File>()
        val skipped = mutableListOf<File>()

        val candidates = dir.listFiles { f -> f.isFile && f.extension == "wav" } ?: return Report(recovered, deleted, skipped)

        for (f in candidates.sortedBy { it.name }) {
            val path = f.absolutePath
            if (path in referencedPaths || path == activePath) {
                skipped += f
                continue
            }
            when (WavRepair.inspectAndRepair(f)) {
                is WavRepair.Outcome.Repaired,
                WavRepair.Outcome.AlreadyConsistent -> {
                    onRecoverable(f)
                    recovered += f
                }
                is WavRepair.Outcome.Unrecoverable -> {
                    f.delete()
                    deleted += f
                }
            }
        }
        return Report(recovered, deleted, skipped)
    }
}
