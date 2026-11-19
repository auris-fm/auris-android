package au.com.shiftyjelly.pocketcasts.voicecontrol.playback.appops

import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises the REAL file store in a host temporary directory (`File` root seam): cross-instance
 * races for one operation ID and for the last capacity slot, fail-closed locking, unresolved claims,
 * corrupt or wrongly shaped storage, durability refusal and full-result replay.
 */
@RunWith(RobolectricTestRunner::class)
class AppOperationJournalFileStoreTest {
    private lateinit var root: File

    private val claim = AppOperationJournal.Claim(
        owner = "user-1",
        conversation = "conv-1",
        turn = "turn-1",
        operationId = "11111111-2222-3333-4444-555555555555",
    )

    private val other = claim.copy(operationId = "22222222-3333-4444-5555-666666666666")

    @Before
    fun setUp() {
        root = Files.createTempDirectory("appops-journal").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun store(maxRecords: Int = 512) = AppOperationJournalStore(root, maxRecords = maxRecords)

    @Test
    fun `two instances cannot both execute the same operation id`() {
        val first = store()
        val second = store()
        assertTrue(AppOperationJournal.admit(claim, "d1", first) is AppOperationJournal.Verdict.Execute)
        val replay = AppOperationJournal.admit(claim, "d1", second) as AppOperationJournal.Verdict.Replay
        assertEquals(AppOperationV1.OUTCOME_UNKNOWN, replay.outcome)
        assertEquals("execution_unresolved", replay.reason)
    }

    @Test
    fun `two instances racing the same id in parallel produce exactly one Execute`() {
        val executor = Executors.newFixedThreadPool(2)
        repeat(12) {
            val a = store()
            val b = store()
            val fresh = claim.copy(operationId = UUID.randomUUID().toString())
            // A fresh latch per round: reusing one released latch let the racers run at arbitrary
            // times instead of simultaneously, which is what the control is meant to exercise.
            val start = CountDownLatch(1)
            val racers: List<Future<Result<AppOperationJournal.Verdict>>> = listOf(
                executor.submit(
                    Callable {
                        start.await()
                        runCatching { AppOperationJournal.admit(fresh, "d1", a) }
                    },
                ),
                executor.submit(
                    Callable {
                        start.await()
                        runCatching { AppOperationJournal.admit(fresh, "d1", b) }
                    },
                ),
            )
            start.countDown()
            val verdicts = racers.map { it.get(30, TimeUnit.SECONDS).getOrThrow() }
            assertEquals(
                "round $it verdicts=${verdicts.map { v -> v.javaClass.simpleName }}",
                1,
                verdicts.count { v -> v is AppOperationJournal.Verdict.Execute },
            )
        }
        executor.shutdown()
        executor.awaitTermination(10, TimeUnit.SECONDS)
    }

    @Test
    fun `two distinct ids racing the last capacity slot cannot both claim it`() {
        val first = store(maxRecords = 1)
        val second = store(maxRecords = 1)
        assertTrue(AppOperationJournal.admit(claim, "d1", first) is AppOperationJournal.Verdict.Execute)
        assertEquals(AppOperationJournal.Verdict.PersistenceUnavailable, AppOperationJournal.admit(other, "d2", second))
        assertEquals(1, root.listFiles()!!.count { it.name.endsWith(".json") })
    }

    @Test
    fun `an unresolved claim answers unknown with execution_unresolved and is never re-executed`() {
        val first = store()
        assertTrue(AppOperationJournal.admit(claim, "d1", first) is AppOperationJournal.Verdict.Execute)

        // Reopening after a restart is just another store over the same root.
        val replay = AppOperationJournal.admit(claim, "d1", store()) as AppOperationJournal.Verdict.Replay
        assertEquals(AppOperationV1.OUTCOME_UNKNOWN, replay.outcome)
        assertEquals("execution_unresolved", replay.reason)
        assertNull(replay.state)
        assertTrue(AppOperationJournal.admit(claim, "d1", store()) is AppOperationJournal.Verdict.Replay)
    }

    @Test
    fun `a settled outcome replays in full across a restart without executing again`() {
        val first = store()
        AppOperationJournal.admit(claim, "d1", first)
        assertTrue(AppOperationJournal.settle(claim, "d1", AppOperationV1.OUTCOME_SUCCEEDED, null, JSONObject().put("playing", false), first))

        val replay = AppOperationJournal.admit(claim, "d1", store()) as AppOperationJournal.Verdict.Replay
        assertEquals(AppOperationV1.OUTCOME_SUCCEEDED, replay.outcome)
        assertEquals("false", replay.state!!.get("playing").toString())
    }

    @Test
    fun `a refusal replays its reason token across a restart`() {
        val first = store()
        AppOperationJournal.admit(claim, "d1", first)
        assertTrue(AppOperationJournal.settle(claim, "d1", AppOperationV1.OUTCOME_REFUSED, "unsupported_operation", null, first))

        val replay = AppOperationJournal.admit(claim, "d1", store()) as AppOperationJournal.Verdict.Replay
        assertEquals(AppOperationV1.OUTCOME_REFUSED, replay.outcome)
        assertEquals("unsupported_operation", replay.reason)
    }

    @Test
    fun `a settled result is immutable even when the second settlement repeats the same outcome`() {
        val s = store()
        AppOperationJournal.admit(claim, "d1", s)
        assertTrue(AppOperationJournal.settle(claim, "d1", AppOperationV1.OUTCOME_SUCCEEDED, null, JSONObject().put("playing", true), s))
        assertFalse(AppOperationJournal.settle(claim, "d1", AppOperationV1.OUTCOME_SUCCEEDED, null, JSONObject().put("playing", false), s))
        val replay = AppOperationJournal.admit(claim, "d1", s) as AppOperationJournal.Verdict.Replay
        assertEquals("true", replay.state!!.get("playing").toString())
    }

    @Test
    fun `an invalid settlement leaves the prior record unchanged on disk`() {
        val s = store()
        AppOperationJournal.admit(claim, "d1", s)
        val before = recordFile().readText()
        assertFalse(AppOperationJournal.settle(claim, "d1", AppOperationV1.OUTCOME_SUCCEEDED, "ok", JSONObject(), s))
        assertFalse(AppOperationJournal.settle(claim, "d1", AppOperationV1.OUTCOME_REFUSED, "Not A Token", null, s))
        assertEquals(before, recordFile().readText())
        val replay = AppOperationJournal.admit(claim, "d1", store()) as AppOperationJournal.Verdict.Replay
        assertEquals(AppOperationV1.OUTCOME_UNKNOWN, replay.outcome)
        assertEquals("execution_unresolved", replay.reason)
    }

    @Test
    fun `settlement under a different digest is rejected`() {
        val s = store()
        AppOperationJournal.admit(claim, "d1", s)
        assertFalse(AppOperationJournal.settle(claim, "d2", AppOperationV1.OUTCOME_SUCCEEDED, null, JSONObject(), s))
    }

    /**
     * The lock file is unusable (a directory) while the record directory itself is writable. Nothing
     * may claim or settle: an operation must not run unserialized.
     */
    @Test
    fun `an unusable lock file fails closed even though the record directory is writable`() {
        File(root, ".appops-root.lock").mkdirs()
        val s = store()
        assertEquals(AppOperationJournal.Verdict.PersistenceUnavailable, AppOperationJournal.admit(claim, "d1", s))
        assertFalse(s.claim(claim, "d1"))
        assertFalse(AppOperationJournal.settle(claim, "d1", AppOperationV1.OUTCOME_SUCCEEDED, null, JSONObject(), s))
        assertNull(s.read(claim))
        assertEquals(
            AppOperationJournal.ClaimResult.CapacityExhausted,
            s.claimOperation(claim, "d1"),
        )
        assertEquals(0, root.listFiles()!!.count { it.name.endsWith(".json") })
    }

    @Test
    fun `corrupt storage refuses instead of reading as an empty journal`() {
        val s = store()
        AppOperationJournal.admit(claim, "d1", s)
        recordFile().writeText("{ this is not json")
        assertEquals(AppOperationJournal.Verdict.StorageCorrupt, AppOperationJournal.admit(claim, "d1", store()))
    }

    @Test
    fun `structurally valid but wrongly shaped storage refuses too`() {
        val s = store()
        AppOperationJournal.admit(claim, "d1", s)
        recordFile().writeText(
            JSONObject()
                .put("key", claim.key)
                .put("operation_id", claim.operationId)
                .put("digest", "d1")
                .put("in_progress", false)
                .put("outcome", "succeeded")
                .toString(),
        )
        assertEquals(AppOperationJournal.Verdict.StorageCorrupt, AppOperationJournal.admit(claim, "d1", store()))
    }

    @Test
    fun `a foreign identity in the record refuses rather than settling someone else's operation`() {
        val s = store()
        AppOperationJournal.admit(claim, "d1", s)
        // Keep the record FILE NAME derived from this claim and change only the stored identity, so
        // the reader actually reaches the identity check instead of finding no record at all.
        val record = recordFile()
        val tampered = JSONObject(record.readText())
            .put("key", "someone-else")
            .put("operation_id", "22222222-3333-4444-5555-666666666666")
        record.writeText(tampered.toString())
        assertFalse(AppOperationJournal.settle(claim, "d1", AppOperationV1.OUTCOME_SUCCEEDED, null, JSONObject(), s))
        assertEquals(AppOperationJournal.Verdict.StorageCorrupt, AppOperationJournal.admit(claim, "d1", store()))
        assertEquals(AppOperationJournal.Verdict.StorageCorrupt, AppOperationJournal.admit(claim, "d1", s))
    }

    @Test
    fun `a losing claim never deletes the record another claimant created`() {
        val owner = store()
        assertTrue(AppOperationJournal.admit(claim, "d1", owner) is AppOperationJournal.Verdict.Execute)
        val contents = recordFile().readText()
        assertFalse(store().claim(claim, "d1"))
        assertEquals(contents, recordFile().readText())
    }

    @Test
    fun `an unusable root refuses before execution rather than claiming durability`() {
        val blocker = File(root, "blocked")
        blocker.writeText("not a directory")
        assertEquals(
            AppOperationJournal.Verdict.PersistenceUnavailable,
            AppOperationJournal.admit(claim, "d1", AppOperationJournalStore(blocker)),
        )
    }

    @Test
    fun `exhausted retention capacity refuses new operations`() {
        val s = store(maxRecords = 1)
        assertTrue(AppOperationJournal.admit(claim, "d1", s) is AppOperationJournal.Verdict.Execute)
        assertEquals(AppOperationJournal.Verdict.PersistenceUnavailable, AppOperationJournal.admit(other, "d2", s))
    }

    @Test
    fun `a staged temp file left by an interrupted write is never read as a record`() {
        val s = store()
        AppOperationJournal.admit(claim, "d1", s)
        val staged = File(root, "interrupted.json.1234.staged")
        staged.writeText("{\"in_progress\":true}")
        // The record directory may legitimately hold unrelated files; only this operation's record
        // path is consulted, so the stray staged file changes nothing about the claim's replay.
        val replay = AppOperationJournal.admit(claim, "d1", store()) as AppOperationJournal.Verdict.Replay
        assertEquals(AppOperationV1.OUTCOME_UNKNOWN, replay.outcome)
        assertTrue(staged.exists())
    }

    // --- Prior binding must be READ before it can be relied on ---------------------------------------

    private val conflictingTurn = claim.copy(turn = "turn-2")

    @Test
    fun `a corrupt prior record refuses a new turn instead of reading as unbound`() {
        // The record for this operation id exists but cannot be parsed. Skipping it would look exactly
        // like "nothing is bound to this id", and the second turn would execute. It must not.
        val s = store()
        AppOperationJournal.admit(claim, "d1", s)
        recordFile().writeText("{ this is not json")
        // A DIFFERENT turn, same operation id: the binding it would collide with is the unreadable one.
        val verdict = AppOperationJournal.admit(conflictingTurn, "d1", store())
        assertEquals(AppOperationJournal.Verdict.StorageCorrupt, verdict)
        assertFalse(verdict is AppOperationJournal.Verdict.Execute)
    }

    @Test
    fun `a prior record with no operation id refuses a new turn`() {
        val s = store()
        AppOperationJournal.admit(claim, "d1", s)
        recordFile().writeText(JSONObject().put("key", claim.key).put("digest", "d1").toString())
        assertEquals(AppOperationJournal.Verdict.StorageCorrupt, AppOperationJournal.admit(conflictingTurn, "d1", store()))
    }

    @Test
    fun `a prior record with a malformed identity refuses a new turn`() {
        val s = store()
        AppOperationJournal.admit(claim, "d1", s)
        recordFile().writeText(
            JSONObject().put("key", 7).put("operation_id", claim.operationId).put("digest", "d1").toString(),
        )
        assertEquals(AppOperationJournal.Verdict.StorageCorrupt, AppOperationJournal.admit(conflictingTurn, "d1", store()))
    }

    @Test
    fun `an unreadable record of another operation refuses too`() {
        // The unreadable record cannot be shown to be unrelated, so no claim is safe against it.
        val s = store()
        AppOperationJournal.admit(other, "d1", s)
        recordFile().writeText("not json at all")
        assertEquals(AppOperationJournal.Verdict.StorageCorrupt, AppOperationJournal.admit(claim, "d1", store()))
    }

    @Test
    fun `concurrent cross-turn claims over the file store produce exactly one Execute`() {
        // The property the sepals earlier split into read-then-claim: with binding and create in one
        // locked section, two turns presenting the same id cannot both observe "not bound".
        val rounds = 12
        repeat(rounds) { round ->
            val dir = Files.createTempDirectory("appops-cross-turn").toFile()
            try {
                val latch = CountDownLatch(1)
                val results = java.util.Collections.synchronizedList(mutableListOf<AppOperationJournal.Verdict>())
                val turns = listOf(claim.copy(turn = "turn-a-$round"), claim.copy(turn = "turn-b-$round"))
                val threads = turns.map { c ->
                    Thread {
                        latch.await()
                        results += AppOperationJournal.admit(c, "d1", AppOperationJournalStore(dir, maxRecords = 512))
                    }.also { it.start() }
                }
                latch.countDown()
                threads.forEach { it.join(10_000) }
                val executes = results.count { it is AppOperationJournal.Verdict.Execute }
                assertEquals("round $round: $results", 1, executes)
                assertEquals("round $round: $results", 2, results.size)
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    private fun recordFile(): File = root.listFiles()!!.single { it.name.endsWith(".json") }
}
