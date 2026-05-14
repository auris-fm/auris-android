package au.com.shiftyjelly.pocketcasts.voicecontrol.di

import au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.WhisperRecognizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.MicrophoneCapture
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.SileroVadSegmenter
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceAudioProcessor
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceAudioSegmenter
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.UserNotDisabledRule
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlGate
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlRule
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.SmolLmIntentParser
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.CascadedVoiceRecognizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.ModelManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackContextMonitor
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackContextRule
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackManagerVoicePlaybackSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoicePlaybackSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AndroidAudioRouteMonitor
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRouteMonitor
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRoutePolicyRule
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

@Module
@InstallIn(SingletonComponent::class)
abstract class VoiceControlModule {

    @Binds abstract fun bindVoiceAudioSegmenter(impl: SileroVadSegmenter): VoiceAudioSegmenter

    // Cascaded pipeline: Whisper ASR -> SmolLM2 360M intent parser
    @Binds abstract fun bindVoiceRecognizer(impl: CascadedVoiceRecognizer): VoiceRecognizer

    @Binds abstract fun bindVoicePlaybackSink(impl: PlaybackManagerVoicePlaybackSink): VoicePlaybackSink

    @Binds abstract fun bindAudioRouteMonitor(impl: AndroidAudioRouteMonitor): AudioRouteMonitor

    companion object {
        @Provides @Singleton
        fun provideWhisperModelFile(manager: ModelManager): File = manager.whisperModelFile

        @Provides @Singleton
        fun provideSmolLmModelFile(manager: ModelManager): File = manager.smolLmModelFile

        @Provides
        @Singleton
        fun provideVoiceControlGate(
            playbackContextMonitor: PlaybackContextMonitor,
            audioRouteMonitor: AudioRouteMonitor,
            settings: Settings,
            @ApplicationScope scope: CoroutineScope,
        ): VoiceControlGate {
            val rules: List<VoiceControlRule> = listOf(
                UserNotDisabledRule(settings, scope),
                PlaybackContextRule(playbackContextMonitor.context, scope),
                AudioRoutePolicyRule(audioRouteMonitor.route, settings.voiceControlAudioRoutePolicy.flow, scope),
            )
            return VoiceControlGate(rules = rules, scope = scope)
        }

        @Provides
        @Singleton
        fun provideVoiceAudioProcessor(
            microphoneCapture: MicrophoneCapture,
            voiceAudioSegmenter: VoiceAudioSegmenter,
        ): VoiceAudioProcessor {
            return VoiceAudioProcessor(microphoneCapture, voiceAudioSegmenter)
        }
    }
}
