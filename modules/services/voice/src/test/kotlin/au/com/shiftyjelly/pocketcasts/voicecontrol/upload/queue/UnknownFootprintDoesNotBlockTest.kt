package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * SPEC a7f6ce81 RULING: an unknown storage footprint must NEVER withhold uploads.
 *
 * The defect this pins: `unknownFootprintLocked()` gated `retryEligible` WHOLESALE
 * (`ContributionStore.kt:2020`, empty list for every id), `roomLocked` (`:1131`, refusal before
 * admission), and the whole ATTEMPT path (`pickupAttemptToken`, `pickupAttempt`, `recordAttempt`).
 * One unmeasurable capture therefore froze capture admission AND every attempt — a self-sustaining
 * hold, because uploading is exactly what would shrink the footprint and let it heal.
 *
 * The mark is INJECTED after recovery on purpose: a mark set before recovery is cleared by recovery
 * itself (`establishCoverage` re-measures everything it finds), so the only honest way to hold the
 * state still and ask "does an unknown footprint block the attempt path?" is a post-recovery seam.
 * Entries are REAL recoverable records ([ContributionRetryCallerTestSupport]) because a stub body
 * passes admission and then dies in recovery, which once made a fixture bug look like a defect.
 */
class UnknownFootprintDoesNotBlockTest {

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
    fun `under an unknown footprint a due entry still reaches a real transport attempt`() {
        val now = System.currentTimeMillis()
        val root = tmp.newFolder("unknown-footprint-attempt")
        val captureId = UUID.randomUUID().toString()
        seedOne(root, captureId, now)

        val store = ContributionStore(root, limits(), clock = { now }, jitter = { 0L })
        try {
            store.recover()
            // Marked AFTER recovery so the mark survives: this is the state the owner's device was in.
            store.markUnmeasuredForTest(captureId)
            assertTrue("precondition: the footprint is unknown", store.unknownFootprintForTest())

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
                "an unknown footprint must not withhold a due entry, so exactly one attempt must reach the transport",
                listOf(captureId),
                attempted,
            )
            assertTrue("the transport was reached (result=$result)", attempted.isNotEmpty())
        } finally {
            store.close()
        }
    }

    @Test
    fun `under an unknown footprint a FRESH clip is still admitted and queued`() {
        // The owner's symptom: admission refused -> no capture context -> nothing retained or queued.
        val now = System.currentTimeMillis()
        val root = tmp.newFolder("unknown-footprint-admission")
        val seeded = UUID.randomUUID().toString()
        seedOne(root, seeded, now)

        val store = ContributionStore(root, limits(), clock = { now }, jitter = { 0L })
        try {
            store.recover()
            store.markUnmeasuredForTest(seeded)
            assertTrue("precondition: the footprint is unknown", store.unknownFootprintForTest())

            val admission = store.admit(sampleCount = 1600, metadataBytes = 512)
            assertNotNull(
                "an unknown footprint must price conservatively, never refuse admission: refusing is what stops capture entirely",
                admission,
            )
            assertTrue("admission must yield a HELD reservation", admission!!.isHeld())
            admission.discard()

            // A fresh clip is queued: the durable record, which is what the queue is.
            val fresh = UUID.randomUUID().toString()
            assertNotNull(
                "a freshly captured clip must be durably queued under an unknown footprint",
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
                "a freshly captured clip must be durably queued under an unknown footprint",
                store.readyEntries().any { it.captureId == fresh },
            )
        } finally {
            store.close()
        }
    }
}
