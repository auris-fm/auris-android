package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
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
        val (sink, context, losses) = sinkWithOpenPrefix(cap = 40L)
        val frames = listOf(ShortArray(8), ShortArray(8))
        val chargedBefore = sink.totalChargedBytesForTest()
        assertEquals(16L, chargedBefore)
        val accepted = sink.completed(SegmentObservation.Completed(context, frames, 16000, 0, frames.sumOf { it.size }, SegmentObservation.WakeState.NotDetected))
        assertFalse(accepted)
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
        val budget = CollectionBudget(64L)
        val losses = mutableListOf<String>()
        val sink = ContributionRetentionSink(budget, lossEventObserver = { event, _ -> losses += event })
        val context = ctx()
        assertTrue(sink.started(SegmentObservation.Started(context, ShortArray(8)))) // 16 charged
        val token = sink.reserveDelta(context, 16)!!
        assertEquals(48L, sink.totalChargedBytesForTest())
        val oversized = ShortArray(40)
        assertFalse(sink.commitDelta(context, oversized, 40, token))
        assertEquals(16L, sink.prefixBytesChargedForTest(context))
        assertEquals(32L, sink.outstandingLeaseBytesForTest())
        assertEquals(48L, sink.totalChargedBytesForTest())
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
        assertTrue(sink.commitDelta(context, ShortArray(8), 8, token))
        assertEquals(32L, sink.totalChargedBytesForTest())
    }
    @Test
    fun `the purge observer runs OUTSIDE the sink lock - another thread can take the lock while it fires`() {
        val observerBlocked = java.util.concurrent.CountDownLatch(1)
        val lockAcquiredByOther = java.util.concurrent.CountDownLatch(1)
        val releaseObserver = java.util.concurrent.CountDownLatch(1)
        val losses = mutableListOf<String>()
        val sink = ContributionRetentionSink(
            CollectionBudget(1L shl 20),
            lossEventObserver = { event, _ ->
                synchronized(losses) { losses += event }
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
        try {
            assertTrue(
                "another thread must acquire the sink lock WHILE the observer is still blocked",
                lockAcquiredByOther.await(2, java.util.concurrent.TimeUnit.SECONDS),
            )
        } finally {
            releaseObserver.countDown()
            purgeThread.join(5_000)
            otherThread.join(5_000)
        }
        assertTrue("the purge must complete once the observer is released", !purgeThread.isAlive)
        assertTrue("the second thread must finish", !otherThread.isAlive)
        synchronized(losses) { assertTrue(losses.any { it == "purge-all:account-change" }) }
    }
}
