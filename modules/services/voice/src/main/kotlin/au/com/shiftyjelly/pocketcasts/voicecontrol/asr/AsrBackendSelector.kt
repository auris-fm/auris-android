package au.com.shiftyjelly.pocketcasts.voicecontrol.asr

import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AsrBackendSelector @Inject constructor(
    private val deviceProbe: DeviceProbe,
    private val whisperCppBackend: Lazy<WhisperCppBackend>,
) {

    /** Manual override: force a specific backend. Set to "whisper-cpp", "sensevoice", or "npu". */
    var manualOverride: String? = null

    fun select(): AsrBackend {
        val override = manualOverride
        if (override != null) {
            return selectByOverride(override)
        }
        return selectByMatrix()
    }

    private fun selectByOverride(override: String): AsrBackend {
        return when (override.lowercase()) {
            "whisper-cpp" -> whisperCppBackend.get()
            "sensevoice" -> error("SenseVoice backend is not yet implemented (Phase 2)")
            "npu" -> error("NPU backend is not yet implemented (Phase 3)")
            else -> error("Unknown backend override: $override")
        }
    }

    private fun selectByMatrix(): AsrBackend {
        // Future matrix:
        // 1. Snapdragon + NPU available + NPU backend shipped -> WhisperNpuBackend
        // 2. SenseVoice shipped + OS language in {zh, en, ja, ko, yue} -> SenseVoiceBackend
        // 3. Default -> WhisperCppBackend
        //
        // Phase 1: always select whisper.cpp (the universal backend).
        return whisperCppBackend.get()
    }
}
