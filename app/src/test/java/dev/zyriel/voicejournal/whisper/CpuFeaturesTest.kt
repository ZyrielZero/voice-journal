package dev.zyriel.voicejournal.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuFeaturesTest {

    private val a55 = "fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp"
    private val a53 = "fp asimd evtstrm aes pmull sha1 sha2 crc32 cpuid"

    private fun cpuinfo(vararg features: String) = features.mapIndexed { i, f ->
        "processor\t: $i\nBogoMIPS\t: 52.00\nFeatures\t: $f\nCPU implementer\t: 0x41\nCPU part\t: 0xd05\n"
    }.joinToString("\n")

    @Test
    fun allCoresWithDotprodAndFp16PickOptimized() {
        val info = cpuinfo(a55, a55, a55, a55)
        assertTrue(CpuFeatures.supportsDotprodFp16(info))
        assertEquals(CpuFeatures.OPTIMIZED_LIB, CpuFeatures.libraryFor(info))
    }

    @Test
    fun baselineCoresPickCompat() {
        val info = cpuinfo(a53, a53)
        assertFalse(CpuFeatures.supportsDotprodFp16(info))
        assertEquals(CpuFeatures.COMPAT_LIB, CpuFeatures.libraryFor(info))
    }

    @Test
    fun oneIncapableCoreForcesCompat() {
        assertEquals(CpuFeatures.COMPAT_LIB, CpuFeatures.libraryFor(cpuinfo(a55, a55, a53)))
    }

    @Test
    fun dotprodWithoutFp16IsNotEnough() {
        val info = cpuinfo("fp asimd asimddp")
        assertEquals(CpuFeatures.COMPAT_LIB, CpuFeatures.libraryFor(info))
    }

    @Test
    fun substringsOfFlagsDoNotCount() {
        // "asimddp" must match as a whole flag, not inside a longer word.
        val info = cpuinfo("fp asimddpx asimdhpx")
        assertEquals(CpuFeatures.COMPAT_LIB, CpuFeatures.libraryFor(info))
    }

    @Test
    fun missingOrUnreadableCpuinfoPicksCompat() {
        assertEquals(CpuFeatures.COMPAT_LIB, CpuFeatures.libraryFor(null))
        assertEquals(CpuFeatures.COMPAT_LIB, CpuFeatures.libraryFor(""))
        assertEquals(CpuFeatures.COMPAT_LIB, CpuFeatures.libraryFor("Hardware\t: Qualcomm\n"))
    }

    @Test
    fun singleGlobalFeaturesLineIsHonored() {
        // Some kernels print one Features line for the whole SoC.
        val info = "Processor\t: AArch64 Processor rev 0 (aarch64)\nFeatures\t: $a55\nHardware\t: Tensor\n"
        assertEquals(CpuFeatures.OPTIMIZED_LIB, CpuFeatures.libraryFor(info))
    }
}
