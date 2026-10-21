package dev.zyriel.voicejournal

import org.junit.Assume.assumeTrue
import java.io.File

/**
 * Resolves committed and fetched files for JVM unit tests without relying
 * on the working directory. Gradle sets `vj.appDir` (app/build.gradle.kts);
 * an IDE runner that starts elsewhere falls back to probing the current
 * directory and `app/`. If neither finds the module, the calling test is
 * skipped through Assume instead of failing on a bogus relative path.
 */
object TestPaths {

    val appDir: File? by lazy {
        System.getProperty("vj.appDir")?.let { return@lazy File(it) }
        listOf(File("."), File("app"))
            .map { it.absoluteFile.normalize() }
            .firstOrNull { File(it, "src/main/AndroidManifest.xml").exists() }
    }

    private fun app(): File {
        val dir = appDir
        assumeTrue(
            "app module not found; set -Dvj.appDir=<repo>/app to run this test",
            dir != null && File(dir, "src/main/AndroidManifest.xml").exists(),
        )
        return dir!!
    }

    /** A file under app/src/main/assets (models are fetched, not tracked). */
    fun mainAsset(path: String): File = File(app(), "src/main/assets/$path")

    /** A file under app/src/debug/assets (the committed accuracy clips). */
    fun debugAsset(path: String): File = File(app(), "src/debug/assets/$path")

    /** A file relative to the repository root. */
    fun repoFile(path: String): File = File(app().parentFile, path)

    /** True when Gradle was run with -Pvj.perfAssertions=true. */
    val perfAssertions: Boolean
        get() = System.getProperty("vj.perfAssertions") == "true"
}
