package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import android.content.Context
import androidx.work.WorkManager
import au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisTokenProvider
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTokenProviding
import au.com.shiftyjelly.pocketcasts.servers.di.Raw
import au.com.shiftyjelly.pocketcasts.servers.di.SyncServiceRetrofit
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountStartup
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoiceStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import okhttp3.OkHttpClient
import timber.log.Timber

/**
 * Production construction of the queue and the one uploader loop.
 *
 * The queue lives in app-private storage, so a recording written here is unreadable to any other
 * app and is deleted with the app's data.
 */
@Module
@InstallIn(SingletonComponent::class)
object VoiceUploadQueueModule {
    private const val QUEUE_ROOT = "voice-upload-queue"

    /**
     * The queue root the previous upload path used. Its recordings cannot be read by the new code
     * (different layout, different record format), so leaving them would be a permanent leftover
     * holding the user's audio for no purpose.
     */
    private const val LEGACY_QUEUE_ROOT = "voice-contributions"

    @Provides
    @Singleton
    fun provideFolderQueue(
        @ApplicationContext context: Context,
    ): FolderQueue {
        return FolderQueue(File(context.filesDir, QUEUE_ROOT)).also {
            // Anything still under a temporary name is an interrupted write from a previous process.
            it.discardIncompleteWrites()
        }
    }

    /**
     * Remove the previous upload path's queue once, at startup.
     *
     * This touches nothing but that one directory: no app data, no sign-in, no consent. It runs
     * before the new queue is constructed, so a leftover can never be mistaken for a recording this
     * path could serve.
     */
    @Provides
    @Singleton
    fun provideLegacyQueueRemoval(
        @ApplicationContext context: Context,
    ): LegacyQueueRemoval = LegacyQueueRemoval(
        filesDir = context.filesDir,
        // The previous path enqueued a unique periodic job that outlives the class it named, so it
        // fails with a missing-class error on every run for a path that no longer exists.
        cancelLegacyWork = {
            runCatching { WorkManager.getInstance(context).cancelUniqueWork(LEGACY_DRAIN_WORK) }
        },
    )

    /** The unique name the deleted upload path scheduled its drain under. */
    private const val LEGACY_DRAIN_WORK = "voice-upload-drain"

    internal fun deleteLegacyQueue(
        filesDir: File,
        // This runs while the object graph is being built, which is before logging is set up, so a
        // Timber call here goes nowhere. The line is emitted through the application's own log
        // buffer instead, which exists from process start.
        log: (String) -> Unit = { message -> LogBuffer.i(TAG, message) },
    ): Boolean {
        val legacy = File(filesDir, LEGACY_QUEUE_ROOT)
        if (!legacy.exists()) return false
        val removed = legacy.deleteRecursively()
        log("upload queue: removed the previous queue root " + LEGACY_QUEUE_ROOT + " (removed=$removed)")
        return removed
    }

    private const val TAG = "VoiceUploadQueue2"

    @Provides
    @Singleton
    fun provideUploaderLoop(
        queue: FolderQueue,
        @SyncServiceRetrofit retrofit: dagger.Lazy<retrofit2.Retrofit>,
        aurisToken: AurisTokenProvider,
        accountStartup: VoiceSharingAccountStartup,
        savedSharingChoice: SavedSharingChoiceStore,
        @Raw rawClient: OkHttpClient,
    ): UploaderLoop {
        // Resolved against the same gateway origin the account routes use, so the endpoint is never
        // guessed. A blank endpoint simply cannot produce a request.
        val endpoint = retrofit.get().baseUrl().resolve("/api/v1/voice-recordings").toString()

        // Read fresh on every pass. A queued recording carries the account and epoch it was
        // captured under; both can be superseded before the queue drains, and sending then would
        // upload data the user withdrew, or hand it to a different account. The same gate answers
        // twice: once for the loop, and once in the sender after the credential arrives, because
        // acquisition can take long enough for the user's answer to change.
        val gate = AccountPreSendGate(
            currentAccount = { accountStartup.currentAccountName() },
            currentEpoch = {
                val account = accountStartup.currentAccountName()
                savedSharingChoice.enrolledChoiceFor(account)?.epoch
            },
            // The saved choice is the durable record of what the user decided, so switching
            // sharing off stops the queue on the next pass rather than at the next sign-in.
            // A WITHDRAWN record is the local stop: enrolledChoiceFor returns null, so the gate
            // refuses even while a withdrawal request is in flight or has failed.
            sharingEnabled = {
                val account = accountStartup.currentAccountName()
                savedSharingChoice.enrolledChoiceFor(account) != null
            },
        )

        val uploader = buildUploader(
            endpoint = endpoint,
            client = rawClient,
            tokenSource = aurisToken,
            eligible = { recording -> gate.verdictFor(recording) is GateVerdict.Send },
        )
        return UploaderLoop(
            queue = queue,
            send = uploader::send,
            cancelInFlightOnce = uploader::cancelInFlight,
            refreshSignIn = {
                // 401 is a lapsed sign-in, not a refusal. Refreshing is best-effort: if it fails the
                // recording stays queued and the next pass tries again.
                runCatching { aurisToken.refreshToken() }
            },
            gate = gate,
            // Logged through the same buffer that records the queue root's removal, which is
            // known to reach the device log. Timber is planted only in debug builds, so a
            // diagnostic that has to survive a release build cannot depend on it.
            log = { message -> LogBuffer.i("VoiceUploadQueue2", message) },
        )
    }

    /**
     * The single place a sender is built, so a control can exercise the same wiring the app uses.
     *
     * The credential is asked for rather than peeked: an expired cache entry would otherwise never
     * be replaced, and every queued recording would wait on it indefinitely.
     */
    internal fun buildUploader(
        endpoint: String,
        client: OkHttpClient,
        tokenSource: CloudTokenProviding,
        eligible: (FolderQueue.Recording) -> Boolean,
    ): RecordingUploader = RecordingUploader(
        endpoint = endpoint,
        // The whole call is bounded by the client, and cancelling the call is what releases a
        // stalled write: interrupting the coroutine cannot, because the write never returns.
        client = client.newBuilder()
            .callTimeout(RecordingUploader.CALL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            // THE PLATFORM AGENT (spec, `platform=` log line): the raw client carries NO User-Agent,
            // so the server logged uploads as platform=unknown. Send the same scoped internal agent
            // the account routes already use.
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", au.com.shiftyjelly.pocketcasts.servers.di.InterceptorModule.PC_INTERNAL_USER_AGENT)
                    .build()
                chain.proceed(request)
            }
            .build(),
        // How a credential is obtained is decided here, once, so there is no second place that
        // could choose differently: asking serves a fresh cached token or obtains a new one, while
        // peeking would leave an expired entry in place forever and stall every queued recording.
        authorization = { tokenSource.currentToken() },
        eligible = eligible,
    )
}

/**
 * Removes the previous upload path's queue root, once, at a point where logging exists.
 *
 * The queue itself is built during object-graph assembly, before the app has set up logging, so
 * doing this there would delete the right directory and leave no trace of having done it.
 */
class LegacyQueueRemoval(
    private val filesDir: File,
    /** Cancels the previous upload path's scheduled work, if the work manager is present. */
    private val cancelLegacyWork: () -> Unit = {},
) {
    fun run() {
        VoiceUploadQueueModule.deleteLegacyQueue(filesDir) { message ->
            LogBuffer.i("VoiceUploadQueue2", message)
        }
        cancelLegacyWork()
    }
}
