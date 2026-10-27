package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.PolicySnapshot
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.AudioBlock
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.CanonicalJson
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ContributionRecordBuilder
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageEntry
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageName
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageState
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The POLICY-DISABLE TAIL of the durable queue.
 *
 * An entry is genuinely PUBLISHED to the store's durable index (real audio and real canonical
 * metadata, through the production `prepare` path, not a fabricated index row). The disable is then
 * delivered through the provider's OWN published flow, exactly as production observes it - the test
 * never calls `revokeAndPurge` itself. Both halves are asserted on DURABLE state, not on an
 * in-memory sink: the entry leaves the index, its files are gone from disk, and a FRESH store over
 * the same directory - a new process owner - cannot recover or re-upload it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DurableQueuePolicyDisableTest {
    @org.junit.After
    fun assertNoSourceLeak() {
        org.junit.Assert.assertEquals(
            "this class left descriptors open; process-wide source slots belong to a later test",
            0,
            ContributionStore.processOpenSourceSlotsForTest(),
        )
    }

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var scope: CoroutineScope
    private lateinit var root: File

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        scope = CoroutineScope(dispatcher)
        root = File.createTempFile("queue-pol", null).let { f ->
            f.delete()
            f.mkdirs()
            f
        }
    }

    @After
    fun tearDown() {
        if (::scope.isInitialized) scope.cancel()
        Dispatchers.resetMain()
    }

    private fun enabled() = PolicySnapshot(
        enabled = true,
        activatedAtMonotonicMs = android.os.SystemClock.elapsedRealtime(),
        activationGeneration = 1L,
    )

    private fun disabled() = PolicySnapshot(enabled = false, activatedAtMonotonicMs = 0L, activationGeneration = 0L)

    private fun chunks(vararg sizes: Int) = sizes.map { ShortArray(it) { i -> i.toShort() } to it }

    /** The real WAV the store will write, so the record's declared sha matches the bytes on disk. */
    private fun wavBytes(samples: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        WavWriter.writeHeader(out, samples)
        WavWriter.writePcm(out, ShortArray(samples) { i -> i.toShort() }, samples)
        return out.toByteArray()
    }

    private fun sha256Hex(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * A FULLY VALID canonical record bound to the real audio, so a fresh store's `recover()` actually
     * restores this capture. A minimal `{capture_id}` record cannot: recovery validates the declared
     * audio sha against the WAV on disk and would drop it, making any later "recovers nothing"
     * assertion vacuously true.
     */
    private fun validRecord(captureId: String, samples: Int): ByteArray {
        val audio = wavBytes(samples)
        val block = AudioBlock(
            sha256 = sha256Hex(audio),
            byteCount = audio.size.toLong(),
            retainedSamples = samples.toLong(),
            originalFormat = "pcm_s16le",
            conversionVersion = "v1",
            truncated = false,
            droppedSpans = emptyList(),
        )
        return CanonicalJson.canonicalBytes(
            ContributionRecordBuilder(captureId, "epoch-1", "42")
                .model("vad", ModelRef.Named("silero-vad", "4.0"))
                .model("wakeword", ModelRef.Named("auris-wake", "1.2"))
                .model("asr", ModelRef.Named("whisper-small", "2024-11"))
                .model("translation", ModelRef.None)
                .model("router", ModelRef.Named("lfm-intent", "0.3"))
                .stage(StageName.Wake, StageEntry(StageState.Completed, mapOf("decision" to RecordNode.Str("detected"))))
                .stage(StageName.Bleed, StageEntry(StageState.Completed, mapOf("decision" to RecordNode.Str("no_overlap"))))
                .stage(StageName.Asr, StageEntry(StageState.Completed, mapOf("text" to RecordNode.Str("skip this ad"))))
                .stage(StageName.Translation, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_text"))))
                .stage(StageName.Routing, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_candidates"))))
                .stage(StageName.Execution, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("outcome_wait_expired"))))
                .build(platform = "android", appVersion = "8.20", build = "12345", audio = block),
        )
    }

    /**
     * Files carrying the capture's CONTENT (audio, metadata, sidecars). Excludes `disqualified/<id>`,
     * which is the store's deliberate tombstone recording that this capture must never be served
     * again - its presence is the purge working, not residue.
     */
    private fun captureContentFiles(id: String): List<File> = root.walkTopDown()
        .filter { it.isFile && it.name.contains(id) }
        .filter { it.parentFile?.name != "disqualified" }
        .toList()

    @Test
    fun `a purge never re-admits a discovered capture into the serving index`() {
        // Reviewer 4e8592ba: calling the FULL recovery routine from a purge contradicted the agreed
        // discovery-without-re-admission contract - recovery PROMOTES entries (ready/retryable), so a
        // capture could be acquired during the very withdrawal that is disposing of it. Discovery must
        // measure and delete, never publish.
        val previousOwner = ContributionStore(root, QueueLimits.defaults())
        val id = UUID.randomUUID().toString()
        val metadata = validRecord(id, 1600)
        assertTrue(
            previousOwner.prepare(id, "epoch-1", chunks(1600), metadata, "me@example.com", 1L) != null,
        )
        previousOwner.close()

        // OBSERVE the boundary: sample the serving index from INSIDE the purge, after discovery has run.
        // If discovery promoted anything, it is visible here.
        var readyDuringPurge = -1
        var servableDuringPurge = false
        lateinit var store: ContributionStore
        store = ContributionStore(root, QueueLimits.defaults())
        // Sample AFTER discovery: onPurgeOrRecoverMutation fires BEFORE it and so cannot see a promotion.
        store.onAfterPurgeDiscoveryForTest = {
            readyDuringPurge = store.readyEntries().size
            // The acquisition surface an attempt would use must ALSO be closed.
            servableDuringPurge = store.durableCapture(id) != null
        }
        assertTrue("PREMISE: a fresh owner's index starts empty", store.readyEntries().isEmpty())

        store.revokeAndPurge("policy-disable")

        assertEquals(
            "the purge re-admitted a discovered capture into the serving index",
            0,
            readyDuringPurge,
        )
        assertEquals(
            "an attempt could still acquire a capture during the purge boundary",
            false,
            servableDuringPurge,
        )
        assertTrue("the purge still removed the capture's files", captureContentFiles(id).isEmpty())
        store.close()
    }

    @Test
    fun `a published disable purges the durable entry and a fresh owner cannot recover it`() {
        val store = ContributionStore(root, QueueLimits.defaults())
        val id = UUID.randomUUID().toString()
        val audio = chunks(1600)

        val metadata = validRecord(id, 1600)
        val entry = store.prepare(
            captureId = id,
            enrollmentEpoch = "epoch-1",
            pcmChunks = audio,
            canonicalMetadata = metadata,
            enrollmentAccount = "me@example.com",
            enrollmentSession = 1L,
        )
        assertTrue("the entry must genuinely publish with real audio and metadata", entry != null)
        assertEquals("exactly one entry is queued", 1, store.readyEntries().size)
        // DURABLE evidence, read from DISK: the capture really has audio and metadata files, not just
        // an index row. (durableCapture additionally requires a full canonical record whose declared
        // audio sha matches the WAV, which a minimal test record cannot satisfy.)
        val filesBefore = captureContentFiles(id)
        assertTrue("the capture has audio and metadata files on disk", filesBefore.size >= 2)
        assertTrue("its audio file is genuinely nonempty", filesBefore.any { it.name.endsWith(".wav") && it.length() > 0L })

        // THE PREMISE: a fresh owner over the same directory really DOES recover this capture while
        // the policy is still enabled. Without this, "recovers nothing after the disable" would be
        // satisfied by a store that never recovers anything at all.
        // The publisher releases first (spec c8f766fb): one live owner per queue.
        store.close()
        val premiseOwner = ContributionStore(root, QueueLimits.defaults())
        val recoveredBefore = premiseOwner.recover()
        assertTrue(
            "PREMISE: a fresh owner recovers the published capture before the disable (got $recoveredBefore)",
            recoveredBefore.any { it.captureId == id },
        )
        premiseOwner.close()

        // THE LIVE OWNER for the disable event. `store` was CLOSED above so the premise owner could take
        // over, and a closed owner REFUSES every mutating surface - correctly, per spec c8f766fb, since
        // its directory may already belong to a successor. Handing the closed store to the watcher made
        // the purge a no-op and the control fail for a reason that had nothing to do with disable: it
        // observed `purges=[] refusals=1`. The watcher gets a store that actually owns the directory now.
        val liveStore = ContributionStore(root, QueueLimits.defaults())

        // THE LIVE EVENT: a disable on the provider's OWN published flow. A cancelled scope keeps
        // the provider's init refresh from overwriting the explicit fixture.
        val startup = VoiceUploadCompositionFixtures.startup(ApplicationProvider.getApplicationContext<Application>())
        val policyProvider = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.VoiceSharingPolicyModule.provideVoiceSharingPolicyProvider(
            remoteConfig = org.mockito.kotlin.mock(),
            featureProvider = org.mockito.kotlin.mock(),
            ownership = startup.ownerForHandoff(),
            scope = CoroutineScope(kotlinx.coroutines.Job().apply { cancel() }),
        )
        policyProvider.applySnapshotForTest(enabled())
        val watcher = IdentityRevocationWatchers.production(
            binding = startup.binding,
            accountState = startup.state,
            snapshot = { null },
            epoch = { null },
            store = liveStore,
            policyProvider = policyProvider,
        )
        scope.launch { watcher() }
        dispatcher.scheduler.runCurrent()

        // A fresh owner's index starts EMPTY; the pre-existing capture becomes servable only once this
        // owner recovers. That is the production startup sequence, and it is also what makes the purge
        // below meaningful - a store that never recovered anything would "purge" an empty index.
        assertTrue("PREMISE: a fresh owner's index starts empty", liveStore.readyEntries().isEmpty())
        val recoveredByLive = liveStore.recover()
        assertTrue(
            "PREMISE: the live owner recovers the pre-existing capture (got $recoveredByLive)",
            recoveredByLive.any { it.captureId == id },
        )
        assertTrue("the entry survives until the disable is published", liveStore.readyEntries().isNotEmpty())

        policyProvider.applySnapshotForTest(disabled())
        dispatcher.scheduler.runCurrent()

        assertTrue(
            "no entry is queued after the observed disable (purges=${liveStore.purgeReasons} " +
                "entries=${liveStore.readyEntries().size} files=${captureContentFiles(id).size} " +
                "refusals=${liveStore.guardedRefusalsForTest()} unknown=${liveStore.unknownBreakdownForTest()})",
            liveStore.readyEntries().isEmpty(),
        )
        assertTrue(
            "no audio or metadata FILE is left on disk for the capture",
            captureContentFiles(id).isEmpty(),
        )
        assertTrue("the purge reports no residue, so nothing survived it", liveStore.purgeResidue.isEmpty())
        assertTrue("the purge is attributed to the observed disable", liveStore.purgeReasons.contains("policy-disable"))

        // THE FRESH OWNER: a brand-new store over the same directory, explicitly RECOVERED. A fresh
        // store's index is empty until recover() reads disk, so asserting readyEntries() alone would
        // pass whether or not anything survived. The premise is proven separately below.
        val freshOwner = ContributionStore(root, QueueLimits.defaults())
        val recoveredAfter = freshOwner.recover()
        assertTrue(
            "a fresh owner RECOVERS NOTHING after the disable (got $recoveredAfter)",
            recoveredAfter.isEmpty(),
        )
        assertTrue("its index is empty too", freshOwner.readyEntries().isEmpty())
        assertTrue(
            "a fresh owner finds no audio or metadata file for the purged capture",
            captureContentFiles(id).isEmpty(),
        )
    }

    /**
     * The CONTRAST: ordinary listening closure must NOT destroy queued data while policy and
     * enrollment stay valid. Without this, a purge keyed too broadly would pass the disable test
     * while silently deleting every user's queued audio.
     */
    @Test
    fun `ordinary listening closure preserves a published entry while policy stays enabled`() {
        val store = ContributionStore(root, QueueLimits.defaults())
        val id = UUID.randomUUID().toString()
        store.prepare(
            captureId = id,
            enrollmentEpoch = "epoch-1",
            pcmChunks = chunks(1600),
            canonicalMetadata = """{"capture_id":"$id"}""".toByteArray(),
            enrollmentAccount = "me@example.com",
            enrollmentSession = 1L,
        )
        assertEquals("the entry is queued", 1, store.readyEntries().size)

        val startup = VoiceUploadCompositionFixtures.startup(ApplicationProvider.getApplicationContext<Application>())
        val policyProvider = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.VoiceSharingPolicyModule.provideVoiceSharingPolicyProvider(
            remoteConfig = org.mockito.kotlin.mock(),
            featureProvider = org.mockito.kotlin.mock(),
            ownership = startup.ownerForHandoff(),
            scope = CoroutineScope(kotlinx.coroutines.Job().apply { cancel() }),
        )
        policyProvider.applySnapshotForTest(enabled())
        val watcher = IdentityRevocationWatchers.production(
            binding = startup.binding,
            accountState = startup.state,
            snapshot = { null },
            epoch = { null },
            store = store,
            policyProvider = policyProvider,
        )
        scope.launch { watcher() }
        dispatcher.scheduler.runCurrent()

        // Establish a REAL in-progress partial: a `.part` staging file is exactly what
        // discardCurrentPartials() sweeps. Without it the contrast would prove only that a COMPLETED
        // entry survives, saying nothing about partial disposal.
        val contributions = File(root, "contributions").apply { mkdirs() }
        val partial = File(contributions, "${UUID.randomUUID()}.wav.part")
        partial.writeBytes(ByteArray(3200))
        assertTrue("the partial exists before closure", partial.isFile)

        // ORDINARY CLOSURE: listening ends, the policy was never disabled.
        store.discardCurrentPartials()
        dispatcher.scheduler.runCurrent()

        assertEquals("the published entry SURVIVES ordinary closure", 1, store.readyEntries().size)
        assertTrue("its content files are intact on disk", captureContentFiles(id).size >= 2)
        assertTrue("the in-progress PARTIAL is discarded by ordinary closure", !partial.exists())
        assertTrue("no purge ran", store.purgeReasons.isEmpty())
    }

    /**
     * STARTUP-DISABLED ACROSS PROCESSES (rulings 51ebeb5f, c47ef263): the production shape is a
     * PREVIOUS process that published the queue and a NEW process that finds it. The new store has
     * never run recover(), so its in-memory index is empty - the case an index-only purge silently
     * missed. The disable arrives on the provider's own flow as the FIRST observation; the test never
     * calls revokeAndPurge and never asks the store to recover first.
     */
    @Test
    fun `a startup-disabled policy purges a pre-existing queue on its first observation`() {
        // THE PREVIOUS OWNER: a separate store instance publishes, then is abandoned entirely.
        val previousOwner = ContributionStore(root, QueueLimits.defaults())
        val id = UUID.randomUUID().toString()
        val metadata = validRecord(id, 1600)
        assertTrue(
            "the legacy entry publishes",
            previousOwner.prepare(
                captureId = id,
                enrollmentEpoch = "epoch-1",
                pcmChunks = chunks(1600),
                canonicalMetadata = metadata,
                enrollmentAccount = "me@example.com",
                enrollmentSession = 1L,
            ) != null,
        )
        assertTrue("the previous owner's files reach disk", captureContentFiles(id).size >= 2)

        // PREMISE: the seeded queue is genuinely recoverable across processes before anything runs.
        // One live owner per queue (spec c8f766fb): each successive owner takes over after the previous
        // releases. The abandoning owner models process death, which drops the lock.
        previousOwner.close()
        val premiseOwner = ContributionStore(root, QueueLimits.defaults())
        val premise = premiseOwner.recover()
        assertTrue("PREMISE: a fresh owner recovers the seeded capture (got $premise)", premise.any { it.captureId == id })
        premiseOwner.close()

        // THIS PROCESS'S STORE: constructed and NEVER recovered. Its index is empty by construction,
        // which is exactly the shape that made the earlier control pass for the wrong reason.
        val store = ContributionStore(root, QueueLimits.defaults())
        assertTrue("the fresh store's index starts empty (unrecovered)", store.readyEntries().isEmpty())

        // The provider starts DISABLED and is never enabled: its very first emission is the disable.
        val startup = VoiceUploadCompositionFixtures.startup(ApplicationProvider.getApplicationContext<Application>())
        val policyProvider = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.VoiceSharingPolicyModule.provideVoiceSharingPolicyProvider(
            remoteConfig = org.mockito.kotlin.mock(),
            featureProvider = org.mockito.kotlin.mock(),
            ownership = startup.ownerForHandoff(),
            scope = CoroutineScope(kotlinx.coroutines.Job().apply { cancel() }),
        )
        policyProvider.applySnapshotForTest(disabled())

        val watcher = IdentityRevocationWatchers.production(
            binding = startup.binding,
            accountState = startup.state,
            snapshot = { null },
            epoch = { null },
            store = store,
            policyProvider = policyProvider,
        )
        scope.launch { watcher() }
        dispatcher.scheduler.runCurrent()

        assertTrue(
            "the pre-existing queue's files are cleared on the first disabled observation",
            captureContentFiles(id).isEmpty(),
        )
        assertTrue("the purge is attributed to the disable", store.purgeReasons.contains("policy-disable"))
        assertTrue("no content file survives", captureContentFiles(id).isEmpty())

        val recoveredAfter = ContributionStore(root, QueueLimits.defaults()).recover()
        assertTrue("a fresh owner recovers nothing after startup-disabled purge", recoveredAfter.isEmpty())
    }

    /**
     * DISCOVERY IS SCOPED to names this store could have written (ruling c47ef263: "enumerate only
     * the queue's owned storage namespace, not arbitrary files"). Asserts that unrelated files in the
     * queue directory survive.
     *
     * NOT claimed here: that one owner never deletes ANOTHER capture's files. A foreign capture is
     * "{uuid}.recording.wav", structurally identical to an owned one, and on a fresh store the index
     * holds no ownership to compare against - so disk discovery cannot distinguish them. That is a
     * real limitation of purging without prior recovery, reported to @spec rather than asserted away.
     */
    @Test
    fun `a purge leaves files outside the capture storage namespace alone`() {
        val previousOwner = ContributionStore(root, QueueLimits.defaults())
        val owned = UUID.randomUUID().toString()
        assertTrue(previousOwner.prepare(owned, "epoch-1", chunks(1600), validRecord(owned, 1600), "me@example.com", 1L) != null)

        val contributions = File(root, "contributions").apply { mkdirs() }
        val unrelated = File(contributions, "notes.txt").apply { writeText("user data") }
        val bareWav = File(contributions, "backup.wav").apply { writeBytes(ByteArray(64)) }
        val prefixed = File(contributions, "capture-notes.wav").apply { writeBytes(ByteArray(8)) }

        // The purging owner takes the directory only after the previous owner releases (spec c8f766fb).
        previousOwner.close()
        val store = ContributionStore(root, QueueLimits.defaults())
        store.revokeAndPurge("policy-disable")

        assertTrue("the owned capture's own files are gone", captureContentFiles(owned).isEmpty())
        assertTrue("an unrelated text file survives", unrelated.isFile)
        assertEquals("its contents are untouched", "user data", unrelated.readText())
        assertTrue("a bare .wav is not a store suffix and survives", bareWav.isFile)
        assertTrue("a non-capture-named file survives", prefixed.isFile)
    }
}
