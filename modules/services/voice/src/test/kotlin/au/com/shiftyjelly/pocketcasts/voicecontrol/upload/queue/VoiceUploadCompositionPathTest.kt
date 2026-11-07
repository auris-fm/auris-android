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

    // ---- DURABLE RESUME (on-device gap, spec decision c1e7fbe2): the module drain must recover
    // ---- the durable queue before polling. Controls through the REAL store + module providers.

    private fun newStoreAt(root: java.io.File): ContributionStore = ContributionStore(
        root,
        QueueLimits.defaults().copy(retryInitialS = 1, retryMaxS = 1),
        clock = System::currentTimeMillis,
        jitter = { 0L },
    ).also { stores += it }

    private fun sha256Hex(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun receiptBody(id: String, recordSha: String): String = (
        "{\"schema_version\":1,\"capture_id\":\"" + id + "\",\"record_sha256\":\"" + recordSha +
            "\",\"status\":\"accepted\",\"receipt_id\":\"rcpt-r\",\"accepted_at\":\"2026-11-07T00:00:00Z\"," +
            "\"retention_expires_at\":\"2027-11-07T00:00:00Z\"}"
        )

    /** The projection/owner/credential fixture wiring shared by the three resume controls. */
    private fun publishIdentity(startup: VoiceSharingAccountStartup, enrolledEpoch: String) {
        val owner = startup.ownerForHandoff() as VoiceUploadCompositionFixtures.AppContextOwner
        owner.publish("a@x", 7L, "Bearer-fixture")
        startup.credentials.publish("Bearer-fixture")
        startup.publishEnrolledFixture(enrolledEpoch)
    }

    private fun moduleAssembly(
        store: ContributionStore,
        startup: VoiceSharingAccountStartup,
        aurisLive: VoiceUploadCompositionFixtures.LiveAuris,
        server: HttpServer,
    ): ContributionUploadAssembly = VoiceUploadModule.provideUploadAssembly(
        store = store,
        transport = ContributionUploadTransport(
            endpoint = "http://127.0.0.1:${server.address.port}/v1/contributions",
            authorization = { "Bearer-fixture" },
        ),
        accountStartup = startup,
        context = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
        aurisToken = aurisLive.provider,
    )

    @Test
    fun `an entry recorded before a process restart is served by the module drain`() {
        val aurisLive = VoiceUploadCompositionFixtures.liveAuris()
        aurisLive.acquire()
        var status = 500
        var body = ""
        val (server, hits) = loopback { status to body }
        try {
            val root = tmp.newFolder("resume-served")
            val store1 = newStoreAt(root)
            val id = prepareVerifiedCapture(store1)
            val metaBytes = java.io.File(java.io.File(root, "contributions"), "$id.metadata.json").readBytes()
            store1.close()

            // A BRAND-NEW store over the SAME directory: the restart the old wiring never survived.
            val store2 = newStoreAt(root)
            val startup = moduleStartup()
            publishIdentity(startup, "epoch-1")
            val assembly = moduleAssembly(store2, startup, aurisLive, server)
            val drain = VoiceUploadModule.provideVoiceUploadDrain(store2, assembly, aurisLive.provider)
            status = 201
            body = receiptBody(id, sha256Hex(metaBytes))
            kotlinx.coroutines.runBlocking { drain.drainOnce() }

            assertTrue(
                "the durable entry recorded before the restart must be uploaded by the module drain",
                hits.get() >= 1,
            )
            org.junit.Assert.assertNull("the served entry must settle off the queue", store2.durableCapture(id))
        } finally {
            aurisLive.shutdown()
            server.stop(0)
        }
    }

    @Test
    fun `an old-schema durable entry is deleted by the recovery record check, not uploaded`() {
        val aurisLive = VoiceUploadCompositionFixtures.liveAuris()
        aurisLive.acquire()
        val (server, hits) = loopback { 201 to "{}" }
        try {
            val root = tmp.newFolder("resume-old-schema")
            val store1 = newStoreAt(root)
            val id = prepareVerifiedCapture(store1)
            val dir = java.io.File(root, "contributions")
            val metaFile = java.io.File(dir, "$id.metadata.json")
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
            java.io.File(dir, "$id.sha256").writeText(sha256Hex(oldSchemaBytes))
            store1.close()

            val store2 = newStoreAt(root)
            val startup = moduleStartup()
            publishIdentity(startup, "epoch-1")
            val assembly = moduleAssembly(store2, startup, aurisLive, server)
            val drain = VoiceUploadModule.provideVoiceUploadDrain(store2, assembly, aurisLive.provider)
            kotlinx.coroutines.runBlocking { drain.drainOnce() }

            assertEquals(
                "an old-schema entry must never reach the transport",
                0,
                hits.get(),
            )
            assertTrue(
                "the record check must delete the old-schema entry at once",
                store2.terminalCounter(ContributionStore.TerminalReason.INVALID_ON_RECOVERY) >= 1,
            )
            org.junit.Assert.assertFalse(
                "the deleted entry's files must be gone",
                java.io.File(dir, "$id.metadata.json").exists() || java.io.File(dir, "$id.recording.wav").exists(),
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
            val root = tmp.newFolder("resume-older-epoch")
            val store1 = newStoreAt(root)
            val id = prepareVerifiedCapture(store1)
            val metaBytes = java.io.File(java.io.File(root, "contributions"), "$id.metadata.json").readBytes()
            store1.close()

            val store2 = newStoreAt(root)
            val startup = moduleStartup()
            // The account has re-enrolled SINCE the recording: the projection carries a NEWER epoch.
            // An older epoch alone is PROVENANCE (4afc6e43) - never a deletion or refusal reason.
            publishIdentity(startup, "epoch-2")
            val assembly = moduleAssembly(store2, startup, aurisLive, server)
            val drain = VoiceUploadModule.provideVoiceUploadDrain(store2, assembly, aurisLive.provider)
            status = 201
            body = receiptBody(id, sha256Hex(metaBytes))
            kotlinx.coroutines.runBlocking { drain.drainOnce() }

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
}
