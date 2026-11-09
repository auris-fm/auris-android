package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue2

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UploaderLoopTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun queue() = FolderQueue(tmp.newFolder("q"), 32, {})

    private fun publish(q: FolderQueue, id: String, now: Long = 1_000L) = q.publish(id, { it.writeBytes(byteArrayOf(1)) }, "{}".toByteArray(), now)

    @Test
    fun `an accepted recording is deleted from the queue`() = runTest {
        val q = queue()
        publish(q, "a")
        val loop = UploaderLoop(q, { 201 }, {}, now = { 10_000 })
        val r = loop.runOnce()
        assertEquals(1, r.accepted)
        assertEquals(0, q.count())
    }

    @Test
    fun `a final refusal deletes and a server error keeps`() = runTest {
        val q = queue()
        publish(q, "refused", now = 1_000)
        publish(q, "serverError", now = 2_000)
        val loop = UploaderLoop(
            q,
            { rec -> if (rec.captureId == "refused") 410 else 503 },
            {},
            now = { 10_000 },
        )
        val r = loop.runOnce()
        assertEquals(1, r.deleted)
        assertEquals(1, r.retried)
        assertEquals("the refused one is gone, the errored one stays", listOf("serverError"), q.recordings().map { it.captureId })
    }

    @Test
    fun `401 refreshes sign-in and keeps the recording for the next attempt`() = runTest {
        val q = queue()
        publish(q, "a")
        var refreshed = 0
        val loop = UploaderLoop(q, { 401 }, { refreshed++ }, now = { 10_000 })
        val r = loop.runOnce()
        assertEquals(1, r.refreshed)
        assertEquals(1, refreshed)
        assertEquals("a lapsed sign-in must NOT delete the recording", 1, q.count())
    }

    @Test
    fun `a failing recording does not stall the ones behind it`() = runTest {
        // This is the property the owner singled out: occasional loss is fine, a stall is not.
        val q = queue()
        publish(q, "stuck", now = 1_000)
        publish(q, "fine", now = 2_000)
        val sent = mutableListOf<String>()
        val loop = UploaderLoop(
            q,
            { rec ->
                sent.add(rec.captureId)
                if (rec.captureId == "stuck") 503 else 201
            },
            {},
            now = { 10_000 },
        )
        loop.runOnce()
        assertEquals(
            "the good recording must still be attempted after the failing one",
            listOf("stuck", "fine"),
            sent,
        )
        assertEquals(listOf("stuck"), q.recordings().map { it.captureId })
    }

    @Test
    fun `backoff grows and is capped at one hour`() = runTest {
        val q = queue()
        publish(q, "a")
        var t = 10_000L
        val loop = UploaderLoop(
            q,
            { 503 },
            {},
            now = { t },
            baseBackoffMs = 60_000,
            maxBackoffMs = 60L * 60 * 1000,
        )
        val seen = mutableListOf<Long>()
        repeat(10) {
            loop.runOnce()
            seen.add(loop.backoffRemainingMs("a"))
            t += 100_000_000
        }
        assertTrue("backoff must grow from the base", seen.first() >= 60_000)
        assertTrue("backoff must never exceed one hour, saw ${seen.max()}", seen.all { it <= 60L * 60 * 1000 })
    }

    @Test
    fun `a recording in backoff is skipped, not retried, on the next pass`() = runTest {
        val q = queue()
        publish(q, "a")
        var t = 10_000L
        var sends = 0
        val loop = UploaderLoop(q, {
            sends++
            503
        }, {}, now = { t }, baseBackoffMs = 60_000)
        loop.runOnce()
        assertEquals(1, sends)
        // Immediately again: still inside the backoff window, so no second request goes out.
        loop.runOnce()
        assertEquals("a recording in backoff must not be attempted again", 1, sends)
        // Once the window passes it is attempted again.
        t += 61_000
        loop.runOnce()
        assertEquals(2, sends)
    }

    @Test
    fun `a thrown request is treated as no answer and retried`() = runTest {
        val q = queue()
        publish(q, "a")
        val loop = UploaderLoop(q, { throw java.io.IOException("offline") }, {}, now = { 10_000 })
        val r = loop.runOnce()
        assertEquals(1, r.retried)
        assertEquals("a network failure must never delete a recording", 1, q.count())
    }
}
