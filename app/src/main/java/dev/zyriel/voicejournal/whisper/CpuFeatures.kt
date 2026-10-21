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

    fun libraryFor(cpuinfo: String?): String =
        if (cpuinfo != null && supportsDotprodFp16(cpuinfo)) OPTIMIZED_LIB else COMPAT_LIB

    /** Reads /proc/cpuinfo, or null if it cannot be read. */
    fun readCpuinfo(): String? = runCatching { File("/proc/cpuinfo").readText() }.getOrNull()
}
