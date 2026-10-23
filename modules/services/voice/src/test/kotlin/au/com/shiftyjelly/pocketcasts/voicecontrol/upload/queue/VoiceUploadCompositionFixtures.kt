package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountStartup

/**
 * Composition fixtures shared by the composition control and the shared joined-path emitter.
 *
 * They live here because the emitter ([VoiceUploadCompositionPaths]) must be callable from the Worker
 * parity control as well: a fixture owned privately by one test class cannot serve both, and the
 * previous `@Before` wiring left parity with no producer at all.
 */
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
        return VoiceSharingAccountStartup(AppContextOwner(app), app, dagger.Lazy { retrofit })
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

    internal fun authenticatedProjection(epoch: String): String = "{\"schema_version\":1,\"membership_revision\":\"rev-1\",\"plan\":\"free\"," +
        "\"voice_sharing_enrolled\":true,\"enrollment_epoch\":\"$epoch\"," +
        "\"accepted_disclosure_version\":\"1\",\"withdrawal_state\":\"active\"}"
}
