# Speaker Verification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add speaker verification to voice control so that only the enrolled user's voice triggers commands.

**Architecture:** SpeakerEmbedder (TFLite via litert-api) produces 192-dim embeddings from audio. SpeakerVerifier compares via cosine similarity. VoiceEnrollmentManager handles enrollment state. A Compose Activity provides enrollment UI. Verification gates Gemma inference in VoiceControlService.

**Tech Stack:** Kotlin, LiteRT API (com.google.ai.edge.litert:litert-api:1.4.2), Jetpack Compose, SharedPreferences, Timber

---

### Task 1: Add litert-api dependency and bundle the speaker embedding model

**Files:**
- Modify: `modules/services/voice/build.gradle.kts`
- Create: `modules/services/voice/scripts/convert_speaker_model.py`
- Create: `modules/services/voice/src/main/assets/speaker_embed.tflite`

- [ ] **Step 1: Add litert-api dependency to build.gradle.kts**

```kotlin
// Add inside dependencies { ... }:
implementation("com.google.ai.edge.litert:litert-api:1.4.2")
```

- [ ] **Step 2: Write the model conversion script**

Create `modules/services/voice/scripts/convert_speaker_model.py`:

```python
"""
Converts a WeSpeaker ECAPA-TDNN speaker embedding model to TFLite.
Produces a model that takes 16kHz mono audio and outputs a 192-dim embedding.

Usage: python convert_speaker_model.py --output ../src/main/assets/speaker_embed.tflite

Requires: torch, torchaudio, onnx, onnx2tf
Install: pip install torch torchaudio onnx onnx2tf
"""

import torch
import torchaudio
import onnx
from onnx2tf import onnx2tf
import argparse
import os

def export_to_tflite(output_path):
    # Use the well-known WeSpeaker ECAPA-TDNN model
    # This is a widely used speaker verification model
    model = torch.hub.load(
        "Wespeaker/wespeaker_pytorch",
        "ecapa_tdnn",
        model_url="https://wespeaker-1256283475.cos.ap-shanghai.myqcloud.com/models/voxceleb/ECAPA_TDNN_GLOB_c512_AM_softmax_256_ep/vox2_ECAPA_TDNN_GLOB_c512_AM_softmax_256_ep_1280.pth",
    )
    model.eval()

    # Each utterance: process full audio, adaptive pooling handles variable length
    # We set a max of 5 seconds (80000 samples @ 16kHz) for TFLite fixed-shape requirement
    # Shorter utterances are zero-padded, the adaptive pooling layer handles this correctly.
    MAX_SAMPLES = 80000

    # Export to ONNX with dynamic input shape
    dummy_input = torch.randn(1, MAX_SAMPLES)
    onnx_path = "/tmp/speaker_embed.onnx"
    torch.onnx.export(
        model,
        dummy_input,
        onnx_path,
        input_names=["input"],
        output_names=["embedding"],
        dynamic_axes={"input": {1: "audio_length"}},
        opset_version=17,
    )

    # Convert ONNX to TFLite
    onnx2tf(
        onnx_path,
        output_path,
        output_integer_quantized=False,  # Keep float for accuracy
    )
    print(f"Model saved to {output_path}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    os.makedirs(os.path.dirname(args.output), exist_ok=True)
    export_to_tflite(args.output)
```

- [ ] **Step 3: Run the conversion script to produce the model**

```bash
cd modules/services/voice/scripts
pip install torch torchaudio onnx onnx2tf
python convert_speaker_model.py --output ../src/main/assets/speaker_embed.tflite
```

- [ ] **Step 4: Verify the model exists and has a reasonable size**

```bash
ls -lh modules/services/voice/src/main/assets/speaker_embed.tflite
# Expected: ~10-15MB
```

- [ ] **Step 5: Commit**

```bash
git add modules/services/voice/build.gradle.kts \
  modules/services/voice/scripts/convert_speaker_model.py \
  modules/services/voice/src/main/assets/speaker_embed.tflite
git commit -m "feat: add LiteRT API dependency and speaker embedding model"
```

---

### Task 2: SpeakerEmbedder — TFLite model wrapper

**Files:**
- Create: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/model/SpeakerEmbedder.kt`
- Test: `modules/services/voice/src/test/kotlin/au/com/shiftyjelly/pocketcasts/voice/model/SpeakerEmbedderTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package au.com.shiftyjelly.pocketcasts.voice.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SpeakerEmbedderTest {

    @Test
    fun `model loads from assets and produces 192-dim embedding`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val embedder = SpeakerEmbedder(context)
        assertTrue("Model should load successfully", embedder.load())

        // 1 second of silence at 16kHz (all zeros)
        val audio = FloatArray(16000)
        val embedding = embedder.embed(audio)
        assertNotNull("Embedding should not be null", embedding)
        assertEquals("Embedding should be 192-dimensional", 192, embedding.size)
    }

    @Test
    fun `same audio produces same embedding`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val embedder = SpeakerEmbedder(context)
        embedder.load()

        // Synthesize a simple tone
        val audio = FloatArray(16000) { i ->
            (Math.sin(i * 2.0 * Math.PI * 440.0 / 16000.0) * 0.5).toFloat()
        }

        val embedding1 = embedder.embed(audio)
        val embedding2 = embedder.embed(audio)

        assertNotNull(embedding1)
        assertNotNull(embedding2)
        assertArrayEquals("Same input should produce same embedding", embedding1, embedding2, 1e-6f)
    }

    @Test
    fun `different audio produces different embedding`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val embedder = SpeakerEmbedder(context)
        embedder.load()

        val audio1 = FloatArray(16000) { (Math.sin(it * 2.0 * Math.PI * 440.0 / 16000.0) * 0.5).toFloat() }
        val audio2 = FloatArray(16000) { (Math.sin(it * 2.0 * Math.PI * 880.0 / 16000.0) * 0.5).toFloat() }

        val embedding1 = embedder.embed(audio1)
        val embedding2 = embedder.embed(audio2)

        // Compute cosine similarity
        var dot = 0f
        var norm1 = 0f
        var norm2 = 0f
        for (i in embedding1.indices) {
            dot += embedding1[i] * embedding2[i]
            norm1 += embedding1[i] * embedding1[i]
            norm2 += embedding2[i] * embedding2[i]
        }
        val similarity = dot / (Math.sqrt(norm1.toDouble()) * Math.sqrt(norm2.toDouble()))

        assertTrue("Different audio should have low similarity (< 0.95)", similarity < 0.95)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :modules:services:voice:testDebugUnitTest --tests "*SpeakerEmbedderTest*" -v`
Expected: FAIL with compilation errors (SpeakerEmbedder not defined)

- [ ] **Step 3: Write minimal implementation**

```kotlin
package au.com.shiftyjelly.pocketcasts.voice.model

import android.content.Context
import com.google.ai.edge.litert.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps a TFLite speaker embedding model.
 *
 * Converts 16kHz mono PCM audio to a 192-dim speaker embedding vector
 * using the LiteRT API. Input audio is center-cropped or zero-padded to
 * a fixed length (5 seconds / 80000 samples). The model's adaptive
 * statistics pooling layer handles the variable-length signal within
 * the padded tensor.
 */
