package au.com.shiftyjelly.pocketcasts.settings.voice

import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrBackend
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrCapabilities
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.AsrResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.ModelFile
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.ModelSpec
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.EnabledByUserCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlGateState
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlRuleState
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.BatteryOkCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.DeviceSupportedCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.ModelsReadyCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.NotCastingCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.NotOnCallCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.OtherAppPlayingCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.mode.ListeningMode
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.ModelDownloadState
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.MicExposure
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordDetector
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every row of the settings status table in `voice-control-core.md` > "Privacy and UX",
 * plus the precedence the table's "first matching row wins" ordering implies.
 */
class VoiceControlStatusTest {

    @Test
    fun `microphone permission missing wins over every other state`() {
        val gate = gateState(
            rules = mapOf(
                EnabledByUserCondition.ID to blocked(),
                ModelsReadyCondition.ID to blocked(),
                NotOnCallCondition.ID to blocked(),
            ),
            setupPassed = false,
            conflictsPassed = false,
            contextPassed = false,
        )

        assertEquals(
            "Blocked: microphone permission required",
            voiceControlStatus(ListeningMode.Continuous, gate, MicExposure.NoMic, permissionGranted = false),
        )
    }

    @Test
    fun `blocked enabled by user reads disabled in settings`() {
        val gate = gateState(
            rules = mapOf(EnabledByUserCondition.ID to blocked()),
            setupPassed = false,
        )

        assertEquals(
            "Disabled in settings",
            voiceControlStatus(ListeningMode.Continuous, gate, MicExposure.Isolated, permissionGranted = true),
        )
    }

    @Test
    fun `blocked models ready reads model download in progress`() {
        val gate = gateState(
            rules = mapOf(ModelsReadyCondition.ID to blocked()),
            setupPassed = false,
        )

        assertEquals(
            "Blocked: model download in progress",
            voiceControlStatus(ListeningMode.WakeWord, gate, MicExposure.Isolated, permissionGranted = true),
        )
    }

    @Test
    fun `still-loading models ready reads model download in progress`() {
        val gate = gateState(
            rules = mapOf(ModelsReadyCondition.ID to unknown()),
            setupPassed = false,
        )

        assertEquals(
            "Blocked: model download in progress",
            voiceControlStatus(ListeningMode.WakeWord, gate, MicExposure.Isolated, permissionGranted = true),
        )
    }

    @Test
    fun `another blocked setup condition reads blocked setup`() {
        val gate = gateState(
            rules = mapOf(DeviceSupportedCondition.ID to blocked()),
            setupPassed = false,
        )

        assertEquals(
            "Blocked: setup",
            voiceControlStatus(ListeningMode.WakeWord, gate, MicExposure.Isolated, permissionGranted = true),
        )
    }

    @Test
    fun `blocked not on call reads phone call in progress`() {
        val gate = gateState(
            rules = mapOf(NotOnCallCondition.ID to blocked()),
            conflictsPassed = false,
        )

        assertEquals(
            "Blocked: phone call in progress",
            voiceControlStatus(ListeningMode.WakeWord, gate, MicExposure.Isolated, permissionGranted = true),
        )
    }

    @Test
    fun `blocked not casting reads casting active`() {
        val gate = gateState(
            rules = mapOf(NotCastingCondition.ID to blocked()),
            conflictsPassed = false,
        )

        assertEquals(
            "Blocked: casting active",
            voiceControlStatus(ListeningMode.WakeWord, gate, MicExposure.Isolated, permissionGranted = true),
        )
    }

    @Test
    fun `blocked battery ok reads battery saver`() {
        val gate = gateState(
            rules = mapOf(BatteryOkCondition.ID to blocked()),
            conflictsPassed = false,
        )

        assertEquals(
            "Blocked: battery saver",
            voiceControlStatus(ListeningMode.WakeWord, gate, MicExposure.Isolated, permissionGranted = true),
        )
    }

    @Test
    fun `blocked other app playing reads another app is playing audio`() {
        val gate = gateState(
            rules = mapOf(OtherAppPlayingCondition.ID to blocked()),
            conflictsPassed = false,
        )

        assertEquals(
            "Blocked: another app is playing audio",
            voiceControlStatus(ListeningMode.WakeWord, gate, MicExposure.Isolated, permissionGranted = true),
        )
    }

    @Test
    fun `no listening context reads inactive`() {
        val gate = gateState(contextPassed = false)

        assertEquals(
            "Inactive: no playback or foreground context",
            voiceControlStatus(ListeningMode.WakeWord, gate, MicExposure.Isolated, permissionGranted = true),
        )
    }

    @Test
    fun `no microphone on route reads no microphone`() {
        val gate = gateState()

        assertEquals(
            "No microphone on this route",
            voiceControlStatus(ListeningMode.Off, gate, MicExposure.NoMic, permissionGranted = true),
        )
    }

    @Test
    fun `built-in microphone reads headset required`() {
        val gate = gateState()

        assertEquals(
            "Built-in microphone; a headset or earbud microphone is required",
            voiceControlStatus(ListeningMode.Off, gate, MicExposure.Exposed, permissionGranted = true),
        )
    }

