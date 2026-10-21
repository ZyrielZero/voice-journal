package dev.zyriel.voicejournal.data

import android.content.ContentResolver
import android.net.Uri
import java.io.FileNotFoundException
import java.io.OutputStream

/**
 * Opens [uri] for a full overwrite. The one-argument openOutputStream uses
 * mode "w", which some SAF providers do not truncate, so exporting over a
 * larger existing file left its tail behind and produced a corrupt zip.
 * "wt" asks for truncation explicitly; providers that reject that mode get
 * a second try with plain "w".
 */
fun ContentResolver.openOutputStreamTruncating(uri: Uri): OutputStream? {
    val truncating = try {
        openOutputStream(uri, "wt")
    } catch (e: FileNotFoundException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: UnsupportedOperationException) {
        null
    }
    return truncating ?: openOutputStream(uri, "w")
}
