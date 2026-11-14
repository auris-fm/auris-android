package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceIntent
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceResponse
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackContextProvider
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoiceActionOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoiceBookmarkSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoiceChapterSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoiceCloudRouteSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoiceEffectsSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoicePlaybackIntentExecutor
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoicePlaybackQuerySink
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoicePlaybackSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoiceQueueSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoiceSleepSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoiceStatsQuerySink
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoiceVolumeSink

/**
 * Hand-built executor stand-in for Robolectric tests.
 *
 * Mockito stubs on [VoicePlaybackIntentExecutor] do NOT intercept inside the Robolectric sandbox:
 * the sandbox reloads the class and the inline mock's instrumentation never applies to the reload,
 * so invocations return defaults and the recorded answer never runs. Every Robolectric test that
 * drives [VoiceControlService] through this boundary uses this subclass instead. The production
 * constructor only stores its dependencies, so null stand-ins are safe for the members these
 * tests never reach.
 */
// Constructor-free stand-in for a class dependency these tests never invoke.
private fun <T : Any> stubOf(type: Class<T>): T = when {
    type.isInterface -> org.mockito.Mockito.mock(type)

    else -> {
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as sun.misc.Unsafe
        @Suppress("UNCHECKED_CAST")
        unsafe.allocateInstance(type) as T
    }
}

class StubVoiceExecutor(
    private val executeBehavior: suspend (VoiceIntent, VoicePlaybackIntentExecutor.TurnOutcome, ((VoiceActionOutcome) -> Unit)?) -> VoiceResponse =
        { _, _, _ -> VoiceResponse.Silent },
) : VoicePlaybackIntentExecutor(
    stubOf(VoicePlaybackSink::class.java),
    stubOf(VoiceEffectsSink::class.java),
    stubOf(VoiceVolumeSink::class.java),
    stubOf(VoiceSleepSink::class.java),
    stubOf(VoiceChapterSink::class.java),
    stubOf(VoiceBookmarkSink::class.java),
    stubOf(VoiceCloudRouteSink::class.java),
    stubOf(PlaybackContextProvider::class.java),
    stubOf(VoiceQueueSink::class.java),
    stubOf(VoicePlaybackQuerySink::class.java),
    stubOf(VoiceStatsQuerySink::class.java),
    stubOf(GracePeriodSignal::class.java),
    stubOf(PlaybackManager::class.java),
    stubOf(au.com.shiftyjelly.pocketcasts.voicecontrol.playback.PlaybackContextMonitor::class.java),
) {
    override suspend fun execute(
        intent: VoiceIntent,
        outcome: VoicePlaybackIntentExecutor.TurnOutcome,
        onOutcome: ((VoiceActionOutcome) -> Unit)?,
        timingRequestId: String?,
    ): VoiceResponse = executeBehavior(intent, outcome, onOutcome)
}
