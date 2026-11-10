package au.com.shiftyjelly.pocketcasts.voicecontrol.engine

import android.content.Context
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.TranslationStage
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceAudioProcessor
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.AudioFeedbackRenderer
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceIntent
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceIntent.CloudTier
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.AuthorityDecision
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionBudget
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ContributionRetentionSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SegmentObserver
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordDetector
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * The routing name a recording publishes chooses the folder the server files it under, so every
 * intent family the app can actually route must name itself. A family left to a catch-all makes
 * those captures indistinguishable from each other in the record: the app did decide, so the
 * record must say which family it decided on.
 */
class IntentCategoryCoverageTest {

    private fun categoryFor(intent: VoiceIntent): String {
        val engine = VoiceAsrEngine(
            voiceAudioProcessor = mock<VoiceAudioProcessor>(),
            utteranceFilter = mock<UtteranceFilter>(),
            intentRecognizer = mock<VoiceRecognizer>(),
            wakeWordDetector = mock<WakeWordDetector>(),
            gracePeriodSignal = GracePeriodSignal(),
            audioFeedbackRenderer = mock<AudioFeedbackRenderer>(),
            currentLocale = { Locale.US },
            translationStage = mock<TranslationStage>(),
            collectionAuthority = { AuthorityDecision(collectionPermitted = false, fullRevocation = true) },
            retentionSink = ContributionRetentionSink(CollectionBudget(1L shl 20)),
            segmentObserver = mock<SegmentObserver>(),
            context = mock<Context>(),
        )
        return engine.intentCategory(intent)
    }

    @Test
    fun `every routable intent family names itself`() {
        // One representative per sealed family -- the mapping keys on the family type, so a leaf is
        // Enough to prove the family is handled.
        val cases = listOf(
            VoiceIntent.Playback.Pause to "playback",
            VoiceIntent.Effects.SetSpeed(1.5) to "effects",
            VoiceIntent.Volume.AdjustVolume(1) to "volume",
            VoiceIntent.Sleep.Set(30) to "sleep",
            VoiceIntent.Chapter.NextChapter to "chapter",
            VoiceIntent.Bookmark.Add() to "bookmark",
            VoiceIntent.Queue.Clear to "queue",
            VoiceIntent.PlaybackQuery.WhatsPlaying to "query",
            VoiceIntent.StatsQuery.ListeningTime() to "stats",
            VoiceIntent.CloudRoute(request = "anything", tier = CloudTier.Free) to "cloud",
        )
        val distinct = cases.map { it.second }.toSet()
        assertEquals(
            "each family must have its own name, got $distinct",
            cases.size,
            distinct.size,
        )
        cases.forEach { (intent, expected) ->
            assertEquals(
                "${intent::class.simpleName} must name its own family, not a catch-all",
                expected,
                categoryFor(intent),
            )
        }
    }

    @Test
    fun `a cloud route is named by its type, never by its request text`() {
        // A cloud route's request is free-form: a model-chosen route may carry the whole utterance,
        // and the fallback path carries the transcript. Naming the folder after it would write the
        // user's speech into the record's routing field.
        val utterance = "play the seventh episode of the podcast I added last tuesday"
        val chosen = VoiceIntent.CloudRoute(request = utterance, tier = CloudTier.Free)
        val fallback = VoiceIntent.CloudRoute(
            request = utterance,
            tier = CloudTier.Free,
            origin = VoiceIntent.CloudRouteOrigin.RoutingFailure,
        )
        assertEquals("cloud", categoryFor(chosen))
        assertEquals("cloud", categoryFor(fallback))
        assertNotEquals(utterance, categoryFor(chosen))
    }
}
