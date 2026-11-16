package dev.zyriel.voicejournal.whisper

import java.io.File

/**
 * Picks which build of the JNI library this CPU can run. Pure JVM so the
 * parsing is unit-tested against real /proc/cpuinfo samples.
 *
 * libwhisperjni.so is compiled for armv8.2-a+dotprod+fp16 and executes SDOT
 * and FP16 vector instructions; on a core without them (Cortex-A53/A73) the
 * first one is a SIGILL, which no try/catch can recover. The check must
 * therefore run before that library is loaded. The kernel lists the two
 * features as `asimddp` (HWCAP_ASIMDDP) and `asimdhp` (HWCAP_ASIMDHP) on
 * the `Features` line of every core.
 */
internal object CpuFeatures {

    const val OPTIMIZED_LIB = "whisperjni"
    const val COMPAT_LIB = "whisperjni_compat"

    /**
     * Written in filesDir before a probe transcription on [OPTIMIZED_LIB]
     * and deleted once it returns; see [FastLibMarker] for what it holds
     * and how many unfinished probes it takes to give up on the fast build.
     */
    const val FAST_INFLIGHT_MARKER = "whisper_fast.inflight"

    /** Written once a probe transcription on [OPTIMIZED_LIB] has returned. */
    const val FAST_VERIFIED_MARKER = "whisper_fast.ok"

    private val REQUIRED = setOf("asimddp", "asimdhp")

    /**
     * True only when every `Features` line lists both required flags. On a
     * big.LITTLE SoC the scheduler can move the thread to any core, so one
     * capable core is not enough. No `Features` line at all (unreadable or
     * unfamiliar format) means false: the slow library is always safe.
     */
    fun supportsDotprodFp16(cpuinfo: String): Boolean {
        val featureLines = cpuinfo.lineSequence()
            .filter { it.substringBefore(':').trim().equals("Features", ignoreCase = true) }
            .toList()
        if (featureLines.isEmpty()) return false
        return featureLines.all { line ->
            val flags = line.substringAfter(':').trim().split(Regex("\\s+")).toSet()
            flags.containsAll(REQUIRED)
        }
    }

    /**
     * @param fastCrashed whether [FastLibMarker.shouldAvoidFast] says the
     *   optimized build keeps dying here, so it is not chosen regardless
     *   of what cpuinfo claims.
     */
    fun libraryFor(cpuinfo: String?, fastCrashed: Boolean = false): String =
        if (!fastCrashed && cpuinfo != null && supportsDotprodFp16(cpuinfo)) OPTIMIZED_LIB else COMPAT_LIB

    /** Reads /proc/cpuinfo, or null if it cannot be read. */
    fun readCpuinfo(): String? = runCatching { File("/proc/cpuinfo").readText() }.getOrNull()
}

/**
 * Crash bookkeeping for the optimized library, kept outside WhisperBridge
 * because touching that object loads the library. A SIGILL kills the
 * process inside the native call, so the only way to notice one is a
 * marker written before the call that is still there at the next launch.
 *
 * A leftover marker is not proof of a bad instruction, though: the low
 * memory killer, a swipe from recents or a reboot during the same window
 * leaves exactly the same file. So the marker counts strikes instead of
 * deciding on one:
 *
 *  - Each probe writes `<buildId>:<strikes so far + 1>` and deletes the
 *    file when the native call returns. Only consecutive unfinished probes
 *    accumulate; one success clears the count.
 *  - At [MAX_STRIKES] the compat build is loaded instead. A genuine SIGILL
 *    is deterministic and gets there in two launches; a one-off kill does
 *    not.
 *  - The count belongs to one build ([buildId], the version code). An
 *    update starts clean, so a device parked on the slow library gets
 *    another chance with new code.
 *  - Once a probe returns, [CpuFeatures.FAST_VERIFIED_MARKER] records it
 *    for this build and later launches skip probing entirely, which closes
 *    the window for everything after the first good transcription.
 *
 * [dir] is set to filesDir by whoever builds a Transcriber, before
 * [WhisperBridge] is first touched. Null (JVM tests, or nothing set yet)
 * disables all of it. All file errors are swallowed: bookkeeping must
 * never be the reason transcription fails.
 */
internal object FastLibMarker {
    /** Consecutive unfinished probes after which the fast build is avoided. */
    const val MAX_STRIKES = 2

    @Volatile var dir: File? = null

    /** Identifies the installed build; strikes from another build are ignored. */
    @Volatile var buildId: String = ""

    fun file(): File? = dir?.let { File(it, CpuFeatures.FAST_INFLIGHT_MARKER) }

    private fun verifiedFile(): File? = dir?.let { File(it, CpuFeatures.FAST_VERIFIED_MARKER) }

    /**
     * Unfinished probes recorded for this build. A marker from another
     * build counts as none. A marker that exists but cannot be parsed (a
     * write cut short by the same crash) counts as one.
     */
    fun strikes(): Int {
        val f = file() ?: return 0
        if (!f.exists()) return 0
        val text = runCatching { f.readText().trim() }.getOrNull() ?: return 1
        val sep = text.lastIndexOf(':')
        if (sep < 0) return 1
        if (text.substring(0, sep) != buildId) return 0
        return text.substring(sep + 1).toIntOrNull()?.coerceAtLeast(1) ?: 1
    }

    /** True when the optimized build has used up its strikes on this build. */
    fun shouldAvoidFast(): Boolean = strikes() >= MAX_STRIKES

    /** True when a probe already returned on this build; no marker is needed. */
    fun isVerified(): Boolean {
        val f = verifiedFile() ?: return false
        return runCatching { f.exists() && f.readText().trim() == buildId }.getOrDefault(false)
    }

    /** Records that a probe is starting. Call right before the native call. */
    fun beginProbe() {
        val f = file() ?: return
        val next = strikes() + 1
        runCatching { f.writeText("$buildId:$next") }
    }

    /** Records that the probe returned: the count is cleared and the build verified. */
    fun endProbe() {
        runCatching { file()?.delete() }
        runCatching { verifiedFile()?.writeText(buildId) }
    }
}
