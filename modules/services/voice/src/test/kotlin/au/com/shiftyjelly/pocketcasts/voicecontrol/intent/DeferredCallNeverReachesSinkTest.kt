package au.com.shiftyjelly.pocketcasts.voicecontrol.intent

import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoicePlaybackIntentExecutor
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The control for unresolved slot repair: a deferred call must not reach the playback sink.
 *
 * Returning null from [SlotRepair.repair] is only half the guarantee. If a caller mapped an
 * unamounted seek onward, the sink would apply its configured interval and the request would
 * execute immediately at a distance nobody stated. These cases pin the second half by walking
 * every deferred fixture utterance through repair and the mapper and asserting no intent exists.
 */
@RunWith(RobolectricTestRunner::class)
class DeferredCallNeverReachesSinkTest {
    private val mapper = ToolCallMapper()

    private fun deferred(utterance: String, params: String, textLanguage: String? = null): Boolean {
        val raw = "<|tool_call_start|>[playback(action='seek_relative'$params)]<|tool_call_end|>"
        val repaired = SlotRepair.repair(
            raw = raw,
            utterance = utterance,
            tool = "playback",
            action = "seek_relative",
            textLanguage = textLanguage,
        ) ?: return true
        // Anything repair still returns must not become a sink call either.
        return mapper.map(repaired) == null
    }

    @Test
    fun `an alternative amount defers rather than defaulting`() {
        listOf(
            "rewind ten or twenty seconds",
            "往回跳十秒或者二十秒",
            "10秒か20秒戻して",
            "retrocede diez o veinte segundos",
        ).forEach { utterance ->
            assert(deferred(utterance, ", delta_seconds=-10")) { "$utterance kept a decoded amount" }
            assert(deferred(utterance, ", direction='backward'")) { "$utterance kept a direction only" }
            assert(deferred(utterance, "")) { "$utterance kept empty params" }
        }
    }

    @Test
    fun `a lower bound defers rather than collapsing to the bound`() {
        listOf(
            "rewind at least ten seconds",
            "往回跳至少十秒",
            "少なくとも10秒戻して",
            "retrocede al menos diez segundos",
        ).forEach { utterance ->
            assert(deferred(utterance, ", delta_seconds=-10")) { "$utterance kept a decoded amount" }
            assert(deferred(utterance, "")) { "$utterance kept empty params" }
        }
    }

    @Test
    fun `a future execution condition defers rather than seeking now`() {
        listOf(
            "when this episode ends, rewind ten seconds",
            "等这集结束后，再往回跳十秒",
            "このエピソードが終わったら10秒戻して",
            "cuando termine este episodio, retrocede diez segundos",
        ).forEach { utterance ->
            assert(deferred(utterance, ", delta_seconds=-10")) { "$utterance kept a decoded amount" }
            assert(deferred(utterance, "")) { "$utterance kept empty params" }
        }
    }

    @Test
    fun `now and exactly do not rescue a remaining alternative or condition`() {
        listOf(
            "rewind ten or twenty seconds now",
            "现在往回跳十秒或者二十秒",
            "今すぐ10秒か20秒戻して",
            "retrocede diez o veinte segundos ahora",
            "when this ends, rewind exactly ten seconds",
            "等这集结束后，再往回跳正好十秒",
            "このエピソードが終わったらちょうど10秒戻して",
            "cuando termine esto, retrocede exactamente diez segundos",
        ).forEach { utterance ->
            assert(deferred(utterance, ", delta_seconds=-10")) { "$utterance was rescued by now/exactly" }
        }
    }

    @Test
    fun `a settled request still reaches the sink`() {
        val repaired = SlotRepair.repair(
            raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-20)]<|tool_call_end|>",
            utterance = "rewind twenty seconds, not ten",
            tool = "playback",
            action = "seek_relative",
        )
        assert(repaired != null) { "an explicit correction must still repair" }
        assert(mapper.map(repaired!!) != null) { "an explicit correction must still reach the sink" }
    }

    @Test
    fun `a number that is spelled like a condition word is not a condition`() {
        // "once" is the number eleven in Spanish and a condition marker in English. The shared
        // contract separates them by the language of the text being repaired, so this guard must
        // not decide it from the wording alone: a verb list would defer the Spanish eleven.
        listOf(
            "retrocede once segundos",
            "retrocede exactamente once segundos",
        ).forEach { utterance ->
            assert(!deferred(utterance, ", delta_seconds=-11", textLanguage = "es")) {
                "$utterance was read as a condition"
            }
        }
        // Without a language the same words stay unresolved: neither reading is safe to assume.
        assert(deferred("retrocede once segundos", ", delta_seconds=-11")) {
            "an unknown language was read as the number"
        }
        // The Spanish conjunction is a condition regardless of the number reading.
        assert(deferred("una vez que termine este episodio, retrocede diez segundos", ", delta_seconds=-10", textLanguage = "es")) {
            "the Spanish conjunction was read as a number"
        }
    }

    @Test
    fun `a resolved amount still defers when the request is tied to a future event`() {
        listOf(
            "when this episode ends, rewind ten seconds",
            "when this episode ends, go to chapter 'Introduction'",
            "when this episode ends, tell me the volume",
        ).forEach { utterance ->
            assert(deferred(utterance, ", delta_seconds=-10")) { "$utterance ran before its event" }
        }
    }
}
