package dev.zyriel.voicejournal.pipeline

import dev.zyriel.voicejournal.pipeline.RecordingController.PipelineState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The auto-stop collector, the salvage collector, the FAB, the notification
 * Stop and audio-focus loss can all call stopAndTranscribe at once. Exactly
 * one may proceed, or one WAV becomes two entries.
 */
class StopCompareAndSetTest {

    @Test
    fun exactlyOneConcurrentStopWins() {
        repeat(200) {
            val flow = MutableStateFlow<PipelineState>(PipelineState.Recording)
            val go = CountDownLatch(1)
            val winners = AtomicInteger()
            val threads = List(8) {
                thread {
                    go.await()
                    if (flow.tryBeginStop()) winners.incrementAndGet()
                }
            }
            go.countDown()
            threads.forEach { it.join() }

            assertEquals(1, winners.get())
            assertEquals(PipelineState.Transcribing, flow.value)
        }
    }

    @Test
    fun stopIsRefusedOutsideRecording() {
        for (s in listOf(PipelineState.Idle, PipelineState.Transcribing, PipelineState.Failed("x"))) {
            val flow = MutableStateFlow(s)
            assertFalse(flow.tryBeginStop())
            assertEquals(s, flow.value)
        }
    }

    @Test
    fun stopFromRecordingMovesToTranscribing() {
        val flow = MutableStateFlow<PipelineState>(PipelineState.Recording)
        assertTrue(flow.tryBeginStop())
        assertEquals(PipelineState.Transcribing, flow.value)
    }
}
