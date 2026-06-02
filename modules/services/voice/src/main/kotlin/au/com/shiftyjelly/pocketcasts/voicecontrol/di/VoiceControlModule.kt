package au.com.shiftyjelly.pocketcasts.voicecontrol.di

import android.content.Context
import android.os.PowerManager
import android.telephony.TelephonyManager
import au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.repositories.chromecast.CastManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.NativeVadSegmenter
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceAudioSegmenter
import au.com.shiftyjelly.pocketcasts.voicecontrol.foreground.ForegroundStateMonitor
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.EnabledByUserCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlGate
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlRule
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.AppInForegroundCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.BatteryOkCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.DeviceSupportedCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.ModelsReadyCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.NotCastingCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.NotOnCallCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.EmbeddingIntentMatcher
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.EntityExtractor
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.embedding.BpeTokenizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.embedding.EmbeddingEngine
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.embedding.JniEmbeddingEngine
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.embedding.TextTokenizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.entity.GrammarEntityExtractor
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.ModelManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackContextActiveCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackContextMonitor
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackManagerVoicePlaybackSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoicePlaybackSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AndroidAudioRouteMonitor
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRouteMonitor
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRoutePolicyRule
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.OpenWakeWordDetector
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordDetector
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
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

    @Binds abstract fun bindWakeWordDetector(impl: OpenWakeWordDetector): WakeWordDetector

    companion object {
        @Provides @Singleton
        @Named("embeddingModel")
        fun provideEmbeddingModelFile(manager: ModelManager): File = manager.embeddingModelFile

        @Provides @Singleton
        @Named("embeddingTokenizer")
        fun provideEmbeddingTokenizerFile(manager: ModelManager): File = manager.tokenizerModelFile

        @Provides @Singleton
        fun provideDeviceProbe(): au.com.shiftyjelly.pocketcasts.voicecontrol.asr.DeviceProbe = au.com.shiftyjelly.pocketcasts.voicecontrol.asr.DeviceProbe()

        @Provides @Singleton
        fun provideDeviceSupportedCondition(): DeviceSupportedCondition = DeviceSupportedCondition()

        @Provides @Singleton
        fun provideModelsReadyCondition(): ModelsReadyCondition = ModelsReadyCondition()

        @Provides @Singleton
        @Suppress("DEPRECATION")
        fun provideNotOnCallCondition(
            @ApplicationContext context: Context,
        ): NotOnCallCondition {
            val inCall = try {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                tm?.callState == TelephonyManager.CALL_STATE_OFFHOOK
            } catch (e: SecurityException) {
                false
            }
            return NotOnCallCondition(isInCall = inCall)
        }

        @Provides @Singleton
        fun provideNotCastingCondition(castManager: CastManager): NotCastingCondition = NotCastingCondition()

        @Provides @Singleton
        fun provideBatteryOkCondition(
            @ApplicationContext context: Context,
        ): BatteryOkCondition = BatteryOkCondition(
            isPowerSaveMode = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                ?.isPowerSaveMode == true,
        )

        @Provides @Singleton
        fun provideAppInForegroundCondition(
            foregroundState: ForegroundStateMonitor,
            @ApplicationScope scope: CoroutineScope,
        ): AppInForegroundCondition = AppInForegroundCondition(foregroundState, scope)

        @Provides
        @Singleton
        fun provideVoiceControlGate(
            playbackContextMonitor: PlaybackContextMonitor,
            audioRouteMonitor: AudioRouteMonitor,
            settings: Settings,
            deviceSupported: DeviceSupportedCondition,
            modelsReady: ModelsReadyCondition,
            notOnCall: NotOnCallCondition,
            notCasting: NotCastingCondition,
            batteryOk: BatteryOkCondition,
            appInForeground: AppInForegroundCondition,
            @ApplicationScope scope: CoroutineScope,
        ): VoiceControlGate {
            val rules: List<VoiceControlRule> = listOf(
                // Setup group
                EnabledByUserCondition(settings, scope),
                deviceSupported,
                modelsReady,
                // Conflicts group
                AudioRoutePolicyRule(audioRouteMonitor.route, settings.voiceControlAudioRoutePolicy.flow, scope),
                notOnCall,
                notCasting,
                batteryOk,
                // Context group
                appInForeground,
                PlaybackContextActiveCondition(playbackContextMonitor.context, scope),
            )
            return VoiceControlGate(rules = rules, scope = scope)
        }
    }
}
