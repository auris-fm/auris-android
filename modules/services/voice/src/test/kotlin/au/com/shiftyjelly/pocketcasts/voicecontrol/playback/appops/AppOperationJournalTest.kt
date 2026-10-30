package au.com.shiftyjelly.pocketcasts.voicecontrol.playback.appops

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Durable execution boundary rules (core `cloud-assistant.md`, committed `bf5fb219`). */
@RunWith(RobolectricTestRunner::class)
class AppOperationJournalTest {
    private val claim = AppOperationJournal.Claim(
        owner = "user-1",
        conversation = "conv-1",
        turn = "turn-1",
        operationId = "11111111-2222-3333-4444-555555555555",
    )

    private class FakeStore : AppOperationJournal.Store {
        val records = mutableMapOf<String, AppOperationJournal.Record>()
        var claimFails = false
        var capacity = true
        var corrupt = false

        override fun claim(claim: AppOperationJournal.Claim, contentsDigest: String): Boolean {
            if (claimFails) return false
            if (records.containsKey(claim.key)) return false
            if (!capacity) return false
            records[claim.key] = AppOperationJournal.Record(contentsDigest, false, null, null, null)
            return true
        }

        override fun markInProgress(claim: AppOperationJournal.Claim): Boolean {
            val r = records[claim.key] ?: return false
            records[claim.key] = AppOperationJournal.Record(r.contentsDigest, true, r.outcome, r.reason, r.state, session = "s1")
            return true
        }

        override fun storeOutcome(
            claim: AppOperationJournal.Claim,
            contentsDigest: String,
            outcome: String,
            reason: String?,
            state: JSONObject?,
        ): Boolean {
            val r = records[claim.key] ?: return false
            if (r.contentsDigest != contentsDigest) return false
            if (r.outcome != null) {
                // A settled result is immutable: neither outcome nor reason/state may be rewritten.
                return r.outcome == outcome && r.reason == reason && r.state?.toString() == state?.toString()
            }
            records[claim.key] = AppOperationJournal.Record(contentsDigest, false, outcome, reason, state, settledBy = "s1")
            return true
        }

        override fun read(claim: AppOperationJournal.Claim): AppOperationJournal.Record? {
            if (corrupt) throw AppOperationJournal.CorruptStorageException("corrupt")
            return records[claim.key]
        }

        override fun mayClaimNew(): Boolean = capacity
    }

    @Test
    fun `first sighting executes exactly once`() {
        val store = FakeStore()
        val verdict = AppOperationJournal.admit(claim, "d1", store)
        assertTrue(verdict is AppOperationJournal.Verdict.Execute)
        assertTrue(store.records.getValue(claim.key).inProgress)
    }

    @Test
    fun `an exact duplicate replays the full stored outcome without executing or re-reading state`() {
        val store = FakeStore()
        AppOperationJournal.admit(claim, "d1", store)
        AppOperationJournal.settle(claim, "d1", AppOperationV1.OUTCOME_SUCCEEDED, null, JSONObject().put("playing", false), store)
        val replay = AppOperationJournal.admit(claim, "d1", store) as AppOperationJournal.Verdict.Replay
        assertEquals(AppOperationV1.OUTCOME_SUCCEEDED, replay.outcome)
        assertEquals("false", replay.state!!.get("playing").toString())
    }

    @Test
    fun `a refusal replays its reason token as well as its outcome`() {
        val store = FakeStore()
        AppOperationJournal.admit(claim, "d1", store)
        AppOperationJournal.settle(claim, "d1", AppOperationV1.OUTCOME_REFUSED, "unsupported_operation", null, store)
        val replay = AppOperationJournal.admit(claim, "d1", store) as AppOperationJournal.Verdict.Replay
        assertEquals(AppOperationV1.OUTCOME_REFUSED, replay.outcome)
        assertEquals("unsupported_operation", replay.reason)
        assertNull(replay.state)
    }

    @Test
    fun `the same identity with different contents is refused without execution`() {
        val store = FakeStore()
        AppOperationJournal.admit(claim, "d1", store)
        assertEquals(AppOperationJournal.Verdict.IdentityConflict, AppOperationJournal.admit(claim, "d2", store))
    }

    @Test
    fun `an unresolved claim resolves to unknown with a machine reason and is never re-executed`() {
        val store = FakeStore()
        val first = AppOperationJournal.admit(claim, "d1", store)
        assertTrue(first is AppOperationJournal.Verdict.Execute)
        // Whichever the duplicate is — concurrent or recovered — the answer is unknown, not a wait.
        val replay = AppOperationJournal.admit(claim, "d1", store) as AppOperationJournal.Verdict.Replay
        assertEquals(AppOperationV1.OUTCOME_UNKNOWN, replay.outcome)
        assertEquals("execution_unresolved", replay.reason)
        assertTrue(AppOperationV1.result(claim.operationId, replay.outcome, reason = replay.reason) != null)
        assertNull(replay.state)
        // Still never re-executed on a third sighting.
        assertTrue(AppOperationJournal.admit(claim, "d1", store) is AppOperationJournal.Verdict.Replay)
    }

    @Test
    fun `a failed claim refuses persistence before any effect`() {
        val store = FakeStore().apply { claimFails = true }
        assertEquals(AppOperationJournal.Verdict.PersistenceUnavailable, AppOperationJournal.admit(claim, "d1", store))
    }