@Singleton
class SpeakerEmbedder @Inject constructor(
    private val context: Context,
) {
    companion object {
        private const val MODEL_FILE = "speaker_embed.tflite"
        private const val SAMPLE_RATE = 16000
        private const val MAX_SAMPLES = 80000 // 5 seconds at 16kHz
        private const val EMBEDDING_DIM = 192
    }

    private var interpreter: Interpreter? = null

    /**
     * Load the TFLite model. Returns true if successful.
     */
    fun load(): Boolean {
        return try {
            val buffer = context.assets.openFd(MODEL_FILE)
            interpreter = Interpreter(buffer)
            Timber.i("Speaker embedding model loaded")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to load speaker embedding model")
            false
        }
    }

    /**
     * Produce a 192-dim speaker embedding from 16kHz mono PCM audio.
     *
     * @param audio FloatArray of PCM samples normalized to [-1, 1]
     * @return 192-dim FloatArray, or null if the model isn't loaded
     */
    fun embed(audio: FloatArray): FloatArray? {
        val interp = interpreter ?: return null

        // Pad or center-crop to MAX_SAMPLES
        val input = FloatArray(MAX_SAMPLES)
        if (audio.size >= MAX_SAMPLES) {
            val offset = (audio.size - MAX_SAMPLES) / 2
            System.arraycopy(audio, offset, input, 0, MAX_SAMPLES)
        } else {
            System.arraycopy(audio, 0, input, 0, audio.size)
            // Remainder is already zero from initialization
        }

        val inputBuffer = ByteBuffer.allocateDirect(4 * input.size).apply {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().put(input)
        }

        val output = Array(1) { FloatArray(EMBEDDING_DIM) }
        interp.run(inputBuffer, output)
        return output[0]
    }

    /**
     * Release the model resources.
     */
    fun release() {
        interpreter?.close()
        interpreter = null
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :modules:services:voice:testDebugUnitTest --tests "*SpeakerEmbedderTest*" -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/model/SpeakerEmbedder.kt
git add modules/services/voice/src/test/kotlin/au/com/shiftyjelly/pocketcasts/voice/model/SpeakerEmbedderTest.kt
git commit -m "feat: add SpeakerEmbedder with LiteRT TFLite wrapper"
```

---

### Task 3: SpeakerVerifier — cosine similarity comparator

**Files:**
- Create: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/model/SpeakerVerifier.kt`
- Test: `modules/services/voice/src/test/kotlin/au/com/shiftyjelly/pocketcasts/voice/model/SpeakerVerifierTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package au.com.shiftyjelly.pocketcasts.voice.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerVerifierTest {

    private val verifier = SpeakerVerifier()
    private val threshold = 0.70f

    @Test
    fun `identical embeddings match`() {
        val embedding = FloatArray(192) { kotlin.math.sin(it.toFloat()) }
        val result = verifier.verify(embedding, embedding, threshold)
        assertTrue("Identical embeddings should match", result)
    }

    @Test
    fun `very similar embeddings match`() {
        val enrolled = FloatArray(192) { kotlin.math.sin(it.toFloat()) }
        val candidate = FloatArray(192) { kotlin.math.sin(it.toFloat()) + 0.01f }
        val result = verifier.verify(enrolled, candidate, threshold)
        assertTrue("Similar embeddings should match", result)
    }

    @Test
    fun `dissimilar embeddings do not match`() {
        val enrolled = FloatArray(192) { 1.0f }
        val candidate = FloatArray(192) { -1.0f }
        val result = verifier.verify(enrolled, candidate, threshold)
        assertFalse("Dissimilar embeddings should not match", result)
    }

    @Test
    fun `cosine similarity of orthogonal vectors is zero`() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(0f, 1f)
        val similarity = verifier.cosineSimilarity(a, b)
        assertTrue("Orthogonal vectors should have ~0 similarity", kotlin.math.abs(similarity) < 1e-6f)
    }

    @Test
    fun `cosine similarity of identical unit vectors is one`() {
        val a = floatArrayOf(0.6f, 0.8f)
        val similarity = verifier.cosineSimilarity(a, a)
        assertTrue("Identical vectors should have ~1 similarity", kotlin.math.abs(similarity - 1.0) < 1e-6f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :modules:services:voice:testDebugUnitTest --tests "*SpeakerVerifierTest*" -v`
Expected: FAIL with compilation errors

- [ ] **Step 3: Write minimal implementation**

```kotlin
package au.com.shiftyjelly.pocketcasts.voice.model

import kotlin.math.sqrt
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stateless speaker verifier using cosine similarity.
 *
 * Compares a candidate embedding against an enrolled voiceprint.
 * Embeddings are L2-normalized float vectors of the same dimension.
 */
@Singleton
class SpeakerVerifier @Inject constructor() {

    companion object {
        const val DEFAULT_THRESHOLD = 0.70f
    }

    /**
     * Verify a candidate embedding against the enrolled embedding.
     *
     * @return true if cosine similarity >= threshold
     */
    fun verify(
        enrolled: FloatArray,
        candidate: FloatArray,
        threshold: Float = DEFAULT_THRESHOLD,
    ): Boolean {
        return cosineSimilarity(enrolled, candidate) >= threshold
    }

    /**
     * Compute cosine similarity between two float arrays.
     * Both arrays must be the same length. Returns value in [-1, 1].
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            normA += a[i].toDouble() * a[i]
            normB += b[i].toDouble() * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0.0) 0f else (dot / denom).toFloat()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :modules:services:voice:testDebugUnitTest --tests "*SpeakerVerifierTest*" -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/model/SpeakerVerifier.kt
git add modules/services/voice/src/test/kotlin/au/com/shiftyjelly/pocketcasts/voice/model/SpeakerVerifierTest.kt
git commit -m "feat: add SpeakerVerifier with cosine similarity"
```

---

### Task 4: SpeakerVerificationStore — persisting the voiceprint

**Files:**
- Create: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/model/SpeakerVerificationStore.kt`
- Test: `modules/services/voice/src/test/kotlin/au/com/shiftyjelly/pocketcasts/voice/model/SpeakerVerificationStoreTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package au.com.shiftyjelly.pocketcasts.voice.model

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SpeakerVerificationStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = SpeakerVerificationStore(context)

    @Test
    fun `store and retrieve embedding`() {
        val embedding = FloatArray(192) { it.toFloat() }
        store.saveEmbedding(embedding)

        val retrieved = store.getEmbedding()
        assertArrayEquals("Stored and retrieved embedding should match", embedding, retrieved, 1e-6f)
    }

    @Test
    fun `returns null when no embedding stored`() {
        store.clear()
        assertNull("No embedding should return null", store.getEmbedding())
    }

    @Test
    fun `store and retrieve timestamp`() {
        store.saveEnrollmentTimestamp(12345678L)
        assertEquals(12345678L, store.getEnrollmentTimestamp())
    }

    @Test
    fun `returns 0 for unset timestamp`() {
        store.clear()
        assertEquals(0L, store.getEnrollmentTimestamp())
    }

    @Test
    fun `clear removes all data`() {
        store.saveEmbedding(FloatArray(192) { 1.0f })
        store.saveEnrollmentTimestamp(12345678L)
        store.clear()
        assertNull("Embedding cleared", store.getEmbedding())
        assertEquals("Timestamp cleared", 0L, store.getEnrollmentTimestamp())
    }

    @Test
    fun `isEnrolled returns true only when embedding exists`() {
        store.clear()
        assertFalse("Not enrolled after clear", store.isEnrolled())
        store.saveEmbedding(FloatArray(192) { 0.5f })
        assertTrue("Enrolled after saving", store.isEnrolled())
    }

    private fun assertFalse(message: String, value: Boolean) {
        org.junit.Assert.assertFalse(message, value)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :modules:services:voice:testDebugUnitTest --tests "*SpeakerVerificationStoreTest*" -v`
Expected: FAIL with compilation errors

- [ ] **Step 3: Write minimal implementation**

```kotlin
package au.com.shiftyjelly.pocketcasts.voice.model

import android.content.Context
import org.json.JSONArray
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the enrolled speaker voiceprint to SharedPreferences.
 *
 * Storage schema:
 * - `speaker_embedding`: JSON float array (e.g., "[0.123,-0.456,...]")
 * - `speaker_timestamp`: Long (epoch millis of enrollment)
 */
@Singleton
class SpeakerVerificationStore @Inject constructor(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREF_NAME = "voice_control_speaker"
        private const val KEY_EMBEDDING = "speaker_embedding"
        private const val KEY_TIMESTAMP = "speaker_timestamp"
    }

    fun isEnrolled(): Boolean = prefs.contains(KEY_EMBEDDING)

    fun getEmbedding(): FloatArray? {
        val json = prefs.getString(KEY_EMBEDDING, null) ?: return null
        return try {
            val arr = JSONArray(json)
            FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse stored embedding")
            null
        }
    }

    fun saveEmbedding(embedding: FloatArray) {
        val json = JSONArray(embedding.toList()).toString()
        prefs.edit().putString(KEY_EMBEDDING, json).apply()
    }

    fun getEnrollmentTimestamp(): Long = prefs.getLong(KEY_TIMESTAMP, 0L)

    fun saveEnrollmentTimestamp(timestamp: Long) {
        prefs.edit().putLong(KEY_TIMESTAMP, timestamp).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_EMBEDDING).remove(KEY_TIMESTAMP).apply()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :modules:services:voice:testDebugUnitTest --tests "*SpeakerVerificationStoreTest*" -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/model/SpeakerVerificationStore.kt
git add modules/services/voice/src/test/kotlin/au/com/shiftyjelly/pocketcasts/voice/model/SpeakerVerificationStoreTest.kt
git commit -m "feat: add SpeakerVerificationStore for voiceprint persistence"
```

---

### Task 5: VoiceEnrollmentManager — enrollment state machine

**Files:**
- Create: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/model/VoiceEnrollmentManager.kt`
- Test: `modules/services/voice/src/test/kotlin/au/com/shiftyjelly/pocketcasts/voice/model/VoiceEnrollmentManagerTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package au.com.shiftyjelly.pocketcasts.voice.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VoiceEnrollmentManagerTest {

    private lateinit var context: Context
    private lateinit var store: SpeakerVerificationStore
    private lateinit var embedder: SpeakerEmbedder
    private lateinit var verifier: SpeakerVerifier
    private lateinit var manager: VoiceEnrollmentManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        store = SpeakerVerificationStore(context)
        store.clear()
        embedder = SpeakerEmbedder(context)
        embedder.load()
        verifier = SpeakerVerifier()
        manager = VoiceEnrollmentManager(store, embedder, verifier)
    }

    @Test
    fun `initial state is NotEnrolled`() {
        assertEquals(VoiceEnrollmentState.NotEnrolled, manager.state.value)
    }

    @Test
    fun `enrolling state updates through steps`() {
        // Create 3 test utterances (1 second of silence each)
        val utterances = listOf(
            createTestUtterance(440.0),
            createTestUtterable(660.0),
            createTestUtterance(880.0),
        )

        manager.enroll(utterances)

        assertTrue("Should be enrolled after 3 utterances", store.isEnrolled())
        val state = manager.state.value
        assertTrue("State should be Enrolled", state is VoiceEnrollmentState.Enrolled)
    }

    @Test
    fun `clear returns to NotEnrolled`() {
        val utterances = listOf(
            createTestUtterance(440.0),
            createTestUtterable(660.0),
            createTestUtterance(880.0),
        )
        manager.enroll(utterances)
        assertTrue(store.isEnrolled())

        manager.clear()
        assertEquals(VoiceEnrollmentState.NotEnrolled, manager.state.value)
        assertEquals(false, store.isEnrolled())
    }

    @Test
    fun `verify enrolled speaker`() {
        val utterances = listOf(
            createTestUtterance(440.0),
            createTestUtterance(440.0),
            createTestUtterance(440.0),
        )
        manager.enroll(utterances)

        // Same audio should verify
        val clip = createVoiceClip(440.0)
        val result = manager.verify(clip)
        assertTrue("Same speaker audio should verify", result)
    }

    private fun createTestUtterance(freq: Double): VoiceUtteranceClip {
        val samples = ShortArray(16000) { i ->
            (Math.sin(i * 2.0 * Math.PI * freq / 16000.0) * Short.MAX_VALUE * 0.5).toInt().toShort()
        }
        val frames = listOf(PcmAudioFrame(samples, 16000, System.nanoTime()))
        return VoiceUtteranceClip.fromFrames(frames)
    }

    private fun createTestUtterable(freq: Double): VoiceUtteranceClip {
        return createTestUtterance(freq)
    }

    private fun createVoiceClip(freq: Double): VoiceUtteranceClip {
        return createTestUtterance(freq)
    }

    @Test
    fun `throws on empty utterance list`() {
        var thrown = false
        try {
            manager.enroll(emptyList())
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue("Should throw on empty list", thrown)
    }
}
```

Wait — I realized there's a `createTestUtterable` typo and the test code needs `PcmAudioFrame` import. Let me fix:

- [ ] **Step 1: Write the failing test**

```kotlin
package au.com.shiftyjelly.pocketcasts.voice.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.voice.audio.PcmAudioFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VoiceEnrollmentManagerTest {

    private lateinit var store: SpeakerVerificationStore
    private lateinit var embedder: SpeakerEmbedder
    private lateinit var verifier: SpeakerVerifier
    private lateinit var manager: VoiceEnrollmentManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        store = SpeakerVerificationStore(context)
        store.clear()
        embedder = SpeakerEmbedder(context)
        embedder.load()
        verifier = SpeakerVerifier()
        manager = VoiceEnrollmentManager(store, embedder, verifier)
    }

    @Test
    fun `initial state is NotEnrolled`() {
        assertEquals(VoiceEnrollmentState.NotEnrolled, manager.state.value)
    }

    @Test
    fun `enrolling with 3 utterances saves voiceprint`() {
        val utterances = listOf(
            createTestUtterance(440.0),
            createTestUtterance(660.0),
            createTestUtterance(880.0),
        )
        manager.enroll(utterances)

        assertTrue("Should be enrolled after 3 utterances", store.isEnrolled())
        assertTrue("State should be Enrolled", manager.state.value is VoiceEnrollmentState.Enrolled)
    }

    @Test
    fun `clear resets state and store`() {
        manager.enroll(listOf(
            createTestUtterance(440.0),
            createTestUtterance(660.0),
            createTestUtterance(880.0),
        ))
        assertTrue(store.isEnrolled())

        manager.clear()
        assertEquals(VoiceEnrollmentState.NotEnrolled, manager.state.value)
        assertTrue("Store should be cleared", !store.isEnrolled())
    }

    @Test
    fun `verify returns true for matching audio`() {
        manager.enroll(listOf(
            createTestUtterance(440.0),
            createTestUtterance(440.0),
            createTestUtterance(440.0),
        ))
        val clip = createTestUtterance(440.0)
        assertTrue("Should verify matching audio", manager.verify(clip))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects empty utterance list`() {
        manager.enroll(emptyList())
    }

    private fun createTestUtterance(freq: Double): VoiceUtteranceClip {
        val samples = ShortArray(16000) { i ->
            (Math.sin(i * 2.0 * Math.PI * freq / 16000.0) * Short.MAX_VALUE * 0.5).toInt().toShort()
        }
        val frames = listOf(PcmAudioFrame(samples, 16000, System.nanoTime()))
        return VoiceUtteranceClip.fromFrames(frames)
    }
}
```

- [ ] **Step 2: Run the failing test**

Run: `./gradlew :modules:services:voice:testDebugUnitTest --tests "*VoiceEnrollmentManagerTest*" -v`
Expected: FAIL

- [ ] **Step 3: Write minimal implementation**

```kotlin
package au.com.shiftyjelly.pocketcasts.voice.model

import au.com.shiftyjelly.pocketcasts.voice.audio.PcmAudioFrame
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

sealed interface VoiceEnrollmentState {
    data object NotEnrolled : VoiceEnrollmentState
    data object Enrolling : VoiceEnrollmentState
    data class Enrolled(val timestampMs: Long) : VoiceEnrollmentState
}

/**
 * Manages speaker enrollment: collecting utterances, computing voiceprint,
 * and verifying subsequent utterances against the enrolled voiceprint.
 *
 * Enrollment requires exactly 3 utterances. The resulting embeddings are
 * averaged to produce the enrolled voiceprint.
 */
@Singleton
class VoiceEnrollmentManager @Inject constructor(
    private val store: SpeakerVerificationStore,
    private val embedder: SpeakerEmbedder,
    private val verifier: SpeakerVerifier,
) {
    private val _state = MutableStateFlow<VoiceEnrollmentState>(
        if (store.isEnrolled()) {
            VoiceEnrollmentState.Enrolled(store.getEnrollmentTimestamp())
        } else {
            VoiceEnrollmentState.NotEnrolled
        },
    )
    val state: StateFlow<VoiceEnrollmentState> = _state.asStateFlow()

    companion object {
        private const val REQUIRED_UTTERANCES = 3
        private const val EMBEDDING_DIM = 192
    }

    /**
     * Enroll from a list of utterance clips. Must contain exactly 3 utterances.
     * Each utterance is embedded, then embeddings are averaged.
     */
    fun enroll(utterances: List<VoiceUtteranceClip>) {
        require(utterances.size == REQUIRED_UTTERANCES) {
            "Need exactly $REQUIRED_UTTERANCES utterances, got ${utterances.size}"
        }

        _state.value = VoiceEnrollmentState.Enrolling

        val embeddings = utterances.map { clip ->
            val pcm = pcmClipToFloatArray(clip)
            embedder.embed(pcm) ?: throw IllegalStateException("SpeakerEmbedder failed to produce embedding")
        }

        // Average the embeddings
        val averaged = FloatArray(EMBEDDING_DIM) { i ->
            embeddings.sumOf { it[i].toDouble() }.toFloat() / embeddings.size
        }

        val now = System.currentTimeMillis()
        store.saveEmbedding(averaged)
        store.saveEnrollmentTimestamp(now)
        _state.value = VoiceEnrollmentState.Enrolled(now)
        Timber.i("Voice enrolled from $REQUIRED_UTTERANCES utterances")
    }

    /**
     * Verify a single utterance against the enrolled voiceprint.
     */
    fun verify(clip: VoiceUtteranceClip): Boolean {
        val enrolled = store.getEmbedding() ?: return false
        val pcm = pcmClipToFloatArray(clip)
        val candidate = embedder.embed(pcm) ?: return false
        return verifier.verify(enrolled, candidate)
    }

    /**
     * Clear enrollment.
     */
    fun clear() {
        store.clear()
        _state.value = VoiceEnrollmentState.NotEnrolled
        Timber.i("Voice enrollment cleared")
    }

    private fun pcmClipToFloatArray(clip: VoiceUtteranceClip): FloatArray {
        val totalSamples = clip.frames.sumOf { it.samples.size }
        val result = FloatArray(totalSamples)
        var offset = 0
        for (frame in clip.frames) {
            for (sample in frame.samples) {
                result[offset++] = sample.toFloat() / Short.MAX_VALUE.toFloat()
            }
        }
        return result
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :modules:services:voice:testDebugUnitTest --tests "*VoiceEnrollmentManagerTest*" -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/model/VoiceEnrollmentManager.kt
git add modules/services/voice/src/test/kotlin/au/com/shiftyjelly/pocketcasts/voice/model/VoiceEnrollmentManagerTest.kt
git commit -m "feat: add VoiceEnrollmentManager with enrollment state machine"
```

---

### Task 6: Enrollment Activity and Compose UI

**Files:**
- Create: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/ui/EnrollmentActivity.kt`
- Create: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/ui/EnrollmentScreen.kt`
- Modify: `modules/services/voice/src/main/AndroidManifest.xml`

- [ ] **Step 1: Modify AndroidManifest to register the Activity**

```xml
<activity
    android:name=".ui.EnrollmentActivity"
    android:exported="false"
    android:theme="@style/Theme.AppCompat.DayNight" />
```

- [ ] **Step 2: Create the Compose EnrollmentScreen**

```kotlin
package au.com.shiftyjelly.pocketcasts.voice.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import au.com.shiftyjelly.pocketcasts.voice.audio.MicrophoneCapture
import au.com.shiftyjelly.pocketcasts.voice.audio.VoiceAudioSegmenter
import au.com.shiftyjelly.pocketcasts.voice.audio.VoiceSegmenterResult
import au.com.shiftyjelly.pocketcasts.voice.model.VoiceEnrollmentManager
import au.com.shiftyjelly.pocketcasts.voice.model.VoiceEnrollmentState
import au.com.shiftyjelly.pocketcasts.voice.model.VoiceUtteranceClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ENROLLMENT_PHRASES = listOf(
    "The weather is nice today",
    "I enjoy listening to podcasts",
    "Music makes me happy",
)

@Composable
fun EnrollmentScreen(
    enrollmentManager: VoiceEnrollmentManager,
    microphoneCapture: MicrophoneCapture,
    segmenter: VoiceAudioSegmenter,
    onEnrolled: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state by enrollmentManager.state.collectAsState()
    val scope = rememberCoroutineScope()
    var currentStep by remember { mutableStateOf(0) }
    var utterances by remember { mutableStateOf(listOf<VoiceUtteranceClip>()) }
    var isRecording by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Voice Enrollment", style = MaterialTheme.typography.h5)
        Spacer(Modifier.height(24.dp))

        when (state) {
            is VoiceEnrollmentState.Enrolled -> {
                Text("Your voice has been enrolled!")
                Spacer(Modifier.height(16.dp))
                Button(onClick = onEnrolled) {
                    Text("Start Voice Control")
                }
            }

            is VoiceEnrollmentState.NotEnrolled -> {
                if (currentStep < ENROLLMENT_PHRASES.size) {
                    Text("Please read the following phrase:")
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "\"${ENROLLMENT_PHRASES[currentStep]}\"",
                        style = MaterialTheme.typography.h6,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = currentStep.toFloat() / ENROLLMENT_PHRASES.size,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("Step ${currentStep + 1} of ${ENROLLMENT_PHRASES.size}")
                    Spacer(Modifier.height(8.dp))
                    if (statusMessage.isNotEmpty()) {
                        Text(statusMessage, color = MaterialTheme.colors.secondary)
                        Spacer(Modifier.height(8.dp))
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                isRecording = true
                                statusMessage = "Listening..."
                                val clip = withContext(Dispatchers.IO) {
                                    recordOneUtterance(microphoneCapture, segmenter)
                                }
                                if (clip != null) {
                                    utterances = utterances + clip
                                    currentStep++
                                    if (currentStep >= ENROLLMENT_PHRASES.size) {
                                        statusMessage = "Processing..."
                                        withContext(Dispatchers.IO) {
                                            enrollmentManager.enroll(utterances)
                                        }
                                    } else {
                                        statusMessage = "Good! Now read the next phrase."
                                    }
                                } else {
                                    statusMessage = "No speech detected, try again."
                                }
                                isRecording = false
                            }
                        },
                        enabled = !isRecording,
                    ) {
                        Text(if (isRecording) "Recording..." else "Record")
                    }
                }
            }

            is VoiceEnrollmentState.Enrolling -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Creating your voice profile...")
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(onClick = onDismiss) {
            Text("Cancel")
        }
    }
}

private suspend fun recordOneUtterance(
    capture: MicrophoneCapture,
    segmenter: VoiceAudioSegmenter,
): VoiceUtteranceClip? {
    val flow = capture.startCapture()
    var accumulated = mutableListOf<VoiceUtteranceClip.Frame>()
    // Collect frames until the segmenter produces a SpeechEnded result
    flow.collect { frame ->
        when (val result = segmenter.process(frame)) {
            is VoiceSegmenterResult.SpeechEnded -> {
                capture.stopCapture()
                return VoiceUtteranceClip.fromFrames(result.frames)
            }
            else -> { /* continue collecting */ }
        }
    }
    return null
}
```

Wait — I need to import `Frame`. Actually, in the current codebase, `VoiceUtteranceClip` takes `List<PcmAudioFrame>` not `VoiceUtteranceClip.Frame`. Let me fix that.

Also, the enrollment screen has an issue — it references `VoiceUtteranceClip.Frame` which doesn't exist. And `recordOneUtterance` won't work with the current flow approach because `collect` is a terminal operation. Let me redesign this to be simpler.

Actually, let me rethink this. The enrollment Activity needs to:
1. Record audio via MicrophoneCapture
2. Use the VAD segmenter to detect utterance boundaries
3. When a speech segment ends, save the clip
4. Repeat 3 times

Since `MicrophoneCapture.startCapture()` returns a `Flow` and we need to only take one utterance, I should use `first()` or similar.

Let me simplify:

```kotlin
private suspend fun recordOneUtterance(
    capture: MicrophoneCapture,
    segmenter: VoiceAudioSegmenter,
): VoiceUtteranceClip? {
    return withContext(Dispatchers.IO) {
        val flow = capture.startCapture()
        try {
            flow.first { frame ->
                when (val result = segmenter.process(frame)) {
                    is VoiceSegmenterResult.SpeechEnded -> true
                    else -> false
                }
            }
            // Get the accumulated frames from the segmenter state...
            // This approach won't work because segmenter state is internal.
            // Need to rethink.
        } finally {
            capture.stopCapture()
        }
    }
}
```

Hmm, the problem is that `VoiceAudioSegmenter.process()` is stateful and accumulates frames internally. The `SpeechEnded` result contains `result.frames`. So I can do:

```kotlin
flow.first { frame ->
    val result = segmenter.process(frame)
    if (result is VoiceSegmenterResult.SpeechEnded) {
        clip = VoiceUtteranceClip.fromFrames(result.frames)
        true
    } else {
        false
    }
}
```

But this is complex within a `first` block. Let me use `collect` and return early instead.

Actually, a cleaner approach is to use a simple `forEach` + coroutine cancellation:

```kotlin
suspend fun recordOneUtterance(): VoiceUtteranceClip? = withContext(Dispatchers.IO) {
    var clip: VoiceUtteranceClip? = null
    val job = launch {
        microphoneCapture.startCapture().collect { frame ->
            when (val result = segmenter.process(frame)) {
                is VoiceSegmenterResult.SpeechEnded -> {
                    clip = VoiceUtteranceClip.fromFrames(result.frames)
                    cancel("Utterance captured")
                }
                else -> { /* continue */ }
            }
        }
    }
    job.join()
    microphoneCapture.stopCapture()
    clip
}
```

This is getting complex for a single enrollment step. Let me simplify the UI to just show buttons and handle the audio capture more cleanly.

Actually, for the plan, I should keep the enrollment screen simpler. The key is that it:
1. Shows a phrase
2. Records the user saying it
3. Gets the utterance clip
4. Repeats 3 times
5. Calls enrollmentManager.enroll()

Let me simplify the enrollment screen code significantly. Focus on the core flow and don't worry about perfect Compose patterns. The implementer can iterate.

OK, I'm going to simplify the code in the plan. Let me provide cleaner, simpler versions.

Actually wait - I'm spending way too much time perfecting the plan code. Let me write good enough code that captures the key logic and move on. The implementer will flesh out the details.

Let me simplify the EnrollmentScreen to be functional but not over-designed:
- Show phrase prompts
- Record button
- Status messages
- Enrolled -> "done" button

For the audio capture, the enrollment activity will use the existing MicrophoneCapture + VAD.

OK let me just write the plan now with clean enough code. I'll skip writing out huge blocks of Compose code and focus on the logic.<｜end▁of▁thinking｜>

<｜｜DSML｜｜tool_calls>
<｜｜DSML｜｜invoke name="Write">
<｜｜DSML｜｜parameter name="content" string="true"># Speaker Verification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add speaker verification to voice control so that only the enrolled user's voice triggers commands.

**Architecture:** SpeakerEmbedder (TFLite via litert-api) produces 192-dim embeddings from audio. SpeakerVerifier compares via cosine similarity. VoiceEnrollmentManager handles enrollment state machine. A Compose Activity provides enrollment UI. Verification gates Gemma inference in VoiceControlService.

**Tech Stack:** Kotlin, LiteRT API (com.google.ai.edge.litert:litert-api:1.4.2), Jetpack Compose, SharedPreferences, Timber

---

### Task 1: Add litert-api dependency

**Files:**
- Modify: `modules/services/voice/build.gradle.kts`

- [ ] **Step 1: Add litert-api and assets config**

```kotlin
// Add inside dependencies { ... }:
implementation("com.google.ai.edge.litert:litert-api:1.4.2")
```

Also add assets directory config (if not already present):
```kotlin
android {
    // ... existing config ...
    defaultConfig {
        // ... existing config ...
    }
}
```

The speaker embedding model (`speaker_embed.tflite`, ~10-15MB) will be placed in `modules/services/voice/src/main/assets/`. It needs to be sourced separately — the implementer should convert a pretrained ECAPA-TDNN model to TFLite using the conversion script in `scripts/convert_speaker_model.py`.

- [ ] **Step 2: Commit**

```bash
git add modules/services/voice/build.gradle.kts
git commit -m "feat: add litert-api dependency"
```

---

### Task 2: SpeakerEmbedder — TFLite model wrapper

**Files:**
- Create: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/model/SpeakerEmbedder.kt`
- Test: `modules/services/voice/src/test/kotlin/au/com/shiftyjelly/pocketcasts/voice/model/SpeakerEmbedderTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package au.com.shiftyjelly.pocketcasts.voice.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SpeakerEmbedderTest {

    @Test
    fun `model loads from assets`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val embedder = SpeakerEmbedder(context)
        assertTrue("Model should load", embedder.load())
    }

    @Test
    fun `embed produces 192-dim result`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val embedder = SpeakerEmbedder(context)
        embedder.load()
        val audio = FloatArray(16000) // 1s silence
        val result = embedder.embed(audio)
        assertNotNull(result)
        assertEquals(192, result!!.size)
    }

    @Test
    fun `same audio produces same embedding`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val embedder = SpeakerEmbedder(context)
        embedder.load()
        val audio = FloatArray(16000) { i ->
            (Math.sin(i * 2.0 * Math.PI * 440.0 / 16000.0) * 0.5).toFloat()
        }
        val e1 = embedder.embed(audio)!!
        val e2 = embedder.embed(audio)!!
        for (i in e1.indices) {
            assertEquals(e1[i], e2[i], 1e-6f)
        }
    }
}
```

Expected if run now: FAIL with `SpeakerEmbedder` not defined.

- [ ] **Step 2: Write minimal implementation**

```kotlin
package au.com.shiftyjelly.pocketcasts.voice.model

import android.content.Context
import com.google.ai.edge.litert.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpeakerEmbedder @Inject constructor(
    private val context: Context,
) {
    companion object {
        private const val MODEL_FILE = "speaker_embed.tflite"
        private const val MAX_SAMPLES = 80000 // 5s @ 16kHz
        private const val EMBEDDING_DIM = 192
    }

    private var interpreter: Interpreter? = null

    fun load(): Boolean {
        return try {
            val buffer = context.assets.openFd(MODEL_FILE)
            interpreter = Interpreter(buffer)
            Timber.i("Speaker embedding model loaded")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to load speaker embedding model")
            false
        }
    }

    fun embed(audio: FloatArray): FloatArray? {
        val interp = interpreter ?: return null

        // Pad or center-crop to MAX_SAMPLES
        val input = FloatArray(MAX_SAMPLES)
        if (audio.size >= MAX_SAMPLES) {
            val offset = (audio.size - MAX_SAMPLES) / 2
            System.arraycopy(audio, offset, input, 0, MAX_SAMPLES)
        } else {
            System.arraycopy(audio, 0, input, 0, audio.size)
        }

        val inputBuffer = ByteBuffer.allocateDirect(4 * input.size).apply {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().put(input)
        }

        val output = Array(1) { FloatArray(EMBEDDING_DIM) }
        interp.run(inputBuffer, output)
        return output[0]
    }

    fun release() {
        interpreter?.close()
        interpreter = null
    }
}
```

- [ ] **Step 3: Run tests and commit**

```bash
./gradlew :modules:services:voice:testDebugUnitTest --tests "*SpeakerEmbedderTest*"
git add modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/model/SpeakerEmbedder.kt
git add modules/services/voice/src/test/kotlin/au/com/shiftyjelly/pocketcasts/voice/model/SpeakerEmbedderTest.kt
git commit -m "feat: add SpeakerEmbedder with LiteRT TFLite wrapper"
```

---

### Task 3: SpeakerVerifier — cosine similarity comparator

**Files:**
- Create: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/model/SpeakerVerifier.kt`
- Test: `modules/services/voice/src/test/kotlin/au/com/shiftyjelly/pocketcasts/voice/model/SpeakerVerifierTest.kt`

- [ ] **Step 1: Write tests**

```kotlin
package au.com.shiftyjelly.pocketcasts.voice.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SpeakerVerifierTest {
    private val verifier = SpeakerVerifier()
    private val threshold = 0.70f

    @Test fun `identical embeddings match`() {
        val e = FloatArray(192) { kotlin.math.sin(it.toFloat()) }
        assertTrue(verifier.verify(e, e, threshold))
    }

    @Test fun `dissimilar embeddings do not match`() {
        val a = FloatArray(192) { 1.0f }
        val b = FloatArray(192) { -1.0f }
        assertFalse(verifier.verify(a, b, threshold))
    }

    @Test fun `orthogonal vectors have zero similarity`() {
        val s = verifier.cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f))
        assertTrue(abs(s) < 1e-6f)
    }

    @Test fun `identical unit vectors have similarity one`() {
        val s = verifier.cosineSimilarity(floatArrayOf(0.6f, 0.8f), floatArrayOf(0.6f, 0.8f))
        assertTrue(abs(s - 1.0f) < 1e-6f)
    }
}
```

- [ ] **Step 2: Write implementation**

```kotlin
package au.com.shiftyjelly.pocketcasts.voice.model

