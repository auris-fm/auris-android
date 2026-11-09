package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue2

import android.content.Context
import au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisTokenProvider
import au.com.shiftyjelly.pocketcasts.servers.di.Raw
import au.com.shiftyjelly.pocketcasts.servers.di.SyncServiceRetrofit
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionAuthorityTransaction
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.LocalWithdrawalIntentStore
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
        deleteLegacyQueue(context.filesDir)
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
        collectionTx: CollectionAuthorityTransaction,
        savedSharingChoice: SavedSharingChoiceStore,
        withdrawalIntent: LocalWithdrawalIntentStore,
        @Raw rawClient: OkHttpClient,
    ): UploaderLoop {
        // Resolved against the same gateway origin the account routes use, so the endpoint is never
        // guessed. A blank endpoint simply cannot produce a request.
        val endpoint = retrofit.get().baseUrl().resolve("/api/v1/voice-recordings").toString()
        // One instance, so the loop can cancel exactly the call the sender started.
        val uploader = RecordingUploader(
            endpoint = endpoint,
            // The whole call is bounded by the client, and cancelling the call is what releases
            // a stalled write: interrupting the coroutine cannot, because the write never
            // returns to be interrupted.
            client = rawClient.newBuilder()
                .callTimeout(RecordingUploader.CALL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build(),
            // Fail-closed: no cached Auris token means no attempt. The recording is retained
            // and retried, never sent under a different credential.
            authorization = { aurisToken.peekCurrentToken() },
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
            // Read fresh on every pass. A queued recording carries the account and epoch it was
            // captured under; both can be superseded before the queue drains, and sending then
            // would upload data the user withdrew, or hand it to a different account.
            gate = AccountPreSendGate(
                currentAccount = { collectionTx.coherentAuthority().accountName },
                currentEpoch = {
                    val account = collectionTx.coherentAuthority().accountName
                    savedSharingChoice.enrolledChoiceFor(account)?.epoch
                },
                // The saved choice is the durable record of what the user decided, so switching
                // sharing off stops the queue on the next pass rather than at the next sign-in.
                sharingEnabled = {
                    // The local intent is consulted as well as the saved choice, because a stop
                    // whose write failed is still a stop: the saved record can still say enrolled
                    // after the user has asked to stop.
                    if (!withdrawalIntent.collectionPermittedByLocalIntent()) {
                        false
                    } else {
                        val account = collectionTx.coherentAuthority().accountName
                        savedSharingChoice.enrolledChoiceFor(account) != null
                    }
                },
            ),
            log = { Timber.tag(TAG).i(it) },
        )
    }
}
