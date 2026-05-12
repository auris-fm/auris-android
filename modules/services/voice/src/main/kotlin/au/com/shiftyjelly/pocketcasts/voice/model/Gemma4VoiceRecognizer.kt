package au.com.shiftyjelly.pocketcasts.voice.model

import au.com.shiftyjelly.pocketcasts.voice.intent.VoiceRecognitionResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gemma4 E2B audio-to-intent pipeline.
 *
 * WARNING: Content.AudioBytes() and Content.AudioFile() both route audio through
 * LiteRT-LM's internal audio encoder, which uses Oboe/AAudio for PCM buffer management.
 * LiteRT-LM v0.10.0 has a native bug in its AAudio ring-buffer that crashes with:
 *   releaseBuffer: mUnreleased out of range
 *   (google/oboe issue #535, google-ai-edge/LiteRT-LM issues #684, #1033)
 *
 * This applies regardless of whether audio is passed as raw bytes or as a WAV file.
 * The GPU+CPU backend split makes it intermittent but doesn't fix the root cause.
 *
 * Until LiteRT-LM fixes this, Vosk handles ASR (PCM frames via acceptWaveForm,
 * zero audio interruption). Gemma4 can be used as a text-only intent classifier
 * once the AAudio crash is resolved upstream.
 */
@Singleton
class Gemma4VoiceRecognizer @Inject constructor() : VoiceRecognizer {

    override suspend fun ensureReady(): Result<Unit> = Result.success(Unit)

    override suspend fun recognize(
        clip: VoiceUtteranceClip,
        context: VoiceRecognitionContext,
    ): VoiceRecognitionResult? = null
}
