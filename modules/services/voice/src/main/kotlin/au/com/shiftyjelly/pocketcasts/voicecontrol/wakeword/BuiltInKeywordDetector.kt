package au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BuiltInKeywordDetector @Inject constructor() : WakeWordDetector {

    override val isReady: Boolean
        get() = true // Bundled model is always available

    override suspend fun detect(segment: FloatArray, sampleRateHz: Int): WakeWordResult {
        // Placeholder: real implementation loads a bundled KWS model
        // and runs inference on the segment.
        // For now, returns not-detected to avoid false positives.
        return WakeWordResult(detected = false, confidence = 0f)
    }

    override fun release() {}
}
