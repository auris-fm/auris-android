package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation


import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Reviewer controls for aggregate ownership at completion and delta growth.
class CompletionOwnershipTest {
    private fun ctx() = CaptureContext(UUID.randomUUID(), 1L, "Headset", IssuedAuthority("a@b.c", "e", "d"), wakeRequired = true)
    private fun sinkWithOpenPrefix(): Triple<ContributionRetentionSink, CaptureContext, MutableList<String>> {
        val losses = mutableListOf<String>()
        val sink = ContributionRetentionSink(lossEventObserver = { event, _ -> losses += event })
        val context = ctx()
        assertTrue(sink.started(SegmentObservation.Started(context, ShortArray(8))))
        return Triple(sink, context, losses)
    }


    @Test
    fun `pending-finalization CAP refuses the completion once the fixed count is full`() {
        // Spec cut 3's replacement bound: a FIXED number of recordings may wait for finalization.
        // At the cap the completion is refused outright — the open prefix ends with a terminal,
        // nothing is retained, nothing dangles.
        val (sink, context, losses) = sinkWithOpenPrefix()
        val frames = listOf(ShortArray(8), ShortArray(8))
        repeat(ContributionRetentionSink.MAX_PENDING_FINALIZATION) { n ->
            val c = ctx().copy(captureId = UUID.randomUUID())
            sink.started(SegmentObservation.Started(c, ShortArray(8)))
            assertTrue("PREMISE: candidate $n retained", sink.completed(SegmentObservation.Completed(c, frames, 16000, 0, frames.sumOf { it.size }, SegmentObservation.WakeState.NotDetected)))
        }
        assertEquals("PREMISE: the cap is full", ContributionRetentionSink.MAX_PENDING_FINALIZATION, sink.retainedSnapshot().size)
        val accepted = sink.completed(SegmentObservation.Completed(context, frames, 16000, 0, frames.sumOf { it.size }, SegmentObservation.WakeState.NotDetected))
        assertFalse("refused at the pending cap", accepted)
        assertFalse(sink.hasOpenPrefix(context))
        assertTrue(losses.any { it.contains("refused-handoff:pending-cap") })
    }


    @Test
    fun `successful completion transfers charges with no double count`() {
        val (sink, context, losses) = sinkWithOpenPrefix()
        val frames = listOf(ShortArray(8), ShortArray(8))
        assertTrue(sink.completed(SegmentObservation.Completed(context, frames, 16000, 0, frames.sumOf { it.size }, SegmentObservation.WakeState.NotDetected)))
        assertEquals(1, sink.retainedSnapshot().size)
    }


    @Test
    fun `clip-cap refusal releases the open prefix too`() {
        val context = ctx()
        val losses = mutableListOf<String>()
        val sink = ContributionRetentionSink(maxBytesPerClip = 16L, lossEventObserver = { event, _ -> losses += event })
        assertTrue(sink.started(SegmentObservation.Started(context, ShortArray(8))))
        val big = List(20) { ShortArray(100) } // 4_000 bytes > 16-byte per-clip cap
        assertFalse(sink.completed(SegmentObservation.Completed(context, big, 16000, 0, big.sumOf { it.size }, SegmentObservation.WakeState.NotDetected)))
        assertEquals(0L, sink.totalChargedBytesForTest())
        assertFalse(sink.hasOpenPrefix(context))
        assertTrue(losses.any { it.contains("completion-refused:clip-cap") })
    }


    @Test
    fun `growth beyond the per-clip cap mutates nothing and keeps the token charge intact`() {
        val losses = mutableListOf<String>()
        val sink = ContributionRetentionSink(maxBytesPerClip = 64L, lossEventObserver = { event, _ -> losses += event })
        val context = ctx()
        assertTrue(sink.started(SegmentObservation.Started(context, ShortArray(8)))) // 16 bytes
        val token = sink.reserveDelta(context, 16)!!
        val oversized = ShortArray(40) // 80 bytes capacity > 64-byte cap
        assertFalse(sink.commitDelta(context, oversized, 40, token))
        // The per-clip refusal ends THIS capture's retention with exactly one terminal; the
        // producer-held lease charge stays outstanding until the producer drops the array.
        assertFalse(sink.hasOpenPrefix(context))
        assertEquals(32L, sink.outstandingLeaseBytesForTest())
        sink.releaseDelta(context, token)
        assertEquals(0L, sink.outstandingLeaseBytesForTest())
    }


    @Test
    fun `invalid valid-range commits are refused before mutation`() {
        val (sink, context, losses) = sinkWithOpenPrefix()
        val token = sink.reserveDelta(context, 8)!!
        assertFalse(sink.commitDelta(context, ShortArray(8), 9, token)) // copied > capacity
        assertFalse(sink.commitDelta(context, ShortArray(8), -1, token)) // negative range
        // Token survives validation failures untouched.
        assertTrue(sink.commitDelta(context, ShortArray(8), 8, token))
    }


    @Test
    fun `the purge observer runs OUTSIDE the sink lock - another thread can take the lock while it fires`() {
        // REVIEWER trace: purgeAllForRevocation invoked logLoss inside its OUTER synchronized
        // block; logLoss's own inner synchronized is reentrant there, so releasing it did not
        // release the outer lock - the observer (a production LogBuffer file write) ran with the
        // sink lock held, stalling every budget/retention caller for the write's duration.
        val observerBlocked = java.util.concurrent.CountDownLatch(1)
        val lockAcquiredByOther = java.util.concurrent.CountDownLatch(1)
        val releaseObserver = java.util.concurrent.CountDownLatch(1)
        val losses = mutableListOf<String>()
        val sink = ContributionRetentionSink(
            lossEventObserver = { event, _ ->
                synchronized(losses) { losses += event }
                // The observer blocks (it is a file write) until the TEST releases it: there is no
                // self-timeout, so a broken (lock-holding) order cannot resume on its own and let
                // the second thread through while the control is no longer watching.
                observerBlocked.countDown()
                releaseObserver.await()
            },
        )
        sink.started(SegmentObservation.Started(ctx(), ShortArray(8)))


        val purgeThread = Thread { sink.purgeAllForRevocation("account-change") }
        purgeThread.start()
        assertTrue("observer must fire", observerBlocked.await(5, java.util.concurrent.TimeUnit.SECONDS))


        val otherThread = Thread {
            sink.retainedSnapshot()
            lockAcquiredByOther.countDown()
        }
        otherThread.start()
        // THE RESULT IS DECIDED BY ACQUISITION, NOT BY TIMEOUT SCHEDULING: while the observer is
        // still blocked (releaseObserver has not been touched), another thread must acquire the
        // sink lock. Under the broken order this latch never reaches zero - the broken code only
        // lets the other thread through after the observer's own 5s await expires.
        try {
            assertTrue(
                "another thread must acquire the sink lock WHILE the observer is still blocked",
                lockAcquiredByOther.await(2, java.util.concurrent.TimeUnit.SECONDS),
            )
        } finally {
            // Always release and join: a failed assertion must still clean up its threads.
            releaseObserver.countDown()
            purgeThread.join(5_000)
            otherThread.join(5_000)
        }
        assertTrue("the purge must complete once the observer is released", !purgeThread.isAlive)
        assertTrue("the second thread must finish", !otherThread.isAlive)
        synchronized(losses) { assertTrue(losses.any { it == "purge-all:account-change" }) }
    }
}
