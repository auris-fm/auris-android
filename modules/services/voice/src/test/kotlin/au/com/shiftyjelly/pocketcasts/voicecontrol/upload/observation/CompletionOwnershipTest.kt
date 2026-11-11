package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Reviewer b81c61df controls for aggregate ownership at completion and delta growth.
class CompletionOwnershipTest {

    private fun ctx() = CaptureContext(UUID.randomUUID(), 1L, "Headset", IssuedAuthority("a@b.c", 1L, 1L, "e", "d"), wakeRequired = true)

    private fun sinkWithOpenPrefix(cap: Long): Triple<ContributionRetentionSink, CaptureContext, MutableList<String>> {
        val losses = mutableListOf<String>()
        val sink = ContributionRetentionSink(CollectionBudget(cap), lossEventObserver = { event, _ -> losses += event })
        val context = ctx()
        assertTrue(sink.started(SegmentObservation.Started(context, ShortArray(8))))
        return Triple(sink, context, losses)
    }

    @Test
    fun `completion reserves peak - prefix plus flattened output charged together`() {
        // Budget fits EITHER the 16-byte prefix OR the 32-byte output, never both (peak = 48).
        val (sink, context) = sinkWithOpenPrefix(cap = 40L)
        // Budget fits either the 16-byte prefix or the 32-byte output, never both (peak = 48).
        val (sink, context) = sinkWithOpenPrefix(cap = 40L)
        val (sink, context, losses) = sinkWithOpenPrefix(cap = 40L)
        val frames = listOf(ShortArray(8), ShortArray(8))
        val chargedBefore = sink.totalChargedBytesForTest()
        assertEquals(16L, chargedBefore)
        // fullBytes = 32; reserve(32) against 16 already charged => peak 48 > 40 => REFUSED.
        val accepted = sink.completed(SegmentObservation.Completed(context, frames, 16000, 0, frames.sumOf { it.size }, SegmentObservation.WakeState.NotDetected))
        assertFalse(accepted)
        // Refused completion CANCELS: prefix released entirely, nothing retained-open, no orphan.
        assertEquals(0L, sink.totalChargedBytesForTest())
        assertFalse(sink.hasOpenPrefix(context))
        assertTrue(losses.any { it.contains("completion-refused") })
        assertEquals(0, sink.retainedSnapshot().size)
    }

    @Test
    fun `successful completion transfers charges with no double count`() {
        val (sink, context, losses) = sinkWithOpenPrefix(cap = 1_000L)
        val frames = listOf(ShortArray(8), ShortArray(8))
        assertTrue(sink.completed(SegmentObservation.Completed(context, frames, 16000, 0, frames.sumOf { it.size }, SegmentObservation.WakeState.NotDetected)))
        // Exactly fullBytes (32) remains charged — retired prefix charge left once, new buffer owns its own.
        assertEquals(32L, sink.totalChargedBytesForTest())
        assertEquals(1, sink.retainedSnapshot().size)
    }

    @Test
    fun `clip-cap refusal releases the open prefix too`() {
        val context = ctx()
        val losses = mutableListOf<String>()
        val sink = ContributionRetentionSink(CollectionBudget(10_000L), maxBytesPerClip = 16L, lossEventObserver = { event, _ -> losses += event })
        assertTrue(sink.started(SegmentObservation.Started(context, ShortArray(8))))
        val big = List(20) { ShortArray(100) } // 4_000 bytes > 16-byte per-clip cap
        assertFalse(sink.completed(SegmentObservation.Completed(context, big, 16000, 0, big.sumOf { it.size }, SegmentObservation.WakeState.NotDetected)))
        assertEquals(0L, sink.totalChargedBytesForTest())
        assertFalse(sink.hasOpenPrefix(context))
        assertTrue(losses.any { it.contains("completion-refused:clip-cap") })
    }

    @Test
    fun `growth reservation failure mutates nothing and keeps token charge intact`() {
        // Budget leaves room for the reservation but NOT for the capacity-growth gap.
        val budget = CollectionBudget(64L)
        val losses = mutableListOf<String>()
        val sink = ContributionRetentionSink(budget, lossEventObserver = { event, _ -> losses += event })
        val context = ctx()
        assertTrue(sink.started(SegmentObservation.Started(context, ShortArray(8)))) // 16 charged
        // Reserve a 16-sample (32-byte) delta: 16 + 32 = 48 <= 64 -> admitted.
        val token = sink.reserveDelta(context, 16)!!
        assertEquals(48L, sink.totalChargedBytesForTest())
        // Commit an array LARGER than reserved (24 samples = 48 bytes): gap 16 bytes would push
        // total to 64+... wait: releasing nothing yet; growth extra = 48-32 = 16 -> 48+16=64 OK.
        // Use a bigger overrun instead: 40 samples = 80 bytes, gap 32 -> 48+32=80 > 64 -> refuse.
        val oversized = ShortArray(40)
        assertFalse(sink.commitDelta(context, oversized, 40, token))
        // NOTHING was appended to the prefix by the refused commit: prefix still holds only the
        // original first chunk (16 bytes charged), and the token's 32-byte lease is UNTOUCHED.
        assertEquals(16L, sink.prefixBytesChargedForTest(context))
        assertEquals(32L, sink.outstandingLeaseBytesForTest())
        assertEquals(48L, sink.totalChargedBytesForTest())
        // Producer drops its array: exactly the token charge leaves; no leak, no lost charge.
        sink.releaseDelta(context, token)
        assertEquals(16L, sink.totalChargedBytesForTest())
        assertEquals(0L, sink.outstandingLeaseBytesForTest())
    }

    @Test
    fun `invalid valid-range commits are refused before mutation`() {
        val (sink, context, losses) = sinkWithOpenPrefix(cap = 1_000L)
        val token = sink.reserveDelta(context, 8)!!
        assertFalse(sink.commitDelta(context, ShortArray(8), 9, token)) // copied > capacity
        assertFalse(sink.commitDelta(context, ShortArray(8), -1, token)) // negative range
        // Token survives validation failures untouched.
        assertTrue(sink.commitDelta(context, ShortArray(8), 8, token))
        assertEquals(32L, sink.totalChargedBytesForTest())
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
            CollectionBudget(1L shl 20),
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
