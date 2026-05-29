package au.com.shiftyjelly.pocketcasts.voicecontrol.di

import au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.NativeVadSegmenter
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceAudioSegmenter
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.UserNotDisabledRule
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlGate
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlRule
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.EmbeddingIntentMatcher
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.EntityExtractor
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.embedding.BpeTokenizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.embedding.EmbeddingEngine
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.embedding.JniEmbeddingEngine
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.embedding.TextTokenizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.entity.GrammarEntityExtractor
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
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

@Module
@InstallIn(SingletonComponent::class)
abstract class VoiceControlModule {

    @Binds abstract fun bindVoiceAudioSegmenter(impl: NativeVadSegmenter): VoiceAudioSegmenter

    @Binds abstract fun bindVoiceRecognizer(impl: EmbeddingIntentMatcher): VoiceRecognizer

    @Binds abstract fun bindVoicePlaybackSink(impl: PlaybackManagerVoicePlaybackSink): VoicePlaybackSink

    @Binds abstract fun bindAudioRouteMonitor(impl: AndroidAudioRouteMonitor): AudioRouteMonitor

    @Binds abstract fun bindTextTokenizer(impl: BpeTokenizer): TextTokenizer

    @Binds abstract fun bindEmbeddingEngine(impl: JniEmbeddingEngine): EmbeddingEngine

    @Binds abstract fun bindEntityExtractor(impl: GrammarEntityExtractor): EntityExtractor

    companion object {
        @Provides @Singleton
        @Named("embeddingModel")
        fun provideEmbeddingModelFile(manager: ModelManager): File = manager.embeddingModelFile

        @Provides @Singleton
        @Named("embeddingTokenizer")
        fun provideEmbeddingTokenizerFile(manager: ModelManager): File = manager.tokenizerModelFile

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
    }
}