    @Test
    fun `corrupt storage refuses and is never read as an empty journal`() {
        val store = FakeStore().apply { corrupt = true }
        assertEquals(AppOperationJournal.Verdict.StorageCorrupt, AppOperationJournal.admit(claim, "d1", store))
    }

    @Test
    fun `a second settlement that repeats the outcome but changes the result is rejected`() {
        val store = FakeStore()
        AppOperationJournal.admit(claim, "d1", store)
        assertTrue(AppOperationJournal.settle(claim, "d1", AppOperationV1.OUTCOME_SUCCEEDED, null, JSONObject().put("playing", true), store))
        assertFalse(AppOperationJournal.settle(claim, "d1", AppOperationV1.OUTCOME_SUCCEEDED, null, JSONObject().put("playing", false), store))
        val replay = AppOperationJournal.admit(claim, "d1", store) as AppOperationJournal.Verdict.Replay
        assertEquals("true", replay.state!!.get("playing").toString())
    }

    @Test
    fun `a second settlement that conflicts with the recorded outcome is rejected`() {
        val store = FakeStore()
        AppOperationJournal.admit(claim, "d1", store)
        assertTrue(AppOperationJournal.settle(claim, "d1", AppOperationV1.OUTCOME_SUCCEEDED, null, JSONObject(), store))
        assertFalse(AppOperationJournal.settle(claim, "d1", AppOperationV1.OUTCOME_FAILED, "boom", null, store))
    }

    @Test
    fun `a settlement under a different digest does not alter the stored outcome`() {
        val store = FakeStore()
        AppOperationJournal.admit(claim, "d1", store)
        assertFalse(AppOperationJournal.settle(claim, "d2", AppOperationV1.OUTCOME_SUCCEEDED, null, JSONObject(), store))
    }

    @Test
    fun `settle copies state so a later alias cannot rewrite a recorded outcome`() {
        val store = FakeStore()
        AppOperationJournal.admit(claim, "d1", store)
        val mutable = JSONObject().put("playing", true)
        AppOperationJournal.settle(claim, "d1", AppOperationV1.OUTCOME_SUCCEEDED, null, mutable, store)
        mutable.put("playing", false)
        val replay = AppOperationJournal.admit(claim, "d1", store) as AppOperationJournal.Verdict.Replay
        assertEquals("true", replay.state!!.get("playing").toString())
    }

    @Test
    fun `success cannot settle without observed state and refusal cannot settle without a reason`() {
        val store = FakeStore()
        AppOperationJournal.admit(claim, "d1", store)
        assertFalse(AppOperationJournal.settle(claim, "d1", AppOperationV1.OUTCOME_SUCCEEDED, null, null, store))
        assertFalse(AppOperationJournal.settle(claim, "d1", AppOperationV1.OUTCOME_REFUSED, null, null, store))
    }

    @Test
    fun `a success carrying a reason cannot settle and leaves the record unresolved`() {
        val store = FakeStore()
        AppOperationJournal.admit(claim, "d1", store)
        assertFalse(AppOperationJournal.settle(claim, "d1", AppOperationV1.OUTCOME_SUCCEEDED, "ok", JSONObject(), store))
        assertNull(store.records.getValue(claim.key).outcome)
    }

    @Test
    fun `an invalid reason token cannot settle and leaves the record unresolved`() {
        val store = FakeStore()
        AppOperationJournal.admit(claim, "d1", store)
        for (bad in listOf("Not A Token", "", "UPPER", "9starts_with_digit")) {
            assertFalse("reason=$bad", AppOperationJournal.settle(claim, "d1", AppOperationV1.OUTCOME_REFUSED, bad, null, store))
        }
        assertNull(store.records.getValue(claim.key).outcome)
    }

    @Test
    fun `an oversize or over-wide state cannot settle`() {
        val store = FakeStore()
        AppOperationJournal.admit(claim, "d1", store)
        assertFalse(
            AppOperationJournal.settle(
                claim,
                "d1",
                AppOperationV1.OUTCOME_SUCCEEDED,
                null,
                JSONObject().put("blob", "x".repeat(AppOperationV1.MAX_STATE_BYTES)),
                store,
            ),
        )
        val wide = org.json.JSONArray().apply { repeat(AppOperationV1.MAX_COLLECTION_ITEMS + 1) { put("x") } }
        assertFalse(
            AppOperationJournal.settle(
                claim,
                "d1",
                AppOperationV1.OUTCOME_SUCCEEDED,
                null,
                JSONObject().put("items", wide),
                store,
            ),
        )
        assertNull(store.records.getValue(claim.key).outcome)
    }

    @Test
    fun `an operation is scoped to owner conversation and turn`() {
        val store = FakeStore()
        AppOperationJournal.admit(claim, "d1", store)
        assertTrue(AppOperationJournal.admit(claim.copy(turn = "turn-2"), "d1", store) is AppOperationJournal.Verdict.Execute)
        assertTrue(AppOperationJournal.admit(claim.copy(owner = "user-2"), "d1", store) is AppOperationJournal.Verdict.Execute)
        assertNotEquals(claim.key, claim.copy(turn = "turn-2").key)
    }

    @Test
    fun `no new operation is claimed when retention capacity is exhausted`() {
        val store = FakeStore().apply { capacity = false }
        assertEquals(AppOperationJournal.Verdict.PersistenceUnavailable, AppOperationJournal.admit(claim, "d1", store))
    }
}
