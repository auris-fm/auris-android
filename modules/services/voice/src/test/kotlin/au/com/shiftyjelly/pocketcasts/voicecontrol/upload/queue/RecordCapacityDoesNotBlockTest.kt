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
            assertEquals(
                "precondition: memory and disk agree here, so this control proves the bound, nothing else",
                store.recordLimit(),
                completeEntriesOnDisk(root),
            )

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

    @Test
    fun `complete entries on disk fill the bound even when the store never learned of them`() {
        // REVIEWER 5e92bba3 finding 1, direction 1. Leftover complete entries from an earlier process are
        // real files this store must still count. An in-memory tally would read zero here and admit past
        // the bound, so the queue would exceed 32 on disk without any single admission looking wrong.
        val now = System.currentTimeMillis()
        val root = tmp.newFolder("disk-count-no-map")
        // Seed through a real store, then discard it: the bytes stay, the memory goes.
        repeat(3) { seedOne(root, UUID.randomUUID().toString(), now) }

        val fresh = ContributionStore(root, limits(), clock = { now }, jitter = { 0L })
        try {
            // NO recover() here on purpose. Recovery ADOPTS on-disk entries into the in-memory index, which
            // would let a memory tally reproduce the right answer and hide the defect this control exists to
            // catch. Admission must count the directory itself, however the store was constructed.
            assertEquals(
                "complete entries must count even before recovery adopts them",
                3,
                fresh.ownedRecordsForTest(),
            )

            // Fill to the bound with a store whose tally starts from disk, then confirm the 33rd is refused.
            var prepared = 3
            while (prepared < fresh.recordLimit()) {
                val id = UUID.randomUUID().toString()
                assertNotNull(
                    "precondition: entry ${prepared + 1} must be prepared below the bound",
                    fresh.prepare(
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
            val overflow = UUID.randomUUID().toString()
            assertNull(
                "the bound must count disk entries the tally never saw",
                fresh.prepare(
                    overflow,
                    "epoch-1",
                    ContributionRetryCallerTestSupport.chunks(1600),
                    ContributionRetryCallerTestSupport.meta(overflow, 1600),
                    "a@x",
                    7L,
                ),
            )
        } finally {
            fresh.close()
        }
    }

    @Test
    fun `stale in-memory charges do not fill the queue when the disk is empty`() {
        // REVIEWER 5e92bba3 finding 1, direction 2. The mirror case: a tally that believes the queue is
        // full while nothing is actually retained would refuse every new clip - the original freeze, from
        // the other side. Capacity is what is ON DISK, so with an empty directory admission must succeed.
        val now = System.currentTimeMillis()
        val root = tmp.newFolder("stale-map-empty-disk")

        val store = ContributionStore(root, limits(), clock = { now }, jitter = { 0L })
        try {
            store.recover()
            // Charge far more captures than the bound WITHOUT ever writing them: pure stale bookkeeping.
            repeat(store.recordLimit() * 2) { store.chargeRetainedForTest(UUID.randomUUID().toString()) }
            assertTrue(
                "precondition: the memory tally believes the queue is overfull",
                store.chargedCountForTest() > store.recordLimit(),
            )
            assertEquals("precondition: nothing is actually retained on disk", 0, completeEntriesOnDisk(root))

            val id = UUID.randomUUID().toString()
            assertNotNull(
                "admission must follow the disk, not a stale tally",
                store.prepare(
                    id,
                    "epoch-1",
                    ContributionRetryCallerTestSupport.chunks(1600),
                    ContributionRetryCallerTestSupport.meta(id, 1600),
                    "a@x",
                    7L,
                ),
            )
        } finally {
            store.close()
        }
    }

    @Test
    fun `files leftover by a dead attempt never count as records`() {
        // REVIEWER 5e92bba3 finding 1, leftover files. A record is a COMPLETE entry: a WAV with its metadata
        // sidecar under a UUID name. A partial write, an orphaned WAV with no sidecar, or a sidecar with no
        // audio must not consume a slot - otherwise crash debris silently shrinks the queue towards zero.
        val now = System.currentTimeMillis()
        val root = tmp.newFolder("leftover-not-a-record")
        val dir = File(root, DIR_NAME)
        dir.mkdirs()

        val partialId = UUID.randomUUID().toString()
        File(dir, "$partialId$SUFFIX_WAV.part").writeBytes(ByteArray(64))
        val orphanWav = UUID.randomUUID().toString()
        File(dir, "$orphanWav$SUFFIX_WAV").writeBytes(ByteArray(64))
        val orphanMeta = UUID.randomUUID().toString()
        File(dir, "$orphanMeta$SUFFIX_META").writeText("{}")
        File(dir, "not-a-uuid$SUFFIX_WAV").writeBytes(ByteArray(64))
        File(dir, "not-a-uuid$SUFFIX_META").writeText("{}")

        val store = ContributionStore(root, limits(), clock = { now }, jitter = { 0L })
        try {
            store.recover()
            assertEquals("leftover files must not count as records", 0, store.ownedRecordsForTest())
            val id = UUID.randomUUID().toString()
            assertNotNull(
                "leftovers must not refuse a real admission",
                store.prepare(
                    id,
                    "epoch-1",
                    ContributionRetryCallerTestSupport.chunks(1600),
                    ContributionRetryCallerTestSupport.meta(id, 1600),
                    "a@x",
                    7L,
                ),
            )
            assertEquals("exactly one complete record now exists", 1, store.ownedRecordsForTest())
        } finally {
            store.close()
        }
    }

    // Mirrors the store's OWN definition of a complete record: a WAV carrying its metadata sidecar under a
    // UUID name. Counted from the directory directly, so a bug in the store's count cannot define its own
    // passing test.
    @Test
    fun `sidecar-only leftovers never fill the queue`() {
        // SPEC 33c1e27a ruling. The bound protects AUDIO. A capture whose audio is gone but whose sidecars
        // survived holds nothing worth bounding, and orphan sidecars are exactly what wedged iOS when they
        // were allowed to accumulate. If they counted, a queue could refuse every new clip while holding no
        // audio at all - the original freeze, reached through sidecars instead of a byte tally.
        val now = System.currentTimeMillis()
        val root = tmp.newFolder("sidecar-only-leftovers")
        val dir = File(root, DIR_NAME)
        dir.mkdirs()

        // More sidecar-only ids than the bound can hold, none of them carrying audio.
        repeat(limits().maxQueueRecords * 2) {
            val id = UUID.randomUUID().toString()
            File(dir, "$id$SUFFIX_META").writeText("{}")
            File(dir, "$id.ready").writeText("")
            File(dir, "$id.sha256").writeText("0".repeat(64))
            File(dir, "$id.attempts").writeText("0")
        }

        val store = ContributionStore(root, limits(), clock = { now }, jitter = { 0L })
        try {
            assertEquals(
                "sidecar-only leftovers must not count against the bound",
                0,
                store.ownedRecordsForTest(),
            )
            val id = UUID.randomUUID().toString()
            assertNotNull(
                "a real clip must still be admitted beside orphan sidecars",
                store.prepare(
                    id,
                    "epoch-1",
                    ContributionRetryCallerTestSupport.chunks(1600),
                    ContributionRetryCallerTestSupport.meta(id, 1600),
                    "a@x",
                    7L,
                ),
            )
            assertEquals("only the one real audio record counts", 1, store.ownedRecordsForTest())
        } finally {
            store.close()
        }
    }

    private fun queueDir(root: File): File = File(root, DIR_NAME)

    private fun completeEntriesOnDisk(root: File): Int = queueDir(root).listFiles().orEmpty().count { f ->
        f.name.endsWith(SUFFIX_WAV) &&
            f.name.removeSuffix(SUFFIX_WAV).let { id ->
                runCatching { UUID.fromString(id).toString() }.getOrNull() == id &&
                    File(queueDir(root), "$id$SUFFIX_META").isFile
            }
    }
}

private const val SUFFIX_WAV = ".recording.wav"
private const val SUFFIX_META = ".metadata.json"
private const val DIR_NAME = "contributions"