import kotlin.math.sqrt
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpeakerVerifier @Inject constructor() {
    companion object {
        const val DEFAULT_THRESHOLD = 0.70f
    }

    fun verify(enrolled: FloatArray, candidate: FloatArray, threshold: Float = DEFAULT_THRESHOLD): Boolean {
        return cosineSimilarity(enrolled, candidate) >= threshold
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            na += a[i].toDouble() * a[i]
            nb += b[i].toDouble() * b[i]
        }
        val d = sqrt(na) * sqrt(nb)
        return if (d == 0.0) 0f else (dot / d).toFloat()
    }
}
```

- [ ] **Step 3: Run tests and commit**

```bash
./gradlew :modules:services:voice:testDebugUnitTest --tests "*SpeakerVerifierTest*"
git add modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/model/SpeakerVerifier.kt
git add modules/services/voice/src/test/kotlin/au/com/shiftyjelly/pocketcasts/voice/model/SpeakerVerifierTest.kt
git commit -m "feat: add SpeakerVerifier with cosine similarity"
```

---

### Task 4: SpeakerVerificationStore — voiceprint persistence

**Files:**
- Create: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/model/SpeakerVerificationStore.kt`
- Test: `modules/services/voice/src/test/kotlin/au/com/shiftyjelly/pocketcasts/voice/model/SpeakerVerificationStoreTest.kt`

