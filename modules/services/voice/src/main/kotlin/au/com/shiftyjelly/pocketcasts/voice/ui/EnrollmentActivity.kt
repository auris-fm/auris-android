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
