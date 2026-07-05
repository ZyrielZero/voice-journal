package dev.zyriel.voicejournal.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Zip-based export/import so a journal survives uninstall. Pure JVM
 * (java.util.zip + org.json, which Android ships in the framework), so the
 * whole format is unit-testable without a device.
 *
 * Layout:
 *   manifest.json           format version, app version, entry metadata
 *   audio/<n>_<name>.wav    one file per entry that has audio
 *
 * Embeddings are deliberately NOT exported: they are derived data tied to
 * a specific model tag, and the existing backfill re-embeds anything with
 * a null vector on the next launch. Exporting them would couple the
 * archive format to the embedding model for zero benefit.
 *
 * Import is stream-order-independent: audio entries and the manifest are
 * collected in a single pass and joined at the end, so archives repacked
 * by other zip tools still import.
 */
object JournalArchive {

    const val FORMAT_VERSION = 1
    private const val MANIFEST = "manifest.json"
    private const val AUDIO_DIR = "audio/"

    /** One entry as read back from an archive. [audioPath] null when the archive had no audio for it. */
    data class ImportedEntry(
        val transcript: String,
        val timestampMs: Long,
        val audioPath: String?,
    )

    class ArchiveFormatException(message: String) : Exception(message)

    /**
     * Writes [entries] and their audio to [out] as a zip. [resolveAudio]
     * maps an entry to its WAV, returning null (or a nonexistent file) for
     * entries whose audio is already gone; those export transcript-only.
     */
    fun export(
        entries: List<JournalEntry>,
        resolveAudio: (JournalEntry) -> File?,
        out: OutputStream,
        appVersion: String,
    ) {
        ZipOutputStream(out.buffered()).use { zip ->
            val manifest = JSONObject()
            manifest.put("formatVersion", FORMAT_VERSION)
            manifest.put("appVersion", appVersion)
            manifest.put("exportedAtMs", System.currentTimeMillis())

            val arr = JSONArray()
            val audioJobs = ArrayList<Pair<String, File>>()
            entries.forEachIndexed { i, entry ->
                val o = JSONObject()
                o.put("transcript", entry.transcript)
                o.put("timestampMs", entry.timestampMs)
                val wav = resolveAudio(entry)
                if (wav != null && wav.isFile && wav.length() > 0) {
                    val zipName = AUDIO_DIR + "${i}_${wav.name}"
                    o.put("audio", zipName)
                    audioJobs += zipName to wav
                }
                arr.put(o)
            }
            manifest.put("entries", arr)

            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            for ((zipName, wav) in audioJobs) {
                zip.putNextEntry(ZipEntry(zipName))
                wav.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Reads an archive from [inp]. For every audio member, [audioSink] is
     * given a safe filename suggestion plus the member's stream and returns
     * the absolute path it wrote to, or null to drop that audio. Returns
     * the manifest's entries joined with the sink's paths.
     *
     * @throws ArchiveFormatException on a missing manifest or an
     *   unsupported format version. Entries referencing audio members that
     *   are absent from the zip import transcript-only rather than failing
     *   the whole archive.
     */
    fun import(
        inp: InputStream,
        audioSink: (suggestedName: String, data: InputStream) -> String?,
    ): List<ImportedEntry> {
        var manifest: JSONObject? = null
        val writtenAudio = HashMap<String, String>() // zip member name -> absolute path

        ZipInputStream(inp.buffered()).use { zip ->
            var e: ZipEntry? = zip.nextEntry
            while (e != null) {
                when {
                    e.name == MANIFEST -> {
                        manifest = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                    }
                    !e.isDirectory && e.name.startsWith(AUDIO_DIR) -> {
                        // Strip any path component from the member name so a
                        // crafted archive cannot write outside the sink's dir.
                        val safe = File(e.name).name
                        audioSink(safe, zip)?.let { writtenAudio[e.name] = it }
                    }
                }
                zip.closeEntry()
                e = zip.nextEntry
            }
        }

        val m = manifest ?: throw ArchiveFormatException("not a Voice Journal archive: no $MANIFEST")
        val version = m.optInt("formatVersion", -1)
        if (version != FORMAT_VERSION) {
            throw ArchiveFormatException("unsupported archive format version $version (expected $FORMAT_VERSION)")
        }

        val arr = m.optJSONArray("entries") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ImportedEntry(
                transcript = o.getString("transcript"),
                timestampMs = o.getLong("timestampMs"),
                audioPath = o.optString("audio", "").takeIf { it.isNotEmpty() }?.let { writtenAudio[it] },
            )
        }
    }
}
