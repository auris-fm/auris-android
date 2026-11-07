package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountStartup
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionAuthorityTransaction
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionBudget
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionLossWatcher
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ContributionRetentionSink
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * THE CONSTRUCTED PRODUCTION PATH (spec 2e4a5472): the pieces come from the MODULE's own provider
 * methods — the same calls Hilt makes — rather than hand-rolled stand-ins, and the path is driven
 * fixtures. What this pins: the module-built path polls, sends, and settles honestly.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class VoiceUploadCompositionPathTest {
    /**
     * Slots in use when THIS test started. The bound is process-wide, so a suite neighbour may legitimately
     * have left residue; what this test must prove is that ITS OWN work returned every slot it took. An
     * absolute zero assertion would fail on someone else's residue and pass for the wrong reason.
     */
    private var entrySourceSlots = 0

    @org.junit.Before
    fun recordEntryBaseline() {
        entrySourceSlots = ContributionStore.processOpenSourceSlotsForTest()
    }

    /**
     * Release every source this class opened. close() drops the source leases, so a store left open keeps
     * its descriptors (and the process-wide slots) alive past the test that owns them (reviewer 2c7dc375).
     */
    @org.junit.After
    fun releaseSources() {
        stores.forEach { store ->
            store.settleSourcesForTest()
            runCatching { store.close() }
        }
        // The JVM-wide producer store ([VoiceUploadCompositionPaths.emitOnce]) is shared by every control in
        // this class, so its descriptors outlive individual tests. Release them here rather than resetting
        // the counter: a reset would hide the descriptors, leases and pin charges this is meant to find.
        // Settle EVERY live store, not just the ones this test kept a reference to: the source bound is
        // process-wide, so a missed descriptor holds a slot for the rest of the JVM (reviewer 2c7dc375).
        stores.forEach { it.settleSourcesForTest() }
        stores.forEach { runCatching { it.close() } }
        val tracked = stores.flatMap { it.openCaptureIdsForTest() }
        org.junit.Assert.assertEquals(
            "this class left descriptors open (" + tracked + ")",
            emptyList<String>(),
            tracked,
        )
    }

    /** Every store this class builds, so teardown can release the sources each one holds. */
    private val stores = mutableListOf<ContributionStore>()

    private fun moduleStartup(): VoiceSharingAccountStartup = VoiceUploadCompositionFixtures.startup(androidx.test.core.app.ApplicationProvider.getApplicationContext())
    private fun authenticatedProjection(epoch: String): String = VoiceUploadCompositionFixtures.authenticatedProjection(epoch)

    @get:Rule
    val tmp = TemporaryFolder()

    // (The owner authority fixture moved to VoiceUploadCompositionFixtures so the shared joined-path
    // emitter can use the same one.)

    /** Loopback "cloud" that records every request that reaches it. */
    private fun loopback(bodyFor: () -> Pair<Int, String> = { 500 to "" }): Pair<HttpServer, AtomicInteger> {
        val hits = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            hits.incrementAndGet()
            val (status, body) = bodyFor()
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        return server to hits
    }

    // `moduleStartup()` above builds the REAL startup object (production state/credentials/coordinator/
    // binding); only its constructor inputs differ from the app's (no account signed in, retrofit never
    // called on this path).

    /**
     * Mirrors VoiceObservationModule.provideCollectionTx over the SAME startup/owner (one shared
     * lock per acquisition).
     */
    private fun collectionTxFor(startup: VoiceSharingAccountStartup): CollectionAuthorityTransaction = CollectionAuthorityTransaction(
        ownership = startup.ownerForHandoff(),
        sharedLock = startup.sharedCollectionLock(),
    )

    /** One capture admitted and prepared exactly as the producer leaves it, so the loop has work. */
    private fun prepareVerifiedCapture(store: ContributionStore): String {
        val id = java.util.UUID.randomUUID().toString()
        val pcm = listOf(ShortArray(1600) { i -> i.toShort() } to 1600)
        val expected = java.io.ByteArrayOutputStream()
        WavWriter.writeHeader(expected, 1600)
        for ((chunk, valid) in pcm) WavWriter.writePcm(expected, chunk, valid)
        val audio = expected.toByteArray()
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(audio).joinToString("") { "%02x".format(it) }
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
                .wakeRequired(true).build(platform = "android", appVersion = "8.20", build = "12345", audio = block),
        )
        val admission = store.admitForTest(1600, metadata.size)
        val generation = store.fence.currentGeneration()
        org.junit.Assert.assertNotNull(store.prepareAt(generation, admission, id, "epoch-1", pcm, metadata, "a@x", 7L))
        return id
    }

    /**
     * ACCEPTANCE THROUGH THE MODULE-BUILT ASSEMBLY (spec 89b4760b item 2): a loopback that returns a
     * VALID receipt settles the entry through the module's own assembly construction — including its
     * real `UploadIdentityAuthority` credential path — and the audio is removed; an INVALID receipt on
     * the same path leaves everything in place.
     */
    @Test
    fun `a valid receipt settles through the module-built assembly and an invalid one does not`() {
        val aurisLive = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.VoiceUploadCompositionFixtures.liveAuris()
        aurisLive.acquire()
        var status = 500
        var body = ""
        val (server, hits) = loopback { status to body }
        try {
            val root = tmp.newFolder("module-built-acceptance")
            // A ~zero retry backoff so both loopback polls are eligible in this fixture's clock;
            // every OTHER limit is the production default.
            val store = ContributionStore(
                root,
                QueueLimits.defaults().copy(retryInitialS = 1, retryMaxS = 1),
                clock = System::currentTimeMillis,
                jitter = { 0L },
            ).also { stores += it }
            val id = prepareVerifiedCapture(store)
            val startup = moduleStartup()
            // Publish the admitted identity exactly as the real account stack would: epoch projection
            // plus the owner snapshot (account, session, bearer) under the owner lock.
            val owner = startup.ownerForHandoff() as VoiceUploadCompositionFixtures.AppContextOwner
            owner.publish("a@x", 7L, "Bearer-fixture")
            // The credential mirror the network gate reads (the production 401 path publishes the
            // SAME way through `credentials.publish`).
            startup.credentials.publish("Bearer-fixture")
            // The production publication: the authoritative GET answer becomes the projection.
            val projection = startup.state.parseProjection(VoiceUploadCompositionFixtures.authenticatedProjection("epoch-1"))!!
            startup.state.publish(projection)

            val assembly = VoiceUploadModule.provideUploadAssembly(
                store = store,
                transport = ContributionUploadTransport(
                    endpoint = "http://127.0.0.1:${server.address.port}/v1/contributions",
                    authorization = { "Bearer-fixture" },
                ),
                accountStartup = startup,
                context = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                aurisToken = aurisLive.provider,
            )

            // 1. INVALID receipt: nothing settles, the durable capture stays.
            status = 201
            body = "{\"schema_version\":1,\"capture_id\":\"" + id + "\",\"record_sha256\":\"deadbeef\",\"status\":\"accepted\"}"
            val firstPoll = assembly.poll()
            org.junit.Assert.assertTrue(
                "the module-built path reached the transport (poll=$firstPoll, hits=${hits.get()})",
                hits.get() >= 1,
            )
            org.junit.Assert.assertNotNull("an invalid receipt never settles", store.durableCapture(id)?.owned?.also { it.settle(1_000L) })

            // 2. VALID receipt echoing the store's own digest: the entry settles and is removed.
            val digest = store.recordedDigest(id)!!
            body = "{\"schema_version\":1,\"capture_id\":\"$id\",\"record_sha256\":\"$digest\",\"status\":\"accepted\",\"receipt_id\":\"rcpt-1\",\"accepted_at\":\"2026-10-22T00:00:00Z\",\"retention_expires_at\":\"2026-11-21T00:00:00Z\"}"
            val hitsBefore = hits.get()
            // The attempt counter must allow a second attempt in this fixture's clock.
            val secondPoll = assembly.poll()
            org.junit.Assert.assertTrue(
                "a second attempt reached the transport (before=$hitsBefore, after=${hits.get()}, poll=$secondPoll)",
                hits.get() > hitsBefore,
            )
            org.junit.Assert.assertNull("a VALIDATED receipt settles the entry", store.durableCapture(id))
        } finally {
            aurisLive.shutdown()
            server.stop(0)
        }
    }

    //
    // THE JOINED PATH (spec follow-up to 89b4760b item 1): engine observation events feed the REAL
    // sink, the module's REAL RetentionPublishTrigger (real owner transaction, real producer)
    // publishes the capture as a DURABLE store record, and the module-built assembly then drains
    // that record through the loopback to a VALIDATED receipt — one continuous path, no hand-built
    // stand-ins for any stage.
    //

    /**
     * RED PENDING SPEC DELTA (spec 14:46:41Z): the REAL EngineStageProvenance emits Unknown stages
     * with NO reason and the record builder refuses them — every real engine publish currently dies
     * record-less. Spec adjudicated a new shared reason `observation_unavailable` (state unknown
     * ONLY); the fix lands with the exact spec delta + strict Worker-validator parity, not before.
     * RED receipt: receipts-backup/task73-joined-red/ (sha256 50b7dd2b…).
     */
    @Test
    fun `the joined engine-sink-trigger-producer path lands a durable record and settles it end to end`() {
        val aurisLive = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.VoiceUploadCompositionFixtures.liveAuris()
        aurisLive.acquire()
        var status = 500
        var body = ""
        val (server, hits) = loopback { status to body }
        try {
            // The ENGINE side is produced once per JVM by [emitJoinedRecord] (the same producer the
            // Worker parity control draws from), so both controls measure the SAME emitted bytes.
            val emitted = VoiceUploadCompositionPaths.emitOnce()
            val store = emitted.store
            val captureId = emitted.captureId
            val startup = emitted.startup
            val owner = emitted.owner
            val scope = emitted.scope
            try {
                // 3. DRAIN side: the module-built assembly uploads and the VALIDATED receipt settles.
                val assembly = VoiceUploadModule.provideUploadAssembly(
                    store = store,
                    transport = ContributionUploadTransport(
                        endpoint = "http://127.0.0.1:${server.address.port}/v1/contributions",
                        authorization = { "Bearer-fixture" },
                    ),
                    accountStartup = startup,
                    context = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                    aurisToken = aurisLive.provider,
                )
                status = 201
                body = "{\"schema_version\":1,\"capture_id\":\"$captureId\",\"record_sha256\":\"${store.recordedDigest(captureId.toString())}\"," +
                    "\"status\":\"accepted\",\"receipt_id\":\"rcpt-j\",\"accepted_at\":\"2026-10-23T00:00:00Z\",\"retention_expires_at\":\"2026-11-22T00:00:00Z\"}"
                assembly.poll()
                assertTrue("the joined path reached the transport", hits.get() >= 1)
                org.junit.Assert.assertNull("the durable record settled through the joined path", store.durableCapture(captureId.toString()))
                // The emitted bytes are the store's own, re-verified by the store before it serves them.
                assertTrue("the emitted record must be the store's servable capture", emitted.bytes.isNotEmpty())
            } finally {
                aurisLive.shutdown()
                scope.cancel()
            }
        } finally {
            server.stop(0)
        }
    }

    /**
     * FOREGROUND vs DEFERRED DRAIN (spec follow-up): one serialized store owner. (1) A second drain
     * while an attempt is in flight never overlaps it — the entry is skipped in-flight, not
     * double-sent. (2) A drain with the network gate closed defers; when the gate opens (the
     * owner-published credential appears) the SAME durable entry is picked up and settles — durable
     * work resumes on the next permitted execution.
     */
    @Test
    fun `drains never overlap an entry and deferred work resumes when the network gate opens`() {
        val aurisLive = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.VoiceUploadCompositionFixtures.liveAuris()
        aurisLive.acquire()
        var status = 500
        var body = ""
        val release = java.util.concurrent.CountDownLatch(1)
        val (server, hits) = loopback {
            // Bounded: a blocked handler must never outlive the test (a parked non-daemon
            // pool thread would hang the JVM after the assertion fails).
            release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            status to body
        }
        try {
            val root = tmp.newFolder("shared-owner")
            val store = ContributionStore(
                root,
                QueueLimits.defaults().copy(retryInitialS = 1, retryMaxS = 1),
                clock = System::currentTimeMillis,
                jitter = { 0L },
            ).also { stores += it }
            val id = prepareVerifiedCapture(store)
            val startup = moduleStartup()
            val owner = startup.ownerForHandoff() as VoiceUploadCompositionFixtures.AppContextOwner
            owner.publish("a@x", 7L, "Bearer-fixture")
            startup.publishEnrolledFixture("epoch-1")
            val assembly = VoiceUploadModule.provideUploadAssembly(
                store = store,
                transport = ContributionUploadTransport(
                    endpoint = "http://127.0.0.1:${server.address.port}/v1/contributions",
                    authorization = { "Bearer-fixture" },
                ),
                accountStartup = startup,
                context = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                aurisToken = aurisLive.provider,
            )
            status = 201
            body = "{\"schema_version\":1,\"capture_id\":\"$id\",\"record_sha256\":\"deadbeef\",\"status\":\"accepted\"}"
            // The network gate is the owner-published credential: open it for the first drain
            // (part 2 below closes and reopens it).
            startup.credentials.publish("Bearer-fixture")

            // (1) Attempt one IN FLIGHT (loopback blocked); a second drain must skip, not overlap.
            val inFlight = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                val first = inFlight.submit { assembly.poll() }
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
                while (hits.get() < 1 && System.nanoTime() < deadline) Thread.sleep(10)
                assertTrue("first drain reached the transport", hits.get() == 1)
                // While the attempt is in flight the caller's in-flight claim makes a re-entrant
                // drain SKIP the entry: the second drain starts no attempt (the transport's
                // metadata-first envelope can legitimately log >1 request for the ONE attempt).
                val second = assembly.poll()
                assertTrue(
                    "an in-flight attempt is NEVER overlapped by a second drain (second=$second): " +
                        "the in-flight claim (skippedInFlight) or the consumed attempt-charge " +
                        "(entry absent from retryEligible) each fail closed",
                    second.attempted == 0 && second.skippedInFlight <= 1,
                )
                release.countDown()
                first.get(5, java.util.concurrent.TimeUnit.SECONDS)
                assertEquals(
                    "exactly ONE attempt was consumed for the entry",
                    1,
                    store.stateAttemptsForTest(id),
                )
            } finally {
                aurisLive.shutdown()
                inFlight.shutdownNow()
            }

            // (2) The invalid receipt retained the entry. The network gate (owner-published
            //     credential) now CLOSES: the drain defers. It reopens: the same durable entry
            //     resumes and settles.
            org.junit.Assert.assertNotNull(store.durableCapture(id)?.owned?.also { it.settle(1_000L) })
            startup.credentials.publish(null)
            val closed = assembly.poll()
            assertEquals("gate closed: deferred, not attempted", 1, closed.deferredNetwork)
            assertEquals("gate closed: nothing sent", 1, hits.get())
            startup.credentials.publish("Bearer-fixture")
            val digest = store.recordedDigest(id)!!
            body = "{\"schema_version\":1,\"capture_id\":\"$id\",\"record_sha256\":\"$digest\",\"status\":\"accepted\",\"receipt_id\":\"rcpt-r\",\"accepted_at\":\"2026-10-23T00:00:00Z\",\"retention_expires_at\":\"2026-11-22T00:00:00Z\"}"
            val resumed = assembly.poll()
            assertTrue("gate open: the deferred entry resumed (poll=$resumed)", hits.get() > 1)
            org.junit.Assert.assertNull("resumed durable work settles", store.durableCapture(id))
        } finally {
            server.stop(0)
        }
    }

    /**
     * WORKER EXECUTION CONSTRUCTION (reviewer 177645cc): the persisted worker is instantiated
     * through the real WorkManager worker-construction path (TestListenableWorkerBuilder +
     * a WorkerFactory producing the @HiltWorker's constructor shape) and its doWork() runs the
     * MODULE-BUILT drain (the module assembly's own poll) against a loopback to a VALIDATED
     * receipt. HONEST BOUNDARY: without a device install the literal HiltWorkerFactory graph
     * is not executed here — the @AssistedInject constructor shape and the generated
     * VoiceUploadDrainWorker_HiltWorker registration are source/compilation evidence only.
     */
    @Test
    fun `the drain worker constructs through the worker factory path and runs the module drain`() {
        val aurisLive = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.VoiceUploadCompositionFixtures.liveAuris()
        aurisLive.acquire()
        var status = 500
        var body = ""
        val (server, hits) = loopback { status to body }
        try {
            val root = tmp.newFolder("worker-construction")
            val store = ContributionStore(
                root,
                QueueLimits.defaults().copy(retryInitialS = 1, retryMaxS = 1),
                clock = System::currentTimeMillis,
                jitter = { 0L },
            ).also { stores += it }
            val id = prepareVerifiedCapture(store)
            val startup = moduleStartup()
            val owner = startup.ownerForHandoff() as VoiceUploadCompositionFixtures.AppContextOwner
            owner.publish("a@x", 7L, "Bearer-fixture")
            startup.credentials.publish("Bearer-fixture")
            startup.publishEnrolledFixture("epoch-1")
            val assembly = VoiceUploadModule.provideUploadAssembly(
                store = store,
                transport = ContributionUploadTransport(
                    endpoint = "http://127.0.0.1:${server.address.port}/v1/contributions",
                    authorization = { "Bearer-fixture" },
                ),
                accountStartup = startup,
                context = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                aurisToken = aurisLive.provider,
            )
            // THE MODULE DRAIN BINDING — the exact provider Hilt consumes.
            val drain = VoiceUploadModule.provideVoiceUploadDrain(store, assembly, aurisLive.provider)
            val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
            val worker = androidx.work.testing.TestListenableWorkerBuilder<VoiceUploadDrainWorker>(context)
                .setWorkerFactory(object : androidx.work.WorkerFactory() {
                    override fun createWorker(
                        appContext: android.content.Context,
                        workerClassName: String,
                        workerParameters: androidx.work.WorkerParameters,
                    ): androidx.work.ListenableWorker = VoiceUploadDrainWorker(appContext, workerParameters, drain)
                })
                .build()
            status = 201
            val digest = store.recordedDigest(id)!!
            body = "{\"schema_version\":1,\"capture_id\":\"$id\",\"record_sha256\":\"$digest\",\"status\":\"accepted\",\"receipt_id\":\"rcpt-w\",\"accepted_at\":\"2026-10-23T00:00:00Z\",\"retention_expires_at\":\"2026-11-22T00:00:00Z\"}"
            val result = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().let { _ ->
                kotlinx.coroutines.runBlocking { worker.doWork() }
            }
            assertEquals("the persisted worker drained successfully", androidx.work.ListenableWorker.Result.success(), result)
            assertTrue("the worker's drain reached the transport", hits.get() >= 1)
            org.junit.Assert.assertNull("the worker's drain settled the durable entry", store.durableCapture(id))
        } finally {
            aurisLive.shutdown()
            server.stop(0)
        }
    }

    // ---- DURABLE RESUME (on-device gap 2026-11-07; recovery now happens ONCE per process, in the
    // ---- module's store provider, BEFORE any capture preparation can begin - reviewer 032a8639).
    // ---- Controls through the REAL provider-created store and the module's own drain binding.

    private val appContext: android.content.Context
        get() = androidx.test.core.app.ApplicationProvider.getApplicationContext()

    /** The queue directory the production provider owns (voice-contributions/contributions). */
    private val appQueue: java.io.File
        get() = java.io.File(java.io.File(appContext.filesDir, "voice-contributions"), "contributions")

    /**
     * The PRE-EXISTING process: a store built with the JVM-runnable sync default (Robolectric
     * cannot run the production android.system.Os barrier), over the SAME directory the production
     * provider owns. Its preparations stage the durable files a later process must recover.
     */
    private fun priorProcessStore(): ContributionStore = ContributionStore(
        java.io.File(appContext.filesDir, "voice-contributions"),
        QueueLimits.defaults().copy(retryInitialS = 1, retryMaxS = 1),
        clock = System::currentTimeMillis,
        jitter = { 0L },
    ).also { stores += it }

    /**
     * THE REAL PROVIDER PATH: contributionStoreFor is the body provideContributionStore runs; each
     * call models a fresh process (Hilt singletons do not survive one) and CREATION runs recovery.
     * The sync override only swaps the platform barrier for the JVM-runnable REAL surface the tests
     * already use elsewhere; Robolectric cannot execute android.system.Os.
     */
    private fun providerStore(): ContributionStore = VoiceUploadModule.contributionStoreFor(
        java.io.File(appContext.filesDir, "voice-contributions"),
        directorySyncOverride = DirectorySync.REAL,
    ).also { stores += it }

    private fun sha256Hex(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun receiptBody(id: String, recordSha: String): String = (
        "{\"schema_version\":1,\"capture_id\":\"" + id + "\",\"record_sha256\":\"" + recordSha +
            "\",\"status\":\"accepted\",\"receipt_id\":\"rcpt-r\",\"accepted_at\":\"2026-11-07T00:00:00Z\"," +
            "\"retention_expires_at\":\"2027-11-07T00:00:00Z\"}"
        )

    /** The projection/owner/credential fixture wiring shared by the resume controls. */
    private fun publishIdentity(startup: VoiceSharingAccountStartup, enrolledEpoch: String) {
        val owner = startup.ownerForHandoff() as VoiceUploadCompositionFixtures.AppContextOwner
        owner.publish("a@x", 7L, "Bearer-fixture")
        startup.credentials.publish("Bearer-fixture")
        startup.publishEnrolledFixture(enrolledEpoch)
    }

    private fun moduleDrain(
        store: ContributionStore,
        startup: VoiceSharingAccountStartup,
        aurisLive: VoiceUploadCompositionFixtures.LiveAuris,
        server: HttpServer,
    ): VoiceUploadDrain = VoiceUploadModule.provideVoiceUploadDrain(
        store,
        VoiceUploadModule.provideUploadAssembly(
            store = store,
            transport = ContributionUploadTransport(
                endpoint = "http://127.0.0.1:${server.address.port}/v1/contributions",
                authorization = { "Bearer-fixture" },
            ),
            accountStartup = startup,
            context = appContext,
            aurisToken = aurisLive.provider,
        ),
        aurisLive.provider,
    )

    private fun runDrain(drain: VoiceUploadDrain) {
        kotlinx.coroutines.runBlocking { drain.drainOnce() }
    }

    @Test
    fun `an entry recorded before a process restart is served after the provider recovers at creation`() {
        val aurisLive = VoiceUploadCompositionFixtures.liveAuris()
        aurisLive.acquire()
        var status = 500
        var body = ""
        val (server, hits) = loopback { status to body }
        try {
            val store1 = priorProcessStore()
            val id = prepareVerifiedCapture(store1)
            val metaBytes = java.io.File(appQueue, "$id.metadata.json").readBytes()
            store1.close()

            // A FRESH provider creation over the same directory = the restart. Recovery runs HERE,
            // at creation, before this test can prepare anything else.
            val startup = moduleStartup()
            publishIdentity(startup, "epoch-1")
            val store2 = providerStore()
            val drain = moduleDrain(store2, startup, aurisLive, server)
            status = 201
            body = receiptBody(id, sha256Hex(metaBytes))
            runDrain(drain)

            assertTrue(
                "the durable entry recorded before the restart must be uploaded",
                hits.get() >= 1,
            )
            org.junit.Assert.assertNull("the served entry must settle off the queue", store2.durableCapture(id))
        } finally {
            aurisLive.shutdown()
            server.stop(0)
        }
    }

    @Test
    fun `an old-schema durable entry is deleted by the creation recovery record check, not uploaded`() {
        val aurisLive = VoiceUploadCompositionFixtures.liveAuris()
        aurisLive.acquire()
        val (server, hits) = loopback { 201 to "{}" }
        try {
            val store1 = priorProcessStore()
            val id = prepareVerifiedCapture(store1)
            val metaFile = java.io.File(appQueue, "$id.metadata.json")
            // Rewrite the record WITHOUT wake_required: exactly what the pre-Phase-2 build wrote.
            val oldSchemaText = metaFile.readText()
                .replace("\"wake_required\":true,", "")
                .replace(",\"wake_required\":true", "")
            org.junit.Assert.assertFalse(
                "test setup: the rewrite must actually remove wake_required",
                oldSchemaText.contains("wake_required"),
            )
            val oldSchemaBytes = oldSchemaText.toByteArray()
            metaFile.writeBytes(oldSchemaBytes)
            java.io.File(appQueue, "$id.sha256").writeText(sha256Hex(oldSchemaBytes))
            store1.close()

            val startup = moduleStartup()
            publishIdentity(startup, "epoch-1")
            val store2 = providerStore()
            val drain = moduleDrain(store2, startup, aurisLive, server)
            runDrain(drain)

            assertEquals(
                "an old-schema entry must never reach the transport",
                0,
                hits.get(),
            )
            assertTrue(
                "the creation recovery's record check must delete the old-schema entry at once",
                store2.terminalCounter(ContributionStore.TerminalReason.INVALID_ON_RECOVERY) >= 1,
            )
            org.junit.Assert.assertFalse(
                "the deleted entry's files must be gone",
                metaFile.exists() || java.io.File(appQueue, "$id.recording.wav").exists(),
            )
        } finally {
            aurisLive.shutdown()
            server.stop(0)
        }
    }

    @Test
    fun `a valid same-owner entry from an older enrollment epoch still uploads after recovery`() {
        val aurisLive = VoiceUploadCompositionFixtures.liveAuris()
        aurisLive.acquire()
        var status = 500
        var body = ""
        val (server, hits) = loopback { status to body }
        try {
            val store1 = priorProcessStore()
            val id = prepareVerifiedCapture(store1)
            val metaBytes = java.io.File(appQueue, "$id.metadata.json").readBytes()
            store1.close()

            val startup = moduleStartup()
            // The account has re-enrolled SINCE the recording: the projection carries a NEWER epoch.
            // An older epoch alone is PROVENANCE (4afc6e43) - never a deletion or refusal reason.
            publishIdentity(startup, "epoch-2")
            val store2 = providerStore()
            val drain = moduleDrain(store2, startup, aurisLive, server)
            status = 201
            body = receiptBody(id, sha256Hex(metaBytes))
            runDrain(drain)

            assertTrue(
                "an older-epoch recording must still upload after recovery",
                hits.get() >= 1,
            )
            org.junit.Assert.assertNull(store2.durableCapture(id))
        } finally {
            aurisLive.shutdown()
            server.stop(0)
        }
    }

    /**
     * RECOVERY-VS-PREPARATION OVERLAP (reviewer 032a8639 HOLD): a drain while an admitted
     * preparation is paused between its `.part` write and publication must NOT disturb it - the
     * capture commits afterwards and uploads EXACTLY ONCE. Because recovery runs only at store
     * creation (before any preparation), the drain has no destructive sweep to race.
     */
    @Test
    fun `a drain during a paused preparation leaves it intact - the capture commits and uploads exactly once`() {
        val aurisLive = VoiceUploadCompositionFixtures.liveAuris()
        aurisLive.acquire()
        var status = 500
        var body = ""
        val (server, hits) = loopback { status to body }
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        try {
            // Recovery-at-creation, THEN the pausable preparation. The hook seam is the store's
            // documented test injection point (StoreHooks); pause AFTER the part write, exactly the
            // window the reviewer flagged.
            val root = tmp.newFolder("overlap-drain-vs-prepare")
            val store = ContributionStore(
                root,
                QueueLimits.defaults().copy(retryInitialS = 1, retryMaxS = 1),
                clock = System::currentTimeMillis,
                jitter = { 0L },
                hooks = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.StoreHooks(
                    onWavPartWrite = {
                        entered.countDown()
                        release.await()
                    },
                ),
            ).also { stores += it }
            store.recover()

            var preparedId: String? = null
            val preparation = Thread { preparedId = prepareVerifiedCapture(store) }
            preparation.start()
            org.junit.Assert.assertTrue("test setup: the preparation must reach the part write", entered.await(10, java.util.concurrent.TimeUnit.SECONDS))

            // The drain fires WHILE the preparation is paused mid-write.
            val startup = moduleStartup()
            publishIdentity(startup, "epoch-1")
            val drain = moduleDrain(store, startup, aurisLive, server)
            runDrain(drain)
            assertEquals("the paused preparation must not be served mid-write", 0, hits.get())

            release.countDown()
            preparation.join(30_000)
            val committedId = preparedId
                ?: error("the capture must still commit despite the concurrent drain")
            org.junit.Assert.assertTrue(
                "the capture must still commit despite the concurrent drain",
                java.io.File(java.io.File(root, "contributions"), "$committedId.recording.wav").exists(),
            )

            status = 201
            body = receiptBody(committedId, sha256Hex(java.io.File(java.io.File(root, "contributions"), "$committedId.metadata.json").readBytes()))
            runDrain(drain)
            assertEquals("the committed capture must upload EXACTLY once across both drains", 1, hits.get())
            org.junit.Assert.assertNull(store.durableCapture(committedId))
        } finally {
            release.countDown()
            aurisLive.shutdown()
            server.stop(0)
        }
    }

    // ---- RETRY POLICY, RESIDUE AND EXPIRY (spec 05ff27d0 + residue ruling): no attempt cap; 7-day
    // ---- deletion hooked into creation recovery; deleted entries leave NO sidecars; recovery sweeps
    // ---- orphan sidecars. Controls through the real provider path.

    /** Rewrites a bounded plain sidecar (attempts / created / retry: bare numbers). */
    private fun writeSidecar(id: String, suffix: String, value: String) {
        java.io.File(appQueue, "$id$suffix").writeText(value)
    }

    private fun sidecarFilesOf(id: String): List<java.io.File> = appQueue.listFiles()?.filter { it.name.startsWith(id) } ?: emptyList()

    private fun sidecarFilesOf(root: java.io.File, id: String): List<java.io.File> = java.io.File(root, "contributions").listFiles()?.filter { it.name.startsWith(id) } ?: emptyList()

    @Test
    fun `an entry that exhausted the old 8-attempt cap still uploads after its backoff elapses`() {
        val aurisLive = VoiceUploadCompositionFixtures.liveAuris()
        aurisLive.acquire()
        var status = 500
        var body = ""
        val (server, hits) = loopback { status to body }
        try {
            val store1 = priorProcessStore()
            val id = prepareVerifiedCapture(store1)
            // The recording failed 8 times during a server outage; its backoff has elapsed.
            writeSidecar(id, ".attempts", "8")
            writeSidecar(id, ".retry", (System.currentTimeMillis() - 60_000L).toString())
            val metaBytes = java.io.File(appQueue, "$id.metadata.json").readBytes()
            store1.close()

            val startup = moduleStartup()
            publishIdentity(startup, "epoch-1")
            val store2 = providerStore()
            val drain = moduleDrain(store2, startup, aurisLive, server)
            status = 201
            body = receiptBody(id, sha256Hex(metaBytes))
            runDrain(drain)

            assertTrue(
                "an over-cap entry must still upload - the attempt cap is gone (spec 05ff27d0)",
                hits.get() >= 1,
            )
            org.junit.Assert.assertNull(store2.durableCapture(id))
        } finally {
            aurisLive.shutdown()
            server.stop(0)
        }
    }

    @Test
    fun `a recording not accepted within 7 days of capture is deleted by recovery`() {
        val aurisLive = VoiceUploadCompositionFixtures.liveAuris()
        aurisLive.acquire()
        val (server, hits) = loopback { 201 to "{}" }
        try {
            val store1 = priorProcessStore()
            val id = prepareVerifiedCapture(store1)
            // Recorded 8 days ago, never accepted.
            writeSidecar(id, ".created", (System.currentTimeMillis() - 8L * 24 * 3600 * 1000).toString())
            store1.close()

            val startup = moduleStartup()
            publishIdentity(startup, "epoch-1")
            val store2 = providerStore()
            val drain = moduleDrain(store2, startup, aurisLive, server)
            runDrain(drain)

            assertEquals("an expired recording must never reach the transport", 0, hits.get())
            assertTrue(
                "recovery must delete the over-age recording (QUEUE_AGE_EXPIRED)",
                store2.terminalCounter(ContributionStore.TerminalReason.QUEUE_AGE_EXPIRED) >= 1,
            )
            assertEquals(
                "a deleted entry leaves NO files - all sidecars go with it",
                0,
                sidecarFilesOf(id).size,
            )
        } finally {
            aurisLive.shutdown()
            server.stop(0)
        }
    }

    @Test
    fun `recovery sweeps orphan sidecars whose entry is gone`() {
        val aurisLive = VoiceUploadCompositionFixtures.liveAuris()
        aurisLive.acquire()
        val (server, hits) = loopback { 201 to "{}" }
        try {
            // Sidecars of an entry that is gone (no .ready, no wav, no meta).
            appQueue.mkdirs()
            val orphan = "00000000-0000-4000-8000-00000000dead"
            for (suffix in listOf(".created", ".owner", ".retry")) {
                java.io.File(appQueue, "$orphan$suffix").writeText("1")
            }
            val startup = moduleStartup()
            publishIdentity(startup, "epoch-1")
            val store2 = providerStore()
            val drain = moduleDrain(store2, startup, aurisLive, server)
            runDrain(drain)

            assertEquals(
                "recovery must sweep orphan sidecars whose entry is gone",
                0,
                sidecarFilesOf(orphan).size,
            )
            assertEquals(0, hits.get())
        } finally {
            aurisLive.shutdown()
            server.stop(0)
        }
    }

    @Test
    fun `an old-schema deletion removes ALL sidecars - no residue`() {
        val aurisLive = VoiceUploadCompositionFixtures.liveAuris()
        aurisLive.acquire()
        val (server, hits) = loopback { 201 to "{}" }
        try {
            val store1 = priorProcessStore()
            val id = prepareVerifiedCapture(store1)
            val metaFile = java.io.File(appQueue, "$id.metadata.json")
            val oldSchemaBytes = metaFile.readText()
                .replace("\"wake_required\":true,", "")
                .replace(",\"wake_required\":true", "")
                .toByteArray()
            metaFile.writeBytes(oldSchemaBytes)
            java.io.File(appQueue, "$id.sha256").writeText(sha256Hex(oldSchemaBytes))
            store1.close()

            val startup = moduleStartup()
            publishIdentity(startup, "epoch-1")
            val store2 = providerStore()
            val drain = moduleDrain(store2, startup, aurisLive, server)
            runDrain(drain)

            assertTrue(store2.terminalCounter(ContributionStore.TerminalReason.INVALID_ON_RECOVERY) >= 1)
            assertEquals(0, hits.get())
            assertEquals(
                "a deleted entry leaves NO residue - created/owner/retry go too",
                0,
                sidecarFilesOf(id).size,
            )
        } finally {
            aurisLive.shutdown()
            server.stop(0)
        }
    }

    @Test
    fun `an entry reaching 7 days in a LIVE process is deleted by the drain - its neighbour is untouched`() {
        val aurisLive = VoiceUploadCompositionFixtures.liveAuris()
        aurisLive.acquire()
        var status = 500
        var body = ""
        val (server, hits) = loopback { status to body }
        var now = System.currentTimeMillis()
        try {
            // Same store instance throughout: NO reconstruction between the clock crossing the
            // seven-day bound and the drain (reviewer 991931a6).
            val root = tmp.newFolder("drain-expiry-live")
            val store = ContributionStore(
                root,
                QueueLimits.defaults().copy(retryInitialS = 1, retryMaxS = 1),
                clock = { now },
                jitter = { 0L },
            ).also { stores += it }
            store.recover()
            val idExpired = prepareVerifiedCapture(store)
            now += 8L * 24 * 3600 * 1000 // the first recording is now past the 7-day bound
            val idKeep = prepareVerifiedCapture(store) // the neighbour is brand new
            val keepMeta = java.io.File(java.io.File(root, "contributions"), "$idKeep.metadata.json").readBytes()

            val startup = moduleStartup()
            publishIdentity(startup, "epoch-1")
            val drain = moduleDrain(store, startup, aurisLive, server)
            status = 201
            body = receiptBody(idKeep, sha256Hex(keepMeta))
            runDrain(drain)

            assertEquals(
                "only the neighbour may reach the transport - the expired entry must be deleted first",
                1,
                hits.get(),
            )
            assertEquals(
                "the expired entry's files must ALL be gone after a same-process drain",
                0,
                sidecarFilesOf(root, idExpired).size,
            )
            org.junit.Assert.assertNull("the neighbour uploads and settles normally", store.durableCapture(idKeep))
            assertEquals(
                "the expired entry's queue charge must be released",
                0L,
                store.totalQueuedBytes(),
            )
        } finally {
            aurisLive.shutdown()
            server.stop(0)
        }
    }
}