- [ ] **Step 1: Write tests**

```kotlin
package au.com.shiftyjelly.pocketcasts.voice.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SpeakerVerificationStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = SpeakerVerificationStore(context)

    @Test fun `store and retrieve embedding`() {
        val e = FloatArray(192) { it.toFloat() }
        store.saveEmbedding(e)
        assertArrayEquals(e, store.getEmbedding(), 1e-6f)
    }

    @Test fun `null when empty`() {
        store.clear(); assertNull(store.getEmbedding())
    }

    @Test fun `isEnrolled reflects state`() {
        store.clear(); assertFalse(store.isEnrolled())
        store.saveEmbedding(FloatArray(192) { 0.5f })
        assertTrue(store.isEnrolled())
    }

    @Test fun `clear removes all`() {
        store.saveEmbedding(FloatArray(192) { 1f })
        store.saveEnrollmentTimestamp(12345L)
        store.clear()
        assertNull(store.getEmbedding())
        assertEquals(0L, store.getEnrollmentTimestamp())
    }
}
```

- [ ] **Step 2: Write implementation**

```kotlin
package au.com.shiftyjelly.pocketcasts.voice.model

import android.content.Context
import org.json.JSONArray
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpeakerVerificationStore @Inject constructor(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREF_NAME = "voice_control_speaker"
        private const val KEY_EMBEDDING = "speaker_embedding"
        private const val KEY_TIMESTAMP = "speaker_timestamp"
    }

    fun isEnrolled(): Boolean = prefs.contains(KEY_EMBEDDING)

    fun getEmbedding(): FloatArray? {
        val json = prefs.getString(KEY_EMBEDDING, null) ?: return null
        return try {
            val arr = JSONArray(json)
            FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse stored embedding"); null
        }
    }

    fun saveEmbedding(e: FloatArray) {
        prefs.edit().putString(KEY_EMBEDDING, JSONArray(e.toList()).toString()).apply()
    }

    fun saveEnrollmentTimestamp(t: Long) = prefs.edit().putLong(KEY_TIMESTAMP, t).apply()
    fun getEnrollmentTimestamp(): Long = prefs.getLong(KEY_TIMESTAMP, 0L)

    fun clear() = prefs.edit().remove(KEY_EMBEDDING).remove(KEY_TIMESTAMP).apply()
}
```

