package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue2

import android.content.Context
import au.com.shiftyjelly.pocketcasts.repositories.cloud.AurisTokenProvider
import au.com.shiftyjelly.pocketcasts.servers.di.SyncServiceRetrofit
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

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

    @Provides
    @Singleton
    fun provideFolderQueue(
        @ApplicationContext context: Context,
    ): FolderQueue = FolderQueue(File(context.filesDir, QUEUE_ROOT)).also {
        // Anything still under a temporary name is an interrupted write from a previous process.
        it.discardIncompleteWrites()
    }

    @Provides
    @Singleton
    fun provideUploaderLoop(
        queue: FolderQueue,
        @SyncServiceRetrofit retrofit: dagger.Lazy<retrofit2.Retrofit>,
        aurisToken: AurisTokenProvider,
    ): UploaderLoop {
        // Resolved against the same gateway origin the account routes use, so the endpoint is never
        // guessed. A blank endpoint simply cannot produce a request.
        val endpoint = retrofit.get().baseUrl().resolve("/api/v1/voice-recordings").toString()
        return UploaderLoop(
            queue = queue,
            send = RecordingUploader(
                endpoint = endpoint,
                // Fail-closed: no cached Auris token means no attempt. The recording is retained
                // and retried, never sent under a different credential.
                authorization = { aurisToken.peekCurrentToken() },
            )::send,
            refreshSignIn = {
                // 401 is a lapsed sign-in, not a refusal. Refreshing is best-effort: if it fails the
                // recording stays queued and the next pass tries again.
                runCatching { aurisToken.refreshToken() }
            },
        )
    }
}
