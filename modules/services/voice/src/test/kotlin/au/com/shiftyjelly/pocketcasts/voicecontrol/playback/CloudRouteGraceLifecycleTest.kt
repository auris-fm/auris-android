package au.com.shiftyjelly.pocketcasts.voicecontrol.playback

import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteEvent
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudRouteTurn
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTokenProviding
import au.com.shiftyjelly.pocketcasts.sharedtest.MainCoroutineRule
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.CloudAudioPlayer
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.PlaybackContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceIntent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CloudRouteGraceLifecycleTest {
    @get:Rule
    val mainCoroutineRule = MainCoroutineRule()

    @Test
    fun `a running cloud turn holds grace and preserves its origin`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 100L)
        signal.onWakeWordDetected()
        val origin = signal.currentGeneration
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val sink = sink(signal, origin) {
            flow {
                entered.complete(Unit)
                finish.await()
                emit(CloudRouteEvent.Done(0, 0))
            }
        }
        val owner = async { sink.routeToCloud("question", VoiceIntent.CloudTier.Premium, PlaybackContext(), false, origin, true) }
        try {
            entered.await()
            advanceTimeBy(350L)
            runCurrent()
            assertFalse(owner.isCompleted)
            assertTrue("in-flight cloud work must prevent inactivity expiry", signal.isActive.value)
            assertEquals(origin, signal.currentGeneration)
            assertTrue(signal.isCurrent(origin))
            finish.complete(Unit)
            owner.await()
            advanceTimeBy(99L)
            runCurrent()
            assertTrue("a no-audio terminal starts a fresh full interval", signal.isActive.value)
            advanceTimeBy(1L)
            runCurrent()
            assertFalse(signal.isActive.value)
        } finally {
            finish.complete(Unit)
            owner.cancelAndJoin()
            signal.onAppBackgrounded()
        }
    }

    @Test
    fun `server done and an empty queue do not release grace before the playback head finishes`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 100L)
        signal.onWakeWordDetected()
        val origin = signal.currentGeneration
        val player = CloudAudioPlayer(RuntimeEnvironment.getApplication())
        player.writeToSink = { _, _, _, count -> count }
        var head = 0L
        player.readPlaybackHeadFrames = { head }
        val terminal = CompletableDeferred<Unit>()
        val sink = sink(signal, origin, player) {
            flow {
                emit(CloudRouteEvent.Connected("pcm@16k"))
                emit(CloudRouteEvent.AudioFrame("pcm@16k", ByteArray(3_200)))
                terminal.complete(Unit)
                emit(CloudRouteEvent.Done(0, 0))
            }
        }
        val owner = async { sink.routeToCloud("question", VoiceIntent.CloudTier.Premium, PlaybackContext(), false, origin, true) }
        try {
            terminal.await()
            runCurrent()
            assertEquals("the entire PCM chunk was submitted", 1_600L, player.trackFramesForTest())
            assertTrue(player.isPlaying)
            advanceTimeBy(350L)
            runCurrent()
            assertFalse("done is still awaiting observed local playback", owner.isCompleted)
            assertTrue("submitted but unplayed audio must keep grace active", signal.isActive.value)
            head = player.trackFramesForTest()
            // Await the actual completion tick; advancing past it would spend part of the new timer.
            owner.await()
            assertFalse(player.isPlaying)
            advanceTimeBy(99L)
            runCurrent()
            assertTrue("the full grace interval follows local completion", signal.isActive.value)
            advanceTimeBy(1L)
            runCurrent()
            assertFalse(signal.isActive.value)
        } finally {
            owner.cancelAndJoin()
            player.release()
            signal.onAppBackgrounded()
        }
    }

    @Test
    fun `the injected production sink holds grace during token lookup and releases on failure`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 100L)
        signal.onWakeWordDetected()
        val origin = signal.currentGeneration
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val config = mock<au.com.shiftyjelly.pocketcasts.repositories.fingerprint.CloudConfig>()
        org.mockito.kotlin.whenever(config.baseUrl()).thenReturn("https://cloud.example.com")
        val monitor = mock<PlaybackContextMonitor>()
        org.mockito.kotlin.whenever(monitor.isHostAudioActive).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(false))
        // Exercise the actual injected constructor and CloudTurnRoute. A missing token prevents any
        // network call; the hold must nevertheless cover the real, suspended token-provider boundary.
        val sink = CloudRouteSink(
            cloudConfig = config,
            tokenProvider = object : CloudTokenProviding {
                override suspend fun currentToken(): String? {
                    entered.complete(Unit)
                    finish.await()
                    return null
                }
            },
            playbackSink = mock(),
            fingerprintTimingManager = mock(),
            playbackContextProvider = object : PlaybackContextProvider {
                override fun current() = PlaybackContext()
            },
            cloudPlaybackContextState = CloudPlaybackContextState(),
            analytics = mock(),
            templateResolver = au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.SpokenTemplateResolver(emptyMap()),
            currentLocale = { java.util.Locale.ENGLISH },
            playbackContextMonitor = monitor,
            audioPlayer = null,
            gracePeriodSignal = signal,
        )
        val owner = async { sink.routeToCloud("question", VoiceIntent.CloudTier.Premium, PlaybackContext(), false, origin, true) }
        try {
            entered.await()
            advanceTimeBy(350L)
            runCurrent()
            assertTrue("the production injection must reach the live grace signal", signal.isActive.value)
            finish.complete(Unit)
            owner.await()
            advanceTimeBy(99L)
            runCurrent()
            assertTrue(signal.isActive.value)
            advanceTimeBy(1L)
            runCurrent()
            assertFalse(signal.isActive.value)
        } finally {
            finish.complete(Unit)
            owner.cancelAndJoin()
            signal.onAppBackgrounded()
        }
    }

    @Test
    fun `cancellation clears local output before starting the follow up countdown`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 100L)
        signal.onWakeWordDetected()
        val origin = signal.currentGeneration
        val player = CloudAudioPlayer(RuntimeEnvironment.getApplication())
        player.writeToSink = { _, _, _, count -> count }
        player.readPlaybackHeadFrames = { 0L }
        val playing = CompletableDeferred<Unit>()
        val sink = sink(signal, origin, player) {
            flow {
                emit(CloudRouteEvent.Connected("pcm@16k"))
                emit(CloudRouteEvent.AudioFrame("pcm@16k", ByteArray(3_200)))
                playing.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val owner = async { sink.routeToCloud("question", VoiceIntent.CloudTier.Premium, PlaybackContext(), false, origin, true) }
        try {
            playing.await()
            advanceTimeBy(350L)
            runCurrent()
            assertTrue(player.isPlaying)
            assertTrue(signal.isActive.value)
            owner.cancelAndJoin()
            assertFalse("cancellation must clear real player output", player.isPlaying)
            advanceTimeBy(99L)
            runCurrent()
            assertTrue(signal.isActive.value)
            advanceTimeBy(1L)
            runCurrent()
            assertFalse(signal.isActive.value)
        } finally {
            owner.cancelAndJoin()
            player.release()
            signal.onAppBackgrounded()
        }
    }

    @Test
    fun `a turn ending after privacy closure cannot extend a new wake window`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 100L)
        signal.onWakeWordDetected()
        val origin = signal.currentGeneration
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val sink = sink(signal, origin) {
            flow {
                entered.complete(Unit)
                finish.await()
                emit(CloudRouteEvent.Done(0, 0))
            }
        }
        val owner = async { sink.routeToCloud("question", VoiceIntent.CloudTier.Premium, PlaybackContext(), false, origin, true) }
        try {
            entered.await()
            signal.onAppBackgrounded()
            advanceTimeBy(350L)
            runCurrent()
            assertFalse("a live cloud turn cannot override privacy", signal.isActive.value)
            signal.onWakeWordDetected()
            val successor = signal.currentGeneration
            advanceTimeBy(80L)
            finish.complete(Unit)
            owner.await()
            assertEquals(successor, signal.currentGeneration)
            advanceTimeBy(20L)
            runCurrent()
            assertFalse("the old completion must not reset the successor timer", signal.isActive.value)
        } finally {
            finish.complete(Unit)
            owner.cancelAndJoin()
            signal.onAppBackgrounded()
        }
    }

    @Test
    fun `superseding a turn cannot release the successor cloud hold`() = runTest {
        val signal = GracePeriodSignal(timeoutMs = 100L)
        signal.onWakeWordDetected()
        val origin = signal.currentGeneration
        val firstEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val sink = sink(signal, origin) { turn ->
            flow {
                if (turn.request == "first") {
                    firstEntered.complete(Unit)
                    kotlinx.coroutines.awaitCancellation()
                } else {
                    secondEntered.complete(Unit)
                    finish.await()
                    emit(CloudRouteEvent.Done(0, 0))
                }
            }
        }
        val first = async { sink.routeToCloud("first", VoiceIntent.CloudTier.Premium, PlaybackContext(), false, origin, true) }
        var second: kotlinx.coroutines.Deferred<CloudTurnResult>? = null
        try {
            firstEntered.await()
            second = async { sink.routeToCloud("second", VoiceIntent.CloudTier.Premium, PlaybackContext(), true, origin, false) }
            secondEntered.await()
            first.join()
            assertTrue(first.isCancelled)
            advanceTimeBy(350L)
            runCurrent()
            assertTrue("old cleanup must leave the successor hold intact", signal.isActive.value)
            finish.complete(Unit)
            second.await()
            advanceTimeBy(100L)
            runCurrent()
            assertFalse(signal.isActive.value)
        } finally {
            finish.complete(Unit)
            first.cancelAndJoin()
            second?.cancelAndJoin()
            signal.onAppBackgrounded()
        }
    }

    private fun sink(
        signal: GracePeriodSignal,
        origin: Long,
        player: CloudAudioPlayer? = null,
        route: (CloudRouteTurn) -> Flow<CloudRouteEvent>,
    ) = CloudRouteSink(
        resolveBaseUrl = { "https://cloud.example.com" },
        tokenProvider = object : CloudTokenProviding {
            override suspend fun currentToken(): String = "test-token"
        },
        playbackSink = mock(),
        fingerprintTimingManager = mock(),
        playbackContextProvider = object : PlaybackContextProvider {
            override fun current() = PlaybackContext()
        },
        conversationAdmission = CloudConversationAdmission().apply { beginOrigin(origin) },
        cloudPlaybackContextState = CloudPlaybackContextState(),
        analytics = mock(),
        routeInvoker = route,
        isHostPlaying = { false },
        audioPlayer = player,
        gracePeriodSignal = signal,
    )
}