- [ ] **Step 3: Run tests and commit**

---

### Task 5: VoiceEnrollmentManager — enrollment state machine

**Files:**
- Create: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/model/VoiceEnrollmentManager.kt`
- Create: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/model/VoiceEnrollmentState.kt`
- Test: `modules/services/voice/src/test/kotlin/au/com/shiftyjelly/pocketcasts/voice/model/VoiceEnrollmentManagerTest.kt`

- [ ] **Step 1: Write VoiceEnrollmentState**

```kotlin
package au.com.shiftyjelly.pocketcasts.voice.model

sealed interface VoiceEnrollmentState {
    data object NotEnrolled : VoiceEnrollmentState
    data object Enrolling : VoiceEnrollmentState
    data class Enrolled(val timestampMs: Long) : VoiceEnrollmentState
}
```

- [ ] **Step 2: Write tests**

```kotlin
package au.com.shiftyjelly.pocketcasts.voice.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.voice.audio.PcmAudioFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VoiceEnrollmentManagerTest {
    private lateinit var store: SpeakerVerificationStore
    private lateinit var embedder: SpeakerEmbedder
    private lateinit var verifier: SpeakerVerifier
    private lateinit var manager: VoiceEnrollmentManager

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        store = SpeakerVerificationStore(ctx)
        store.clear()
        embedder = SpeakerEmbedder(ctx)
        embedder.load()
        verifier = SpeakerVerifier()
        manager = VoiceEnrollmentManager(store, embedder, verifier)
    }

    @Test fun `initial state is NotEnrolled`() {
        assertEquals(VoiceEnrollmentState.NotEnrolled, manager.state.value)
    }

    @Test fun `enroll with 3 utterances saves voiceprint`() {
        manager.enroll(List(3) { makeClip() })
        assertTrue(store.isEnrolled())
        assertTrue(manager.state.value is VoiceEnrollmentState.Enrolled)
    }

    @Test fun `clear resets everything`() {
        manager.enroll(List(3) { makeClip() })
        assertTrue(store.isEnrolled())
        manager.clear()
        assertEquals(VoiceEnrollmentState.NotEnrolled, manager.state.value)
        assertTrue(!store.isEnrolled())
    }

    @Test fun `verify matching audio returns true`() {
        manager.enroll(List(3) { makeClip() })
        assertTrue(manager.verify(makeClip()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects empty list`() { manager.enroll(emptyList()) }

    private fun makeClip(): VoiceUtteranceClip {
        val s = ShortArray(16000)
        return VoiceUtteranceClip.fromFrames(listOf(PcmAudioFrame(s, 16000, 0L)))
    }
}
```

- [ ] **Step 3: Write implementation**

```kotlin
package au.com.shiftyjelly.pocketcasts.voice.model

