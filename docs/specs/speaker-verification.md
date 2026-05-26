# Speaker Verification

## Summary

Add speaker verification to voice control so that only the enrolled user's voice triggers playback commands. Utterances that don't match the enrolled voiceprint are silently discarded.

## Architecture

### Component Flow

```
┌──────────────────────────────────────────────────────────────────┐
│                      VoiceControlService                         │
│                                                                  │
│  Audio → VAD → UtteranceClip → SpeakerVerifier → Recognizer → Exec │
│                                     │                            │
│                                     ▼                            │
│                             EnrollmentManager                    │
│                                      │                           │
│                                      ▼                           │
│                              SpeakerEmbedder                     │
│                              (TFLite model)                      │
└──────────────────────────────────────────────────────────────────┘
```

### SpeakerEmbedder

- Wraps a TFLite speaker embedding model (ECAPA-TDNN, ~10-15MB)
- Runtime: LiteRT API — shares the `libLiteRt.so` native library already bundled. No additional native libs, ~50KB APK overhead.
- Input: PCM 16kHz mono float array, variable length (adaptive pooling, padded to 5s max)
- Output: 192-dim normalized float embedding vector
- Runs on Dispatchers.IO, ~10-50ms per utterance
- Model bundled as an asset: `speaker_embed.tflite`

### SpeakerVerifier

- Stateless: given a candidate embedding and the enrolled embedding, compute cosine similarity
- Threshold: 0.70 (default, adjustable per-enrollment)
- Exposes: `fun verify(clip: VoiceUtteranceClip): Boolean`

### VoiceEnrollmentManager

- Singleton, injected
- States: `NotEnrolled`, `Enrolling(currentStep, totalSteps)`, `Enrolled(timestamp)`
- Enrolls from N utterances (default 3): embeds each → averages embeddings → stores to persistent storage
- Provides `isEnrolled()`, `getEnrolledEmbedding()`, `enroll(utterances)`, `clear()`

### SpeakerVerificationStore

- Persistent key-value storage
- Keys: `enrolled_embedding` (JSON float array), `threshold` (float), `enrollment_timestamp` (long)
- ~800 bytes total for 192 floats

## Enrollment Flow

### Prerequisites
- Voice control settings screen (already exists)
- User taps "Enroll voice" button

### Steps
1. User is shown 3 short phrases one at a time (e.g., "The weather is nice today", "I enjoy listening to podcasts", "Music makes me happy")
2. Each phrase is recorded via the existing Oboe capture pipeline
3. VAD segments each utterance (same as current command capture)
4. Each utterance is converted to float PCM, fed to SpeakerEmbedder → embedding
5. After all 3 utterances, embeddings are averaged to produce the enrollment voiceprint
6. Voiceprint + metadata stored via SpeakerVerificationStore
7. Voice control is ready for use

### UI
- Compose screen: `EnrollmentScreen`
- Shows phrase prompts, progress (1/3, 2/3, 3/3)
- Live audio level meter during recording
- Success/failure states
- "Re-enroll" option after enrollment

## Command-Time Verification

```
VoiceControlService.processUtterance(clip)
  → SpeakerVerifier.verify(clip)
    → match: proceed to recognizer inference (existing path)
    → no match: discard silently, log at debug level
```

Key behavior:
- Verification gates the expensive recognition inference — saves battery on non-owner speech
- Both enrollment and verification use the same SpeakerEmbedder model

## Mandatory Enrollment

- Voice control cannot be activated without an enrolled voiceprint
- If not enrolled: `VoiceControlService` shows a notification directing user to the enrollment screen
- Enrollment is required once; re-enrollment overwrites the existing voiceprint
- Speaker verification is independent of audio route policy — all utterances must pass verification regardless of whether the route is HeadsetOnly or SpeakerExperimental

## Error Handling

| Scenario | Behavior |
|---|---|
| No enrollment exists | Voice control won't start; user directed to enrollment |
| Model file missing | Voice control won't start; logged as error |
| Embedding below threshold | Utterance discarded silently |
| Low-quality enrollment audio | VAD rejects it (TooShort / LowConfidence); user prompted to repeat |
| Model inference failure | Utterance discarded (fail closed, per mandatory-enrollment model) |

## Dependencies

- LiteRT API for model inference
- Speaker embedding model file (~10-15MB) bundled as asset
- Voice module already has: Oboe, VAD (Silero), persistent storage via shared prefs
- `VoiceUtteranceClip` and `VoiceRecognitionContext` types from the [voice-control-core foundation spec](voice-control-core.md)

## Testing

- SpeakerEmbedder: unit test with known audio → expected embedding shape and normalization
- SpeakerVerifier: unit test cosine similarity against known vectors
- VoiceEnrollmentManager: test enrollment flow, overwrite, cleared state
- Integration: test that non-matching utterance is discarded in service
- Manual: record two different voices, verify only the enrolled one triggers commands
