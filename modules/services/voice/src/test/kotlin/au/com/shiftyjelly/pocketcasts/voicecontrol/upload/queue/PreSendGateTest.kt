package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The checks that run before a queued recording is sent.
 *
 * A queued recording is a claim about the past — that this account was enrolled, under this epoch,
 * with sharing on, and that it has not expired. Collection can happen long before the queue drains,
 * so each of those can be false by the time the loop reaches it. These controls pin the loop's
 * answer to each: delete, never send.
 */
class PreSendGateTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val now = 1_000_000_000L

    private fun queueWith(
        epoch: String = "epoch-1",
        account: String = "a@example.test",
        publishedAt: Long = now,
    ): Pair<FolderQueue, String> {
        val q = FolderQueue(tmp.newFolder("q"), 32, {})
        val id = java.util.UUID.randomUUID().toString()
        val metadata = """
            {"schema_version":1,"capture_id":"$id","enrollment_epoch":"$epoch","account":"$account"}
        """.trimIndent().toByteArray()
        val published = q.publish(
            captureId = id,
            audioWriter = { file -> file.writeBytes(ByteArray(64) { 1 }) },
            metadataBytes = metadata,
            nowMs = publishedAt,
        )
        assertTrue("PREMISE: the recording is queued", published != null)
        return q to id
    }

    private fun gate(
        account: String? = "a@example.test",
        epoch: String? = "epoch-1",
        sharing: Boolean = true,
    ) = AccountPreSendGate(
        currentAccount = { account },
        currentEpoch = { epoch },
        sharingEnabled = { sharing },
        now = { now },
    )

    @Test
    fun `a current recording is sent`() {
        val (q, _) = queueWith()
        assertEquals(GateVerdict.Send, gate().verdictFor(q.recordings().single()))
    }

    /**
     * The user's current answer outranks anything already queued: turning sharing off must stop the
     * queue now, not at the next sign-in.
     */
    @Test
    fun `sharing switched off stops a queued recording and it is deleted unsent`() = runTest {
        val (q, _) = queueWith()
        var sent = 0
        val loop = UploaderLoop(q, {
            sent++
            201
        }, {}, gate = gate(sharing = false), now = { now })

        loop.runOnce()

        assertEquals("a withdrawn recording must never reach the network", 0, sent)
        assertEquals("and it must not sit in the queue forever either", 0, q.count())
    }

    /** A recording captured under a previous enrollment is not this enrollment's data. */
    @Test
    fun `a recording from a superseded enrollment epoch is deleted unsent`() = runTest {
        val (q, _) = queueWith(epoch = "epoch-old")
        var sent = 0
        val loop = UploaderLoop(q, {
            sent++
            201
        }, {}, gate = gate(epoch = "epoch-new"), now = { now })

        loop.runOnce()

        assertEquals(0, sent)
        assertEquals(0, q.count())
    }

    /** Signing out leaves no account to which the recording could be attributed. */
    @Test
    fun `a signed-out device sends nothing and drops the recording`() = runTest {
        val (q, _) = queueWith()
        var sent = 0
        val loop = UploaderLoop(q, {
            sent++
            201
        }, {}, gate = gate(account = null), now = { now })

        loop.runOnce()

        assertEquals(0, sent)
        assertEquals(0, q.count())
    }

    /** A queue that outlives a sign-in must not hand one account's audio to another. */
    @Test
    fun `a recording belonging to another account is deleted unsent`() = runTest {
        val (q, _) = queueWith(account = "previous@example.test")
        var sent = 0
        val loop = UploaderLoop(q, {
            sent++
            201
        }, {}, gate = gate(account = "current@example.test"), now = { now })

        loop.runOnce()

        assertEquals("another account's recording must never be uploaded", 0, sent)
        assertEquals(0, q.count())
    }

    /** Retention applies before send, so an expired recording is never uploaded late. */
    @Test
    fun `a recording older than the retention limit is deleted unsent`() = runTest {
        val (q, _) = queueWith(publishedAt = now - FolderQueue.MAX_AGE_MS - 1)
        var sent = 0
        val loop = UploaderLoop(q, {
            sent++
            201
        }, {}, gate = gate(), now = { now })

        loop.runOnce()

        assertEquals(0, sent)
        assertEquals(0, q.count())
    }

    /**
     * Expiry is the QUEUE's job, not the gate's.
     *
     * With an always-open gate this is the only thing that can remove an old recording, so it pins
     * the loop's own retention pass rather than the gate's age check.
     */
    @Test
    fun `the loop deletes expired recordings even when every other check passes`() = runTest {
        val (q, _) = queueWith(publishedAt = now - FolderQueue.MAX_AGE_MS - 1)
        var sent = 0

        UploaderLoop(q, {
            sent++
            201
        }, {}, gate = PreSendGate.OPEN, now = { now }).runOnce()

        assertEquals("an expired recording must not be sent", 0, sent)
        assertEquals("nor left behind for the next pass", 0, q.count())
    }

    /** Unreadable before send is the same fact as unreadable after: there is nothing to send. */
    @Test
    fun `a recording whose audio cannot be read is deleted unsent`() = runTest {
        val (q, _) = queueWith()
        val recording = q.recordings().single()
        assertTrue(recording.audio.delete())

        var sent = 0
        val loop = UploaderLoop(q, {
            sent++
            201
        }, {}, gate = gate(), now = { now })

        // The audio is gone, so the folder is incomplete and never surfaces as a recording at all.
        loop.runOnce()

        assertEquals(0, sent)
        assertFalse(recording.folder.exists())
    }

    /**
     * The gate is re-read on every pass, not cached: a recording that was sendable stays sendable,
     * and one that stops being sendable is dropped by the same loop.
     */
    @Test
    fun `the verdict is re-read each pass`() {
        val (q, _) = queueWith()
        val recording = q.recordings().single()
        var sharing = true
        val gate = AccountPreSendGate(
            currentAccount = { "a@example.test" },
            currentEpoch = { "epoch-1" },
            sharingEnabled = { sharing },
            now = { now },
        )

        assertEquals(GateVerdict.Send, gate.verdictFor(recording))
        sharing = false
        assertEquals(
            GateVerdict.Refuse("recording sharing is off"),
            gate.verdictFor(recording),
        )
    }

    private fun runTest(block: suspend () -> Unit) = kotlinx.coroutines.test.runTest { block() }
}

private fun File.writeBytes(bytes: ByteArray) {
    outputStream().use { it.write(bytes) }
}
