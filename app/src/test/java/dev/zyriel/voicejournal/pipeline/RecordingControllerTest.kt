package dev.zyriel.voicejournal.pipeline

import dev.zyriel.voicejournal.audio.AudioRecorder
import dev.zyriel.voicejournal.audio.OrphanSweep
import dev.zyriel.voicejournal.audio.WavWriter
import dev.zyriel.voicejournal.data.JournalDao
import dev.zyriel.voicejournal.data.JournalEntry
import dev.zyriel.voicejournal.pipeline.RecordingController.PipelineState
import dev.zyriel.voicejournal.search.EmbeddingEngine
import dev.zyriel.voicejournal.whisper.ModelUnavailableException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The record -> transcribe -> embed -> insert state machine against fakes:
 * no microphone, no whisper, no Room, no service. Real dispatchers and real
 * files, so the races are the real ones.
 */
class RecordingControllerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dir: File = Files.createTempDirectory("controller").toFile()

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    /** Writes a real WAV on start; finalizes it on stop, like the capture loop. */
    private class FakeCapture(private val samplesOnStop: Int = 1600) : RecordingController.Capture {
        override val state = MutableStateFlow<AudioRecorder.State>(AudioRecorder.State.Idle)
        override val elapsedMs = MutableStateFlow(0L)
        var failStartWith: String? = null
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        private var file: File? = null

        override fun start(outFile: File): Boolean {
            failStartWith?.let { state.value = AudioRecorder.State.Error(it); return false }
            starts.incrementAndGet()
            file = outFile
            elapsedMs.value = 0
            state.value = AudioRecorder.State.Recording(outFile)
            return true
        }

        override suspend fun stopAndWait() {
            stops.incrementAndGet()
            val f = file ?: return
            file = null
            WavWriter(f).use { if (samplesOnStop > 0) it.appendSamples(ShortArray(samplesOnStop) { 40 }) }
            if (state.value is AudioRecorder.State.Recording) state.value = AudioRecorder.State.Idle
        }
    }

    private class FakeDao : JournalDao {
        val rows = CopyOnWriteArrayList<JournalEntry>()
        @Volatile var failInsert = false
        override suspend fun insert(entry: JournalEntry): Long {
            if (failInsert) throw IllegalStateException("disk I/O error")
            val id = rows.size + 1L
            rows += entry.copy(id = id)
            return id
        }
        override suspend fun update(entry: JournalEntry) = Unit
        override suspend fun delete(entry: JournalEntry) = Unit
        override fun observeAll(): Flow<List<JournalEntry>> = flowOf(rows.toList())
        override suspend fun allOnce(): List<JournalEntry> = rows.toList()
        override suspend fun needingEmbedding(model: String): List<JournalEntry> = emptyList()
        override suspend fun setEmbeddingIfUnchanged(id: Long, transcript: String, embedding: FloatArray, model: String) = 0
        override suspend fun setTranscript(id: Long, transcript: String) = 0
        override suspend fun countByAudioPath(path: String) = rows.count { it.audioPath == path }
    }

    private class FakeHost(var foreground: Boolean = true) : RecordingController.Host {
        val serviceStarts = AtomicInteger()
        override fun startService() { serviceStarts.incrementAndGet() }
        override fun canHostRecoveryService() = foreground
    }

    private val engine = object : EmbeddingEngine {
        override suspend fun embed(text: String) = floatArrayOf(text.length.toFloat())
    }

    private class Rig(
        val controller: RecordingController,
        val capture: FakeCapture,
        val dao: FakeDao,
        val host: FakeHost,
        val backfills: AtomicInteger,
        val started: CompletableDeferred<Unit>,
    )

    private fun rig(
        capture: FakeCapture = FakeCapture(),
        dao: FakeDao = FakeDao(),
        host: FakeHost = FakeHost(),
        withEngine: Boolean = true,
        maxClipMs: Long = 60_000,
        transcribe: suspend (File) -> String? = { "hello from ${it.name}" },
    ): Rig {
        val backfills = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val c = RecordingController(
            scope = scope,
            recorder = capture,
            dao = dao,
            dir = dir,
            transcribe = transcribe,
            loadEngine = { if (withEngine) engine else null },
            host = host,
            importInFlight = { false },
            // Backfill is requested last in the launch work, so it marks
            // "engine loaded and launch sweep done".
            backfill = { backfills.incrementAndGet(); started.complete(Unit) },
            now = { 1_700_000_000_000 },
            maxClipMs = maxClipMs,
        )
        c.start()
        return Rig(c, capture, dao, host, backfills, started)
    }

    private fun Rig.awaitLaunch() = runBlocking { withTimeout(10_000) { started.await() } }

    private fun Rig.awaitState(pred: (PipelineState) -> Boolean): PipelineState =
        runBlocking { withTimeout(10_000) { controller.pipeline.first(pred) } }

    private fun orphan(name: String): File =
        File(dir, name).also { f -> WavWriter(f).use { it.appendSamples(ShortArray(1600) { 30 }) } }

    // ---- The happy path ----

    @Test
    fun recordStopProducesOneEmbeddedEntry() {
        val r = rig()
        r.awaitLaunch()
        r.controller.startRecording()
        assertEquals(PipelineState.Recording, r.controller.pipeline.value)
        assertEquals(1, r.host.serviceStarts.get())

        r.controller.stopAndTranscribe()
        r.awaitState { it is PipelineState.Idle }

        val row = r.dao.rows.single()
        assertTrue(row.transcript.startsWith("hello from entry_"))
        assertEquals(1_700_000_000_000, row.timestampMs)
        assertTrue(File(row.audioPath).isFile)
        assertNotEquals(null, row.embedding)
        assertNotEquals(null, row.embeddingModel)
    }

    @Test
    fun silenceIsSavedAsNoSpeechNotAsAFailure() {
        val r = rig(transcribe = { "" })
        r.awaitLaunch()
        r.controller.startRecording()
        r.controller.stopAndTranscribe()
        r.awaitState { it is PipelineState.Idle }
        assertEquals("(no speech detected)", r.dao.rows.single().transcript)
    }

    @Test
    fun withoutAnEngineTheEntryIsSavedUnembedded() {
        val r = rig(withEngine = false)
        r.awaitLaunch()
        r.controller.startRecording()
        r.controller.stopAndTranscribe()
        r.awaitState { it is PipelineState.Idle }
        val row = r.dao.rows.single()
        assertNull(row.embedding)
        assertNull(row.embeddingModel)
    }

    // ---- Stop races ----

    @Test
    fun manyConcurrentStopsProduceExactlyOneEntry() {
        val r = rig()
        r.awaitLaunch()
        repeat(20) {
            r.controller.startRecording()
            assertEquals(PipelineState.Recording, r.controller.pipeline.value)
            // The FAB, the notification, audio focus and the collectors at once.
            List(6) { thread { r.controller.stopAndTranscribe() } }.forEach { it.join() }
            r.awaitState { it is PipelineState.Idle }
        }
        assertEquals(20, r.dao.rows.size)
        assertEquals(20, r.capture.stops.get())
        assertEquals(20, r.dao.rows.map { it.audioPath }.toSet().size)
    }

    @Test
    fun recordingsStartedWithinTheSameSecondGetDistinctFiles() {
        // now() is frozen, so every recording has the same timestamp name.
        val r = rig()
        r.awaitLaunch()
        repeat(3) {
            r.controller.startRecording()
            r.controller.stopAndTranscribe()
            r.awaitState { it is PipelineState.Idle }
        }
        val paths = r.dao.rows.map { it.audioPath }
        assertEquals(3, paths.toSet().size)
        assertTrue(paths.all { File(it).isFile })
    }

    @Test
    fun stopWhenNotRecordingIsIgnored() {
        val r = rig()
        r.awaitLaunch()
        r.controller.stopAndTranscribe()
        assertEquals(PipelineState.Idle, r.controller.pipeline.value)
        assertEquals(0, r.capture.stops.get())
    }

    @Test
    fun startWhileRecordingOrTranscribingIsIgnored() {
        val gate = CompletableDeferred<Unit>()
        val r = rig(transcribe = { gate.await(); "done" })
        r.awaitLaunch()
        r.controller.startRecording()
        r.controller.startRecording()
        assertEquals(1, r.capture.starts.get())

        r.controller.stopAndTranscribe()
        r.awaitState { it is PipelineState.Transcribing }
        r.controller.startRecording()
        r.controller.toggleRecording()
        assertEquals(1, r.capture.starts.get())

        gate.complete(Unit)
        r.awaitState { it is PipelineState.Idle }
        assertEquals(1, r.dao.rows.size)
    }

    // ---- Auto-stop and salvage ----

    @Test
    fun recordingStopsItselfAtTheLengthCap() {
        val r = rig(maxClipMs = 5_000)
        r.awaitLaunch()
        r.controller.startRecording()
        r.capture.elapsedMs.value = 4_999
        Thread.sleep(50)
        assertEquals(PipelineState.Recording, r.controller.pipeline.value)

        r.capture.elapsedMs.value = 5_000
        r.awaitState { it is PipelineState.Idle }
        assertEquals(1, r.dao.rows.size)
    }

    @Test
    fun aCaptureErrorMidRecordingSalvagesWhatWasCaptured() {
        val r = rig()
        r.awaitLaunch()
        r.controller.startRecording()
        r.capture.state.value = AudioRecorder.State.Error("AudioRecord.read error -3")

        // No stop was requested: the controller must notice and save the audio.
        r.awaitState { it is PipelineState.Idle }
        assertEquals(1, r.dao.rows.size)
    }

    @Test
    fun aCaptureErrorWithNothingCapturedFailsAndLeavesNoFile() {
        val r = rig(capture = FakeCapture(samplesOnStop = 0))
        r.awaitLaunch()
        r.controller.startRecording()
        r.capture.state.value = AudioRecorder.State.Error("mic revoked")

        val s = r.awaitState { it is PipelineState.Failed } as PipelineState.Failed
        assertTrue(s.message.contains("mic revoked"))
        assertTrue(r.dao.rows.isEmpty())
        assertTrue(dir.listFiles().orEmpty().none { it.extension == "wav" })
    }

    // ---- Failures ----

    @Test
    fun aFailedStartReportsTheRecordersReasonAndStartsNoService() {
        val capture = FakeCapture().apply { failStartWith = "Microphone permission missing" }
        val r = rig(capture = capture)
        r.awaitLaunch()
        r.controller.startRecording()
        assertEquals(PipelineState.Failed("Microphone permission missing"), r.controller.pipeline.value)
        assertEquals(0, r.host.serviceStarts.get())

        // Failed is not a dead end: dismiss, or just record again.
        capture.failStartWith = null
        r.controller.startRecording()
        assertEquals(PipelineState.Recording, r.controller.pipeline.value)
    }

    @Test
    fun aNativeTranscriptionFailureKeepsTheAudioAndInsertsNothing() {
        val r = rig(transcribe = { null })
        r.awaitLaunch()
        r.controller.startRecording()
        r.controller.stopAndTranscribe()
        r.awaitState { it is PipelineState.Failed }

        assertTrue(r.dao.rows.isEmpty())
        // The WAV stays, unreferenced, for the next launch's sweep.
        assertEquals(1, dir.listFiles().orEmpty().count { it.extension == "wav" })
        r.controller.dismissError()
        assertEquals(PipelineState.Idle, r.controller.pipeline.value)
    }

    @Test
    fun aFailedInsertEndsInFailedNotStuckInTranscribing() {
        val dao = FakeDao().apply { failInsert = true }
        val r = rig(dao = dao)
        r.awaitLaunch()
        r.controller.startRecording()
        r.controller.stopAndTranscribe()
        val s = r.awaitState { it is PipelineState.Failed } as PipelineState.Failed
        assertTrue(s.message.contains("disk I/O error"))
    }

    // ---- Launch recovery ----

    @Test
    fun launchSweepRecoversAnOrphanWithItsOwnTimestamp() {
        val wav = orphan("entry_old.wav")
        wav.setLastModified(1_600_000_000_000)
        val r = rig()
        r.awaitLaunch()

        val row = r.dao.rows.single()
        assertEquals(wav.absolutePath, row.audioPath)
        assertEquals(1_600_000_000_000, row.timestampMs)
        assertFalse(r.controller.recovering.value)
        assertEquals(1, r.host.serviceStarts.get())
        assertEquals(1, r.backfills.get())
    }

    @Test
    fun recoveryInTheBackgroundRunsWithoutTheService() {
        orphan("entry_old.wav")
        val r = rig(host = FakeHost(foreground = false))
        r.awaitLaunch()
        assertEquals(1, r.dao.rows.size)
        assertEquals(0, r.host.serviceStarts.get())
    }

    @Test
    fun aBrokenModelStopsTheSweepWithoutCountingAgainstTheAudio() {
        val a = orphan("entry_a.wav")
        val b = orphan("entry_b.wav")
        val calls = AtomicInteger()
        val r = rig(transcribe = { calls.incrementAndGet(); throw ModelUnavailableException("model missing") })
        r.awaitLaunch()

        assertEquals(1, calls.get()) // the second orphan was never tried
        assertTrue(r.dao.rows.isEmpty())
        assertTrue(a.isFile && b.isFile)
        assertFalse(File(a.path + OrphanSweep.FAILURE_SIDECAR).exists())
        assertEquals(0, r.controller.quarantined.value)
    }

    @Test
    fun quarantinedAudioIsCountedRetriedAndDeletable() {
        orphan("entry_bad.wav")
        // Earlier launches already used up its attempts.
        File(dir, "entry_bad.wav" + OrphanSweep.FAILURE_SIDECAR).writeText(OrphanSweep.MAX_ATTEMPTS.toString())
        val broken = MutableStateFlow(true)
        val r = rig(transcribe = { if (broken.value) null else "recovered at last" })
        r.awaitLaunch()
        assertEquals(1, r.controller.quarantined.value)
        assertEquals(1, r.controller.quarantinedFiles().size)
        assertTrue(r.dao.rows.isEmpty())

        // Still broken: the retry fails, but the file is back in play, not lost.
        r.controller.retryQuarantined()
        runBlocking { withTimeout(10_000) { r.controller.quarantined.first { it == 0 } } }
        assertTrue(File(dir, "entry_bad.wav").isFile)

        // Fixed: the next retry of the sweep turns it into an entry.
        broken.value = false
        r.controller.retryQuarantined()
        runBlocking {
            withTimeout(10_000) { while (r.dao.rows.isEmpty()) kotlinx.coroutines.delay(10) }
        }
        assertEquals("recovered at last", r.dao.rows.single().transcript)
    }

    @Test
    fun deleteQuarantinedRemovesTheFilesAndTheCount() {
        orphan("entry_bad.wav")
        File(dir, "entry_bad.wav" + OrphanSweep.FAILURE_SIDECAR).writeText(OrphanSweep.MAX_ATTEMPTS.toString())
        val r = rig(transcribe = { null })
        r.awaitLaunch()
        assertEquals(1, r.controller.quarantined.value)

        r.controller.deleteQuarantined()
        runBlocking { withTimeout(10_000) { r.controller.quarantined.first { it == 0 } } }
        assertTrue(r.controller.quarantinedFiles().isEmpty())
    }

    @Test
    fun aRecordingMadeDuringTheLaunchSweepIsNotSweptAsAnOrphan() {
        orphan("entry_old.wav")
        val gate = CompletableDeferred<Unit>()
        val seen = CopyOnWriteArrayList<String>()
        val r = rig(transcribe = { seen += it.name; if (it.name == "entry_old.wav") gate.await(); "text" })
        // The sweep is parked inside the orphan's transcription.
        runBlocking { withTimeout(10_000) { r.controller.recovering.first { it } } }
        r.controller.startRecording()
        assertEquals(PipelineState.Recording, r.controller.pipeline.value)
        gate.complete(Unit)
        r.awaitLaunch()
        r.controller.stopAndTranscribe()
        r.awaitState { it is PipelineState.Idle }

        // Two files, each transcribed exactly once.
        assertEquals(2, r.dao.rows.size)
        assertEquals(2, seen.size)
        assertEquals(2, seen.toSet().size)
    }
}
