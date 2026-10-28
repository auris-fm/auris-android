package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.VoiceSharingPolicyProvider
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
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
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * THE GATE-3 COMPOSITION CONTROL (plan verification gate 3; @spec 76342c89).
 *
 * The neighboring controls each double ONE side of this composition: the wiring tests drive the
 * real assembly but with `policyAllows` as a lambda fixture; the observed-disable and durable-queue
 * tests drive the real provider flow but never through the module's own factory methods. Neither
 * can see a wiring defect in the JOIN: e.g. `provideUploadAssembly` reading a DIFFERENT provider
 * instance than the watcher subscribes to would keep every neighbor green while production never
 * observes a disable at the send boundary.
 *
 * This control therefore composes the MODULE'S OWN factories — `VoiceUploadModule
 * .provideUploadAssembly` (whose `policyAllows` reads the provider the module was handed) and
 * `IdentityRevocationWatchers.production` (the real policy-disable tail subscription) — around ONE
 * real `VoiceSharingPolicyProvider`, and delivers the flip through that provider's real published
 * flow. The only doubles are the Firebase config seam (established pattern) and the local HTTP
 * server standing in for the worker.
 *
 * Two independent guards are pinned, so a mutant in either must fail:
 *  - the disable TAIL: a queued entry published BEFORE the flip is purged from the durable index;
 *  - the GATE READ: an entry prepared AFTER the flip is never admitted — nothing further arrives
 *    at the server, and the entry stays owned by the queue.
 *
 * Nothing here enables collection outside this class: the provider is constructed per-test and
 * dies with it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class VoiceUploadModulePolicyGateTest {

    private val dispatcher = UnconfinedTestDispatcher()

    /** The provider (and its poll-loop/watcher collectors) run on a REAL dispatcher: the lease
     *  timer and renewal loop are wall-clock code paths (sleepMs defaults to real delay), and the
     *  15-minute lease must simply never expire inside this test. A virtual scheduler would fire
     *  the no-op sleeps immediately and fail the policy closed — the wrong reason entirely. */
    private val providerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var scope: CoroutineScope
    private lateinit var app: Application

    private val requests = AtomicInteger(0)
    private val seenBodies = ConcurrentLinkedQueue<String>()
    private var server: HttpServer? = null

    /** When non-null, the server parks each request until released: a genuinely IN-FLIGHT call. */
    @Volatile
    var holdRequests: java.util.concurrent.CountDownLatch? = null

    /** Signals the handler has REACHED the hold (arrival, not parking). */
    @Volatile
    var holdReached: java.util.concurrent.CountDownLatch? = null

    /** Signals the handler finished writing its response: the client attempt has settled. */
    @Volatile
    var handlerCompleted: java.util.concurrent.CountDownLatch? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // REAL Unconfined (as the wiring controls use): the trigger's poll loop delays between
        // passes, and a virtual scheduler would strand it after the first pass.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        app = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        server?.stop(0)
        scope.cancel()
        providerScope.cancel()
        Dispatchers.resetMain()
    }

    /** REAL provider. The flip is published through the REAL StateFlow the watcher subscribes to
     *  and the gate reads (the same seam the observed-disable and durable-queue controls use, and
     *  the same honest scope: what this control pins is the DI/subscription JOIN, not the Firebase
     *  fetch/activate choreography, which the provider's own test owns). */
    private fun provider(sharedLock: Any): VoiceSharingPolicyProvider {
        val config = mock<FirebaseRemoteConfig>()
        // The provider's OWN init refresh must never publish over this control's seam snapshots.
        // Its fetch bookkeeping reads as fresh and its fetch parks uncompleted, so the init
        // refresh suspends inside its bounded wait for the whole test and the published flow
        // carries ONLY what the seam publishes.
        val info = mock<com.google.firebase.remoteconfig.FirebaseRemoteConfigInfo>()
        whenever(info.fetchTimeMillis).thenReturn(1_000L)
        whenever(info.lastFetchStatus).thenReturn(FirebaseRemoteConfig.LAST_FETCH_STATUS_SUCCESS)
        whenever(config.info).thenReturn(info)
        whenever(config.fetch()).thenAnswer { au.com.shiftyjelly.pocketcasts.sharedtest.JvmTask<Unit>() }
        return VoiceSharingPolicyProvider(
            remoteConfig = config,
            featureProvider = mock(),
            scope = providerScope,
            nowMs = { android.os.SystemClock.elapsedRealtime() },
            sleepMs = { },
            refreshTimeoutMs = 60_000L,
            sharedCollectionLock = sharedLock,
        )
    }

    private fun snapshot(enabled: Boolean) = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.PolicySnapshot(
        enabled = enabled,
        activatedAtMonotonicMs = if (enabled) android.os.SystemClock.elapsedRealtime() else 0L,
        activationGeneration = if (enabled) 1L else 0L,
    )

    private fun startServer(receiptBody: (String) -> String) {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/v1/contributions") { exchange ->
            try {
                requests.incrementAndGet()
                seenBodies.add(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
                holdReached?.countDown()
                holdRequests?.await(30, java.util.concurrent.TimeUnit.SECONDS)
                val id = Regex("\"capture_id\":\"([^\"]+)\"").find(seenBodies.last())?.groupValues?.get(1)
                val body = receiptBody(id ?: "").toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(201, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } finally {
                // A CANCELLED call never reads a response: the completion signal must fire even
                // when the client is gone, or the server thread parks for its full 30 s.
                handlerCompleted?.countDown()
            }
        }
        http.start()
        server = http
    }

    /** A REAL published capture through the store's prepare path (same as the wiring controls). */
    private fun prepareCapture(store: ContributionStore, id: String) {
        val pcm = listOf(ShortArray(1600) { i -> i.toShort() } to 1600)
        val expected = java.io.ByteArrayOutputStream()
        WavWriter.writeHeader(expected, 1600)
        for ((chunk, valid) in pcm) WavWriter.writePcm(expected, chunk, valid)
        val audio = expected.toByteArray()
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(audio)
            .joinToString("") { "%02x".format(it) }
        val block = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.AudioBlock(
            sha256 = sha,
            byteCount = audio.size.toLong(),
            retainedSamples = 1600,
            originalFormat = "pcm_s16le",
            conversionVersion = "v1",
            truncated = false,
            droppedSpans = emptyList(),
        )
        val metadata = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.CanonicalJson.canonicalBytes(
            au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ContributionRecordBuilder(id, "epoch-1", "42")
                .model("vad", au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef.Named("silero-vad", "4.0"))
                .model("wakeword", au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef.Named("auris-wake", "1.2"))
                .model("asr", au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef.Named("whisper-small", "2024-11"))
                .model("translation", au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef.None)
                .model("router", au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef.Named("lfm-intent", "0.3"))
                .stage(au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageName.Wake, au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageEntry(au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageState.Completed, mapOf("decision" to au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode.Str("detected"))))
                .stage(au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageName.Bleed, au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageEntry(au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageState.Completed, mapOf("decision" to au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode.Str("no_overlap"))))
                .stage(au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageName.Asr, au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageEntry(au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageState.Completed, mapOf("text" to au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode.Str("skip this ad"))))
                .stage(au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageName.Translation, au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageEntry(au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageState.NotRun, mapOf("reason" to au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode.Str("no_text"))))
                .stage(au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageName.Routing, au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageEntry(au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageState.NotRun, mapOf("reason" to au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode.Str("no_candidates"))))
                .stage(au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageName.Execution, au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageEntry(au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.StageState.NotRun, mapOf("reason" to au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode.Str("outcome_wait_expired"))))
                .build(platform = "android", appVersion = "8.20", build = "12345", audio = block),
        )
        val admission = store.admitForTest(1600, metadata.size)
        val generation = store.fence.currentGeneration()
        org.junit.Assert.assertNotNull(store.prepareAt(generation, admission, id, "epoch-1", pcm, metadata, "a@x", 7L))
        assertEquals(1, store.recordAttempt(generation, id))
    }

    private fun await(condition: () -> Boolean, what: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("timed out waiting for: $what", condition())
    }

    @Test
    fun `a published disable purges queued work AND the gate read refuses the next admission`() {
        // Hoisted for the finally cleanup (assigned inside the try).
        var root: File? = null
        var store: ContributionStore? = null
        val slotsBaseline = ContributionStore.processOpenSourceSlotsForTest()
        try {
            // ONE owner fixture shared by the account stack and the provider's collection lock: the
            // module requires the SAME primitive (reviewer 23cae70e b1).
            val startup = VoiceUploadCompositionFixtures.startup(app)
            val owner = startup.ownerForHandoff() as VoiceUploadCompositionFixtures.AppContextOwner
            // The account half of the credential authority: owner-published snapshot + its mirror.
            owner.publish("a@x", 7L, "Bearer-A")
            startup.credentials.publish("Bearer-A")
            // The production session/projection sequence (as the composition-path controls drive it):
            // arm the session, open an operation, apply the enrolled projection to THAT ticket.
            val token = startup.state.sessionStarted(7L)
            val ticket = startup.state.beginOperation(token, requireProjection = false, ownerSession = 7L)!!
            org.junit.Assert.assertTrue(
                "the real account state accepts the projection",
                startup.state.applyOutcome(token, ticket, VoiceUploadCompositionFixtures.authenticatedProjection("epoch-1"), ownerSession = 7L),
            )

            root = File.createTempFile("gate3", null).let { f ->
                f.delete()
                f.mkdirs()
                f
            }
            store = ContributionStore(
                root,
                QueueLimits.defaults().copy(maxQueueRecords = 8, retryInitialS = 4, retryMaxS = 8),
                clock = { 51_000_000L },
                jitter = { it - 1 },
            )

            val policyProvider = provider(owner.collectionSharedLock)
            // ENABLED FIRST, before anything subscribes: the watcher's collector is LEVEL-based, so an
            // initial disabled emission would purge the candidate and make later assertions pass for
            // the wrong reason (lesson from the observed-disable controls).
            policyProvider.applySnapshotForTest(snapshot(enabled = true))

            val idA = UUID.randomUUID().toString()
            prepareCapture(store, idA)

            startServer { id ->
                val digest = store.recordedDigest(id) ?: error("no recorded digest for $id")
                """{"schema_version":1,"capture_id":"$id","record_sha256":"$digest","status":"accepted",""" +
                    """"receipt_id":"rcpt-1","accepted_at":"2026-10-22T00:00:00Z","retention_expires_at":"2026-11-21T00:00:00Z"}"""
            }

            // THE MODULE'S OWN FACTORY: the gate reads the provider this composition handed it.
            val assembly = VoiceUploadModule.provideUploadAssembly(
                store,
                ContributionUploadTransport(
                    endpoint = "http://127.0.0.1:${server!!.address.port}/v1/contributions",
                    authorization = { "Bearer t" },
                    // Long enough that the PARKED call cannot client-time out mid-test: a timed-out
                    // attempt would free the caller to send B before the flip and vacate guard 1.
                    callTimeoutMs = 120_000,
                ),
                policyProvider,
                startup,
                actualNetwork = { true },
            )

            // THE MODULE'S OWN TRIGGER FACTORY — the real composition including the policy-disable
            // tail (a hand-rolled watcher here would leave the tail OUTSIDE the production path, and
            // the tail-removal mutant survived exactly that way). The retention side is the module's
            // real factory too; no capture is ever published through it in this control.
            val sink = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ContributionRetentionSink(au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionBudget(4L * 1024 * 1024))
            val producer = VoiceUploadModule.provideContributionCaptureProducer(
                context = app,
                store = store,
                accountStartup = startup,
            )
            val publishTrigger = VoiceUploadModule.provideRetentionPublishTrigger(
                sink = sink,
                producer = producer,
                accountStartup = startup,
                scope = scope,
            )
            val trigger = VoiceUploadModule.provideUploadTrigger(
                assembly = assembly,
                store = store,
                accountStartup = startup,
                policyProvider = policyProvider,
                scope = scope,
                publishTrigger = publishTrigger,
            )
            // ARM THE HOLD BEFORE START: a handler that arrives before the latch exists would finish
            // A outright, and requests>=1 would prove only arrival, never parking.
            holdRequests = java.util.concurrent.CountDownLatch(1)
            holdReached = java.util.concurrent.CountDownLatch(1)
            handlerCompleted = java.util.concurrent.CountDownLatch(1)
            trigger.start()

            // Premise: A's handler is verifiably PARKED (not merely arrived).
            await({ holdReached!!.await(0, java.util.concurrent.TimeUnit.MILLISECONDS) }, "A's handler reached the hold")

            // B must be genuinely QUEUED at flip time: A's call is parked IN-FLIGHT so the poll loop
            // cannot consume B while the policy is still enabled. (The first draft had no hold: B was
            // sent before the flip, the "purged" assertion passed vacuously, and the tail-removal
            // mutant SURVIVED - the masked-guard lesson again.)
            val idB = UUID.randomUUID().toString()
            prepareCapture(store, idB)
            assertTrue("B is queued before the flip", store.readyEntries().any { it.captureId == idB })

            // GUARD 1 (the disable TAIL), exercised while a call is genuinely in flight: the parked A
            // is cancelled, and B, queued before the flip, is purged from the durable index.
            policyProvider.applySnapshotForTest(snapshot(enabled = false))
            assertEquals("the disable published", false, policyProvider.publishedPolicy.value.enabled)
            await({ store.readyEntries().none { it.captureId == idB } }, "the disable tail purged B")

            // Release the parked call AFTER the flip and await its attempt's ACTUAL completion (the
            // handler finishing the response), not merely three manual polls, before the settled claims.
            holdRequests!!.countDown()
            // The parked attempt's TERMINAL outcome under the flip is CANCELLATION: the tail's
            // revokeAndPurge closes the in-flight call, so the honest completion signal is the entry
            // leaving the store entirely (attempts -1 = gone, not retried), not a settled 201.
            await({ store.stateAttemptsForTest(idA) == -1 }, "A's attempt terminally cancelled and revoked")
            await({ handlerCompleted!!.await(0, java.util.concurrent.TimeUnit.MILLISECONDS) }, "the server handler finished")

            // GUARD 2 (the GATE READ): an entry prepared AFTER the flip is never admitted. The gate
            // read lives in the assembly, so the same poll the loop runs is driven DIRECTLY here,
            // deterministically (the module cadence is 60s and would never poll within the test).
            val idC = UUID.randomUUID().toString()
            prepareCapture(store, idC)
            assertTrue("C is queued", store.readyEntries().any { it.captureId == idC })
            repeat(3) { org.junit.Assert.assertEquals("the closed gate admits nothing", 0, assembly.poll().attempted) }

            assertEquals("nothing further may arrive after the disable", 1, requests.get())
            assertTrue("C stays owned by the queue", store.readyEntries().any { it.captureId == idC })
        } finally {
            // Lifecycle completion, in dependency order: unblock the handler, stop the server,
            // CANCEL the trigger/provider work, then REQUIRE bounded successful joins of each
            // scope's children - a missed join fails here instead of passing silently - so nothing
            // can race the store close or the root removal. handlerCompleted stays an observation
            // of the HANDLER's own completion and is never counted from cleanup.
            holdRequests?.countDown()
            server?.stop(0)
            scope.cancel()
            val triggerJoined = kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(5_000) {
                    scope.coroutineContext[kotlinx.coroutines.Job]?.children?.toList()?.forEach { it.join() }
                    true
                } ?: false
            }
            org.junit.Assert.assertTrue("trigger scope children must terminate before cleanup", triggerJoined)
            providerScope.cancel()
            val providerJoined = kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(5_000) {
                    providerScope.coroutineContext[kotlinx.coroutines.Job]?.children?.toList()?.forEach { it.join() }
                    true
                } ?: false
            }
            org.junit.Assert.assertTrue("provider scope children must terminate before cleanup", providerJoined)
            // The cancelled attempt's borrowed descriptor must be back before the store closes.
            org.junit.Assert.assertEquals(
                "no source slots may leak past settlement",
                slotsBaseline,
                ContributionStore.processOpenSourceSlotsForTest(),
            )
            runCatching { store?.close() }
            root?.deleteRecursively()
        }
    }
}
