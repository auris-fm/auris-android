package au.com.shiftyjelly.pocketcasts.voicecontrol.playback.appops

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `AppOperationDelivery`'s own mapping branches.
 *
 * These drive `deliver()` directly rather than through the sink. The sink suite asserts the protocol
 * end to end, but it cannot reach a settlement failure: the real journal store does not fail on demand,
 * so the branch where the effect ran and the record did not get written has no end-to-end control.
 * A `Store` fake that claims successfully and then refuses to settle reaches it with no production
 * seam — the interface already allows it, and adds nothing new to production code.
 */
@RunWith(RobolectricTestRunner::class)
class AppOperationDeliveryTest {
    private val claim = AppOperationJournal.Claim(
        owner = "user-1",
        conversation = "conv-1",
        turn = "turn-1",
        operationId = "11111111-2222-3333-4444-555555555555",
    )

    private val frameJson =
        """{"operation_id":"11111111-2222-3333-4444-555555555555","capability_version":"app-ops/1",""" +
            """"type":"action","tool":"playback","action":"pause","params":{}}"""

    /** The REAL codec's own request: a hand-built one would not prove the loop closes. */
    private fun request(): AppOperationV1.Request {
        val parsed = AppOperationV1.parse(frameJson)
        assertTrue("fixture frame must parse", parsed is AppOperationV1.Parsed.Valid)
        return (parsed as AppOperationV1.Parsed.Valid).request
    }

    /**
     * Claims, marks in progress, then FAILS every settlement.
     *
     * This is the crash-window shape the contract cares about: the effect is about to run and the
     * durable record will not be updated. [record] retains whatever was written, so a control can
     * assert the in-progress record is preserved rather than deleted.
     */
    private class SettleRefusingStore : AppOperationJournal.Store {
        var record: AppOperationJournal.Record? = null
            private set
        var settleAttempts = 0
            private set

        override fun claim(claim: AppOperationJournal.Claim, contentsDigest: String): Boolean {
            if (record != null) return false
            record = AppOperationJournal.Record(contentsDigest, false, null, null, null)
            return true
        }

        override fun markInProgress(claim: AppOperationJournal.Claim): Boolean {
            val r = record ?: return false
            record = AppOperationJournal.Record(r.contentsDigest, true, r.outcome, r.reason, r.state)
            return true
        }

        override fun storeOutcome(
            claim: AppOperationJournal.Claim,
            contentsDigest: String,
            outcome: String,
            reason: String?,
            state: JSONObject?,
        ): Boolean {
            settleAttempts++
            // Refuses, and does NOT mutate the record: a failed durable write leaves the prior
            // in-progress record exactly as it was, which is what makes replay refuse to re-execute.
            return false
        }

        override fun read(claim: AppOperationJournal.Claim): AppOperationJournal.Record? = record

        override fun claimOperation(claim: AppOperationJournal.Claim, contentsDigest: String): AppOperationJournal.ClaimResult = if (claim(claim, contentsDigest)) {
            AppOperationJournal.ClaimResult.Claimed
        } else {
            AppOperationJournal.ClaimResult.AlreadyClaimed
        }
    }

    @Test
    fun `an observed effect whose settlement fails reports unknown, never refused`() {
        val store = SettleRefusingStore()
        val delivery = kotlinx.coroutines.runBlocking {
            AppOperationDelivery.deliver(
                request = request(),
                claim = claim,
                store = store,
                effect = { AppOperationDelivery.Observation.Observed(JSONObject().put("playing", false)) },
            )
        }

        assertNotNull("a result is still returned: silence would hang the server", delivery)
        assertEquals(1, store.settleAttempts)
        val json = JSONObject(delivery!!.resultJson)
        // THE EFFECT ALREADY HAPPENED. Claiming `refused` would assert no execution occurred, which
        // is false and would invite a retry that must never re-execute.
        assertEquals("unknown", json.getString("outcome"))
        assertEquals("unobserved_result", json.getString("reason"))
        assertFalse("a non-success carries no state", json.has("state"))
        assertFalse("this answer is not a replay", delivery.replayed)

        // The in-progress record SURVIVES: restart/replay sees an unresolved claim and must not run
        // the effect a second time.
        val preserved = store.record
        assertNotNull("the claim record must not be discarded", preserved)
        assertTrue("it stays in progress", preserved!!.inProgress)
        assertNull("and it is not settled", preserved.outcome)
    }

