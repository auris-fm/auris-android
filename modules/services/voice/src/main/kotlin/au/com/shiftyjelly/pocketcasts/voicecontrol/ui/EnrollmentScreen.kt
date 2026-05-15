package au.com.shiftyjelly.pocketcasts.voicecontrol.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.MicrophoneCapture
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceAudioSegmenter
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceSegmenterResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceEnrollmentManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceEnrollmentState
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceUtteranceClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

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
    onEnroll: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by manager.state.collectAsState()
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    var utterances by remember { mutableStateOf(listOf<VoiceUtteranceClip>()) }
    var isRecording by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current

    fun recordAndProcess() {
        scope.launch {
            isRecording = true
            errorMessage = null
            try {
                val clip = captureUtterance(microphoneCapture, segmenter)
                if (clip != null) {
                    utterances = utterances + clip
                    step++
                    if (step >= PHRASES.size) {
                        try {
                            withContext(Dispatchers.IO) { manager.enroll(utterances) }
                        } catch (e: Exception) {
                            Timber.e(e, "Enrollment failed")
                            errorMessage = "Enrollment failed: ${e.message}. Tap Record to try again."
                            utterances = emptyList()
                            step = 0
                        }
                    }
                } else {
                    errorMessage = "No speech detected. Please try again."
                }
            } catch (e: Exception) {
                Timber.e(e, "Capture failed")
                errorMessage = "Capture failed: ${e.message}. Please try again."
            }
            isRecording = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (granted) {
                recordAndProcess()
            } else {
                errorMessage = "Microphone permission is required for enrollment"
            }
        },
    )

    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Voice Enrollment", style = MaterialTheme.typography.h5)
        Spacer(Modifier.height(24.dp))

        when (val s = state) {
            is VoiceEnrollmentState.Enrolled -> {
                Text("Your voice has been enrolled!")
                Spacer(Modifier.height(16.dp))
                Button(onClick = onEnroll) { Text("Start Voice Control") }
            }

            is VoiceEnrollmentState.Enrolling -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Creating your voice profile...")
            }

            is VoiceEnrollmentState.NotEnrolled -> {
                if (step < PHRASES.size) {
                    Text("Read the following phrase aloud:", style = MaterialTheme.typography.subtitle1)
                    Spacer(Modifier.height(16.dp))
                    Text("\"${PHRASES[step]}\"", style = MaterialTheme.typography.h6)
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = step.toFloat() / PHRASES.size,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Step ${step + 1} of ${PHRASES.size}")

                    if (errorMessage != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(errorMessage!!, color = MaterialTheme.colors.error)
                    }

                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            val permission = Manifest.permission.RECORD_AUDIO
                            val hasPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
                            if (hasPermission) {
                                recordAndProcess()
                            } else {
                                permissionLauncher.launch(permission)
                            }
                        },
                        enabled = !isRecording,
                    ) {
                        Text(if (isRecording) "Recording..." else "Record")
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onDismiss) { Text("Cancel") }
    }
}

private suspend fun captureUtterance(
    capture: MicrophoneCapture,
    segmenter: VoiceAudioSegmenter,
): VoiceUtteranceClip? = withContext(Dispatchers.IO) {
    var clip: VoiceUtteranceClip? = null
    try {
        capture.startCapture()
            .onEach { frame ->
                val result = segmenter.process(frame)
                if (result is VoiceSegmenterResult.SpeechEnded) {
                    clip = VoiceUtteranceClip.fromFrames(result.frames)
                }
            }
            .first { clip != null }
    } finally {
        capture.stopCapture()
    }
    clip
}
