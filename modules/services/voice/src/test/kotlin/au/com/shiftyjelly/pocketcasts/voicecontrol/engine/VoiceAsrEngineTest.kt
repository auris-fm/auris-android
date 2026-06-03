@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@file:Suppress("DEPRECATION")

package au.com.shiftyjelly.pocketcasts.voicecontrol.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrBackend
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceAudioProcessor
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceSegmenterResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.mode.ListeningMode
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRoute
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.MicExposure
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any

class VoiceAsrEngineTest {

    private val context = mock<Context>()
    private val audioManager = mock<AudioManager>()
    private val voiceAudioProcessor = mock<VoiceAudioProcessor>()
    private val utteranceFilter = mock<UtteranceFilter>()
    private val intentRecognizer = mock<VoiceRecognizer>()
    private val wakeWordDetector = mock<WakeWordDetector>()
    private val backend = mock<AsrBackend>()

    private var capturedReceiver: BroadcastReceiver? = null

    private val captureFlow: Flow<VoiceSegmenterResult> = MutableStateFlow(VoiceSegmenterResult.Silence)

    private lateinit var engine: VoiceAsrEngine

    private fun CoroutineScope.createEngine() {
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
        `when`(audioManager.mode).thenReturn(AudioManager.MODE_NORMAL)
        `when`(voiceAudioProcessor.startProcessing()).thenReturn(captureFlow)

        `when`(
            context.registerReceiver(
                any<BroadcastReceiver>(),
                any<IntentFilter>(),
            ),
        ).thenAnswer { invocation ->
            capturedReceiver = invocation.getArgument(0)
            Intent()
        }

        engine = VoiceAsrEngine(
            voiceAudioProcessor = voiceAudioProcessor,
            utteranceFilter = utteranceFilter,
            intentRecognizer = intentRecognizer,
            wakeWordDetector = wakeWordDetector,
            context = context,
        )
        engine.scope = this
    }

    private fun startEngine(route: AudioRoute, mode: ListeningMode = ListeningMode.Continuous) {
        engine.start(
            backend = backend,
            audioRoute = route,
            listeningMode = mode,
            playbackBufferProvider = { FloatArray(0) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = {},
        )
    }

    private fun simulateScoState(state: Int) {
        val intent = mock<Intent>()
        `when`(
            intent.getIntExtra(
                AudioManager.EXTRA_SCO_AUDIO_STATE,
                AudioManager.SCO_AUDIO_STATE_ERROR,
            ),
        ).thenReturn(state)
        capturedReceiver?.onReceive(context, intent)
    }

    // ── Speaker / WiredHeadset routes: no SCO ──────────────────────────

    @Test
    fun `start with Speaker route skips SCO and starts capture`() = runTest {
        createEngine()
        startEngine(AudioRoute.Speaker)
        advanceUntilIdle()

        verify(audioManager, never()).startBluetoothSco()
        verify(voiceAudioProcessor).startProcessing()

        engine.stop()
    }

    @Test
    fun `start with WiredHeadset route skips SCO`() = runTest {
        createEngine()
        startEngine(AudioRoute.Headset(hasMicrophone = true))
        advanceUntilIdle()

        verify(audioManager, never()).startBluetoothSco()
        verify(voiceAudioProcessor).startProcessing()

        engine.stop()
    }

    // ── Bluetooth route: SCO await ─────────────────────────────────────

    @Test
    fun `start with BluetoothA2dpOnly awaits SCO connected before capture`() = runTest {
        createEngine()
        startEngine(AudioRoute.BluetoothA2dpOnly)
        advanceUntilIdle()

        verify(audioManager).startBluetoothSco()
        verify(voiceAudioProcessor, never()).startProcessing()
        assertTrue("Expected receiver registered", capturedReceiver != null)

        simulateScoState(AudioManager.SCO_AUDIO_STATE_CONNECTED)
        advanceUntilIdle()

        verify(voiceAudioProcessor).startProcessing()

        engine.stop()
    }

    @Test
    fun `start with BluetoothA2dpOnly starts capture even when SCO disconnects`() = runTest {
        createEngine()
        startEngine(AudioRoute.BluetoothA2dpOnly)
        advanceUntilIdle()

        verify(audioManager).startBluetoothSco()
        verify(voiceAudioProcessor, never()).startProcessing()

        simulateScoState(AudioManager.SCO_AUDIO_STATE_DISCONNECTED)
        advanceUntilIdle()

        verify(voiceAudioProcessor).startProcessing()

        engine.stop()
    }

    // ── Cancellation / Stop ────────────────────────────────────────────

    @Test
    fun `stop during SCO await cancels wait and unregisters receiver`() = runTest {
        createEngine()
        startEngine(AudioRoute.BluetoothA2dpOnly)
        advanceUntilIdle()

        verify(audioManager).startBluetoothSco()
        assertTrue("Expected receiver registered", capturedReceiver != null)

        engine.stop()
        advanceUntilIdle()

        verify(context).unregisterReceiver(any<BroadcastReceiver>())
        verify(voiceAudioProcessor, never()).startProcessing()
    }

    @Test
    fun `stop after capture started closes SCO and releases backend`() = runTest {
        createEngine()
        startEngine(AudioRoute.BluetoothA2dpOnly)
        advanceUntilIdle()

        simulateScoState(AudioManager.SCO_AUDIO_STATE_CONNECTED)
        advanceUntilIdle()
        verify(voiceAudioProcessor).startProcessing()

        engine.stop()
        advanceUntilIdle()

        verify(audioManager).stopBluetoothSco()
        verify(backend).release()
    }

    // ── SCO not reopened for subsequent starts ─────────────────────────

    @Test
    fun `stop then restart on Bluetooth re-opens SCO`() = runTest {
        createEngine()
        startEngine(AudioRoute.BluetoothA2dpOnly)
        advanceUntilIdle()

        simulateScoState(AudioManager.SCO_AUDIO_STATE_CONNECTED)
        advanceUntilIdle()

        engine.stop()
        advanceUntilIdle()
        verify(audioManager).stopBluetoothSco()

        // Restart — stop cleared scoStarted, so SCO must be re-opened
        startEngine(AudioRoute.BluetoothA2dpOnly)
        advanceUntilIdle()

        verify(audioManager, times(2)).startBluetoothSco()

        engine.stop()
    }

    // ── Route switching scenarios ──────────────────────────────────────

    @Test
    fun `restart from Speaker to Bluetooth triggers SCO await`() = runTest {
        createEngine()
        startEngine(AudioRoute.Speaker)
        advanceUntilIdle()
        verify(audioManager, never()).startBluetoothSco()

        engine.stop()
        capturedReceiver = null

        startEngine(AudioRoute.BluetoothA2dpOnly)
        advanceUntilIdle()

        verify(audioManager, times(1)).startBluetoothSco()
        assertTrue("Expected receiver registered", capturedReceiver != null)

        engine.stop()
    }

    @Test
    fun `restart from Bluetooth to Speaker closes SCO`() = runTest {
        createEngine()
        startEngine(AudioRoute.BluetoothA2dpOnly)
        advanceUntilIdle()

        simulateScoState(AudioManager.SCO_AUDIO_STATE_CONNECTED)
        advanceUntilIdle()

        engine.stop()
        advanceUntilIdle()
        verify(audioManager).stopBluetoothSco()

        startEngine(AudioRoute.Speaker)
        advanceUntilIdle()
        verify(voiceAudioProcessor, times(2)).startProcessing()

        verify(audioManager, times(1)).startBluetoothSco()
        verify(audioManager, times(1)).stopBluetoothSco()

        engine.stop()
    }
}
