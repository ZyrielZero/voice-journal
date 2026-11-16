package dev.zyriel.voicejournal.whisper

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FastLibMarkerTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("fastlib").toFile()
        FastLibMarker.dir = dir
        FastLibMarker.buildId = "5"
    }

    @After
    fun tearDown() {
        FastLibMarker.dir = null
        FastLibMarker.buildId = ""
        dir.deleteRecursively()
    }

    private fun marker() = File(dir, CpuFeatures.FAST_INFLIGHT_MARKER)

    @Test
    fun freshInstallHasNoStrikesAndIsNotVerified() {
        assertEquals(0, FastLibMarker.strikes())
        assertFalse(FastLibMarker.shouldAvoidFast())
        assertFalse(FastLibMarker.isVerified())
    }

    @Test
    fun oneUnfinishedProbeIsNotEnoughToAvoidTheFastBuild() {
        // The process was killed once mid-transcription (lmkd, a swipe).
        FastLibMarker.beginProbe()
        assertEquals(1, FastLibMarker.strikes())
        assertFalse(FastLibMarker.shouldAvoidFast())
    }

    @Test
    fun consecutiveUnfinishedProbesAvoidTheFastBuild() {
        repeat(FastLibMarker.MAX_STRIKES) { FastLibMarker.beginProbe() }
        assertEquals(FastLibMarker.MAX_STRIKES, FastLibMarker.strikes())
        assertTrue(FastLibMarker.shouldAvoidFast())
    }

    @Test
    fun aFinishedProbeClearsTheCountAndVerifiesTheBuild() {
        FastLibMarker.beginProbe()
        FastLibMarker.beginProbe()
        FastLibMarker.endProbe()
        assertFalse(marker().exists())
        assertEquals(0, FastLibMarker.strikes())
        assertTrue(FastLibMarker.isVerified())
    }

    @Test
    fun strikesFromAnotherBuildAreIgnored() {
        repeat(3) { FastLibMarker.beginProbe() }
        assertTrue(FastLibMarker.shouldAvoidFast())
        FastLibMarker.buildId = "6" // the app was updated
        assertEquals(0, FastLibMarker.strikes())
        assertFalse(FastLibMarker.shouldAvoidFast())
        // The next probe on the new build starts counting from one.
        FastLibMarker.beginProbe()
        assertEquals("6:1", marker().readText())
    }

    @Test
    fun verificationBelongsToOneBuild() {
        FastLibMarker.beginProbe()
        FastLibMarker.endProbe()
        FastLibMarker.buildId = "6"
        assertFalse(FastLibMarker.isVerified())
    }

    @Test
    fun anUnparsableMarkerCountsAsOneStrike() {
        marker().writeText("")
        assertEquals(1, FastLibMarker.strikes())
        marker().writeText("whisperjni") // the pre-counter format
        assertEquals(1, FastLibMarker.strikes())
        marker().writeText("5:garbage")
        assertEquals(1, FastLibMarker.strikes())
    }

    @Test
    fun noDirectoryDisablesEverything() {
        FastLibMarker.dir = null
        FastLibMarker.beginProbe()
        FastLibMarker.endProbe()
        assertEquals(0, FastLibMarker.strikes())
        assertFalse(FastLibMarker.isVerified())
    }
}
