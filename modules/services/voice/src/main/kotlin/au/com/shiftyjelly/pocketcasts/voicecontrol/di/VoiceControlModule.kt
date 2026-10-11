package au.com.shiftyjelly.pocketcasts.voicecontrol.di

import android.content.Context
import android.media.AudioManager
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.telephony.TelephonyManager
import au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.repositories.chromecast.CastManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.GoogleMlKitTranslator
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.TranslationStage
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.CaptureHardware
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.MicrophoneCapture
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.OboeCaptureHardware
import au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackBufferRecorder
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.AudioFeedbackRenderer
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.EarconPlayer
import au.com.shiftyjelly.pocketcasts.voicecontrol.foreground.ForegroundStateMonitor
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.EnabledByUserCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlGate
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlRule
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.AppInForegroundCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.BatteryOkCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.DeviceSupportedCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.GracePeriodActiveCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.ModelsReadyCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.NotCastingCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.NotOnCallCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions.OtherAppPlayingCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.EmbeddingIntentMatcher
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.EntityExtractor
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.embedding.BpeTokenizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.embedding.EmbeddingEngine
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.embedding.JniEmbeddingEngine
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.embedding.TextTokenizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.entity.GrammarEntityExtractor
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.lfm.LfmIntentRouter
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.ModelManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.AudioManagerVolumeSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.CloudPlaybackContextState
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.CloudRouteAnalytics
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.CloudRouteSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackContextActiveCondition
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackContextMonitor
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackContextProvider
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackManagerBookmarkSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackManagerChapterSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackManagerEffectsSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackManagerPlaybackContextProvider
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackManagerPlaybackSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackQuerySink
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.SleepTimerSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.StatsQuerySink
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.TimberCloudRouteAnalytics
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.UpNextQueueSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoiceBookmarkSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoiceChapterSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoiceCloudRouteSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoiceEffectsSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoicePlaybackQuerySink
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoicePlaybackSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoiceQueueSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoiceSleepSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoiceStatsQuerySink
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoiceVolumeSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AndroidAudioRouteMonitor
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AndroidRouteAcquisitionPlatform
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRouteMonitor
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.RouteAcquisition
import au.com.shiftyjelly.pocketcasts.voicecontrol.route.RouteAcquisitionPlatform
import au.com.shiftyjelly.pocketcasts.voicecontrol.tts.AndroidPlatformTtsEngine
import au.com.shiftyjelly.pocketcasts.voicecontrol.tts.TtsEngine
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.OpenWakeWordDetector
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordDetector
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.Locale
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

@Module
@InstallIn(SingletonComponent::class)
abstract class VoiceControlModule {

    @Binds abstract fun bindVoiceRecognizer(impl: LfmIntentRouter): VoiceRecognizer

    @Binds abstract fun bindVoicePlaybackSink(impl: PlaybackManagerPlaybackSink): VoicePlaybackSink

    @Binds abstract fun bindVoiceEffectsSink(impl: PlaybackManagerEffectsSink): VoiceEffectsSink

    @Binds abstract fun bindVoiceVolumeSink(impl: AudioManagerVolumeSink): VoiceVolumeSink

    @Binds abstract fun bindVoiceSleepSink(impl: SleepTimerSink): VoiceSleepSink

    @Binds abstract fun bindVoiceChapterSink(impl: PlaybackManagerChapterSink): VoiceChapterSink

    @Binds abstract fun bindVoiceBookmarkSink(impl: PlaybackManagerBookmarkSink): VoiceBookmarkSink

    @Binds abstract fun bindVoiceCloudRouteSink(impl: CloudRouteSink): VoiceCloudRouteSink

    @Binds abstract fun bindCloudRouteAnalytics(impl: TimberCloudRouteAnalytics): CloudRouteAnalytics

    @Binds abstract fun bindPlaybackContextProvider(impl: PlaybackManagerPlaybackContextProvider): PlaybackContextProvider

    @Binds abstract fun bindVoiceQueueSink(impl: UpNextQueueSink): VoiceQueueSink

    @Binds abstract fun bindVoicePlaybackQuerySink(impl: PlaybackQuerySink): VoicePlaybackQuerySink

    @Binds abstract fun bindVoiceStatsQuerySink(impl: StatsQuerySink): VoiceStatsQuerySink

    @Binds abstract fun bindAudioRouteMonitor(impl: AndroidAudioRouteMonitor): AudioRouteMonitor

    @Binds abstract fun bindTextTokenizer(impl: BpeTokenizer): TextTokenizer

    @Binds abstract fun bindEmbeddingEngine(impl: JniEmbeddingEngine): EmbeddingEngine

    @Binds abstract fun bindEntityExtractor(impl: GrammarEntityExtractor): EntityExtractor

