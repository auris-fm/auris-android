package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the microphone is actually capturing.
 *
 * The settings screen runs in a different component from the foreground service and cannot read
 * its state, so the service publishes this one bit. It is a boolean rather than a state enum on
 * purpose: the only question the status line has to answer is whether capture is running, and
 * `Starting`/`Idle`/`StartFailed` collapse into "not yet" or "not".
 */
@Singleton
class VoiceControlCaptureStateHolder @Inject constructor() {
    private val mutableCapturing = MutableStateFlow(false)

    val capturing: StateFlow<Boolean> = mutableCapturing.asStateFlow()

    fun update(capturing: Boolean) {
        mutableCapturing.value = capturing
    }
}