    @Test
    fun `continuous mode reads active grace period`() {
        val gate = gateState()

        assertEquals(
            "Active (grace period)",
            voiceControlStatus(ListeningMode.Continuous, gate, MicExposure.Isolated, permissionGranted = true),
        )
    }

    @Test
    fun `wake word mode reads active wake word required`() {
        val gate = gateState()

        assertEquals(
            "Active (wake word required)",
            voiceControlStatus(ListeningMode.WakeWord, gate, MicExposure.Isolated, permissionGranted = true),
        )
    }

    @Test
    fun `otherwise reads off`() {
        val gate = gateState()

        assertEquals(
            "Off",
            voiceControlStatus(ListeningMode.Off, gate, MicExposure.Isolated, permissionGranted = true),
        )
    }

    @Test
    fun `blocked setup beats a blocked conflict`() {
        val gate = gateState(
            rules = mapOf(
                EnabledByUserCondition.ID to blocked(),
                NotOnCallCondition.ID to blocked(),
            ),
            setupPassed = false,
            conflictsPassed = false,
        )

        assertEquals(
            "Disabled in settings",
            voiceControlStatus(ListeningMode.WakeWord, gate, MicExposure.Isolated, permissionGranted = true),
        )
    }

    @Test
    fun `blocked models beats a blocked conflict`() {
        val gate = gateState(
            rules = mapOf(
                ModelsReadyCondition.ID to blocked(),
                NotCastingCondition.ID to blocked(),
            ),
            setupPassed = false,
            conflictsPassed = false,
        )

        assertEquals(
            "Blocked: model download in progress",
            voiceControlStatus(ListeningMode.WakeWord, gate, MicExposure.Isolated, permissionGranted = true),
        )
    }

    @Test
    fun `blocked conflict beats the missing-microphone row`() {
        val gate = gateState(
            rules = mapOf(NotOnCallCondition.ID to blocked()),
            conflictsPassed = false,
        )

        assertEquals(
            "Blocked: phone call in progress",
            voiceControlStatus(ListeningMode.WakeWord, gate, MicExposure.NoMic, permissionGranted = true),
        )
    }

    @Test
    fun `built-in microphone row needs an allowed context`() {
        val gate = gateState(contextPassed = false)

        assertEquals(
            "Inactive: no playback or foreground context",
            voiceControlStatus(ListeningMode.Off, gate, MicExposure.Exposed, permissionGranted = true),
        )
    }

    @Test
    fun `an eligible route that is not capturing reads as off, not active`() {
        val gate = gateState()

        assertEquals(
            "Off",
            voiceControlStatus(
                ListeningMode.WakeWord,
                gate,
                MicExposure.Isolated,
                permissionGranted = true,
                capturing = false,
            ),
        )
    }

    private fun gateState(
        rules: Map<String, VoiceControlRuleState> = emptyMap(),
        setupPassed: Boolean = true,
        conflictsPassed: Boolean = true,
        contextPassed: Boolean = true,
    ) = VoiceControlGateState(
        allowed = setupPassed && conflictsPassed && contextPassed,
        rules = rules,
        setupPassed = setupPassed,
        conflictsPassed = conflictsPassed,
        contextPassed = contextPassed,
    )

    private fun blocked() = VoiceControlRuleState.Blocked("blocked")

    private fun unknown() = VoiceControlRuleState.Unknown("unknown")
}

/**
 * The model rows must name the component they measure. The wake word runs from a bundled detector
 * model; speech recognition is SenseVoice or Canary Flash depending on locale. A single
 * "Speech Model" row that reported a different component's state told the reader the wake word was
 * unready while it was working (owner-observed on the Pixel 8).
 */
class VoiceControlModelRowsTest {

    @Test
    fun `wake word readiness comes from the detector, not from a bundled-asset assumption`() {
        assertEquals(
            "Ready",
            wakeWordModelText(detectorReady = true),
        )
        assertEquals(
            "Unavailable",
            wakeWordModelText(detectorReady = false),
        )
    }

    @Test
    fun `the labels separate the wake word model from the recognition model`() {
        assertEquals("Wake Word Model", WAKE_WORD_MODEL_LABEL)
        assertEquals("Speech Recognition Model", RECOGNITION_MODEL_LABEL)
    }

    /**
     * Reviewer correction (cec2f3be): the row must report the SELECTED transcription backend, not
     * The row must report the SELECTED transcription backend. `AsrBackendSelector` chooses
     * SenseVoice or Canary Flash by locale, so a row keyed to anything else says the wrong
     * component is unready while the real backend is fine.
     */
    @Test
    fun `the recognition row names the selected backend and reports that backend's readiness`() {
        assertEquals(
            "SenseVoice ready",
            recognitionModelText(
                backend = RecognitionBackend.SenseVoice,
                backendReady = true,
            ),
        )
        assertEquals(
            "SenseVoice model missing",
            recognitionModelText(
                backend = RecognitionBackend.SenseVoice,
                backendReady = false,
            ),
        )
        assertEquals(
            "Canary Flash ready",
            recognitionModelText(
                backend = RecognitionBackend.CanaryFlash,
                backendReady = true,
            ),
        )
    }

