package au.com.shiftyjelly.pocketcasts.voicecontrol.engine

import android.content.Context
import android.media.AudioManager
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTokenProviding
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudTurnRoute
import au.com.shiftyjelly.pocketcasts.sharedtest.MainCoroutineRule
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrBackend
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrCapabilities
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.ModelSpec
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.TranslationStage
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.PcmAudioFrame
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceAudioProcessor
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceSegmenterResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.PlaybackContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.ToolCall
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.ToolCallMapper
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceIntent
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceResponse
import au.com.shiftyjelly.pocketcasts.voicecontrol.mode.ListeningMode
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.IntentRoutingInput
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognitionContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizeResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.CloudConversationAdmission
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.CloudPlaybackContextState
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.CloudRouteSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackContextProvider
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoicePlaybackIntentExecutor
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoicePlaybackSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRoute
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.MicExposure
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordDetector
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual ASR preparation → dispatch → executor → prepared turn → socket auth JSON. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VoiceAsrCloudLanguageTest {
    @get:Rule
    val mainCoroutineRule = MainCoroutineRule()

    @Test
    fun `model selected cloud sends complete Cantonese source instead of model paraphrase`() = runTest {
        for (fallback in listOf(false, true)) {
            checkCloud("請解釋嘉賓對睡眠嘅看法。", "yue", "Explain the guest's view on sleep.", fallback)
        }
    }

    @Test
    fun `routing failure sends complete Mandarin source instead of English translation`() = runTest {
        for (fallback in listOf(false, true)) {
            checkCloud("请解释嘉宾对睡眠的看法。", "zh", "Explain the guest's view on sleep.", fallback)
        }
    }

    @Test
    fun `both routes preserve unknown language without using app locale`() = runTest {
        for (fallback in listOf(false, true)) {
            checkCloud("請解釋呢段。", null, "請解釋呢段。", fallback)
        }
    }

    @Test
    fun `translated only backend sends available text and known source language on both routes`() = runTest {
        for (fallback in listOf(false, true)) {
            checkCloud("Explain this passage.", "de", "Explain this passage.", fallback, backendTranslation = true)
        }
    }

    @Test
    fun `translated only backend with unknown language cannot invent the original`() = runTest {
        checkCloud("Explain this passage.", null, "Explain this passage.", fallback = false, backendTranslation = true)
    }

    @Test
    fun `retry keeps the first prepared source text and language`() = runTest {
        checkCloud("解釋呢段。", "yue", "Explain this passage.", fallback = false, retry = true)
    }

    @Test
    fun `local intent still receives English and never opens a cloud socket`() = runTest {
        val fixture = startPipeline("暫停。", "yue", "Pause.", VoiceIntent.Playback.Pause)
        try {
            kotlinx.coroutines.withTimeout(5_000L) { fixture.completed.await() }
            assertEquals("Pause.", fixture.inputs.single().routerTranscript)
            assertEquals("暫停。", fixture.inputs.single().sourceTranscript)
            verify(fixture.playback).pause()
            assertTrue(fixture.client.frames.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `two separate routing failures in one grace window send two distinct resumable cloud requests`() = runTest {
        val fixture = startPipeline("哦，我指的是AI推理。", "zh", "I mean AI reasoning.", intent = null, utterances = 2)
        try {
            kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                while (fixture.client.frames.size < 2) kotlinx.coroutines.delay(10L)
            }
            assertEquals("both independent utterances reached the actual router", 2, fixture.inputs.size)
            assertEquals("each eligible request must reach the actual auth writer", 2, fixture.client.frames.size)
            val first = JSONObject(fixture.client.frames[0])
            val second = JSONObject(fixture.client.frames[1])
            org.junit.Assert.assertNotEquals(first.getString("request_id"), second.getString("request_id"))
            assertEquals(first.getString("conversation_id"), second.getString("conversation_id"))
            assertEquals("create", first.getString("conversation_mode"))
            assertEquals("resume", second.getString("conversation_mode"))
            assertEquals("哦，我指的是AI推理。", second.getString("request"))
            assertEquals(fixture.intents[0].let { (it as VoiceIntent.CloudRoute).windowGeneration }, (fixture.intents[1] as VoiceIntent.CloudRoute).windowGeneration)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `recognizer owned request identity survives the engine handoff`() = runTest {
        val chosen = VoiceIntent.CloudRoute("runtime request", VoiceIntent.CloudTier.Premium, logicalRequestId = "dialog-owned-request")
        val fixture = startPipeline("A question.", "en", "A question.", chosen)
        try {
            kotlinx.coroutines.withTimeout(5_000L) { fixture.completed.await() }
            assertEquals("dialog-owned-request", (fixture.intents.single() as VoiceIntent.CloudRoute).logicalRequestId)
        } finally {
            fixture.close()
        }
    }

    private suspend fun TestScope.checkCloud(
        source: String,
        language: String?,
        routerText: String,
        fallback: Boolean,
        backendTranslation: Boolean = false,
        retry: Boolean = false,
    ) {
        // Exercise the real model-call mapper, including a paraphrase that must not become the request.
        val mapped = if (fallback) {
            null
        } else {
            ToolCallMapper().map(
                ToolCall(
                    "cloud_route",
                    "route",
                    mapOf("request" to "Model-authored English paraphrase", "tier" to "premium"),
                ),
            )
        }
        assertTrue("the chosen-route fixture must map a real cloud call", fallback || mapped is VoiceIntent.CloudRoute)
        val fixture = startPipeline(source, language, routerText, mapped, backendTranslation)
        try {
            kotlinx.coroutines.withTimeout(5_000L) { fixture.completed.await() }
            val first = JSONObject(fixture.client.frames.single())
            assertEquals("cloud request must preserve the available ASR utterance", source, first.getString("request"))
            if (language == null) {
                assertFalse("unknown ASR language must be absent", first.has("source_language"))
            } else {
                assertTrue("known source language must be serialized", first.has("source_language"))
                assertEquals(
                    "source language must survive to the actual auth writer",
                    language,
                    first.getString("source_language"),
                )
            }
            val input = fixture.inputs.single()
            assertEquals(routerText, input.routerTranscript)
            assertEquals(if (backendTranslation) null else source, input.sourceTranscript)
            if (retry) {
                val original = fixture.intents.single() as VoiceIntent.CloudRoute
                fixture.executor.execute(
                    original.copy(request = "Later unrelated English", sourceLanguage = "en", isRetry = true),
                )
                val second = JSONObject(fixture.client.frames.last())
                assertEquals(2, fixture.client.frames.size)
                val retainedKeys = listOf("request", "source_language", "request_id", "conversation_id", "conversation_mode")
                for (key in retainedKeys) {
                    assertEquals("retry must reuse $key", first.get(key), second.get(key))
                }
            }
        } finally {
            fixture.close()
        }
    }

    private suspend fun TestScope.startPipeline(
        text: String,
        language: String?,
        routerText: String,
        intent: VoiceIntent?,
        backendTranslation: Boolean = false,
        utterances: Int = 1,
    ): Fixture {
        val signal = GracePeriodSignal()
        signal.onWakeWordDetected()
        val client = CapturingClient()
        val token = object : CloudTokenProviding {
            override suspend fun currentToken() = "fixture-token"
        }
        val route = CloudTurnRoute("https://cloud.example.com", token, okHttpClient = client)
        val playback = mock<VoicePlaybackSink>()
        whenever(playback.pause()).thenReturn(VoiceResponse.Silent)
        val provider = object : PlaybackContextProvider {
            override fun current() = PlaybackContext()
        }
        val sink = CloudRouteSink(
            resolveBaseUrl = { "https://cloud.example.com" }, tokenProvider = token,
            playbackSink = playback, fingerprintTimingManager = mock(), playbackContextProvider = provider,
            conversationAdmission = CloudConversationAdmission().apply { beginOrigin(signal.currentGeneration) },
            cloudPlaybackContextState = CloudPlaybackContextState(), analytics = mock(), routeInvoker = route::route,
            isHostPlaying = { false }, gracePeriodSignal = signal, currentLocale = { java.util.Locale.JAPANESE },
        )
        val executor = VoicePlaybackIntentExecutor(
            playback, mock(), mock(), mock(), mock(), mock(), sink, provider, mock(), mock(), mock(), signal, mock(),
        )
        val inputs = mutableListOf<IntentRoutingInput>()
        val recognizer = object : VoiceRecognizer {
            override suspend fun ensureReady() = Result.success(Unit)
            override suspend fun recognize(
                input: IntentRoutingInput,
                context: VoiceRecognitionContext,
                logicalRequestId: String,
            ): VoiceRecognizeResult {
                inputs += input
                // A null intent and missing failure reason exercise production routing-failure escalation.
                return VoiceRecognizeResult(intent)
            }
            override fun release() = Unit
        }
        val backend = object : AsrBackend {
            override suspend fun ensureReady() = Result.success(Unit)
            override suspend fun transcribe(samples: FloatArray, sampleRateHz: Int) = AsrResult(text, detectedLanguage = language)
            override val requiredModel = ModelSpec(emptyList(), "")
            override val capabilities = AsrCapabilities(
                setOf("en", "yue", "zh", "de"),
                canTranslateToEnglish = backendTranslation,
            )
            override fun release() = Unit
        }
        val finishedTurns = kotlinx.coroutines.channels.Channel<Unit>(1)
        val processor = mock<VoiceAudioProcessor>()
        whenever(processor.attachCapture()).thenReturn(true)
        whenever(processor.startProcessing()).thenReturn(
            kotlinx.coroutines.flow.flow {
                repeat(utterances) {
                    emit(
                        VoiceSegmenterResult.SpeechEnded(
                            listOf(PcmAudioFrame(shortArrayOf(100, 200, 300, 400), 16000)),
                            speechOnsetSample = 2,
                        ),
                    )
                    // The second utterance follows actual terminal/executor completion of the first.
                    if (utterances > 1) finishedTurns.receive()
                }
            },
        )
        val filter = mock<UtteranceFilter>()
        whenever(filter.shouldProcessReference(any(), any(), any(), any(), anyOrNull())).thenReturn(true)
        val wake = mock<WakeWordDetector>()
        whenever(wake.detect(any(), any(), any())).thenReturn(WakeWordResult(false, 0f))
        val translator = mock<TranslationStage>()
        if (language != null) {
            whenever(translator.ensureReady(language)).thenReturn(Result.success(Unit))
            whenever(translator.translate(text, language)).thenReturn(Result.success(routerText))
        }
        val context = mock<Context>()
        val manager = mock<AudioManager>()
        whenever(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(manager)
        whenever(manager.mode).thenReturn(AudioManager.MODE_NORMAL)
        val engine = VoiceAsrEngine(
            voiceAudioProcessor = processor,
            utteranceFilter = filter,
            intentRecognizer = recognizer,
            wakeWordDetector = wake,
            gracePeriodSignal = signal,
            audioFeedbackRenderer = mock(),
            translationStage = translator,
            currentLocale = { java.util.Locale.JAPANESE },
            context = context,
        )
        engine.scope = this
        val completed = CompletableDeferred<Unit>()
        val intents = mutableListOf<VoiceIntent>()
        engine.start(
            backend,
            AudioRoute.Speaker,
            ListeningMode.Continuous,
            playbackBufferProvider = { PlaybackReference(FloatArray(0), 16000, null) },
            micExposureProvider = { MicExposure.Exposed },
            onIntent = { dispatched ->
                intents += dispatched
                launch {
                    try {
                        executor.execute(dispatched)
                        completed.complete(Unit)
                    } catch (t: Throwable) {
                        completed.completeExceptionally(t)
                    } finally {
                        finishedTurns.trySend(Unit)
                    }
                }
            },
        )
        return Fixture(engine, signal, client, inputs, intents, executor, playback, completed)
    }

    private data class Fixture(
        val engine: VoiceAsrEngine,
        val signal: GracePeriodSignal,
        val client: CapturingClient,
        val inputs: List<IntentRoutingInput>,
        val intents: List<VoiceIntent>,
        val executor: VoicePlaybackIntentExecutor,
        val playback: VoicePlaybackSink,
        val completed: CompletableDeferred<Unit>,
    ) {
        fun close() {
            engine.stop()
            signal.onAppBackgrounded()
        }
    }

    /** Only the network peer is simulated; production transport and JSON adapter do all encoding. */
    private class CapturingClient : OkHttpClient() {
        val frames = mutableListOf<String>()
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            val socket = mock<WebSocket>()
            whenever(socket.send(any<String>())).thenAnswer { invocation ->
                frames += invocation.getArgument<String>(0)
                listener.onMessage(socket, """{"type":"connected","codec":"pcm_s16le@24k","reservation_id":"test-reservation"}""")
                listener.onMessage(socket, """{"type":"done","outcome":"completed","usage":{"input_tokens":0,"output_tokens":0,"speech":{"amount":0,"unit":"characters"}}}""")
                listener.onClosed(socket, 1000, "complete")
                true
            }
            listener.onOpen(
                socket,
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(101).message("Switching Protocols").build(),
            )
            return socket
        }
    }
}
