# ASR Intent Pipeline — Implementation Plan

> **Spec:** [asr-intent-pipeline spec](../specs/asr-intent-pipeline.md) — architecture, component design, language coverage, error handling.

> **For agentic workers:** Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate Moonshine Voice for audio capture + VAD + ASR, add UtteranceFilter for speaker consistency and playback bleed rejection, and wire SmolLM2 for structured intent parsing via llama.cpp.

**Tech Stack:** Moonshine Voice (`ai.moonshine:moonshine-voice`), llama.cpp (only remaining native dep), JNI, Hilt DI

---

### Task 1: Add Moonshine dependency and native build

**Files:**
- `gradle/libs.versions.toml` — add `moonshine-voice` version entry
- `modules/services/voice/build.gradle.kts` — add `ai.moonshine:moonshine-voice`
- `modules/services/voice/src/main/cpp/CMakeLists.txt` — fetch and build llama.cpp, link LmJni

- [ ] **Step 1: Update version catalog and build.gradle.kts**

```toml
# libs.versions.toml — add under [versions] and [libraries]
moonshine-voice = "0.0.61"
moonshine-voice = { module = "ai.moonshine:moonshine-voice", version.ref = "moonshine-voice" }
```

```kotlin
// build.gradle.kts
dependencies {
    implementation(libs.moonshine.voice)
}
```

- [ ] **Step 2: Set up CMake for llama.cpp**

CMakeLists.txt fetches llama.cpp via FetchContent and builds a shared library with LmJni for SmolLM2 inference. Moonshine handles audio capture, VAD, and ASR — no FetchContent needed for Oboe or whisper.cpp.

---

### Task 2: Create MoonshineVoiceEngine wrapper

**Files:**
- Create: `modules/services/voice/src/main/kotlin/.../voicecontrol/engine/MoonshineVoiceEngine.kt`
- Create: `modules/services/voice/src/main/kotlin/.../voicecontrol/engine/UtteranceFilter.kt`

- [ ] **Step 1: Create `UtteranceFilter`**

Two checks run in sequence on each utterance before it reaches SmolLM2:

```kotlin
@Singleton
class UtteranceFilter @Inject constructor(
    private val playbackCorrelator: PlaybackCrossCorrelator,
    private val audioRouteMonitor: AudioRouteMonitor,
) {
    private var sessionTargetSpeaker: Int? = null

    fun reset() { sessionTargetSpeaker = null }

    /**
     * Returns true if the utterance passes all filters and should be processed.
     * [audio] is the raw float PCM from Moonshine's TranscriptLine.audioData.
     * [speakerIndex] is Moonshine's TranscriptLine.speakerIndex (only valid when hasSpeakerId is true).
     */
    fun shouldProcess(
        audio: FloatArray,
        hasSpeakerId: Boolean,
        speakerIndex: Int,
        playbackBuffer: FloatArray,
    ): Boolean {
        // 1. Speaker consistency (Moonshine diarization via identify_speakers)
        if (hasSpeakerId) {
            if (sessionTargetSpeaker == null) {
                sessionTargetSpeaker = speakerIndex
            } else if (speakerIndex != sessionTargetSpeaker) {
                return false // different speaker
            }
        }

        // 2. Playback bleed check — only when using speaker/A2DP
        if (audioRouteMonitor.currentRoute !is Headset) {
            if (playbackCorrelator.isPlaybackBleed(audio, playbackBuffer)) {
                return false
            }
        }

        return true
    }
}
```

- [ ] **Step 2: Create `PlaybackCrossCorrelator`**

```kotlin
@Singleton
class PlaybackCrossCorrelator @Inject constructor() {
    fun isPlaybackBleed(
        micAudio: FloatArray,
        playbackBuffer: FloatArray,
    ): Boolean {
        if (playbackBuffer.size < micAudio.size) return false

        var maxCorrelation = 0.0
        val minDelay = (0.050 * 16000).toInt()
        val maxDelay = (0.500 * 16000).toInt()

        for (offset in minDelay..maxDelay) {
            val correlation = normalizedCrossCorrelation(micAudio, playbackBuffer, offset)
            if (correlation > maxCorrelation) maxCorrelation = correlation
        }

        return maxCorrelation > BLEED_THRESHOLD
    }

    companion object {
        private const val BLEED_THRESHOLD = 0.3 // TBD via testing
    }
}
```

- [ ] **Step 3: Create `MoonshineVoiceEngine`**

Wraps Moonshine `MicTranscriber` and wires it to `UtteranceFilter`.
Moonshine audio is `float[]` (native PCM), model architecture is an `int` constant.