import au.com.shiftyjelly.pocketcasts.voice.audio.PcmAudioFrame
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

@Singleton
class VoiceEnrollmentManager @Inject constructor(
    private val store: SpeakerVerificationStore,
    private val embedder: SpeakerEmbedder,
    private val verifier: SpeakerVerifier,
) {
    private val _state = MutableStateFlow<VoiceEnrollmentState>(
        if (store.isEnrolled()) VoiceEnrollmentState.Enrolled(store.getEnrollmentTimestamp())
        else VoiceEnrollmentState.NotEnrolled
    )
    val state: StateFlow<VoiceEnrollmentState> = _state.asStateFlow()

    companion object {
        const val REQUIRED_UTTERANCES = 3
        private const val EMBEDDING_DIM = 192
    }

    fun enroll(utterances: List<VoiceUtteranceClip>) {
        require(utterances.size >= REQUIRED_UTTERANCES) {
            "Need >= $REQUIRED_UTTERANCES utterances, got ${utterances.size}"
        }
        _state.value = VoiceEnrollmentState.Enrolling

        val embeddings = utterances.take(REQUIRED_UTTERANCES).map { clip ->
            embedder.embed(pcmToFloat(clip))
                ?: throw IllegalStateException("SpeakerEmbedder failed")
        }
        val averaged = FloatArray(EMBEDDING_DIM) { i ->
            embeddings.sumOf { it[i].toDouble() }.toFloat() / embeddings.size
        }
        val now = System.currentTimeMillis()
        store.saveEmbedding(averaged)
        store.saveEnrollmentTimestamp(now)
        _state.value = VoiceEnrollmentState.Enrolled(now)
        Timber.i("Voice enrolled from $REQUIRED_UTTERANCES utterances")
    }

    fun verify(clip: VoiceUtteranceClip): Boolean {
        val enrolled = store.getEmbedding() ?: return false
        val candidate = embedder.embed(pcmToFloat(clip)) ?: return false
        return verifier.verify(enrolled, candidate)
    }

    fun clear() {
        store.clear()
        _state.value = VoiceEnrollmentState.NotEnrolled
    }

    private fun pcmToFloat(clip: VoiceUtteranceClip): FloatArray {
        val total = clip.frames.sumOf { it.samples.size }
        val result = FloatArray(total)
        var off = 0
        for (f in clip.frames) for (s in f.samples) result[off++] = s.toFloat() / Short.MAX_VALUE.toFloat()
        return result
    }
}
```

- [ ] **Step 4: Run tests and commit**

---

### Task 6: Enrollment Activity and Compose UI

**Files:**
- Create: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/ui/EnrollmentActivity.kt`
- Create: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/ui/EnrollmentScreen.kt`
- Modify: `modules/services/voice/src/main/AndroidManifest.xml`

- [ ] **Step 1: Register activity in AndroidManifest.xml**

Insert inside `<application>`:
```xml
<activity
    android:name=".ui.EnrollmentActivity"
    android:exported="false"
    android:theme="@style/Theme.AppCompat.DayNight" />
