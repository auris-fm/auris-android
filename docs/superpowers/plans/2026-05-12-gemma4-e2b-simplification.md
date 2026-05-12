# Gemma 4 E2B Simplification Implementation Plan

> **Goal:** Replace Vosk ASR + DeterministicVoiceIntentInterpreter with Gemma 4 E2B single-pass audio-to-intent via LiteRT-LM. Collapse the two-stage pipeline into one model inference, simplify the interface, and remove unused code.

**Architecture Change:**
```
Before: Audio → Segmenter → Vosk ASR → text → DeterministicIntentInterpreter → VoicePlaybackIntent → Executor
After:  Audio → Segmenter → Gemma 4 E2B (structured JSON) → VoicePlaybackIntent → Executor
```

**Removed files:**
- `VoskVoiceRecognizer.kt`
- `VoiceIntentInterpreter.kt`
- `DeterministicVoiceIntentInterpreter.kt`
- `VoiceRecognitionResult.kt`
- `NoOpVoiceRecognizer` (in `VoiceRecognizer.kt`)

**Modified files:**
- `VoiceRecognizer.kt` — interface returns `VoicePlaybackIntent?` directly
- `Gemma4VoiceRecognizer.kt` — real LiteRT-LM inference + JSON parsing
- `VoiceControlModule.kt` — remove interpreter binding, swap recognizer bind
- `VoiceControlService.kt` — remove interpreter chaining, simplify utterance processing
- `VoiceModelManager.kt` — promote Gemma 4 to primary, remove Vosk code
- `build.gradle.kts` — remove `libs.vosk.android`, keep `libs.litertlm.android`

**Removed tests:**
- `DeterministicVoiceIntentInterpreterTest.kt`

**Preserved unchanged:**
- `VoicePlaybackIntent` sealed interface
- `VoicePlaybackIntentExecutor` + `VoicePlaybackSink`
- `VoiceUtteranceClip`, `VoiceRecognitionContext`
- Gate, route, segmenter, audio capture, service lifecycle
- All existing gate/route/segmenter tests

---

## Task 1: Update VoiceRecognizer interface

**Files:**
- Modify: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/model/VoiceRecognizer.kt`

Change return type from `VoiceRecognitionResult?` to `VoicePlaybackIntent?`, remove `NoOpVoiceRecognizer`, remove unused `PcmAudioFrame` import.

- [ ] **Step 1: Write a test for the new interface behaviors**

Create/update a test for Gemma4VoiceRecognizer (exists as placeholder test).

- [ ] **Step 2: Update the interface**

```kotlin
interface VoiceRecognizer {
    suspend fun ensureReady(): Result<Unit>
    suspend fun recognize(clip: VoiceUtteranceClip, context: VoiceRecognitionContext): VoicePlaybackIntent?
}
```

Remove `NoOpVoiceRecognizer` and stale imports.

- [ ] **Step 3: Compile check**

```bash
./gradlew :modules:services:voice:compileDebugKotlin
```

Expected: fail because Gemma4VoiceRecognizer still returns VoiceRecognitionResult.

- [ ] **Step 4: Commit**

```bash
git add modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/model/VoiceRecognizer.kt
git commit -m "Update VoiceRecognizer interface to return VoicePlaybackIntent directly"
```

## Task 2: Implement Gemma4VoiceRecognizer

**Files:**
- Modify: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/model/Gemma4VoiceRecognizer.kt`

- [ ] **Step 1: Write test**

- [ ] **Step 2: Implement the recognizer**

The recognizer:
1. Loads the Gemma 4 E2B LiteRT-LM model from `VoiceModelManager`.
2. On `recognize()`, converts PCM frames to a WAV byte array (16-bit mono 16 kHz).
3. Passes audio to LiteRT-LM via `Content.AudioBytes()`.
4. Parses the model's structured JSON response into `VoicePlaybackIntent`.
5. Returns `null` on malformed JSON, unknown intents, or low confidence.

Output format expected from the model:
```json
{"intent": "pause"}
{"intent": "resume"}
{"intent": "seek_relative", "delta_seconds": 30}
{"intent": "seek_absolute", "position_seconds": 120}
{"intent": "next_chapter"}
{"intent": "previous_chapter"}
{"intent": "chapter_by_index", "index": 3}
{"intent": "chapter_by_title", "query": "introduction"}
{"intent": "set_speed", "speed": 1.5}
```

- [ ] **Step 3: Compile**

```bash
./gradlew :modules:services:voice:compileDebugKotlin
```

Expected: pass.

- [ ] **Step 4: Commit**

```bash
git add modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/model/Gemma4VoiceRecognizer.kt
git commit -m "Implement Gemma4VoiceRecognizer with LiteRT-LM audio-to-intent"
```

## Task 3: Remove VoiceIntentInterpreter and DeterministicVoiceIntentInterpreter