    @Test
    fun `an unsupported locale says so instead of reporting an unselected model`() {
        assertEquals(
            "Not available for this language",
            recognitionModelText(
                backend = null,
                backendReady = false,
            ),
        )
    }

    private class FakeBackend(override val requiredModel: ModelSpec) : AsrBackend {
        override suspend fun ensureReady(): Result<Unit> = Result.success(Unit)
        override suspend fun transcribe(samples: FloatArray, sampleRateHz: Int): AsrResult = AsrResult(text = "")
        override val capabilities: AsrCapabilities = AsrCapabilities(
            supportedLanguages = setOf("en"),
            canTranslateToEnglish = false,
            requiresSnapdragon = false,
        )
        override fun release() = Unit
    }

    private fun spec(dir: String) = ModelSpec(
        files = listOf(ModelFile(url = "https://example/$dir.ort", filename = "$dir.ort")),
        targetDir = dir,
    )

    @Test
    fun `the row queries the selected backend's own required model`() {
        val backend = FakeBackend(spec("sensevoice-model"))
        val queried = mutableListOf<ModelSpec>()
        recognitionRowsFor(
            selectedBackend = backend,
            isModelReady = { s ->
                queried += s
                false
            },
            downloadState = ModelDownloadState.NotStarted,
        )
        assertEquals(listOf(backend.requiredModel), queried)
    }

    @Test
    fun `a preinstalled working backend is ready even though the download flow is NotStarted`() {
        val rows = recognitionRowsFor(
            selectedBackend = FakeBackend(spec("sensevoice-model")),
            isModelReady = { true },
            downloadState = ModelDownloadState.NotStarted,
        )
        assertEquals("SenseVoice ready", rows.recognitionText)
    }

    @Test
    fun `another model starting a download does not make the selected backend ready`() {
        val rows = recognitionRowsFor(
            selectedBackend = FakeBackend(spec("sensevoice-model")),
            isModelReady = { false },
            downloadState = ModelDownloadState.Ready,
        )
        assertEquals("SenseVoice model missing", rows.recognitionText)
    }

    @Test
    fun `wake word readiness is read off the main thread`() {
        // Claude review 4374362889: the detector's construction loads ~3.4MB of ONNX and creates
        // native sessions, so reading it during composition stalled the settings screen. Records the
        // thread the READ happens on -- the observable that decides whether the screen stalls.
        var readOn: String? = null
        val detector = object : WakeWordDetector {
            override val isReady: Boolean
                get() {
                    readOn = Thread.currentThread().name
                    return true
                }

            override suspend fun detect(segment: FloatArray, sampleRateHz: Int, speechOnsetSample: Int) = WakeWordResult(detected = false)

            override fun release() = Unit
        }
        var providedOn: Thread? = null
        val caller = Thread.currentThread()
        val provider = javax.inject.Provider<WakeWordDetector> {
            providedOn = Thread.currentThread()
            detector
        }
        val ready = kotlinx.coroutines.runBlocking { readWakeWordReadiness(provider) }
        assertTrue("detector construction must run off the caller thread", providedOn != caller)
        assertTrue("premise: readiness was read", ready)
        assertNotNull("premise: the getter ran", readOn)
        // Dispatchers.IO names its threads "DefaultDispatcher-worker-*". Anything else means the
        // read ran on the caller's thread, which is the stall this control exists to prevent.
        assertTrue(
            "readiness must be read on an IO worker, was: $readOn",
            readOn!!.contains("DefaultDispatcher-worker"),
        )
    }

    @Test
    fun `the rendered row shows a download in flight, not just the computed field`() {
        // Claude review 4374362768: the progress text was computed and asserted, but the row
        // rendered recognitionText only, so a downloading model looked identical to a missing one.
        // Assert the RENDERED choice, which is what the earlier test did not cover.
        val rows = RecognitionRows(
            recognitionText = "SenseVoice model missing",
            recognitionProgressText = "Downloading: 37%",
        )
        assertEquals("Downloading: 37%", recognitionRowSecondaryText(rows))
    }

    @Test
    fun `the rendered row falls back to readiness when nothing is downloading`() {
        val rows = RecognitionRows(
            recognitionText = "SenseVoice ready",
            recognitionProgressText = "",
        )
        assertEquals("SenseVoice ready", recognitionRowSecondaryText(rows))
    }

    @Test
    fun `a downloading selected backend reports its own progress and not readiness`() {
        val rows = recognitionRowsFor(
            selectedBackend = FakeBackend(spec("sensevoice-model")),
            isModelReady = { false },
            downloadState = ModelDownloadState.Downloading(37),
        )
        assertEquals("SenseVoice model missing", rows.recognitionText)
        assertEquals("Downloading: 37%", rows.recognitionProgressText)
    }

    @Test
    fun `an unsupported locale is not blamed on a missing model`() {
        val rows = recognitionRowsFor(
            selectedBackend = null,
            isModelReady = { error("no spec should be queried when no backend is selected") },
            downloadState = ModelDownloadState.NotStarted,
        )
        assertEquals("Not available for this language", rows.recognitionText)
    }
}
