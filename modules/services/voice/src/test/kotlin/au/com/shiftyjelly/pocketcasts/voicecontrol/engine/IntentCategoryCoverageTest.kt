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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * The routing name a recording publishes is `routing.predicted_intent`, which the upload spec fixes
 * as `<tool>.<action>` from the Voice Intents tool schema, or `cloud_route` alone for a cloud route.
 * The server expands it into the object name (`route-<intent>`), so this value decides where a
 * recording is filed and what training data can say about it.
 *
 * Two properties are the spec's, not a convention: the name is derived from the intent's type and
 * action, so it is stable and never carries argument values; and it must actually distinguish the
 * actions, because a shared name makes two different decisions indistinguishable in the record.
 */
class IntentCategoryCoverageTest {

    private fun predictedIntentFor(intent: VoiceIntent): String {
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
    fun `the name is the tool and action from the schema`() {
        // One representative per tool, spanning every sealed family the app can route.
        val cases = listOf(
            VoiceIntent.Playback.Pause to "playback.pause",
            VoiceIntent.Playback.SeekRelative(30) to "playback.seek_relative",
            VoiceIntent.Playback.NextEpisode to "playback.next_episode",
            VoiceIntent.Effects.SetSpeed(1.5) to "effects.set_speed",
            VoiceIntent.Volume.SetVolume(5) to "volume.set_volume",
            VoiceIntent.Sleep.Set(30) to "sleep.set",
            VoiceIntent.Chapter.NextChapter to "chapter.next",
            VoiceIntent.Bookmark.Add() to "bookmark.add",
            VoiceIntent.Queue.Clear to "queue.clear",
            VoiceIntent.PlaybackQuery.WhatsPlaying to "playback_query.whats_playing",
            VoiceIntent.StatsQuery.ListeningTime() to "stats_query.listening_time",
            VoiceIntent.CloudRoute(request = "anything", tier = CloudTier.Free) to "cloud_route",
        )
        cases.forEach { (intent, expected) ->
            assertEquals(
                "${intent::class.simpleName} must publish its schema name",
                expected,
                predictedIntentFor(intent),
            )
        }
    }

    @Test
    fun `different actions in the same tool are different names`() {
        // The defect this replaced used one name per tool, so every effects action -- speed, volume
        // boost, trim mode -- filed the same way and the record could not say what was done.
        val names = listOf(
            VoiceIntent.Effects.SetSpeed(1.5),
            VoiceIntent.Effects.AdjustSpeed(0.2),
            VoiceIntent.Effects.SetVolumeBoost(true),
            VoiceIntent.Queue.Clear,
            VoiceIntent.Queue.AddTop(),
            VoiceIntent.Bookmark.Add(),
            VoiceIntent.Bookmark.Delete("ref"),
        ).map { predictedIntentFor(it) }
        assertEquals("each action must name itself, got $names", names.size, names.toSet().size)
    }

    @Test
    fun `the name carries no argument values`() {
        // The spec makes content-exclusion a property of the field: "never from argument values or
        // request text". Two different arguments to the same action must publish the same name.
        assertEquals(
            predictedIntentFor(VoiceIntent.Playback.SeekRelative(30)),
            predictedIntentFor(VoiceIntent.Playback.SeekRelative(-90)),
        )
        assertEquals(
            predictedIntentFor(VoiceIntent.Bookmark.Add(title = "the secret plan")),
            predictedIntentFor(VoiceIntent.Bookmark.Add(title = null)),
        )

        // A cloud route's request is the utterance itself, on both the model-chosen and the
        // fallback path. Naming the field after it would write the user's speech into the record.
        val utterance = "play the seventh episode of the podcast I added last tuesday"
        val chosen = predictedIntentFor(VoiceIntent.CloudRoute(request = utterance, tier = CloudTier.Free))
        val fallback = predictedIntentFor(
            VoiceIntent.CloudRoute(request = utterance, tier = CloudTier.Free, origin = VoiceIntent.CloudRouteOrigin.RoutingFailure),
        )
        assertEquals("cloud_route", chosen)
        assertEquals("cloud_route", fallback)
        assertNotEquals(utterance, chosen)

        // The value is stored as written; the server lowercases it and turns underscores into the
        // Separator when it builds the object name. What the field must not contain is anything
        // the schema cannot produce: the tool and action vocabulary is lowercase with underscores.
        listOf(
            VoiceIntent.Playback.SeekRelative(1),
            VoiceIntent.Bookmark.Add(),
            VoiceIntent.StatsQuery.ListeningTime(),
        ).forEach {
            val name = predictedIntentFor(it)
            assertTrue(
                "'$name' must be lowercase schema vocabulary, at most 32 chars",
                name.matches(Regex("[a-z0-9_]+(\\.[a-z0-9_]+)?")) && name.length <= 32,
            )
        }
    }
}