    @Binds abstract fun bindWakeWordDetector(impl: OpenWakeWordDetector): WakeWordDetector

    @Binds abstract fun bindTranslationStage(impl: GoogleMlKitTranslator): TranslationStage

    companion object {
        @Provides @Singleton
        @Named("embeddingModel")
        fun provideEmbeddingModelFile(manager: ModelManager): File = manager.embeddingModelFile

        @Provides @Singleton
        @Named("embeddingTokenizer")
        fun provideEmbeddingTokenizerFile(manager: ModelManager): File = manager.tokenizerModelFile

        @Provides @Singleton
        fun provideSpokenTemplateResolver(
            @ApplicationContext context: android.content.Context,
        ): au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.SpokenTemplateResolver = au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.SpokenTemplateResolver(context)

        @Provides @Singleton
        fun provideDeviceProbe(): au.com.shiftyjelly.pocketcasts.voicecontrol.asr.DeviceProbe = au.com.shiftyjelly.pocketcasts.voicecontrol.asr.DeviceProbe()

        @Provides @Singleton
        fun provideCurrentLocale(): () -> Locale = { Locale.getDefault() }

        @Provides @Singleton
        fun provideCaptureHardware(): CaptureHardware = OboeCaptureHardware()

        @Provides @Singleton
        fun provideRouteAcquisitionPlatform(
            @ApplicationContext context: Context,
            microphoneCapture: MicrophoneCapture,
        ): RouteAcquisitionPlatform = AndroidRouteAcquisitionPlatform(context, microphoneCapture)

        @Provides @Singleton
        fun provideRouteAcquisition(platform: RouteAcquisitionPlatform): RouteAcquisition = RouteAcquisition(platform)

        @Provides @Singleton
        fun provideDeviceSupportedCondition(): DeviceSupportedCondition = DeviceSupportedCondition()

        @Provides @Singleton
        fun provideModelsReadyCondition(): ModelsReadyCondition = ModelsReadyCondition()

        @Provides @Singleton
        @Suppress("DEPRECATION") // TelephonyManager.callState deprecated in API 31; CallStateCallback requires CALL_STATE permission
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

        @Provides @Singleton
        fun provideGracePeriodActiveCondition(
            gracePeriodSignal: GracePeriodSignal,
            @ApplicationScope scope: CoroutineScope,
        ): GracePeriodActiveCondition = GracePeriodActiveCondition(gracePeriodSignal, scope)

        @Provides @Singleton
        fun provideOtherAppPlayingCondition(
            @ApplicationContext context: Context,
            playbackContextMonitor: PlaybackContextMonitor,
            audioFeedbackRenderer: AudioFeedbackRenderer,
            @ApplicationScope scope: CoroutineScope,
        ): OtherAppPlayingCondition = OtherAppPlayingCondition(
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager,
            hostIsPlaying = playbackContextMonitor.isHostAudioActive,
            // Our own feedback audio is host audio: without this the gate reads an earcon as a
            // foreign app and closes the microphone seconds after acknowledging a wake word.
            hasEmittedAudio = audioFeedbackRenderer.hasEmittedAudio,
            lastEmittedAtMs = audioFeedbackRenderer.lastEmittedAtMs,
            scope = scope,
        )

        @Provides
        @Singleton
        fun provideVoiceControlGate(
            playbackContextMonitor: PlaybackContextMonitor,
            settings: Settings,
            deviceSupported: DeviceSupportedCondition,
            modelsReady: ModelsReadyCondition,
            notOnCall: NotOnCallCondition,
            notCasting: NotCastingCondition,
            batteryOk: BatteryOkCondition,
            appInForeground: AppInForegroundCondition,
            otherAppPlaying: OtherAppPlayingCondition,
            gracePeriodActive: GracePeriodActiveCondition,
            @ApplicationScope scope: CoroutineScope,
        ): VoiceControlGate {
            val rules: List<VoiceControlRule> = listOf(
                // Setup group
                EnabledByUserCondition(settings, scope),
                deviceSupported,
                modelsReady,
                // Conflicts group — the route does not gate voice control here. Capture eligibility
                // is `MicExposure` in `ListeningModePolicy.resolve`: only `Isolated` permits capture,
                // so `Exposed` (built-in) blocks as well as `NoMic`.
                notOnCall,
                notCasting,
                batteryOk,
                otherAppPlaying,
                // Context group
                appInForeground,
                PlaybackContextActiveCondition(playbackContextMonitor.context, scope),
                gracePeriodActive,
            )
            return VoiceControlGate(rules = rules, scope = scope)
        }

        @Provides
        @Singleton
        fun provideEarconPlayer(
            @ApplicationContext context: Context,
            playbackBufferRecorder: PlaybackBufferRecorder,
        ): EarconPlayer = EarconPlayer(context, playbackBufferRecorder)

        @Provides
        @Singleton
        fun provideAudioFeedbackRenderer(
            earconPlayer: EarconPlayer,
            ttsEngine: TtsEngine,
        ): AudioFeedbackRenderer = AudioFeedbackRenderer(earconPlayer, ttsEngine)

        /**
         * Supplies the monitor's spoken-pause seam to Dagger.
         *
         * Same reason as the seams below: a default value on an `@Inject` constructor parameter
         * does NOT satisfy Dagger, so without this binding the graph fails to build
         * (`[Dagger/MissingBinding]` for `java.lang.Boolean`, injected at `PlaybackContextMonitor`).
         *
         * It is `false`, the monitor's own default: the injected predicate is how production reports
         * that a stop was a user's spoken pause, and the default keeps that report absent rather than
         * invented. The seam stays on the constructor so a test can present one directly.
         */
        @Provides
        fun provideIsSpokenUserPause(): Boolean = false

        /**
         * Supplies the TTS engine.
         *
         * Provided rather than `@Binds`-bound to an `@Inject` constructor: that constructor takes a
         * synthesizer seam whose type is a nullable function, and a nullable function type is not a
         * binding Dagger can match -- the graph asks for a non-null `Function1<Function1<Boolean,
         * Unit>, TextToSpeech>` and the whole build fails with `[Dagger/MissingBinding]`. Passing the
         * factory here keeps the seam on the constructor (so a test can present its own) without
         * asking Dagger to resolve it.
         *
         * The factory body is what the engine did when the seam was absent: construct a real
         * `TextToSpeech` and report whether initialisation succeeded.
         */
        @Provides
        @Singleton
        fun provideTtsEngine(
            @ApplicationContext context: Context,
            playbackBufferRecorder: PlaybackBufferRecorder,
        ): TtsEngine = AndroidPlatformTtsEngine(
            context,
            playbackBufferRecorder,
        ) { onInitialized ->
            TextToSpeech(context.applicationContext) { status ->
                onInitialized(status == TextToSpeech.SUCCESS)
            }
        }

        /**
         * Supplies the monitor's optional enumeration seam to Dagger.
         *
         * A default value on an `@Inject` constructor parameter does NOT satisfy Dagger: the graph
         * still needs a binding for `Function0<EnumeratedDevices>`, and without this one the whole
         * Hilt graph fails to build (`[Dagger/MissingBinding]` at `hiltJavaCompileDebug`).
         *
         * It returns `null`, which is what the monitor's own default was: "no injected enumeration,
         * use the platform's". Returning an empty enumeration instead would be a behaviour change —
         * it would report that the device offers no outputs and no inputs, and `readAvailability()`
         * is built from this. The seam stays on the constructor so a test can present one directly;
         * `AndroidAudioRouteMonitorTest` does that by constructing the monitor without Hilt.
         */
        @Provides
        fun provideEnumeratedDeviceTypes(): (() -> AndroidAudioRouteMonitor.EnumeratedDevices)? = null

        /**
         * The live capture route, read from the capture stream's own device id. Provided rather than
         * defaulted because this is the observation PR124 requires: without it the monitor can only
         * see device availability, which is not the routing. Null while no capture stream is live,
         * which the monitor reads as "unknown" rather than as "no microphone".
         */
        @Provides
        fun provideCaptureRoutedDeviceId(): (() -> Int?)? = { au.com.shiftyjelly.pocketcasts.voicecontrol.audio.MicrophoneCapture.routedCaptureDeviceId() }

        @Provides
        @Singleton
        fun provideCloudAudioPlayer(
            @ApplicationContext context: Context,
            audioFeedbackRenderer: AudioFeedbackRenderer,
            playbackBufferRecorder: PlaybackBufferRecorder,
            routedOutputObserver: au.com.shiftyjelly.pocketcasts.voicecontrol.route.RoutedOutputObserver,
        ): au.com.shiftyjelly.pocketcasts.voicecontrol.audio.CloudAudioPlayer = au.com.shiftyjelly.pocketcasts.voicecontrol.audio.CloudAudioPlayer(
            context = context,
            playbackBufferRecorder = playbackBufferRecorder,
            // The production connection: the monitor reads what this player observes, so a live stream
            // decides the route instead of the enumerated availability.
            routedOutputObserver = routedOutputObserver,
        ).apply {
            // The cloud answer is audible output, but it is not produced by the feedback
            // renderer — so without this the gate's "we are making sound" stamp goes stale
            // while an answer plays, the answer is read as a foreign app, and the microphone
            // is cut mid-answer. Joining the renderer's existing signal is the systematic fix:
            // the gate keeps one question ("are we the sound?") and gains no cloud special case.
            onPlaybackAudibleChanged = { audible ->
                if (audible) audioFeedbackRenderer.noteAudibleNow()
            }
        }
    }
}