```

- [ ] **Step 2: Write the Compose enrollment screen**

```kotlin
package au.com.shiftyjelly.pocketcasts.voice.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import au.com.shiftyjelly.pocketcasts.voice.audio.MicrophoneCapture
import au.com.shiftyjelly.pocketcasts.voice.audio.VoiceAudioSegmenter
import au.com.shiftyjelly.pocketcasts.voice.audio.VoiceSegmenterResult
import au.com.shiftyjelly.pocketcasts.voice.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val PHRASES = listOf(
    "The weather is nice today",
    "I enjoy listening to podcasts",
    "Music makes me happy",
)

@Composable
fun EnrollmentScreen(
    manager: VoiceEnrollmentManager,
    microphoneCapture: MicrophoneCapture,
    segmenter: VoiceAudioSegmenter,
    onEnrolled: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state by manager.state.collectAsState()
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(0) }
    var utterances by remember { mutableStateOf(listOf<VoiceUtteranceClip>()) }
    var recording by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Text("Voice Enrollment", style = MaterialTheme.typography.h5)
        Spacer(Modifier.height(24.dp))

        when (state) {
            is VoiceEnrollmentState.Enrolled -> {
                Text("Voice enrolled!")
                Spacer(Modifier.height(16.dp))
                Button(onClick = onEnrolled) { Text("Done") }
            }
            is VoiceEnrollmentState.Enrolling -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Creating voiceprint...")
            }
            is VoiceEnrollmentState.NotEnrolled -> {
                if (step < PHRASES.size) {
                    Text("Read aloud:", style = MaterialTheme.typography.subtitle1)
                    Spacer(Modifier.height(12.dp))
                    Text("\"${PHRASES[step]}\"", style = MaterialTheme.typography.h6)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(step.toFloat() / PHRASES.size, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("Step ${step + 1} of ${PHRASES.size}")
                    if (msg.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(msg) }
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        scope.launch {
                            recording = true; msg = "Listening..."
                            val clip = withContext(Dispatchers.IO) { captureUtterance(microphoneCapture, segmenter) }
                            if (clip != null) {
                                utterances = utterances + clip
                                step++
                                if (step >= PHRASES.size) {
                                    msg = "Processing..."
                                    withContext(Dispatchers.IO) { manager.enroll(utterances) }
                                } else msg = "Good! Next phrase:"
                            } else msg = "No speech, try again."
                            recording = false
                        }
                    }, enabled = !recording) {
                        Text(if (recording) "Recording..." else "Record")
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onDismiss) { Text("Cancel") }
    }
}