```kotlin
import ai.moonshine.voice.MicTranscriber
import ai.moonshine.voice.TranscriptEvent
import ai.moonshine.voice.TranscriptEventListener
import java.util.function.Consumer

@Singleton
class MoonshineVoiceEngine @Inject constructor(
    private val utteranceFilter: UtteranceFilter,
    private val intentParser: SmolLmIntentParser,
) {
    private var micTranscriber: MicTranscriber? = null
    private var playbackBufferProvider: (() -> FloatArray)? = null

    fun start(
        modelPath: String,
        modelArch: Int,  // e.g. MOONSHINE_MODEL_ARCH_SMALL_STREAMING
        playbackBufferProvider: () -> FloatArray,
        onIntent: (VoicePlaybackIntent) -> Unit,
    ) {
        this.playbackBufferProvider = playbackBufferProvider
        utteranceFilter.reset()

        micTranscriber = MicTranscriber().apply {
            loadFromFiles(modelPath, modelArch)
            addListener(Consumer<TranscriptEvent> { event ->
                if (event is TranscriptEvent.LineCompleted) {
                    val line = event.line
                    processUtterance(
                        text = line.text,
                        audio = line.audioData ?: FloatArray(0),
                        hasSpeakerId = line.hasSpeakerId,
                        speakerIndex = line.speakerIndex,
                        onIntent = onIntent,
                    )
                }
            })
            start()
        }
    }

    private fun processUtterance(
        text: String,
        audio: FloatArray,
        hasSpeakerId: Boolean,
        speakerIndex: Int,
        onIntent: (VoicePlaybackIntent) -> Unit,
    ) {
        if (text.isBlank()) return

        val playbackBuffer = playbackBufferProvider?.invoke() ?: FloatArray(0)
        if (!utteranceFilter.shouldProcess(audio, hasSpeakerId, speakerIndex, playbackBuffer)) return

        val intent = intentParser.parseIntent(text, /* context */)
        if (intent != null) onIntent(intent)
    }

    fun stop() {
        micTranscriber?.stop()
        micTranscriber = null
    }
}
```

---

### Task 3: Wire MoonshineVoiceEngine into the service

**Files to modify:**
- `VoiceControlModule.kt` — add `MoonshineVoiceEngine` and `UtteranceFilter` bindings
- `VoiceControlService.kt` — integrate `MoonshineVoiceEngine`
- `VoiceControlNotificationManager.kt` — add downloading/listening notification types

- [ ] **Step 1: Update `VoiceControlModule.kt`**

```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class VoiceControlModule {
    @Binds abstract fun bindVoiceRecognizer(impl: SmolLmIntentParser): VoiceRecognizer
    @Binds abstract fun bindVoicePlaybackSink(...): VoicePlaybackSink
    @Binds abstract fun bindAudioRouteMonitor(...): AudioRouteMonitor
}
```

`MoonshineVoiceEngine` and `UtteranceFilter` are `@Singleton` with `@Inject` constructors — Hilt auto-discovers them.

- [ ] **Step 2: Integrate into `VoiceControlService.kt`**

The service starts `MoonshineVoiceEngine` when the gate allows, feeding it the Moonshine model path and a playback buffer provider. On transcript events, `MoonshineVoiceEngine` runs `UtteranceFilter` checks then passes text to `SmolLmIntentParser`.

- [ ] **Step 3: Update notification manager**

Add `DOWNLOADING` (model download in progress) and `LISTENING` (voice control active) notification types.

---

### Task 4: Wire playback buffer for cross-correlation

**Files:**
- Modify: `PlaybackManagerVoicePlaybackSink` or create a new `PlaybackBufferRecorder`

The cross-correlation filter needs a rolling buffer of recently-played audio. The app already decodes and plays audio through `PlaybackManager` — tap into this to maintain a ring buffer of the last ~2 seconds of PCM data.

- [ ] **Step 1: Create `PlaybackBufferRecorder`**

```kotlin
@Singleton
class PlaybackBufferRecorder @Inject constructor() {
    private val buffer = CircularFloatArray(SAMPLE_RATE * BUFFER_DURATION_SECONDS)

    fun write(pcm: FloatArray) { buffer.write(pcm) }
    fun snapshot(): FloatArray = buffer.toArray()

    companion object {
        const val SAMPLE_RATE = 16000
        const val BUFFER_DURATION_SECONDS = 2
    }
}
```

- [ ] **Step 2: Feed playback audio into the recorder**

Identify the point in the audio pipeline where decoded PCM is handed to the audio sink (likely in or near `PlaybackManager`). Call `playbackBufferRecorder.write(pcm)` at that point. If the playback sample rate doesn't match 16kHz, resample.

---

### Task 5: Build and verify

- [ ] **Step 1: Compile**

```bash
./gradlew :modules:services:voice:assembleDebug
```

- [ ] **Step 2: Run unit tests**

```bash
./gradlew :modules:services:voice:testDebugUnitTest
```

- [ ] **Step 3: Verify CMake build**

CMakeLists.txt builds `pocketcasts_voice_capture` with llama.cpp + LmJni. Verify the shared library loads correctly.

- [ ] **Step 4: Spotless**

```bash
./gradlew spotlessApply
```

- [ ] **Step 5: Integration smoke test**

On a device, start playback, speak a voice command, verify the utterance is transcribed (Moonshine), parsed (SmolLM2), and executed. Verify that podcast audio playing during a command does not trigger false intents (cross-correlation working). Verify that a second person speaking does not trigger intents (speaker diarization gating).
