package au.com.shiftyjelly.pocketcasts.voice.model

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import au.com.shiftyjelly.pocketcasts.voice.intent.VoiceRecognitionResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class AndroidSpeechRecognizer @Inject constructor(
    @ApplicationContext private val context: Context,
) : VoiceRecognizer {

    override suspend fun recognize(
        clip: VoiceUtteranceClip,
        context: VoiceRecognitionContext,
    ): VoiceRecognitionResult? {
        if (!SpeechRecognizer.isRecognitionAvailable(this.context)) {
            Timber.w("Speech recognition not available on this device")
            return null
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        return suspendCancellableCoroutine { continuation ->
            val recognizer = SpeechRecognizer.createSpeechRecognizer(this.context)

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Timber.i("Speech recognizer ready for speech")
                }

                override fun onBeginningOfSpeech() {
                    Timber.i("Speech beginning detected")
                }

                override fun onRmsChanged(rmsdB: Float) { }

                override fun onBufferReceived(buffer: ByteArray?) { }

                override fun onEndOfSpeech() {
                    Timber.i("Speech ended")
                }

                override fun onError(error: Int) {
                    Timber.w("Speech recognition error: $error")
                    recognizer.destroy()
                    if (continuation.isActive) continuation.resume(null)
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                    val transcript = matches?.firstOrNull() ?: ""
                    val confidence = scores?.firstOrNull() ?: 0f
                    Timber.i("Recognition result: '$transcript' confidence=$confidence")
                    recognizer.destroy()
                    if (continuation.isActive) {
                        continuation.resume(VoiceRecognitionResult(transcript, confidence))
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    // Not used for final result
                }

                override fun onEvent(eventType: Int, params: Bundle?) { }
            })

            recognizer.startListening(intent)
            continuation.invokeOnCancellation { recognizer.destroy() }
        }
    }
}
