package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recording sink + gate spy for the observer controls. */
private class SpySink : SegmentSink {
    val started = mutableListOf<SegmentObservation.Started>()
    val incremental = mutableListOf<SegmentObservation.Incremental>()
    val completed = mutableListOf<SegmentObservation.Completed>()
    val cancelled = mutableListOf<SegmentObservation.Cancelled>()
    var acceptsStarts = true

    override fun started(observation: SegmentObservation.Started): Boolean {
        started += observation
        return acceptsStarts
    }

    override fun incremental(observation: SegmentObservation.Incremental) {
        incremental += observation
    }

    override fun completed(observation: SegmentObservation.Completed) {
        completed += observation
    }

    override fun cancelled(observation: SegmentObservation.Cancelled) {
        cancelled += observation
    }
}

class SegmentObserverTest {

    @Test
    fun `gate refusal at START retains NOTHING - zero observations, zero PCM`() {
        var gateCalls = 0
        val sink = SpySink()
        val observer = SegmentObserver(
            CollectionGate { _, _ ->
                gateCalls++
                false
            },
            sink,
        )

        observer.speechStarted(captureSession = 1L, routeClass = "Headset")
        observer.speechIncremental(160)
        observer.wakeCompleted(ShortArray(320), 16000, 0, 300, SegmentObservation.WakeState.NotDetected)

        assertEquals(1, gateCalls) // consulted EXACTLY once, at start — before any allocation
        assertTrue(sink.started.isEmpty())
        assertTrue(sink.incremental.isEmpty())
        assertTrue(sink.completed.isEmpty())
        assertTrue(sink.cancelled.isEmpty())
    }

    @Test
    fun `context is frozen at START and stays IMMUTABLE through every later event`() {
        val sink = SpySink()
        val observer = SegmentObserver(CollectionGate { _, _ -> true }, sink)

        val captureId = observer.speechStarted(7L, "Headset")
        observer.speechIncremental(100)
        observer.wakeCompleted(ShortArray(10), 16000, 1, 9, SegmentObservation.WakeState.Detected)

        val startedContext = sink.started.single().context
        val completedContext = sink.completed.single().context
        assertEquals(captureId, startedContext.captureId)
        assertEquals(startedContext, completedContext) // same frozen context, unchanged
        assertEquals(7L, completedContext.captureSession)
        assertEquals("Headset", completedContext.routeClass)
    }

    @Test
    fun `cancel discards the partial and closes the capture - late completion is a NO-OP`() {
        val sink = SpySink()
        val observer = SegmentObserver(CollectionGate { _, _ -> true }, sink)

        val id = observer.speechStarted(1L, "Headset")
        observer.speechIncremental(50)
        observer.cancelled("TooShort")
        // A queued LATE outcome for the cancelled capture:
        observer.wakeCompleted(ShortArray(8), 16000, 0, 7, SegmentObservation.WakeState.NotDetected)

        assertEquals(1, sink.cancelled.size)
        assertEquals(id, sink.cancelled.single().context.captureId)
        assertTrue("late completion after cancel must not retain audio", sink.completed.isEmpty())
        assertFalse(observer.isOpen(id))
    }

    @Test
    fun `incremental accounting is BOUNDED by collector ownership - refusing sink gets nothing more`() {
        val sink = SpySink().apply { acceptsStarts = false } // sink declines to own retention
        val observer = SegmentObserver(CollectionGate { _, _ -> true }, sink)

        observer.speechStarted(2L, "Speaker")
        observer.speechIncremental(100)
        observer.wakeCompleted(ShortArray(4), 16000, 0, 3, SegmentObservation.WakeState.NotDetected)

        assertEquals(1, sink.started.size) // asked once
        assertTrue(sink.incremental.isEmpty()) // never fed again after ownership declined
        assertTrue(sink.completed.isEmpty())
    }

    @Test
    fun `each capture gets a FRESH identity while the session incarnation is preserved`() {
        val sink = SpySink()
        val observer = SegmentObserver(CollectionGate { _, _ -> true }, sink)

        val first = observer.speechStarted(3L, "Headset")
        observer.cancelled("pipeline-stopped")
        val second = observer.speechStarted(3L, "Headset")
        assertNotEquals(first, second)
        observer.wakeCompleted(ShortArray(2), 16000, 0, 1, SegmentObservation.WakeState.NotDetected)

        assertEquals(2, sink.started.size)
        assertEquals(second, sink.completed.single().context.captureId)
        assertEquals(3L, sink.completed.single().context.captureSession)
    }

    @Test
    fun `events between captures are DROPPED - no orphan context exists`() {
        val sink = SpySink()
        val observer = SegmentObserver(CollectionGate { _, _ -> true }, sink)
        observer.speechIncremental(100)
        observer.cancelled("stray")
        assertTrue(sink.incremental.isEmpty())
        assertTrue(sink.cancelled.isEmpty())
        assertNull(sink.started.firstOrNull())
    }

    @Test
    fun `wake observation state rides the completed segment`() {
        val sink = SpySink()
        val observer = SegmentObserver(CollectionGate { _, _ -> true }, sink)
        observer.speechStarted(9L, "BluetoothA2dpOnly")
        observer.wakeCompleted(ShortArray(16), 16000, 2, 15, SegmentObservation.WakeState.Detected)

        val done = sink.completed.single()
        assertEquals(SegmentObservation.WakeState.Detected, done.wakeState)
        assertEquals("BluetoothA2dpOnly", done.context.routeClass)
        assertEquals(16, done.pcm.size) // exactly the VAD's samples
    }

    private fun uuid(): UUID = UUID.randomUUID()
}
