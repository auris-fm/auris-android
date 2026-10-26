package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.PolicySnapshot
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
    fun `a published disable purges the durable entry and a fresh owner cannot recover it`() {
        val store = ContributionStore(root, QueueLimits.defaults())
        val id = UUID.randomUUID().toString()
        val audio = chunks(1600, 1600)

        val entry = store.prepare(
            captureId = id,
            enrollmentEpoch = "epoch-1",
            pcmChunks = audio,
            canonicalMetadata = """{"capture_id":"$id"}""".toByteArray(),
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
            store = store,
            policyProvider = policyProvider,
        )
        scope.launch { watcher() }
        dispatcher.scheduler.runCurrent()

        assertTrue("the entry survives until the disable is published", store.readyEntries().isNotEmpty())

        policyProvider.applySnapshotForTest(disabled())
        dispatcher.scheduler.runCurrent()

        assertTrue("no entry is queued after the observed disable", store.readyEntries().isEmpty())
        assertTrue(
            "no audio or metadata FILE is left on disk for the capture",
            captureContentFiles(id).isEmpty(),
        )
        assertTrue("the purge reports no residue, so nothing survived it", store.purgeResidue.isEmpty())
        assertTrue("the purge is attributed to the observed disable", store.purgeReasons.contains("policy-disable"))

        // THE FRESH OWNER: a brand-new store over the same directory cannot recover it.
        val freshOwner = ContributionStore(root, QueueLimits.defaults())
        assertTrue("a fresh owner recovers nothing", freshOwner.readyEntries().isEmpty())
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

        // ORDINARY CLOSURE: listening ends, the policy was never disabled.
        store.discardCurrentPartials()
        dispatcher.scheduler.runCurrent()

        assertEquals("the published entry SURVIVES ordinary closure", 1, store.readyEntries().size)
        assertTrue("its content files are intact on disk", captureContentFiles(id).size >= 2)
        assertTrue("no purge ran", store.purgeReasons.isEmpty())
    }
}