**Files:**
- Delete: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/intent/VoiceIntentInterpreter.kt`
- Delete: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/intent/DeterministicVoiceIntentInterpreter.kt`
- Delete: `modules/services/voice/src/test/kotlin/au/com/shiftyjelly/pocketcasts/voice/intent/DeterministicVoiceIntentInterpreterTest.kt`
- Delete: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/intent/VoiceRecognitionResult.kt`

- [ ] **Step 1: Remove the interpreter interface, implementation, test, and VoiceRecognitionResult**

- [ ] **Step 2: Compile**

```bash
./gradlew :modules:services:voice:compileDebugKotlin
```

Expected: fail because `VoiceControlModule` and `VoiceControlService` still reference these types.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "Remove VoiceIntentInterpreter, DeterministicVoiceIntentInterpreter, VoiceRecognitionResult"
```

## Task 4: Remove VoskVoiceRecognizer and Vosk dependency

**Files:**
- Delete: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/model/VoskVoiceRecognizer.kt`
- Modify: `modules/services/voice/build.gradle.kts` — remove `implementation(libs.vosk.android)`

- [ ] **Step 1: Remove VoskVoiceRecognizer**

- [ ] **Step 2: Update build.gradle.kts**

Remove the Vosk dependency line.

- [ ] **Step 3: Compile**

```bash
./gradlew :modules:services:voice:compileDebugKotlin
```

Expected: pass (recognizer bindings updated in Task 5).

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "Remove VoskVoiceRecognizer and Vosk library dependency"
```

## Task 5: Update VoiceControlModule bindings

**Files:**
- Modify: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/di/VoiceControlModule.kt`

- [ ] **Step 1: Update bindings**

- Remove `DeterministicVoiceIntentInterpreter` import and binding.
- Remove `VoskVoiceRecognizer` import.
- Update comment for `bindVoiceRecognizer` to reference Gemma 4 E2B only.
- Keep all other bindings unchanged.

- [ ] **Step 2: Compile**

```bash
./gradlew :modules:services:voice:compileDebugKotlin
```

Expected: pass.

- [ ] **Step 3: Commit**

```bash
git add modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/di/VoiceControlModule.kt
git commit -m "Update DI bindings for Gemma 4 E2B-only recognizer"
```

## Task 6: Simplify VoiceControlService

**Files:**
- Modify: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/service/VoiceControlService.kt`

- [ ] **Step 1: Remove interpreter dependency and simplify pipeline**

- Remove `@Inject lateinit var voiceIntentInterpreter: VoiceIntentInterpreter`
- Remove `VoiceIntentInterpreter` import.
- Replace two-step `recognize()` → `interpret()` chain with single `recognize()` call.
- Clean up unused imports.

```kotlin
// Before:
val result = voiceRecognizer.recognize(clip, recognitionContext)
if (result != null) {
    handleCommand(clip, result)
}

// handleCommand does debounce then:
val intent = voiceIntentInterpreter.interpret(result)
if (intent != null) {
    voicePlaybackIntentExecutor.execute(intent)
}

// After:
val intent = voiceRecognizer.recognize(clip, recognitionContext)
if (intent != null) {
    // debounce
    voicePlaybackIntentExecutor.execute(intent)
}
```

- [ ] **Step 2: Compile**

```bash
./gradlew :modules:services:voice:compileDebugKotlin
```

Expected: pass.

- [ ] **Step 3: Commit**

```bash
git add modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/service/VoiceControlService.kt
git commit -m "Simplify VoiceControlService: remove interpreter chaining"
```

## Task 7: Update VoiceModelManager

**Files:**
- Modify: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/model/VoiceModelManager.kt`

- [ ] **Step 1: Promote Gemma 4 to primary, remove Vosk code**

- Remove Vosk model URL, directory constants, download/extraction methods (`ensureModel()`, `getModelPath()`, `isModelReady()`, `downloadFile()`, `extractZip()`).
- Rename `ensureGemma4Model()` → `ensureModel()`, `getGemma4ModelPath()` → `getModelPath()`, `isGemma4ModelReady()` → `isModelReady()`.
- Update references in the class to use the renamed methods.

- [ ] **Step 2: Compile**

```bash
./gradlew :modules:services:voice:compileDebugKotlin
```

Expected: pass.

- [ ] **Step 3: Commit**

```bash
git add modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/model/VoiceModelManager.kt
git commit -m "Promote Gemma 4 E2B model management to primary, remove Vosk model code"
```

## Task 8: Full verification

**Files:**
- No source edits.

- [ ] **Step 1: Run all voice module tests**

```bash
./gradlew :modules:services:voice:testDebugUnitTest
```

Expected: all tests pass (interpreter tests removed, existing gate/route/segmenter/executor tests unchanged).

- [ ] **Step 2: Compile full app**

```bash
./gradlew :modules:services:voice:compileDebugKotlin :app:compileDebugKotlin
```

Expected: pass.

- [ ] **Step 3: Run spotless**

```bash
./gradlew spotlessApply
./gradlew spotlessCheck
```

Expected: pass.

- [ ] **Step 4: Commit fixes if any**
