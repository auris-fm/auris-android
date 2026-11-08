package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * SPEC a3ef694f: capacity is a pure RECORD COUNT bounded by `limits.maxQueueRecords` (default 32).
 *
 * The defect this pins: a full queue must never be mistaken for a blocked one. A queue at its record
 * bound still serves due entries to the drain, and a fresh clip is still admitted while the queue only
 * holds retained (already-queued) records - refusing capture entirely is what stops uploads, which is
 * the opposite of the contract.
 *
 * Entries are REAL recoverable records ([ContributionRetryCallerTestSupport]) because a stub body passes
 * admission and then dies in recovery, which once made a fixture bug look like a defect.
 */
class RecordCapacityDoesNotBlockTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun limits() = QueueLimits.defaults().copy(retryInitialS = 1, retryMaxS = 1)

    private fun seedOne(root: File, captureId: String, now: Long) {
        val store = ContributionStore(root, limits(), clock = { now }, jitter = { 0L })
        try {
            store.recover()
            val samples = 1600
            assertNotNull(
                "precondition: the capture must be queued",
                store.prepare(
                    captureId,
                    "epoch-1",
                    ContributionRetryCallerTestSupport.chunks(samples),
                    ContributionRetryCallerTestSupport.meta(captureId, samples),
                    "a@x",
                    7L,
                ),
            )
        } finally {
            store.close()
        }
    }

    @Test
    fun `a due entry reaches a real transport attempt`() {
        val now = System.currentTimeMillis()
        val root = tmp.newFolder("record-capacity-attempt")
        val captureId = UUID.randomUUID().toString()
        seedOne(root, captureId, now)

        val store = ContributionStore(root, limits(), clock = { now }, jitter = { 0L })
        try {
            store.recover()
            assertTrue("precondition: the seeded capture holds a record", store.isChargedForTest(captureId))

            val attempted = mutableListOf<String>()
            val caller = ContributionRetryCaller(
                store = store,
                clock = { now },
                networkAvailable = { true },
                attemptUpload = { _, id, _, _ ->
                    attempted += id
                    // Accepted, so this control is about REACHING the transport, not about retrying.
                    true
                },
                ackResolver = ContributionAckResolver(store, CaptureReceiptValidator.forStore(store)),
            )
            val result = caller.pollOnce()

            assertEquals(
                "a due entry must reach the transport, not be withheld by capacity accounting",
                listOf(captureId),
                attempted,
            )
            assertTrue("the transport was reached (result=$result)", attempted.isNotEmpty())
        } finally {
            store.close()
        }
    }

    @Test
    fun `a fresh clip is admitted and queued while the queue holds retained entries`() {
        // The owner's symptom: admission refused -> no capture context -> nothing retained or queued.
        val now = System.currentTimeMillis()
        val root = tmp.newFolder("record-capacity-admission")
        val seeded = UUID.randomUUID().toString()
        seedOne(root, seeded, now)

        val store = ContributionStore(root, limits(), clock = { now }, jitter = { 0L })
        try {
            store.recover()
            assertTrue("precondition: the seeded capture holds a record", store.isChargedForTest(seeded))

            val admission = store.admit(sampleCount = 1600, metadataBytes = 512)
            assertNotNull(
                "a retained record must never refuse admission: refusing is what stops capture entirely",
                admission,
            )
            assertTrue("admission must yield a HELD reservation", admission!!.isHeld())
            admission.discard()

            // A fresh clip is queued: the durable record, which is what the queue is.
            val fresh = UUID.randomUUID().toString()
            assertNotNull(
                "a freshly captured clip must be durably queued alongside retained records",
                store.prepare(
                    fresh,
                    "epoch-1",
                    ContributionRetryCallerTestSupport.chunks(1600),
                    ContributionRetryCallerTestSupport.meta(fresh, 1600),
                    "a@x",
                    7L,
                ),
            )
            assertTrue(
                "a freshly captured clip must be durably queued alongside retained records",
                store.readyEntries().any { it.captureId == fresh },
            )
        } finally {
            store.close()
        }
    }

    @Test
    fun `the record bound refuses the 33rd complete entry`() {
        val now = System.currentTimeMillis()
        val root = tmp.newFolder("record-bound-refusal")
        val store = ContributionStore(root, limits(), clock = { now }, jitter = { 0L })
        try {
            store.recover()
            var prepared = 0
            while (prepared < store.recordLimit()) {
                val id = UUID.randomUUID().toString()
                assertNotNull(
                    "precondition: the ${prepared + 1}-th complete entry must be prepared",
                    store.prepare(
                        id,
                        "epoch-1",
                        ContributionRetryCallerTestSupport.chunks(1600),
                        ContributionRetryCallerTestSupport.meta(id, 1600),
                        "a@x",
                        7L,
                    ),
                )
                prepared++
            }
            assertEquals("precondition: the record bound is reached", store.recordLimit(), store.ownedRecordsForTest())

            val overflow = UUID.randomUUID().toString()
            assertNull(
                "the record bound must refuse the 33rd complete entry",
                store.prepare(
                    overflow,
                    "epoch-1",
                    ContributionRetryCallerTestSupport.chunks(1600),
                    ContributionRetryCallerTestSupport.meta(overflow, 1600),
                    "a@x",
                    7L,
                ),
            )
            assertEquals(
                "the refusal must leave the queue exactly at the record bound",
                store.recordLimit(),
                store.ownedRecordsForTest(),
            )
        } finally {
            store.close()
        }
    }

    @Test
    fun `a queue already at its record bound can still upload what it holds`() {
        val now = System.currentTimeMillis()
        val root = tmp.newFolder("record-bound-upload")
        val store = ContributionStore(root, limits(), clock = { now }, jitter = { 0L })
        try {
            store.recover()
            val ids = (1..store.recordLimit()).map { UUID.randomUUID().toString() }
            for (id in ids) {
                assertNotNull(
                    "precondition: fill the queue to its record bound",
                    store.prepare(
                        id,
                        "epoch-1",
                        ContributionRetryCallerTestSupport.chunks(1600),
                        ContributionRetryCallerTestSupport.meta(id, 1600),
                        "a@x",
                        7L,
                    ),
                )
            }
            assertEquals("precondition: the queue is at its record bound", store.recordLimit(), store.ownedRecordsForTest())

            // A due entry at the bound must still be handed to the drain: a full queue is not a blocked queue.
            val eligible = store.retryEligible(now)
            assertTrue(
                "a queue already at its record bound must still hand a due entry to the drain (eligible=$eligible)",
                eligible.isNotEmpty(),
            )
        } finally {
            store.close()
        }
    }
}
