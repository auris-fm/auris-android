package au.com.shiftyjelly.pocketcasts.voicecontrol.engine

import android.content.Context
import android.media.AudioManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.PlaybackContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceIntent
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceResponse
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackContextProvider
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackManagerPlaybackSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoiceActionOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoiceCloudRouteSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoicePlaybackIntentExecutor
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoicePlaybackSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.service.VoiceIntentDispatcher
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ExecutionOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * EVERY EXIT CARRIES THE PRIMARY UNKNOWN (reviewer 00b0d7a9): a cloud duck can report its
 * auxiliary grant and then the stream throws or is cancelled. In both cases the turn's own result
 * is unobserved, so the aggregate must be the explicit unknown — never the auxiliary success.
 * These drive the REAL executor and a real granting sink through its production
 * `execute(intent, onOutcome)` channel; nothing is injected by the test.
 */
class CloudTurnExitControlTest {

    @Test
    fun `duck success followed by a thrown stream still records the primary as unobserved`() {
        val facts = runTurn(cloudRouteFor = { playback ->
            DuckThenThrow(playback, IllegalStateException("stream failed"))
        })
        assertAuxiliaryObserved(facts)
        assertTrue("a throw must still yield the primary unknown, was $facts", facts.contains(VoiceActionOutcome.NotObserved))
        assertNull("never success", aggregate(facts))
    }

    @Test
    fun `duck success followed by cancellation still records the primary as unobserved`() {
        val facts = runTurn(cloudRouteFor = { playback ->
            DuckThenThrow(playback, CancellationException("cancelled"))
        })
        assertAuxiliaryObserved(facts)
        assertTrue("cancellation must still yield the primary unknown, was $facts", facts.contains(VoiceActionOutcome.NotObserved))
        assertNull("never success", aggregate(facts))
    }

    @Test
    fun `an auxiliary fact plus a merely dispatched primary stays unknown`() {
        // DISPATCH IS NOT A RESULT: Dispatched proves the call was made, so it does not clear the
        // unobserved fallback (the dispatcher maps it to nothing and would drop the only marker).
        // The REAL executor reports Dispatched for pause/resume itself; the duck runs on the real
        // sink. So the facts reaching aggregation are exactly auxiliary-grant + dispatched-primary.
        val facts = runTurn(
            cloudRouteFor = { playback -> DuckThenPause(playback) },
        )
        assertTrue(
            "a dispatched-only primary must still be recorded unobserved, was $facts",
            facts.contains(VoiceActionOutcome.NotObserved),
        )
        assertNull("dispatched plus auxiliary may never aggregate to success", aggregate(facts))
    }

    private fun assertAuxiliaryObserved(facts: List<VoiceActionOutcome>) {
        assertTrue(
            "the duck grant must be observed, was $facts",
            facts.any { it is VoiceActionOutcome.AuxiliaryPerformed },
        )
    }

    private fun aggregate(facts: List<VoiceActionOutcome>): ExecutionOutcome? {
        val dispatcher = VoiceIntentDispatcher()
        return VoiceAsrEngine.aggregateObservations(facts.mapNotNull { dispatcher.executionOutcomeOf(it) })
    }

    @Suppress("DEPRECATION")
    private fun runTurn(
        cloudRouteFor: (PlaybackManagerPlaybackSink) -> VoiceCloudRouteSink,
    ): List<VoiceActionOutcome> {
        val context = mock<Context>()
        val audioManager = mock<AudioManager>()
        whenever(context.getSystemService(AudioManager::class.java)).thenReturn(audioManager)
        whenever(audioManager.mode).thenReturn(AudioManager.MODE_NORMAL)
        whenever(audioManager.requestAudioFocus(any(), any(), any()))
            .thenReturn(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val real = PlaybackManagerPlaybackSink(mock<PlaybackManager>(), context)
        val route = cloudRouteFor(real)
        val executor = VoicePlaybackIntentExecutor(
            playbackSink = real,
            effectsSink = mock(),
            volumeSink = mock(),
            sleepSink = mock(),
            chapterSink = mock(),
            bookmarkSink = mock(),
            cloudRouteSink = route,
            playbackContextProvider = object : PlaybackContextProvider {
                override fun current(): PlaybackContext = PlaybackContext()
            },
            queueSink = mock(),
            playbackQuerySink = mock(),
            statsQuerySink = mock(),
            gracePeriodSignal = mock(),
            playbackManager = mock(),
        )
        (route as? DuckThenPause)?.executor = executor
        val facts = mutableListOf<VoiceActionOutcome>()
        runCatching {
            runBlocking {
                executor.execute(VoiceIntent.CloudRoute(request = "play", tier = VoiceIntent.CloudTier.Premium)) { facts += it }
            }
        }
        return facts
    }

    /** The production duck, then the stream fails or is cancelled. */
    private class DuckThenThrow(
        private val playback: PlaybackManagerPlaybackSink,
        private val failure: Throwable,
    ) : VoiceCloudRouteSink {
        override suspend fun routeToCloud(
            request: String,
            tier: VoiceIntent.CloudTier,
            context: PlaybackContext,
            onDuck: ((VoiceActionOutcome) -> Unit)?,
        ): VoiceResponse {
            playback.duckReporting(onDuck)
            throw failure
        }
    }

    /**
     * The production duck, then the turn's OWN action is a real executed pause. The executor is
     * what reports Dispatched for pause/resume (its dispatch sites), so this is the production
     * dispatched fact, not a hand-built one.
     */
    private class DuckThenPause(private val playback: PlaybackManagerPlaybackSink) : VoiceCloudRouteSink {
        internal var executor: VoicePlaybackIntentExecutor? = null

        override suspend fun routeToCloud(
            request: String,
            tier: VoiceIntent.CloudTier,
            context: PlaybackContext,
            onDuck: ((VoiceActionOutcome) -> Unit)?,
        ): VoiceResponse {
            playback.duckReporting(onDuck)
            executor?.execute(VoiceIntent.Playback.Pause, onDuck)
            return VoiceResponse.Silent
        }
    }
}
