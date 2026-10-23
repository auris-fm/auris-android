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

    private fun authenticatedProjection(epoch: String): String = "{\"schema_version\":1,\"membership_revision\":\"rev-1\",\"plan\":\"free\"," +
        "\"voice_sharing_enrolled\":true,\"enrollment_epoch\":\"$epoch\"," +
        "\"accepted_disclosure_version\":\"1\",\"withdrawal_state\":\"active\"}"
}