    /** Claims and marks in progress, then THROWS on settle — a store failing after the effect. */
    private class ThrowingSettleStore : AppOperationJournal.Store {
        var record: AppOperationJournal.Record? = null
            private set

        override fun claim(claim: AppOperationJournal.Claim, contentsDigest: String): Boolean {
            if (record != null) return false
            record = AppOperationJournal.Record(contentsDigest, false, null, null, null)
            return true
        }

        override fun markInProgress(claim: AppOperationJournal.Claim): Boolean {
            val r = record ?: return false
            record = AppOperationJournal.Record(r.contentsDigest, true, r.outcome, r.reason, r.state)
            return true
        }

        override fun storeOutcome(
            claim: AppOperationJournal.Claim,
            contentsDigest: String,
            outcome: String,
            reason: String?,
            state: JSONObject?,
        ): Boolean = throw IllegalStateException("disk went away after the effect")

        override fun read(claim: AppOperationJournal.Claim): AppOperationJournal.Record? = record

        override fun claimOperation(claim: AppOperationJournal.Claim, contentsDigest: String): AppOperationJournal.ClaimResult = if (claim(claim, contentsDigest)) {
            AppOperationJournal.ClaimResult.Claimed
        } else {
            AppOperationJournal.ClaimResult.AlreadyClaimed
        }
    }

    @Test
    fun `an executor that mutates and then throws is unknown, not refused`() {
        // The dangerous shape: the effect RAN (a pause actually paused the player) and then threw on
        // the way back. Answering `refused` would say no execution occurred, and a retry would act
        // twice on a player the user can already see paused.
        val store = AppOperationJournalTestFakes.AcceptingStore()
        // ONE counter, incremented by BOTH callbacks: a counter that only the redelivery touches
        // cannot support a total-count claim, and would report zero for a control whose whole point
        // is that the first attempt did run.
        var effects = 0
        val delivery = kotlinx.coroutines.runBlocking {
            AppOperationDelivery.deliver(
                request = request(),
                claim = claim,
                store = store,
                effect = {
                    effects++
                    throw IllegalStateException("mutated, then the observation blew up")
                },
            )
        }

        assertEquals("the fixture must actually have run the effect", 1, effects)
        assertNotNull("a result is still returned: silence would hang the server", delivery)
        val json = JSONObject(delivery!!.resultJson)
        assertEquals("unknown", json.getString("outcome"))
        assertEquals("unobserved_result", json.getString("reason"))
        assertFalse("a non-success carries no state", json.has("state"))
        // The record is NOT settled: a later delivery sees an unresolved claim and must not re-execute.
        assertEquals("an unresolved claim, never a settled refusal", null, store.record?.outcome)
        assertTrue("and it stays in progress", store.record?.inProgress == true)

        // AND THE PROMISE IS EXERCISED, not inferred: redeliver the EXACT SAME bytes and count the
        // effects on the SAME counter. The second delivery must reach the store, find the unresolved
        // claim, and stop -- so the total across both attempts stays exactly one. A second effect
        // would make this 2, and the throw below cannot hide it because the increment precedes it.
        val redelivered = kotlinx.coroutines.runBlocking {
            AppOperationDelivery.deliver(
                request = request(),
                claim = claim,
                store = store,
                effect = {
                    effects++
                    throw IllegalStateException("must never be reached on a redelivery")
                },
            )
        }
        assertEquals("exactly one effect across both attempts", 1, effects)
        assertNotNull(redelivered)
        // A redelivery of an unresolved claim is reported as unknown, not re-executed.
        assertEquals("unknown", JSONObject(redelivered!!.resultJson).getString("outcome"))
    }

    @Test
    fun `a settlement store that throws after the effect is unknown, not refused`() {
        val store = ThrowingSettleStore()
        // The same ONE shared counter, so the total below is a claim about both attempts together.
        var effects = 0
        val delivery = kotlinx.coroutines.runBlocking {
            AppOperationDelivery.deliver(
                request = request(),
                claim = claim,
                store = store,
                effect = {
                    effects++
                    AppOperationDelivery.Observation.Observed(JSONObject().put("playing", false))
                },
            )
        }
        assertEquals("the effect ran on the first attempt", 1, effects)

        assertNotNull(delivery)
        val json = JSONObject(delivery!!.resultJson)
        assertEquals("unknown", json.getString("outcome"))
        assertEquals("unobserved_result", json.getString("reason"))
        assertFalse(json.has("state"))
        assertEquals("the throw must not settle the record", null, store.record?.outcome)
        assertTrue("the in-progress record survives the throw", store.record?.inProgress == true)

        // An exact-identity redelivery must not reach the executor again either: the shared counter
        // must still read one afterwards.
        val redelivered = kotlinx.coroutines.runBlocking {
            AppOperationDelivery.deliver(
                request = request(),
                claim = claim,
                store = store,
                effect = {
                    effects++
                    throw IllegalStateException("must never be reached on a redelivery")
                },
            )
        }
        assertEquals("exactly one effect across both attempts", 1, effects)
        assertEquals("unknown", JSONObject(redelivered!!.resultJson).getString("outcome"))
    }