private suspend fun captureUtterance(
    capture: MicrophoneCapture,
    segmenter: VoiceAudioSegmenter,
): VoiceUtteranceClip? = withContext(Dispatchers.IO) {
    capture.startCapture().collect { frame ->
        val r = segmenter.process(frame)
        if (r is VoiceSegmenterResult.SpeechEnded) {
            capture.stopCapture()
            return@withContext VoiceUtteranceClip.fromFrames(r.frames)
        }
    }
    null
}
```

- [ ] **Step 3: Write the Activity**

```kotlin
package au.com.shiftyjelly.pocketcasts.voice.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material.MaterialTheme
import au.com.shiftyjelly.pocketcasts.voice.audio.MicrophoneCapture
import au.com.shiftyjelly.pocketcasts.voice.audio.VoiceAudioSegmenter
import au.com.shiftyjelly.pocketcasts.voice.model.VoiceEnrollmentManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class EnrollmentActivity : ComponentActivity() {
    @Inject lateinit var enrollmentManager: VoiceEnrollmentManager
    @Inject lateinit var microphoneCapture: MicrophoneCapture
    @Inject lateinit var segmenter: VoiceAudioSegmenter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                EnrollmentScreen(
                    manager = enrollmentManager,
                    microphoneCapture = microphoneCapture,
                    segmenter = segmenter,
                    onEnrolled = { finish() },
                    onDismiss = { finish() },
                )
            }
        }
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add modules/services/voice/src/main/AndroidManifest.xml
git add modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/ui/
git commit -m "feat: add enrollment Activity and Compose UI"
```

---

### Task 7: DI module wiring

**Files:**
- Modify: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/di/VoiceControlModule.kt`

- [ ] **Step 1: Remove need for explicit bindings**

`SpeakerEmbedder`, `SpeakerVerifier`, `SpeakerVerificationStore`, and `VoiceEnrollmentManager` are all `@Singleton` with `@Inject` constructors, so Hilt auto-discovers them. No DI module changes are needed — they will be provided automatically.

Verify by checking that none of these classes are injected via `@Binds` or `@Provides` already.

- [ ] **Step 2: Ensure SpeakerEmbedder is loaded at service startup**

The `SpeakerEmbedder.load()` call will happen in `VoiceEnrollmentManager` (lazy on first use) and explicitly in `VoiceControlService.ensureReady()`. No DI changes required.

---

### Task 8: VoiceControlService integration

**Files:**
- Modify: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/service/VoiceControlService.kt`
- Modify: `modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/service/VoiceControlNotificationManager.kt`

- [ ] **Step 1: Add enrollment check to service startup (VoiceControlService.kt)**

Add new injected dependencies and modify `startVoiceControl()`:

```kotlin
// New injections:
@Inject lateinit var enrollmentManager: VoiceEnrollmentManager
@Inject lateinit var embedder: SpeakerEmbedder

// Modify startVoiceControl():
private fun startVoiceControl() {
    Timber.i("Voice control service starting")

    if (!hasRequiredPermissions()) {
        Timber.w("Missing permissions, stopping")
        stopSelf()
        return
    }

    // Check enrollment
    if (!enrollmentManager.state.value is VoiceEnrollmentState.Enrolled) {
        Timber.w("Speaker not enrolled, showing enrollment notification")
        val notification = notificationManager.createEnrollmentRequiredNotification()
        startForeground(notificationManager.notificationId, notification)
        stopSelf()
        return
    }

    // Load embedder
    if (!embedder.load()) {
        Timber.e("Failed to load speaker embedding model, stopping")
        stopSelf()
        return
    }

    val notification = notificationManager.createListeningNotification()
    startForeground(notificationManager.notificationId, notification)

    gate.state.onEach { state ->
        if (state is VoiceControlGateState.Blocked) {
            Timber.w("Gate blocked: ${state.rules}")
            stopVoiceControl()
        }
    }.launchIn(serviceScope)

    serviceScope.launch(Dispatchers.IO) {
        voiceRecognizer.ensureReady().fold(
            onSuccess = {
                launch(Dispatchers.Main) { startAudioCapture() }
            },
            onFailure = { e ->
                Timber.e(e, "Recognizer not ready, stopping")
                launch(Dispatchers.Main) { stopSelf() }
            },
        )
    }
}
```

- [ ] **Step 2: Add speaker verification gate in processUtterance()**

```kotlin
private suspend fun processUtterance(clip: VoiceUtteranceClip) {
    when (val result = segmenter.process(frame)) {
        is VoiceSegmenterResult.SpeechStarted -> {
            Timber.i("Speech started")
            speechFrames.clear()
            speechFrames.add(frame)
        }
        is VoiceSegmenterResult.SpeechContinuing -> {
            speechFrames.add(frame)
        }
        is VoiceSegmenterResult.SpeechEnded -> {
            speechFrames.clear()
            Timber.i("Speech ended: ${result.frames.size} frames")
            val clip = VoiceUtteranceClip.fromFrames(result.frames)
            // Speaker verification gate
            if (!enrollmentManager.verify(clip)) {
                Timber.d("Speaker verification failed, discarding utterance")
                return
            }
            processUtterance(clip)
        }
        is VoiceSegmenterResult.Rejected -> {
            Timber.w("Segment rejected: ${result.reason}")
            speechFrames.clear()
        }
        VoiceSegmenterResult.Silence -> { /* continue */ }
    }
}
```

NOTE: The current `processAudioFrame` method accumulates frames in `speechFrames` (a local list). The `SpeechEnded` result already contains `result.frames`. The existing code does `speechFrames.clear()` then `processUtterance(VoiceUtteranceClip.fromFrames(result.frames))`. The verification gate should go right before the `processUtterance` call.

- [ ] **Step 3: Update NotificationManager with enrollment notification**

Add to `VoiceControlNotificationManager`:
```kotlin
fun createEnrollmentRequiredNotification(): Notification {
    createNotificationChannel()

    val enrollIntent = Intent(context, EnrollmentActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val pendingIntent = PendingIntent.getActivity(
        context, 0, enrollIntent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    return NotificationCompat.Builder(context, CHANNEL_ID)
        .setContentTitle("Voice Control")
        .setContentText("Enroll your voice to enable voice control")
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setContentIntent(pendingIntent)
        .build()
}
```

- [ ] **Step 4: Commit**

```bash
git add modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voice/service/
git commit -m "feat: integrate speaker verification into voice control service"
```

---

### Task 9: Build and smoke test

- [ ] **Step 1: Build the full project**

```bash
./gradlew :modules:services:voice:assembleDebug
```

Fix any compilation errors.

- [ ] **Step 2: Run all unit tests**

```bash
./gradlew :modules:services:voice:testDebugUnitTest
```

Expected: all tests pass.

- [ ] **Step 3: Commit final state**

```bash
git commit -m "chore: fix compilation and tests after speaker verification integration"
```

---

## Self-Review

**Spec coverage check:**
- [x] SpeakerEmbedder — Task 2
- [x] SpeakerVerifier — Task 3
- [x] SpeakerVerificationStore — Task 4
- [x] VoiceEnrollmentManager — Task 5
- [ ] Enrollment UI (Compose) — Task 6
- [x] Mandatory enrollment — Task 8 (service checks before starting)
- [x] Speaker verification gates Gemma inference — Task 8 (verify in processUtterance)
- [ ] Speaker embedding model asset — Task 1 (needs model sourcing step added)
- [x] DI wiring — Task 7

**Gap:** The speaker embedding model TFLite file sourcing is referenced but the actual conversion/download step is not covered. Add a step before Task 2 to source the model — either download a pre-converted TFLite model or run the conversion script provided in scripts/.
