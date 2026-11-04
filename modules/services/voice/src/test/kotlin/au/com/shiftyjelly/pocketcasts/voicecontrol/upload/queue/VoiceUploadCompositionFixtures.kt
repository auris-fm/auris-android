package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountStartup

/**
 * Composition fixtures shared by the composition control and the shared joined-path emitter.
 *
 * They live here because the emitter ([VoiceUploadCompositionPaths]) must be callable from the Worker
 * parity control as well: a fixture owned privately by one test class cannot serve both, and the
 * previous `@Before` wiring left parity with no producer at all.
 */
internal fun au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountStartup.publishEnrolledFixture(epoch: String = "epoch-1") {
    state.publish(state.parseProjection(VoiceUploadCompositionFixtures.authenticatedProjection(epoch))!!)
}

internal object VoiceUploadCompositionFixtures {
    /**
     * The producer constructed the way production constructs it, EXCEPT that provenance is supplied
     * explicitly.
     *
     * Why: under Robolectric the test package carries no `versionName` (it resolves to null), so the
     * production `provideContributionCaptureProducer` cannot produce a record here at all — the record
     * builder refuses a blank `app_version` exactly as the Cloud intake does. Supplying the real
     * binary provenance keeps the CONTROL honest (the record content is otherwise produced by the
     * production code path) while the fact that this environment has no version is stated here rather
     * than papered over with `?: ""`.
     */
    fun producer(
        app: android.content.Context,
        store: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.ContributionStore,
        startup: VoiceSharingAccountStartup,
    ) = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.ContributionCaptureProducer(
        store,
        appVersion = { "8.20" },
        appBuild = { "12345" },
        captureSessionId = { startup.authoritySnapshot().session.toString() },
        enrollmentAccount = { startup.authoritySnapshot().accountName ?: "" },
        enrollmentSession = { startup.authoritySnapshot().session },
    )

    fun startup(app: android.app.Application): VoiceSharingAccountStartup {
        val retrofit = retrofit2.Retrofit.Builder().baseUrl("http://127.0.0.1:1/").build()
        return VoiceSharingAccountStartup(AppContextOwner(app), app, dagger.Lazy { retrofit }, noEnvironmentAuris())
    }

    /** No Auris environment configured: the provider yields NO token, so the composition refuses
     *  rather than sending the upstream mirror. That is the asserted behaviour, not a shortcut. */
    fun noEnvironmentAuris() = au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisTokenProvider(
        clientProvider = { null },
        credentialProvider = object : au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisAccountCredentialProviding {
            override fun credential(): String? = null
        },
    )

    /**
     * A REAL Auris provider over a REAL auth exchange (server-first, no test seam in the provider):
     * a loopback auth server issues one token, [acquire] performs the actual suspend acquisition
     * the production trigger/drain performs, and the fail-closed synchronous read the upload path
     * consumes then serves that freshly acquired token. The returned server MUST be shut down.
     */
    class LiveAuris(val provider: au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisTokenProvider, val server: okhttp3.mockwebserver.MockWebServer) {
        private val job = kotlinx.coroutines.SupervisorJob()
        private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + job)
        fun acquire() = kotlinx.coroutines.runBlocking { provider.currentToken() }
        fun shutdown() {
            job.cancel()
            server.shutdown()
        }
    }

    fun liveAuris(access: String = "auris-fixture-token"): LiveAuris {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        server.enqueue(
            okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody(
                """{"access_token":"$access","token_type":"Bearer","expires_in":900,"refresh_token":"r-fixture"}""",
            ),
        )
        val provider = au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisTokenProvider(
            clientProvider = { au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisAuthClient(server.url("/").toString().trimEnd('/')) },
            credentialProvider = object : au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisAccountCredentialProviding {
                override fun credential(): String? = "fixture-credential"
            },
        )
        return LiveAuris(provider, server)
    }

    internal class AppContextOwner(private val app: android.content.Context) : au.com.shiftyjelly.pocketcasts.repositories.sync.CredentialOwnershipOwner {
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

        // The mirror-clear exchange class (reviewer 82a9958d): credential lock only.
        override fun <T> credentialTransaction(block: () -> T): T = synchronized(collectionSharedLock) { block() }

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

    /**
     * Publishes the authoritative enrolled projection for [startup]'s owner — exactly what the
     * production membership GET does when it applies.
     */
    internal fun authenticatedProjection(epoch: String): String = "{\"schema_version\":1,\"membership_revision\":\"rev-1\",\"plan\":\"free\"," +
        "\"voice_sharing_enrolled\":true,\"enrollment_epoch\":\"$epoch\"," +
        "\"accepted_disclosure_version\":\"1\",\"withdrawal_state\":\"active\"}"
}