    @Test
    fun `an unobservable effect is unknown and settles as such when storage allows`() {
        val store = AppOperationJournalTestFakes.AcceptingStore()
        val delivery = kotlinx.coroutines.runBlocking {
            AppOperationDelivery.deliver(
                request = request(),
                claim = claim,
                store = store,
                effect = { AppOperationDelivery.Observation.Unconfirmed(AppOperationDelivery.REASON_UNOBSERVED) },
            )
        }

        val json = JSONObject(delivery!!.resultJson)
        assertEquals("unknown", json.getString("outcome"))
        assertEquals("unobserved_result", json.getString("reason"))
        assertFalse(json.has("state"))
        assertEquals("unknown", store.record?.outcome)
    }

    @Test
    fun `a typed Failed observation reports failed, not unknown`() {
        val store = AppOperationJournalTestFakes.AcceptingStore()
        val delivery = kotlinx.coroutines.runBlocking {
            AppOperationDelivery.deliver(
                request = request(),
                claim = claim,
                store = store,
                effect = { AppOperationDelivery.Observation.Failed("pause_not_observed") },
            )
        }

        val json = JSONObject(delivery!!.resultJson)
        // WE LOOKED and the effect did not take. The TYPE carries this, not a reason string, so it
        // cannot be reclassified by a constant going out of sync.
        assertEquals("failed", json.getString("outcome"))
        assertEquals("pause_not_observed", json.getString("reason"))
        assertFalse(json.has("state"))
        assertEquals("failed", store.record?.outcome)
    }

    @Test
    fun `an unavailable observation reports playback_state_unavailable`() {
        val store = AppOperationJournalTestFakes.AcceptingStore()
        val delivery = kotlinx.coroutines.runBlocking {
            AppOperationDelivery.deliver(
                request = request(),
                claim = claim,
                store = store,
                effect = { AppOperationDelivery.Observation.Unconfirmed("playback_state_unavailable") },
            )
        }

        val json = JSONObject(delivery!!.resultJson)
        assertEquals("unknown", json.getString("outcome"))
        assertEquals("playback_state_unavailable", json.getString("reason"))
        assertFalse(json.has("state"))
    }
}

/** A store that accepts everything, for controls whose subject is the mapping rather than storage. */
private object AppOperationJournalTestFakes {
    class AcceptingStore : AppOperationJournal.Store {
        var record: AppOperationJournal.Record? = null
            private set

        override fun claim(claim: AppOperationJournal.Claim, contentsDigest: String): Boolean {
            if (record != null) return false
            record = AppOperationJournal.Record(contentsDigest, false, null, null, null)
            return true
        }

        override fun markInProgress(claim: AppOperationJournal.Claim): Boolean {
            val r = record ?: return false
            record = AppOperationJournal.Record(r.contentsDigest, true, r.outcome, r.reason, r.state)
            return true
        }

        override fun storeOutcome(
            claim: AppOperationJournal.Claim,
            contentsDigest: String,
            outcome: String,
            reason: String?,
            state: JSONObject?,
        ): Boolean {
            val r = record ?: return false
            if (r.contentsDigest != contentsDigest) return false
            record = AppOperationJournal.Record(contentsDigest, false, outcome, reason, state)
            return true
        }

        override fun read(claim: AppOperationJournal.Claim): AppOperationJournal.Record? = record

        override fun claimOperation(claim: AppOperationJournal.Claim, contentsDigest: String): AppOperationJournal.ClaimResult = if (claim(claim, contentsDigest)) {
            AppOperationJournal.ClaimResult.Claimed
        } else {
            AppOperationJournal.ClaimResult.AlreadyClaimed
        }
    }
}
