package dev.zyriel.voicejournal.search

import android.content.res.AssetManager
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

object ModelAssets {
    /**
     * Maps an asset read-only, straight out of the APK, so a large model
     * never occupies the Java heap. Only works for assets stored
     * uncompressed (`noCompress` in app/build.gradle.kts covers .onnx);
     * openFd throws for a compressed one, and the build would have to be
     * fixed, not worked around here.
     */
    fun map(assets: AssetManager, path: String): ByteBuffer =
        assets.openFd(path).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { ch ->
                ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
}
