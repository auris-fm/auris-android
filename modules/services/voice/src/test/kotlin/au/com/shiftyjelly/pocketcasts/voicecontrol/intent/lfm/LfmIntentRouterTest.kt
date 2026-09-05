package au.com.shiftyjelly.pocketcasts.voicecontrol.intent.lfm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.voicecontrol.dialog.VoiceDialogManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.ToolCallMapper
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceIntent
import au.com.shiftyjelly.pocketcasts.voicecontrol.mode.ListeningMode
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.IntentRoutingInput
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.ModelManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.RouterInputFormat
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.TranslationKind
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognitionContext
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.MicExposure
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LfmIntentRouterTest {
    @get:Rule val tempDir = TemporaryFolder()

    @Test
    fun noMatch_returnsNull() = runTest {
        val inference = FakeLfmInference().apply {
            classifyLabel = "no_match:"
        }
        val diagnostics = mutableListOf<RouterStageDiagnostic>()
        val router = createRouter(inference).also { it.diagnosticSink = { diagnostics += it } }

        router.ensureReady().getOrThrow()
        assertNull(router.recognize(english("hello"), RECOGNITION_CONTEXT).intent)
        assertEquals(1, inference.resetCount)
        assertEquals(RouterStageDiagnostic.STAGE_NO_MATCH, diagnostics.single().failedStage)
        assertEquals(RouterInputFormat.EnglishV1.wireName, diagnostics.single().inputFormat)
        assertEquals(TranslationKind.NONE.wireName, diagnostics.single().translationKind)
    }

    @Test
    fun dialogControl_routesThroughDialogManager() = runTest {
        val inference = FakeLfmInference().apply {
            classifyLabel = "dialog_control:begin"
            generateResult =
                "<|tool_call_start|>[dialog_control(action='begin', target_tool='bookmark', target_action='rename')]<|tool_call_end|>"
        }
        val diagnostics = mutableListOf<RouterStageDiagnostic>()
        val router = createRouter(inference).also { it.diagnosticSink = { diagnostics += it } }

        router.ensureReady().getOrThrow()
        assertNull(router.recognize(english("rename my bookmark"), RECOGNITION_CONTEXT).intent)
        assertEquals(RouterStageDiagnostic.STAGE_MAPPER_DIALOG, diagnostics.single().failedStage)
    }

    @Test
    fun spanFailure_returnsNullWithoutGuessingTool() = runTest {
        val inference = FakeLfmInference().apply {
            tokenizeThrows = IllegalArgumentException("user utterance tokens not found in prompt")
        }
        val diagnostics = mutableListOf<RouterStageDiagnostic>()
        val router = createRouter(inference).also { it.diagnosticSink = { diagnostics += it } }

        router.ensureReady().getOrThrow()
        assertNull(router.recognize(english("pause"), RECOGNITION_CONTEXT).intent)
        assertEquals(0, inference.classifyCount)
        assertEquals(RouterStageDiagnostic.STAGE_EXCEPTION, diagnostics.single().failedStage)
        assertEquals(1, inference.resetCount)
    }

    @Test
    fun decodeFailure_returnsNullWithoutGuessingTool() = runTest {
        val inference = FakeLfmInference().apply {
            classifyLabel = "playback:pause"
            generateResult = null
        }
        val diagnostics = mutableListOf<RouterStageDiagnostic>()
        val router = createRouter(inference).also { it.diagnosticSink = { diagnostics += it } }

        router.ensureReady().getOrThrow()
        assertNull(router.recognize(english("pause"), RECOGNITION_CONTEXT).intent)
        assertEquals(RouterStageDiagnostic.STAGE_GENERATE, diagnostics.single().failedStage)
        assertEquals("playback:pause", diagnostics.single().classifierLabel)
    }

    @Test
    fun blankTranscript_emitsBlankStageDiagnostic() = runTest {
        val inference = FakeLfmInference()
        val diagnostics = mutableListOf<RouterStageDiagnostic>()
        val router = createRouter(inference).also { it.diagnosticSink = { diagnostics += it } }

        router.ensureReady().getOrThrow()
        assertNull(router.recognize(english("   "), RECOGNITION_CONTEXT).intent)
        assertEquals(RouterStageDiagnostic.STAGE_BLANK, diagnostics.single().failedStage)
        assertEquals(0, inference.resetCount)
    }

    @Test
    fun notReady_emitsNotReadyDiagnostic() = runTest {
        val inference = FakeLfmInference()
        val diagnostics = mutableListOf<RouterStageDiagnostic>()
        val router = createRouter(inference).also { it.diagnosticSink = { diagnostics += it } }

        assertNull(router.recognize(english("pause"), RECOGNITION_CONTEXT).intent)
        assertEquals(RouterStageDiagnostic.STAGE_NOT_READY, diagnostics.single().failedStage)
    }

    @Test
    fun ensureReady_failsWhenNativeLoadFails() = runTest {
        val inference = FakeLfmInference().apply {
            loadResult = false
            lastErrorMessage = "invalid classifier.bin magic"
        }
        val router = createRouter(inference)

        assertFalse(router.ensureReady().isSuccess)
    }

    @Test
    fun ensureReady_failsClosedOnUnknownInputFormat() = runTest {
        val inference = FakeLfmInference()
        val router = createRouter(inference, routerInputFormat = "future_v9")

        assertFalse(router.ensureReady().isSuccess)
        val manager = ModelManager(ApplicationProvider.getApplicationContext()).apply {
            filesDir = tempDir.root
        }
        assertFalse(manager.isLfmModelReady())
    }

    @Test
    fun englishV1_promptUsesRouterTranscriptOnly() = runTest {
        val inference = FakeLfmInference().apply {
            classifyLabel = "playback:pause"
            generateResult =
                "<|tool_call_start|>[playback(action='pause')]<|tool_call_end|>"
        }
        val diagnostics = mutableListOf<RouterStageDiagnostic>()
        val router = createRouter(inference).also { it.diagnosticSink = { diagnostics += it } }

        router.ensureReady().getOrThrow()
        val input = IntentRoutingInput(
            sourceTranscript = "倒回去3分钟。",
            sourceLanguage = "zh",
            routerTranscript = "Go back to 3 minutes.",
            translationKind = TranslationKind.PLATFORM,
        )
        assertEquals(
            VoiceIntent.Playback.Pause,
            router.recognize(input, RECOGNITION_CONTEXT).intent,
        )
        assertTrue(inference.tokenizedTexts.any { it == "Go back to 3 minutes." })
        assertFalse(inference.tokenizedTexts.any { it.contains("倒回去") })
        val diagnostic = diagnostics.single()
        assertEquals(RouterStageDiagnostic.OUTCOME_INTENT, diagnostic.finalOutcome)
        assertNull(diagnostic.failedStage)
        assertEquals("zh", diagnostic.sourceLanguage)
        assertEquals(TranslationKind.PLATFORM.wireName, diagnostic.translationKind)
        assertEquals(RouterInputFormat.EnglishV1.wireName, diagnostic.inputFormat)
        assertEquals("playback:pause", diagnostic.classifierLabel)
        assertEquals("q8_0", diagnostic.quant)
    }

    @Test
    fun diagnosticSinkThrows_emitsExactlyOneDiagnosticAndKeepsOutcome() = runTest {
        val inference = FakeLfmInference().apply {
            classifyLabel = "playback:pause"
            generateResult =
                "<|tool_call_start|>[playback(action='pause')]<|tool_call_end|>"
        }
        val diagnostics = mutableListOf<RouterStageDiagnostic>()
        val router = createRouter(inference).also {
            it.diagnosticSink = { diagnostic ->
                diagnostics += diagnostic
                throw IllegalStateException("sink boom")
            }
        }

        router.ensureReady().getOrThrow()
        assertEquals(
            VoiceIntent.Playback.Pause,
            router.recognize(english("pause"), RECOGNITION_CONTEXT).intent,
        )
        assertEquals(1, diagnostics.size)
        assertEquals(RouterStageDiagnostic.OUTCOME_INTENT, diagnostics.single().finalOutcome)
        assertEquals(1, inference.resetCount)
    }

    @Test
    fun resetThrows_preservesRoutingResultAndSingleDiagnostic() = runTest {
        val inference = FakeLfmInference().apply {
            classifyLabel = "playback:pause"
            generateResult =
                "<|tool_call_start|>[playback(action='pause')]<|tool_call_end|>"
            resetThrows = IllegalStateException("reset boom")
        }
        val diagnostics = mutableListOf<RouterStageDiagnostic>()
        val router = createRouter(inference).also { it.diagnosticSink = { diagnostics += it } }

        router.ensureReady().getOrThrow()
        assertEquals(
            VoiceIntent.Playback.Pause,
            router.recognize(english("pause"), RECOGNITION_CONTEXT).intent,
        )
        assertEquals(1, diagnostics.size)
        assertEquals(RouterStageDiagnostic.OUTCOME_INTENT, diagnostics.single().finalOutcome)
        assertEquals(1, inference.resetCount)
    }

    @Test
    fun postClassifyException_retainsClassifierLabel() = runTest {
        val inference = FakeLfmInference().apply {
            classifyLabel = "playback:pause"
            generateThrows = IllegalStateException("generate boom")
        }
        val diagnostics = mutableListOf<RouterStageDiagnostic>()
        val router = createRouter(inference).also { it.diagnosticSink = { diagnostics += it } }

        router.ensureReady().getOrThrow()
        assertNull(router.recognize(english("pause"), RECOGNITION_CONTEXT).intent)
        val diagnostic = diagnostics.single()
        assertEquals(RouterStageDiagnostic.STAGE_EXCEPTION, diagnostic.failedStage)
        assertEquals("playback:pause", diagnostic.classifierLabel)
    }

    @Test
    fun successPath_recordsDeterministicPerStageAndTotalLatency() = runTest {
        var now = 1_000L
        val inference = FakeLfmInference(onStep = { now += it }).apply {
            classifyLabel = "playback:pause"
            generateResult =
                "<|tool_call_start|>[playback(action='pause')]<|tool_call_end|>"
        }
        val diagnostics = mutableListOf<RouterStageDiagnostic>()
        val router = createRouter(inference, monoMs = { now }).also {
            it.diagnosticSink = { diagnostics += it }
        }

        router.ensureReady().getOrThrow()
        now = 2_000L
        assertEquals(
            VoiceIntent.Playback.Pause,
            router.recognize(english("pause"), RECOGNITION_CONTEXT).intent,
        )
        val diagnostic = diagnostics.single()
        assertEquals(
            mapOf(
                RouterStageDiagnostic.STAGE_TOKENIZE to 20L, // two tokenize calls @10
                RouterStageDiagnostic.STAGE_CLASSIFY to 5L,
                RouterStageDiagnostic.STAGE_GENERATE to 7L,
                RouterStageDiagnostic.STAGE_PARSE_REPAIR to 0L,
                RouterStageDiagnostic.STAGE_MAPPER_DIALOG to 0L,
            ),
            diagnostic.stageLatencyMs,
        )
        assertTrue(diagnostic.stageLatencyMs.keys.all { it in RouterStageDiagnostic.STAGE_LATENCY_KEYS })
        assertEquals(32L, diagnostic.totalLatencyMs)
    }

    @Test
    fun noMatch_recordsOnlyTokenizeAndClassifyLatency() = runTest {
        var now = 100L
        val inference = FakeLfmInference(onStep = { now += it }).apply {
            classifyLabel = "no_match:"
        }
        val diagnostics = mutableListOf<RouterStageDiagnostic>()
        val router = createRouter(inference, monoMs = { now }).also {
            it.diagnosticSink = { diagnostics += it }
        }

        router.ensureReady().getOrThrow()
        now = 500L
        assertNull(router.recognize(english("hello"), RECOGNITION_CONTEXT).intent)
        val diagnostic = diagnostics.single()
        assertEquals(
            mapOf(
                RouterStageDiagnostic.STAGE_TOKENIZE to 20L,
                RouterStageDiagnostic.STAGE_CLASSIFY to 5L,
            ),
            diagnostic.stageLatencyMs,
        )
        assertEquals(25L, diagnostic.totalLatencyMs)
    }

    @Test
    fun pauseCommand_mapsToPlaybackPause() = runTest {
        val inference = FakeLfmInference().apply {
            classifyLabel = "playback:pause"
            generateResult =
                "<|tool_call_start|>[playback(action='pause')]<|tool_call_end|>"
        }
        val router = createRouter(inference)

        router.ensureReady().getOrThrow()
        assertEquals(
            VoiceIntent.Playback.Pause,
            router.recognize(english("pause"), RECOGNITION_CONTEXT).intent,
        )
    }

    @Test
    fun seekRelative_mapsDeltaSecondsThroughSlotRepair() = runTest {
        val inference = FakeLfmInference().apply {
            classifyLabel = "playback:seek_relative"
            generateResult =
                "<|tool_call_start|>[playback(action='seek_relative', minutes=1)]<|tool_call_end|>"
        }
        val router = createRouter(inference)

        router.ensureReady().getOrThrow()
        assertEquals(
            VoiceIntent.Playback.SeekRelative(-60_000),
            router.recognize(english("go back a minute"), RECOGNITION_CONTEXT).intent,
        )
    }

    private fun createRouter(
        inference: FakeLfmInference,
        routerInputFormat: String? = null,
        includeFormatField: Boolean = true,
        monoMs: () -> Long = { System.currentTimeMillis() },
    ): LfmIntentRouter {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = ModelManager(context).apply {
            filesDir = tempDir.root
        }
        seedLfmAssets(
            manager,
            routerInputFormat = routerInputFormat,
            includeFormatField = includeFormatField,
        )
        return LfmIntentRouter(
            dialogManager = VoiceDialogManager(ToolCallMapper()),
            modelManager = manager,
            inference = inference,
            monoMs = monoMs,
        )
    }

    private fun seedLfmAssets(
        manager: ModelManager,
        routerInputFormat: String? = null,
        includeFormatField: Boolean = true,
        quant: String? = "q8_0",
    ) {
        val modelDir = File(manager.filesDir, "function-call").apply { mkdirs() }
        File(modelDir, "model.gguf").writeText("gguf")
        File(modelDir, "classifier.bin").writeText("cls")
        File(modelDir, "label_map.json").writeText("""{"labels":["playback:pause"]}""")
        val formatLine = when {
            !includeFormatField -> ""
            routerInputFormat == null -> """"router_input_format": "english_v1","""
            else -> """"router_input_format": "$routerInputFormat","""
        }
        val quantLine = quant?.let { """"quant": "$it",""" } ?: ""
        File(modelDir, "manifest.json").writeText(
            """
            {
              "version": "2026-06-21-143005",
              $quantLine
              $formatLine
              "assets": {
                "model.gguf": {
                  "bytes": 4,
                  "sha256": "${sha256("gguf")}",
                  "url": "https://example.test/model.gguf"
                },
                "classifier.bin": {
                  "bytes": 3,
                  "sha256": "${sha256("cls")}",
                  "url": "https://example.test/classifier.bin"
                },
                "label_map.json": {
                  "bytes": ${"""{"labels":["playback:pause"]}""".length},
                  "sha256": "${sha256("""{"labels":["playback:pause"]}""")}",
                  "url": "https://example.test/label_map.json"
                }
              }
            }
            """.trimIndent(),
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun english(transcript: String) = IntentRoutingInput.english(transcript)

    private companion object {
        val RECOGNITION_CONTEXT = VoiceRecognitionContext(
            listeningMode = ListeningMode.Continuous,
            micExposure = MicExposure.Exposed,
        )
    }
}

internal class FakeLfmInference(
    private val onStep: (Long) -> Unit = {},
) : LfmInference {
    var loadResult = true
    var lastErrorMessage = ""
    var classifyLabel: String? = "playback:pause"
    var generateResult: String? =
        "<|tool_call_start|>[playback(action='pause')]<|tool_call_end|>"
    var tokenizeThrows: Throwable? = null
    var generateThrows: Throwable? = null
    var resetThrows: Throwable? = null
    var classifyCount = 0
    var resetCount = 0
    val tokenizedTexts = mutableListOf<String>()

    override fun lastError(): String = lastErrorMessage

    override fun load(
        modelPath: String,
        classifierPath: String,
        labelMapPath: String,
        nCtx: Int,
    ): Boolean = loadResult

    override fun tokenize(text: String, addBos: Boolean): IntArray? {
        tokenizedTexts += text
        onStep(10)
        tokenizeThrows?.let { throw it }
        return when {
            text == "pause" || text == "go back a minute" || text == "Go back to 3 minutes." ||
                text == "hello" || text == "rename my bookmark" -> intArrayOf(10)

            else -> intArrayOf(1, 10, 2)
        }
    }

    override fun classify(promptTokenIds: IntArray, poolStart: Int, poolEnd: Int): String? {
        onStep(5)
        classifyCount++
        return classifyLabel
    }

    override fun generate(prefill: String, nPredict: Int): String? {
        onStep(7)
        generateThrows?.let { throw it }
        return generateResult
    }

    override fun reset() {
        resetCount++
        resetThrows?.let { throw it }
    }

    override fun release() = Unit
}
