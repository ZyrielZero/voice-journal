package dev.zyriel.voicejournal.audio

import java.io.File
import java.io.IOException
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
 * temp+rename shape, a death mid-copy leaves only a .part file that
 * [deleteStaleSiblings] later removes; the destination either doesn't exist
 * or is the complete artifact. Rename within the same directory is atomic on
 * the filesystems Android app storage uses.
 *
 * Each call copies into its own uniquely named temp file. An earlier version
 * used a fixed `<name>.part` opened with truncation, so a second concurrent
 * writer zeroed the first one's file mid-copy and the first rename could
 * publish a file with a zero-filled hole. Callers should still serialize
 * materialization (two copies of 60 MB is wasted work), but a race can no
 * longer corrupt the result.
 *
 * Pure JVM so the failure modes are unit-tested with a throwing stream.
 */
object AtomicMaterialize {

    /** Pass as `expectedLength` when the source size is not known. */
    const val UNKNOWN_LENGTH = -1L

    /**
     * Ensures [dest] contains the full contents of the stream [open]
     * produces. No-op if [dest] already exists non-empty and, when
     * [expectedLength] is known, has exactly that length. A destination of
     * the wrong length (a torn write from an older build, or an asset that
     * changed under the same name) is replaced. Throws whatever [open] or
     * the copy throws, and [IOException] when the copy's length does not
     * match [expectedLength]; on any failure no file is published at
     * [dest] and the temp file is removed.
     *
     * @return dest, for call-site convenience.
     */
    fun ensure(dest: File, expectedLength: Long = UNKNOWN_LENGTH, open: () -> InputStream): File {
        if (dest.isFile && dest.length() > 0L &&
            (expectedLength < 0L || dest.length() == expectedLength)
        ) return dest
        // A zero-byte dest is a legacy artifact of the old pattern; a
        // wrong-length one is corrupt or stale. Either way it must go, and
        // if it cannot, renaming over it would fail later anyway.
        if (dest.exists() && !dest.delete()) {
            throw IOException("cannot delete stale ${dest.name}")
        }
        // The fixed temp name older builds used. No writer uses it now, so
        // removing it races with nothing; a failed delete is left for
        // deleteStaleSiblings.
        File(dest.parentFile, dest.name + ".part").delete()

        open().use { input ->
            val part = File.createTempFile(dest.name, ".part", dest.parentFile)
            try {
                part.outputStream().use { input.copyTo(it) }
                if (expectedLength >= 0L && part.length() != expectedLength) {
                    throw IOException(
                        "copied ${part.length()} bytes for ${dest.name}, expected $expectedLength"
                    )
                }
                if (!part.renameTo(dest)) {
                    throw IOException("rename ${part.name} -> ${dest.name} failed")
                }
            } finally {
                // Success renamed it away; failure must not leave the
                // partial. A delete that fails here leaves a uniquely named
                // .part that deleteStaleSiblings removes on a later launch.
                if (part.exists()) part.delete()
            }
        }
        return dest
    }

    /**
     * Deletes materialized files in [dir] whose names match [isManaged] but
     * are not in [keep], plus any `.part` temp files those names left
     * behind. Model file names carry their version, so a model bump leaves
     * the previous file in filesDir forever unless something removes it.
     *
     * Must not run while a materialization into [dir] is in flight: it
     * would delete that copy's temp file.
     *
     * @return names that could not be deleted (empty on full success).
     */
    fun deleteStaleSiblings(
        dir: File,
        keep: Set<String>,
        isManaged: (String) -> Boolean,
    ): List<String> {
        val files = dir.listFiles() ?: return emptyList()
        val failed = ArrayList<String>()
        for (f in files) {
            if (!f.isFile) continue
            val name = f.name
            val stale = if (name.endsWith(".part")) {
                isManaged(stripTempSuffix(name.removeSuffix(".part")))
            } else {
                isManaged(name) && name !in keep
            }
            if (stale && !f.delete()) failed += name
        }
        return failed
    }

    /**
     * Temp files are `<dest name><random digits>.part` (createTempFile) or
     * the legacy `<dest name>.part`; strip the digits to recover the dest
     * name for [deleteStaleSiblings].
     */
    private fun stripTempSuffix(base: String): String = base.trimEnd { it.isDigit() }
}
