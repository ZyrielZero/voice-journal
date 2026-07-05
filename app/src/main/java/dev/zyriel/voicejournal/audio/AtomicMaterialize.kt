package dev.zyriel.voicejournal.audio

import java.io.File
import java.io.InputStream

/**
 * Copies a stream to a destination file atomically: the bytes land in a
 * sibling temp file first and only a completed copy is renamed into place.
 *
 * Why this exists: the naive open-dest-and-copy pattern, killed mid-write,
 * leaves a file that exists with nonzero length — which passes every
 * "exists and not empty" check forever after. For the whisper model that
 * meant a truncated 60 MB copy permanently bricked transcription (context
 * init fails on every launch, nothing ever deletes the bad file). With the
 * temp+rename shape, a death mid-copy leaves only a .part file that the
 * next attempt overwrites; the destination either doesn't exist or is the
 * complete artifact. Rename within the same directory is atomic on the
 * filesystems Android app storage uses.
 *
 * Pure JVM so the failure modes are unit-tested with a throwing stream.
 */
object AtomicMaterialize {

    /**
     * Ensures [dest] contains the full contents of the stream [open]
     * produces. No-op if [dest] already exists (it can only exist
     * complete). Throws whatever [open] or the copy throws; on any
     * failure [dest] is untouched and no partial file remains at its path.
     *
     * @return dest, for call-site convenience.
     */
    fun ensure(dest: File, open: () -> InputStream): File {
        if (dest.isFile && dest.length() > 0L) return dest
        // A zero-byte dest is a legacy artifact of the old pattern; clear it.
        if (dest.exists()) dest.delete()

        val part = File(dest.parentFile, dest.name + ".part")
        try {
            open().use { input ->
                part.outputStream().use { input.copyTo(it) }
            }
            if (!part.renameTo(dest)) {
                error("rename ${part.name} -> ${dest.name} failed")
            }
        } finally {
            // Success renamed it away; failure must not leave the partial.
            part.delete()
        }
        return dest
    }
}
