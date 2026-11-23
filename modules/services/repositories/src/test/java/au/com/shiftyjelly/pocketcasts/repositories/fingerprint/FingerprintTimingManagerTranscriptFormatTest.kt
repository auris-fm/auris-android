package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.models.type.EpisodeDownloadStatus
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.preferences.UserSetting
import au.com.shiftyjelly.pocketcasts.repositories.playback.ExoPlayerDataSourceFactory
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackState
import au.com.shiftyjelly.pocketcasts.repositories.podcast.ChapterManager
import au.com.shiftyjelly.pocketcasts.sharedtest.InMemoryFeatureFlagRule
import au.com.shiftyjelly.pocketcasts.utils.featureflag.Feature
import au.com.shiftyjelly.pocketcasts.utils.featureflag.FeatureFlag
import au.com.shiftyjelly.pocketcasts.utils.fingerprint.FingerprintDecodePolicy
import au.com.shiftyjelly.pocketcasts.utils.fingerprint.FingerprintPolicy
import com.automattic.eventhorizon.EventHorizon
import com.automattic.eventhorizon.SyncedTranscriptsUnavailableEvent
import dagger.Lazy
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Date
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.whenever

/**
 * The transcript reference and the Auris reference come from different hosts that serve different
 * formats. Changing what the Auris endpoint serves must not stop transcript sync: the transcript
 * host is not ours to migrate, and its references hold the same library's values.
 *
 * These cases drive the real preparation path, so a format check placed at the wrong site — or one
 * shared constant used at both — fails here rather than in playback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class FingerprintTimingManagerTranscriptFormatTest {

    @get:Rule
    val featureFlagRule = InMemoryFeatureFlagRule()

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var retriever: FingerprintReferenceRetriever
    private lateinit var manager: FingerprintTimingManager
    private lateinit var audioFile: File
    private lateinit var episode: PodcastEpisode
    private val eventHorizon = mock(EventHorizon::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun setUp() {
        FeatureFlag.setEnabled(Feature.SYNCED_TRANSCRIPTS, true)
        audioFile = temp.newFile("episode.mp3").apply { writeBytes(ByteArray(4096)) }
        episode = PodcastEpisode(
            uuid = "ep_transcript",
            publishedDate = Date(),
            downloadStatus = EpisodeDownloadStatus.Downloaded,
            downloadedFilePath = audioFile.absolutePath,
            duration = 100.0,
        )

        val playbackManager = mock(PlaybackManager::class.java)
        whenever(playbackManager.playbackStateFlow).thenReturn(MutableStateFlow(PlaybackState()))
        whenever(playbackManager.getCurrentEpisode()).thenReturn(episode)
        whenever(playbackManager.isPlaybackRemote()).thenReturn(false)

        retriever = mock(FingerprintReferenceRetriever::class.java)
        val settings = mock(Settings::class.java)
        @Suppress("UNCHECKED_CAST")
        val off = mock(UserSetting::class.java) as UserSetting<Boolean>
        whenever(off.value).thenReturn(false)
        whenever(settings.warnOnMeteredNetwork).thenReturn(off)
        whenever(settings.cacheEntirePlayingEpisode).thenReturn(off)

        val chapterManager = mock(ChapterManager::class.java)
        runBlocking { whenever(chapterManager.hasGeneratedChapters(any())).thenReturn(false) }

        val cloudConfig = mock(SharedPreferencesCloudConfig::class.java)
        whenever(cloudConfig.baseUrl()).thenReturn("")

        val decodePolicy = mock(FingerprintDecodePolicy::class.java)
        whenever(decodePolicy.current()).thenReturn(FingerprintPolicy.PLATFORM)

        manager = FingerprintTimingManager(
            playbackManager = playbackManager,
            referenceRetriever = retriever,
            eventHorizon = eventHorizon,
            context = RuntimeEnvironment.getApplication(),
            chapterManager = Lazy { chapterManager },
            settings = settings,
            dataSourceFactory = Lazy { mock(ExoPlayerDataSourceFactory::class.java) },
            pcmTap = FingerprintPcmTap(),
            cloudConfig = cloudConfig,
            cloudFetcher = mock(CloudFingerprintReferenceFetcher::class.java),
            cloudReferenceStore = mock(CloudReferenceStore::class.java),
            decodePolicy = decodePolicy,
            scope = scope,
        )
    }

    /** A compact reference in [format] with three one-second checkpoints carrying real values. */
    private fun payload(format: String): ByteArray {
        val checkpoints = (0 until 3).joinToString(",") { index ->
            val buffer = ByteBuffer.allocate(3 * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (hash in longArrayOf(7L, 9L, 11L)) buffer.putInt((hash and 0xFFFFFFFFL).toInt())
            val delta = if (index == 0) 0 else 1
            """[$delta, "${Base64.getEncoder().encodeToString(buffer.array())}"]"""
        }
        return """
            {
              "format": "$format",
              "total_duration": 100.0,
              "checkpoint_interval": 1,
              "checkpoint_duration": 8,
              "timestamp_quantum": 1,
              "checkpoints": [$checkpoints]
            }
        """.trimIndent().toByteArray()
    }

    private fun stageStoredReference(format: String) {
        val data = payload(format)
        // Written where the retriever really looks, so the real load path reads it.
        File(FingerprintReferenceRetriever.referencePath(audioFile.absolutePath)).writeBytes(data)
        runBlocking {
            whenever(retriever.loadReferenceData(any())).thenAnswer {
                File(FingerprintReferenceRetriever.referencePath(audioFile.absolutePath))
                    .takeIf { f -> f.exists() }?.readBytes()
            }
            whenever(retriever.fetchReferenceData(any(), any(), any()))
                .thenReturn(FingerprintReferenceRetriever.FetchResult.Success(data))
        }
    }

    /**
     * Runs the real preparation and reports whether the stored reference was refused at the format
     * check. A refusal publishes `reference_decode_failed`; an accepted reference goes on to build
     * the native matcher, which no JVM host can load, so it never publishes that reason.
     */
    private fun refusedAtFormatCheck(): Boolean {
        val reasons = Collections.synchronizedList(mutableListOf<String>())
        doAnswer { invocation ->
            (invocation.arguments[0] as? SyncedTranscriptsUnavailableEvent)?.let { reasons.add(it.reason) }
            null
        }.whenever(eventHorizon).track(any())

        // The accepted path ends at the native matcher, which cannot load on a JVM host. That
        // attempt escapes the preparation coroutine, so watch for it without swallowing anything
        // else: unrelated errors go to the handler that was already installed.
        val reachedLibrary = AtomicBoolean(false)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (error is UnsatisfiedLinkError || error is NoClassDefFoundError) {
                reachedLibrary.set(true)
            } else {
                previous?.uncaughtException(thread, error)
            }
        }
        try {
            manager.prepareForCurrentEpisode(FingerprintTimingManager.PrepareTrigger.PLAYBACK)
            val deadline = System.currentTimeMillis() + PREPARATION_BUDGET_MS
            while (System.currentTimeMillis() < deadline) {
                if (reasons.contains("reference_decode_failed")) return true
                if (reachedLibrary.get()) return false
                Thread.sleep(POLL_MS)
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
        throw AssertionError("preparation published neither a refusal nor a library attempt: ${manager.state}")
    }

    /**
     * The caller owns the scope the preparation runs on; leaving it alive lets its failure — on the
     * JVM, the native library's — surface in whichever test class runs next.
     */
    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `a transcript-format reference is still accepted`() {
        stageStoredReference(ReferenceFingerprint.TRANSCRIPT_FORMAT)

        assertFalse(
            "the transcript host's own format must still be usable",
            refusedAtFormatCheck(),
        )
    }

    @Test
    fun `an Auris-format payload is not a transcript reference`() {
        // The two sources are independent: a payload in the Auris endpoint's format did not come
        // from the transcript host, so this site treats it as absent.
        stageStoredReference(ReferenceFingerprint.AURIS_FORMAT)

        assertTrue(refusedAtFormatCheck())
    }

    private companion object {
        const val PREPARATION_BUDGET_MS = 3_000L
        const val POLL_MS = 25L
    }
}