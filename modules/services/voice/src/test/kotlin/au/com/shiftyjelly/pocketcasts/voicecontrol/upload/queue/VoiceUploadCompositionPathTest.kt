package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountStartup
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionAuthorityTransaction
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionBudget
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionLossWatcher
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ContributionRetentionSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.PolicySnapshotReader
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.VoiceSharingPolicyProvider
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * THE CONSTRUCTED PRODUCTION PATH (spec 2e4a5472): the pieces come from the MODULE's own provider
 * methods — the same calls Hilt makes — rather than hand-rolled stand-ins, and the path is driven
 * with DISABLED fixtures (published policy off, no credentials). What this pins is the honest
 * compiled-off claim: the module's trigger starts, polls, and puts NOTHING on the network.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class VoiceUploadCompositionPathTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** The app's owner authority: one lock, snapshots read inside it. No account is signed in. */
    private class AppContextOwner(private val app: android.content.Context) : au.com.shiftyjelly.pocketcasts.repositories.sync.CredentialOwnershipOwner {
        override val collectionSharedLock = Any()

        @Volatile
        private var snap = au.com.shiftyjelly.pocketcasts.repositories.sync.AuthoritySnapshot(
            null,
            0L,
            0L,
            au.com.shiftyjelly.pocketcasts.preferences.AccessToken(""),
        )
        override fun authoritySnapshot() = snap
        override fun sessionGeneration(): Long = 0L
        override fun <T> transaction(block: () -> T): T = synchronized(collectionSharedLock) { block() }
        override fun installOwnershipBinding(binding: au.com.shiftyjelly.pocketcasts.repositories.sync.CredentialOwnershipBinding?) = snap
        override fun beginRefresh(observed: au.com.shiftyjelly.pocketcasts.repositories.sync.AuthoritySnapshot) = null
        override fun publishRefresh(
            lease: au.com.shiftyjelly.pocketcasts.repositories.sync.RefreshLease,
            refreshToken: au.com.shiftyjelly.pocketcasts.preferences.RefreshToken,
            accessToken: au.com.shiftyjelly.pocketcasts.preferences.AccessToken,
        ) = false
        override fun publishCredentials(
            observed: au.com.shiftyjelly.pocketcasts.repositories.sync.AuthoritySnapshot,
            refreshToken: au.com.shiftyjelly.pocketcasts.preferences.RefreshToken,
            accessToken: au.com.shiftyjelly.pocketcasts.preferences.AccessToken,
        ) = false
        override fun invalidateIfCurrent(
            observed: au.com.shiftyjelly.pocketcasts.repositories.sync.AuthoritySnapshot,
            transaction: () -> Boolean,
        ) = transaction()
        override fun reconcileExternalState() = snap

        /** The account/session/token published by the REAL owner under its lock. */
        fun publish(account: String, session: Long, bearer: String) = synchronized(collectionSharedLock) {
            snap = au.com.shiftyjelly.pocketcasts.repositories.sync.AuthoritySnapshot(
                account,
                session,
                session,
                au.com.shiftyjelly.pocketcasts.preferences.AccessToken(bearer),
            )
        }
    }

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

    /**
     * The REAL startup object (its own production state/credentials/coordinator/binding). Only its
     * constructor inputs differ from the app's: no account is signed in (owner snapshot empty) and
     * the retrofit is never called by this path.
     */
    private fun moduleStartup(): VoiceSharingAccountStartup {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val retrofit = retrofit2.Retrofit.Builder().baseUrl("http://127.0.0.1:1/").build()
        return VoiceSharingAccountStartup(
            AppContextOwner(app),
            app,
            dagger.Lazy { retrofit },
        )
    }

    /**
     * COMPILED-OFF, through the module's OWN trigger: with the published policy disabled, the real
     * upload trigger polls on its scope, a queued capture is never picked up, and the loopback
     * server records zero requests.
     */
    @Test
    fun `the module's own trigger polls with the compiled-off gate and touches nothing`() {
        val (server, hits) = loopback()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined + kotlinx.coroutines.SupervisorJob())
        try {
            val root = tmp.newFolder("compiled-off")
            val store = ContributionStore(root, QueueLimits.defaults(), clock = System::currentTimeMillis)
            val id = prepareVerifiedCapture(store)
            val startup = moduleStartup()
            // The module's OWN policy provider, composed with the real modules (the published
            // snapshot is the gate the assembly reads; its refresh loop is never started here).
            val policy = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.VoiceSharingPolicyModule.provideVoiceSharingPolicyProvider(
                remoteConfig = org.mockito.kotlin.mock(),
                featureProvider = org.mockito.kotlin.mock(),
                ownership = startup.ownerForHandoff(),
                // A cancelled scope: the provider's init refresh (a remote fetch) must never run in
                // this control — the enabled snapshot is the explicit fixture, and no background job
                // may silently overwrite it.
                scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job().apply { cancel() }),
            )
            val assembly = VoiceUploadModule.provideUploadAssembly(
                store = store,
                transport = ContributionUploadTransport(
                    endpoint = "http://127.0.0.1:${server.address.port}/v1/contributions",
                    authorization = { "Bearer t" },
                ),
                policyProvider = policy,
                accountStartup = startup,
                context = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            )
            val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
            val producer = VoiceUploadModule.provideContributionCaptureProducer(
                context = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
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
                scope = scope,
                publishTrigger = publishTrigger,
            )
            // The module's trigger uses the PRODUCTION cadence (60s); the loop's own counter is the
            // observable, so one started poll is the evidence that the module's loop runs.
            trigger.start()
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (trigger.pollsStarted.get() < 1 && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue("the module's trigger must actually poll (got ${trigger.pollsStarted.get()})", trigger.pollsStarted.get() >= 1)
            // Idempotence: a second start() must not launch a second loop.
            trigger.start()
            assertEquals("one loop only", 1, trigger.pollsStarted.get())
            assertEquals("compiled-off: the gate is closed, so nothing is sent", 0, hits.get())
            assertTrue("the capture is untouched in the queue", store.readyEntries().any { it.captureId == id })
        } finally {
            scope.cancel()
            server.stop(0)
        }
    }

    /**
     * Mirrors VoiceObservationModule.provideCollectionTx over the SAME startup/owner (one shared
     * lock, one immutable policy snapshot per acquisition).
     */
    private fun collectionTxFor(startup: VoiceSharingAccountStartup, policy: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.VoiceSharingPolicyProvider): CollectionAuthorityTransaction = CollectionAuthorityTransaction(
        ownership = startup.ownerForHandoff(),
        policy = object : PolicySnapshotReader {
            override fun snapshot() = policy.publishedPolicy.value
        },
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
                .build(platform = "android", appVersion = "8.20", build = "12345", audio = block),
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
            )
            val id = prepareVerifiedCapture(store)
            val startup = moduleStartup()
            // Publish the admitted identity exactly as the real account stack would: epoch projection
            // plus the owner snapshot (account, session, bearer) under the owner lock.
            val owner = startup.ownerForHandoff() as AppContextOwner
            owner.publish("a@x", 7L, "Bearer-fixture")
            // The credential mirror the network gate reads (the production 401 path publishes the
            // SAME way through `credentials.publish`).
            startup.credentials.publish("Bearer-fixture")
            val token = startup.state.sessionStarted(7L)
            // The production order (see VoiceSharingAccountSessionCoordinator): arm the session,
            // OPEN an operation, then apply the fetched projection to THAT ticket.
            val ticket = startup.state.beginOperation(token, requireProjection = false, ownerSession = 7L)!!
            org.junit.Assert.assertTrue(
                "the real account state accepts the projection",
                startup.state.applyOutcome(token, ticket, authenticatedProjection("epoch-1"), ownerSession = 7L),
            )

            val policy = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.VoiceSharingPolicyModule.provideVoiceSharingPolicyProvider(
                remoteConfig = org.mockito.kotlin.mock(),
                featureProvider = org.mockito.kotlin.mock(),
                ownership = owner,
                // A cancelled scope: the provider's init refresh (a remote fetch) must never run in
                // this control — the enabled snapshot below is the explicit fixture, and no
                // background job may silently overwrite it.
                scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job().apply { cancel() }),
            )
            // The gate the module reads is the published snapshot; enabling it is the EXACT explicit
            // fixture spec authorizes (no remote fetch, no activation).
            policy.applySnapshotForTest(
                au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.PolicySnapshot(
                    enabled = true,
                    activatedAtMonotonicMs = 0L,
                    activationGeneration = 1L,
                ),
            )

            val assembly = VoiceUploadModule.provideUploadAssembly(
                store = store,
                transport = ContributionUploadTransport(
                    endpoint = "http://127.0.0.1:${server.address.port}/v1/contributions",
                    authorization = { "Bearer-fixture" },
                ),
                policyProvider = policy,
                accountStartup = startup,
                context = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            )

            // 1. INVALID receipt: nothing settles, the durable capture stays.
            status = 201
            body = "{\"schema_version\":1,\"capture_id\":\"" + id + "\",\"record_sha256\":\"deadbeef\",\"status\":\"accepted\"}"
            val firstPoll = assembly.poll()
            org.junit.Assert.assertTrue(
                "the module-built path reached the transport (poll=$firstPoll, hits=${hits.get()})",
                hits.get() >= 1,
            )
            org.junit.Assert.assertNotNull("an invalid receipt never settles", store.durableCapture(id))
            org.junit.Assert.assertTrue("policy stays open after an invalid receipt: ${policy.publishedPolicy.value}", policy.publishedPolicy.value.enabled)

            // 2. VALID receipt echoing the store's own digest: the entry settles and is removed.
            val digest = store.recordedDigest(id)!!
            body = "{\"schema_version\":1,\"capture_id\":\"$id\",\"record_sha256\":\"$digest\",\"status\":\"accepted\",\"receipt_id\":\"rcpt-1\",\"accepted_at\":\"2026-10-22T00:00:00Z\",\"retention_expires_at\":\"2026-11-21T00:00:00Z\"}"
            val hitsBefore = hits.get()
            // The attempt counter must allow a second attempt in this fixture's clock.
            val secondPoll = assembly.poll()
            org.junit.Assert.assertTrue(
                "a second attempt reached the transport (before=$hitsBefore, after=${hits.get()}, poll=$secondPoll, policy=${policy.publishedPolicy.value})",
                hits.get() > hitsBefore,
            )
            org.junit.Assert.assertNull("a VALIDATED receipt settles the entry", store.durableCapture(id))
        } finally {
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
        var status = 500
        var body = ""
        val (server, hits) = loopback { status to body }
        try {
            val root = tmp.newFolder("joined-path")
            // Fast retry so the publish→drain sequence needs no wall-clock backoff.
            val store = ContributionStore(
                root,
                QueueLimits.defaults().copy(retryInitialS = 1, retryMaxS = 1),
                clock = System::currentTimeMillis,
                jitter = { 0L },
            )
            val startup = moduleStartup()
            val owner = startup.ownerForHandoff() as AppContextOwner
            owner.publish("a@x", 7L, "Bearer-fixture")
            startup.credentials.publish("Bearer-fixture")
            val token = startup.state.sessionStarted(7L)
            val ticket = startup.state.beginOperation(token, requireProjection = false, ownerSession = 7L)!!
            assertTrue(startup.state.applyOutcome(token, ticket, authenticatedProjection("epoch-1"), ownerSession = 7L))
            val policy = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.VoiceSharingPolicyModule.provideVoiceSharingPolicyProvider(
                remoteConfig = org.mockito.kotlin.mock(),
                featureProvider = org.mockito.kotlin.mock(),
                ownership = owner,
                scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job().apply { cancel() }),
            )
            policy.applySnapshotForTest(
                au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.PolicySnapshot(
                    enabled = true,
                    activatedAtMonotonicMs = 1L,
                    activationGeneration = 1L,
                ),
            )
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined + kotlinx.coroutines.SupervisorJob())
            try {
                // 1. ENGINE side: real observation events into the real sink (frozen authority
                //    matching the published owner identity), then the Executed join.
                val sink = ContributionRetentionSink(CollectionBudget(4L * 1024 * 1024))
                val captureId = java.util.UUID.randomUUID()
                val ctx = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CaptureContext(
                    captureId,
                    captureSession = 1L,
                    routeClass = "Bluetooth",
                    issuedAuthority = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.IssuedAuthority(
                        accountName = "a@x",
                        sessionGeneration = 7L,
                        credentialIncarnation = 1L,
                        enrollmentEpoch = "epoch-1",
                        acceptedDisclosureVersion = "1",
                        policyEnabledAtIssue = true,
                    ),
                    asrModel = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.ModelRef.Named("whisper-model", "ggml-small-q5_1.bin"),
                )
                assertTrue(sink.started(au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SegmentObservation.Started(ctx, ShortArray(800) { it.toShort() }, validSamples = 800)))
                sink.completed(
                    au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SegmentObservation.Completed(
                        ctx,
                        frames = listOf(ShortArray(800) { (it + 1).toShort() }),
                        sampleRateHz = 16000,
                        speechOnsetSample = 0,
                        speechEndSample = 1600,
                        wakeState = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SegmentObservation.WakeState.Detected,
                    ),
                )
                // 2. PRODUCER side: the module's own trigger/producer (real owner transaction).
                val producer = VoiceUploadModule.provideContributionCaptureProducer(
                    context = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                    store = store,
                    accountStartup = startup,
                )
                val publishTrigger = VoiceUploadModule.provideRetentionPublishTrigger(
                    sink = sink,
                    producer = producer,
                    accountStartup = startup,
                    scope = scope,
                )
                publishTrigger.attach()
                // Real pipeline join order: the transcript join carries the Asr stage, the
                // Executed join is the terminal dispatch observation.
                assertTrue(sink.joinOutcome(au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionOutcome.Recognized(captureId, transcript = "skip this ad", detectedLanguage = "en")))
                assertTrue(sink.joinOutcome(au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionOutcome.Executed(captureId, dispatchedWithoutThrowing = true)))
                assertTrue(
                    "RED (spec checkpoint): the REAL EngineStageProvenance output cannot build a record - " +
                        "Unknown stages (bleed/translation/execution) carry no enumerated reason, so " +
                        "ContributionRecordBuilder.stage throws and every real engine publish dies " +
                        "record-less in the dispatched coroutine",
                    store.readyEntries().any { it.captureId == captureId.toString() },
                )

                // 3. DRAIN side: the module-built assembly uploads and the VALIDATED receipt settles.
                val assembly = VoiceUploadModule.provideUploadAssembly(
                    store = store,
                    transport = ContributionUploadTransport(
                        endpoint = "http://127.0.0.1:${server.address.port}/v1/contributions",
                        authorization = { "Bearer-fixture" },
                    ),
                    policyProvider = policy,
                    accountStartup = startup,
                    context = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                )
                status = 201
                body = "{\"schema_version\":1,\"capture_id\":\"$captureId\",\"record_sha256\":\"${store.recordedDigest(captureId.toString())}\"," +
                    "\"status\":\"accepted\",\"receipt_id\":\"rcpt-j\",\"accepted_at\":\"2026-10-23T00:00:00Z\",\"retention_expires_at\":\"2026-11-22T00:00:00Z\"}"
                assembly.poll()
                assertTrue("the joined path reached the transport", hits.get() >= 1)
                org.junit.Assert.assertNull("the durable record settled through the joined path", store.durableCapture(captureId.toString()))
            } finally {
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
            )
            val id = prepareVerifiedCapture(store)
            val startup = moduleStartup()
            val owner = startup.ownerForHandoff() as AppContextOwner
            owner.publish("a@x", 7L, "Bearer-fixture")
            val token = startup.state.sessionStarted(7L)
            val ticket = startup.state.beginOperation(token, requireProjection = false, ownerSession = 7L)!!
            assertTrue(startup.state.applyOutcome(token, ticket, authenticatedProjection("epoch-1"), ownerSession = 7L))
            val policy = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.VoiceSharingPolicyModule.provideVoiceSharingPolicyProvider(
                remoteConfig = org.mockito.kotlin.mock(),
                featureProvider = org.mockito.kotlin.mock(),
                ownership = owner,
                scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job().apply { cancel() }),
            )
            policy.applySnapshotForTest(
                au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.PolicySnapshot(
                    enabled = true,
                    activatedAtMonotonicMs = 1L,
                    activationGeneration = 1L,
                ),
            )
            val assembly = VoiceUploadModule.provideUploadAssembly(
                store = store,
                transport = ContributionUploadTransport(
                    endpoint = "http://127.0.0.1:${server.address.port}/v1/contributions",
                    authorization = { "Bearer-fixture" },
                ),
                policyProvider = policy,
                accountStartup = startup,
                context = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
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
                inFlight.shutdownNow()
            }

            // (2) The invalid receipt retained the entry. The network gate (owner-published
            //     credential) now CLOSES: the drain defers. It reopens: the same durable entry
            //     resumes and settles.
            org.junit.Assert.assertNotNull(store.durableCapture(id))
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
     * FOREGROUND BYPASS (reviewer 177645cc): the platform scheduler constraint alone cannot stop a
     * drain started outside WorkManager — the policy's `upload_network` must gate EACH attempt.
     * With the published policy requiring UNMETERED and the actual network metered, the module
     * assembly defers and touches nothing; when the actual network satisfies the policy, the same
     * entry goes out and a VALIDATED receipt settles it.
     */
    @Test
    fun `a tightened upload_network gates the foreground drain per attempt`() {
        var status = 500
        var body = ""
        val (server, hits) = loopback { status to body }
        // The ACTUAL network, controlled by the control (production: ConnectivityManager).
        var actualUnmetered = false
        try {
            val root = tmp.newFolder("foreground-bypass")
            val store = ContributionStore(
                root,
                QueueLimits.defaults().copy(retryInitialS = 1, retryMaxS = 1),
                clock = System::currentTimeMillis,
                jitter = { 0L },
            )
            val id = prepareVerifiedCapture(store)
            val startup = moduleStartup()
            val owner = startup.ownerForHandoff() as AppContextOwner
            owner.publish("a@x", 7L, "Bearer-fixture")
            startup.credentials.publish("Bearer-fixture")
            val token = startup.state.sessionStarted(7L)
            val ticket = startup.state.beginOperation(token, requireProjection = false, ownerSession = 7L)!!
            assertTrue(startup.state.applyOutcome(token, ticket, authenticatedProjection("epoch-1"), ownerSession = 7L))
            val policy = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.VoiceSharingPolicyModule.provideVoiceSharingPolicyProvider(
                remoteConfig = org.mockito.kotlin.mock(),
                featureProvider = org.mockito.kotlin.mock(),
                ownership = owner,
                scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job().apply { cancel() }),
            )
            policy.applySnapshotForTest(
                au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.PolicySnapshot(
                    enabled = true,
                    activatedAtMonotonicMs = 1L,
                    activationGeneration = 1L,
                    uploadNetwork = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.UploadNetwork.UNMETERED,
                ),
            )
            val assembly = VoiceUploadModule.provideUploadAssembly(
                store = store,
                transport = ContributionUploadTransport(
                    endpoint = "http://127.0.0.1:${server.address.port}/v1/contributions",
                    authorization = { "Bearer-fixture" },
                ),
                policyProvider = policy,
                accountStartup = startup,
                actualNetwork = { requested ->
                    when (requested) {
                        au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.UploadNetwork.ANY -> true
                        au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.UploadNetwork.UNMETERED -> actualUnmetered
                    }
                },
            )
            // Policy demands UNMETERED; the actual network is metered: DEFER, never send.
            status = 201
            body = "{\"schema_version\":1,\"capture_id\":\"$id\",\"record_sha256\":\"deadbeef\",\"status\":\"accepted\"}"
            val deferred = assembly.poll()
            assertEquals("policy network unsatisfied: the foreground drain defers", 1, deferred.deferredNetwork)
            assertEquals("nothing is sent against the policy network", 0, hits.get())

            // The actual network now satisfies the policy: the SAME entry goes out per attempt.
            actualUnmetered = true
            val digest = store.recordedDigest(id)!!
            body = "{\"schema_version\":1,\"capture_id\":\"$id\",\"record_sha256\":\"$digest\",\"status\":\"accepted\",\"receipt_id\":\"rcpt-n\",\"accepted_at\":\"2026-10-23T00:00:00Z\",\"retention_expires_at\":\"2026-11-22T00:00:00Z\"}"
            assembly.poll()
            assertTrue("the per-attempt gate opens with the actual network", hits.get() >= 1)
            org.junit.Assert.assertNull("the durable entry settled", store.durableCapture(id))
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
            )
            val id = prepareVerifiedCapture(store)
            val startup = moduleStartup()
            val owner = startup.ownerForHandoff() as AppContextOwner
            owner.publish("a@x", 7L, "Bearer-fixture")
            startup.credentials.publish("Bearer-fixture")
            val token = startup.state.sessionStarted(7L)
            val ticket = startup.state.beginOperation(token, requireProjection = false, ownerSession = 7L)!!
            assertTrue(startup.state.applyOutcome(token, ticket, authenticatedProjection("epoch-1"), ownerSession = 7L))
            val policy = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.VoiceSharingPolicyModule.provideVoiceSharingPolicyProvider(
                remoteConfig = org.mockito.kotlin.mock(),
                featureProvider = org.mockito.kotlin.mock(),
                ownership = owner,
                scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job().apply { cancel() }),
            )
            policy.applySnapshotForTest(
                au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.PolicySnapshot(
                    enabled = true,
                    activatedAtMonotonicMs = 1L,
                    activationGeneration = 1L,
                ),
            )
            val assembly = VoiceUploadModule.provideUploadAssembly(
                store = store,
                transport = ContributionUploadTransport(
                    endpoint = "http://127.0.0.1:${server.address.port}/v1/contributions",
                    authorization = { "Bearer-fixture" },
                ),
                policyProvider = policy,
                accountStartup = startup,
                context = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            )
            // THE MODULE DRAIN BINDING — the exact provider Hilt consumes.
            val drain = VoiceUploadModule.provideVoiceUploadDrain(assembly)
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
            server.stop(0)
        }
    }

    private fun authenticatedProjection(epoch: String): String = "{\"schema_version\":1,\"membership_revision\":\"rev-1\",\"plan\":\"free\"," +
        "\"voice_sharing_enrolled\":true,\"enrollment_epoch\":\"$epoch\"," +
        "\"accepted_disclosure_version\":\"1\",\"withdrawal_state\":\"active\"}"
}
