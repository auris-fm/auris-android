package au.com.shiftyjelly.pocketcasts.voicecontrol.engine

import android.content.Context
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.TranslationStage
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceAudioProcessor
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.AudioFeedbackRenderer
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.SlotRepair
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.ToolCall
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.ToolCallMapper
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceIntent
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceIntent.CloudTier
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizer
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.AuthorityDecision
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ContributionRetentionSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SegmentObserver
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordDetector
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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

    private fun predictedIntentFor(intent: VoiceIntent): String? {
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
            retentionSink = ContributionRetentionSink(),
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
            assertNotNull("these intents have schema names", name)
            assertTrue(
                "'$name' must be lowercase schema vocabulary, at most 32 chars",
                name!!.matches(Regex("[a-z0-9_]+(\\.[a-z0-9_]+)?")) && name.length <= 32,
            )
        }
    }

    @Test
    fun `every published name round-trips through the forward mapper`() {
        // The membership check: each name this mapping emits must parse back through the app's own
        // ToolCallMapper to the same tool and action. Direction matters -- the mapping and the
        // mapper were written independently from the schema, so a name that only one of them
        // believes in is caught here. A cloud_route keeps its single token and is the only
        // exception the spec grants.
        val mapper = ToolCallMapper()
        intentLeaves().forEach { intent ->
            val name = predictedIntentFor(intent)
            if (name == null) {
                return@forEach
            }
            if (name == "cloud_route") {
                assertEquals("a cloud route publishes the tool alone", "cloud_route", name)
                return@forEach
            }
            val dot = name.indexOf('.')
            assertTrue("'$name' must be <tool>.<action>", dot > 0 && dot == name.lastIndexOf('.'))
            val tool = name.substring(0, dot)
            val action = name.substring(dot + 1)
            val parsed = mapper.map(
                ToolCall(
                    name = tool,
                    action = action,
                    params = schemaSatisfyingParams(tool, action),
                ),
            )
            assertNotNull("'$name' must parse through the forward mapper", parsed)
            assertEquals(
                "'$name' must round-trip to the intent it names, got $parsed",
                intent::class,
                parsed!!::class,
            )
        }
    }

    /**
     * Params the forward mapper needs to build a leaf, taken from the app's own declaration of each
     * action's parameters rather than a list kept here -- a second hand-maintained list is one more
     * thing that can drift from the schema.
     */
    private fun schemaSatisfyingParams(tool: String, action: String): Map<String, Any?> {
        val declared = SlotRepair.ACTION_PARAMS[tool to action].orEmpty()
        return declared.associateWith { key ->
            when (key) {
                "speed", "delta" -> 0.5
                "enabled" -> true
                "mode" -> "skip"
                "position_seconds", "index", "volume" -> 1
                "minutes" -> 5
                "delta_seconds" -> 10
                else -> "value"
            }
        }
    }

    @Test
    fun `an intent the schema does not define publishes no name`() {
        // The canonical schema has no action for these two, and the spec says a name comes only
        // from the schema. They are reachable -- the forward mapper builds them and the executor
        // answers them -- so they are not dead code; publishing an invented action would put a
        // name in the record that nothing can interpret.
        assertEquals(null, predictedIntentFor(VoiceIntent.PlaybackQuery.CurrentPodcast))
        assertEquals(null, predictedIntentFor(VoiceIntent.StatsQuery.QueueTotal))
    }

    @Test
    fun `the pre-alignment action name still routes`() {
        // Android asked the on-device model for `query_effects` before the canonical schema was
        // aligned. The model emits free text and nothing constrains its vocabulary, so whether it
        // says `query` or `query_effects` depends on weights this repo does not contain. Accepting
        // both keeps the command working either way, and the published name is derived from the
        // INTENT, so both spellings file as the one canonical name.
        val mapper = ToolCallMapper()
        listOf("query", "query_effects").forEach { action ->
            val intent = mapper.map(ToolCall(name = "effects", action = action, params = emptyMap()))
            assertNotNull("'effects.$action' must still route", intent)
            assertEquals(
                "'effects.$action' must publish the canonical name",
                "effects.query",
                predictedIntentFor(intent!!),
            )
            // The param table is consulted BEFORE the mapper, so an alias missing from it would
            // strip slots silently. Every alias the mapper accepts must also be declared there.
            assertTrue(
                "'effects.$action' must be declared in the slot table too",
                SlotRepair.ACTION_PARAMS.containsKey("effects" to action),
            )
        }
    }

    /**
     * Every routable intent leaf, listed explicitly. A family that gains an action fails the count
     * assertion above until the leaf is added here and given a name -- which is the point: the
     * mapping must not quietly keep publishing a name for an action nobody classified.
     */
    private fun intentLeaves(): List<VoiceIntent> = listOf(
        VoiceIntent.Playback.Pause, VoiceIntent.Playback.Resume, VoiceIntent.Playback.SeekRelative(0),
        VoiceIntent.Playback.SeekAbsolute(0), VoiceIntent.Playback.NextEpisode,
        VoiceIntent.Effects.SetSpeed(0.0), VoiceIntent.Effects.AdjustSpeed(0.0),
        VoiceIntent.Effects.SetTrimMode(""), VoiceIntent.Effects.SetVolumeBoost(false),
        VoiceIntent.Effects.QueryEffects,
        VoiceIntent.Volume.SetVolume(0), VoiceIntent.Volume.AdjustVolume(0), VoiceIntent.Volume.Query,
        VoiceIntent.Sleep.Set(0), VoiceIntent.Sleep.EndOfEpisode, VoiceIntent.Sleep.EndOfChapter,
        VoiceIntent.Sleep.AddTime(0), VoiceIntent.Sleep.Cancel, VoiceIntent.Sleep.QuerySleep,
        VoiceIntent.Chapter.NextChapter, VoiceIntent.Chapter.PreviousChapter, VoiceIntent.Chapter.ByIndex(0),
        VoiceIntent.Chapter.ByTitle(""), VoiceIntent.Chapter.OpenLink(0), VoiceIntent.Chapter.QueryList,
        VoiceIntent.Chapter.QueryCurrent, VoiceIntent.Chapter.QueryCount, VoiceIntent.Chapter.QueryNext,
        VoiceIntent.Bookmark.Add(), VoiceIntent.Bookmark.Rename("", ""), VoiceIntent.Bookmark.Play(""),
        VoiceIntent.Bookmark.Delete(""), VoiceIntent.Bookmark.DeleteAll, VoiceIntent.Bookmark.QueryBookmarkList,
        VoiceIntent.Bookmark.QueryBookmarkCount, VoiceIntent.Bookmark.QueryNearby,
        VoiceIntent.Queue.AddTop(), VoiceIntent.Queue.AddBottom(), VoiceIntent.Queue.Remove(""),
        VoiceIntent.Queue.MoveToTop(""), VoiceIntent.Queue.MoveToBottom(""), VoiceIntent.Queue.Clear,
        VoiceIntent.Queue.RemoveByPodcast(""), VoiceIntent.Queue.Sort(""), VoiceIntent.Queue.QueryContents,
        VoiceIntent.Queue.QueryQueueNext, VoiceIntent.Queue.QueryLength, VoiceIntent.Queue.QueryIsQueued(""),
        VoiceIntent.PlaybackQuery.WhatsPlaying, VoiceIntent.PlaybackQuery.Position,
        VoiceIntent.PlaybackQuery.TimeRemaining, VoiceIntent.PlaybackQuery.CurrentPodcast,
        VoiceIntent.PlaybackQuery.EpisodeDuration, VoiceIntent.PlaybackQuery.PublishDate,
        VoiceIntent.PlaybackQuery.EpisodeDescription, VoiceIntent.PlaybackQuery.DownloadStatus,
        VoiceIntent.PlaybackQuery.EpisodeTitle,
        VoiceIntent.StatsQuery.ListeningTime(), VoiceIntent.StatsQuery.TopPodcasts(),
        VoiceIntent.StatsQuery.EpisodesFinished(), VoiceIntent.StatsQuery.ListeningStreak,
        VoiceIntent.StatsQuery.SubscriptionCount, VoiceIntent.StatsQuery.UnplayedTotal,
        VoiceIntent.StatsQuery.DownloadStats, VoiceIntent.StatsQuery.QueueTotal,
        VoiceIntent.StatsQuery.NewEpisodes(), VoiceIntent.StatsQuery.TimeSinceLastListen,
        VoiceIntent.CloudRoute(request = "", tier = CloudTier.Free),
    )
}
