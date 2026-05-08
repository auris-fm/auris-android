package au.com.shiftyjelly.pocketcasts.voice.di

import au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.voice.audio.EnergyVoiceAudioSegmenter
import au.com.shiftyjelly.pocketcasts.voice.audio.MicrophoneCapture
import au.com.shiftyjelly.pocketcasts.voice.audio.VoiceAudioProcessor
import au.com.shiftyjelly.pocketcasts.voice.audio.VoiceAudioSegmenter
import au.com.shiftyjelly.pocketcasts.voice.gate.VoiceControlGate
import au.com.shiftyjelly.pocketcasts.voice.gate.VoiceControlRule
import au.com.shiftyjelly.pocketcasts.voice.intent.DeterministicVoiceIntentInterpreter
import au.com.shiftyjelly.pocketcasts.voice.intent.VoiceIntentInterpreter
import au.com.shiftyjelly.pocketcasts.voice.model.AndroidSpeechRecognizer
import au.com.shiftyjelly.pocketcasts.voice.model.VoiceRecognizer
import au.com.shiftyjelly.pocketcasts.voice.playback.PlaybackContextMonitor
import au.com.shiftyjelly.pocketcasts.voice.playback.PlaybackContextRule
import au.com.shiftyjelly.pocketcasts.voice.playback.PlaybackManagerVoicePlaybackSink
import au.com.shiftyjelly.pocketcasts.voice.playback.VoicePlaybackSink
import au.com.shiftyjelly.pocketcasts.voice.route.AndroidAudioRouteMonitor
import au.com.shiftyjelly.pocketcasts.voice.route.AudioRouteMonitor
import au.com.shiftyjelly.pocketcasts.voice.route.AudioRoutePolicyRule
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

@Module
@InstallIn(SingletonComponent::class)
abstract class VoiceControlModule {
    @Binds abstract fun bindVoiceAudioSegmenter(impl: EnergyVoiceAudioSegmenter): VoiceAudioSegmenter

    @Binds abstract fun bindVoiceRecognizer(impl: AndroidSpeechRecognizer): VoiceRecognizer

    @Binds abstract fun bindVoiceIntentInterpreter(impl: DeterministicVoiceIntentInterpreter): VoiceIntentInterpreter

    @Binds abstract fun bindVoicePlaybackSink(impl: PlaybackManagerVoicePlaybackSink): VoicePlaybackSink

    @Binds abstract fun bindAudioRouteMonitor(impl: AndroidAudioRouteMonitor): AudioRouteMonitor

    companion object {
        @Provides
        @Singleton
        fun provideVoiceControlGate(
            playbackContextMonitor: PlaybackContextMonitor,
            audioRouteMonitor: AudioRouteMonitor,
            settings: Settings,
            @ApplicationScope scope: CoroutineScope,
        ): VoiceControlGate {
            val rules: List<VoiceControlRule> = listOf(
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
