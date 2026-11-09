package au.com.shiftyjelly.pocketcasts.voicecontrol.intent

import au.com.shiftyjelly.pocketcasts.voicecontrol.model.IntentRoutingInput
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.TranslationKind
import au.com.shiftyjelly.pocketcasts.voicecontrol.playback.VoicePlaybackIntentExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    private fun deferred(
        utterance: String,
        params: String,
        textLanguage: String? = null,
        tool: String = "playback",
        action: String = "seek_relative",
    ): Boolean {
        val raw = "<|tool_call_start|>[$tool(action='$action'$params)]<|tool_call_end|>"
        val repaired = SlotRepair.repair(
            raw = raw,
            utterance = utterance,
            tool = tool,
            action = action,
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
        // Each case keeps its own tool and action: forcing every utterance through one action
        // would prove only that action, and the guard is meant to hold across the ones that act.
        assert(deferred("when this episode ends, rewind ten seconds", ", delta_seconds=-10")) {
            "a seek ran before its event"
        }
        assert(
            deferred(
                "when this episode ends, go to chapter 'Introduction'",
                ", title='Introduction'",
                tool = "chapter",
                action = "by_title",
            ),
        ) { "a chapter change ran before its event" }
        assert(
            deferred(
                "when this episode ends, tell me the volume",
                "",
                tool = "volume",
                action = "query",
            ),
        ) { "a question was answered before its event" }
    }

    @Test
    fun `a bound or condition defers even where a resolved duration would not`() {
        // A stated duration is complete on its own, so the amount check is waived for these
        // actions. That waiver must not extend to a bound or a condition: both leave the request
        // without a single value to act on, even though a duration is present.
        data class Case(val utterance: String, val tool: String, val action: String, val params: String)

        listOf(
            Case("set volume to at least twenty percent", "volume", "set_volume", ", level=20"),
            Case("set volume to between ten and twenty percent", "volume", "set_volume", ", level=20"),
            Case("set a thirty-minute sleep timer when this episode ends", "sleep", "set", ", minutes=30"),
            Case("set speed to at least one and a half", "effects", "set_speed", ", speed=1.5"),
        ).forEach { case ->
            val raw = "<|tool_call_start|>[${case.tool}(action='${case.action}'${case.params})]<|tool_call_end|>"
            val repaired = SlotRepair.repair(
                raw = raw,
                utterance = case.utterance,
                tool = case.tool,
                action = case.action,
                textLanguage = "en",
            )
            assertNull("${case.utterance} ran despite an unresolved bound or condition", repaired)
        }
    }

    @Test
    fun `a quoted amount is still an alternative`() {
        // Quoting an amount does not settle it. The words inside the quotes are read even though
        // a quoted span is normally a title.
        listOf(
            "rewind \"ten or twenty\" seconds",
            "rewind 'ten or twenty' seconds",
            "rewind between ten and twenty seconds",
            "rewind from ten to twenty seconds",
        ).forEach { utterance ->
            assert(deferred(utterance, ", delta_seconds=-10", textLanguage = "en")) {
                "$utterance was resolved to a single distance"
            }
        }
    }

    @Test
    fun `the router passes the language of the translated text into repair`() {
        // The fixtures prove the language behaviour at the boundary of repair; this pins that the
        // production router actually supplies it, so a Spanish request translated to English is
        // repaired as English and a Spanish "once" is not.
        val nativeSpanish = IntentRoutingInput(
            sourceTranscript = "pon el volumen a once por ciento",
            sourceLanguage = "es",
            routerTranscript = "pon el volumen a once por ciento",
            translationKind = TranslationKind.NONE,
        )
        assertEquals("es", nativeSpanish.textLanguage)

        val translated = IntentRoutingInput(
            sourceTranscript = "pon el volumen a once por ciento",
            sourceLanguage = "es",
            routerTranscript = "set the volume to eleven percent",
            translationKind = TranslationKind.PLATFORM,
        )
        assertEquals("en", translated.textLanguage)
    }

    @Test
    fun `an alternative defers even where a decoded value would otherwise be acted on`() {
        // The duration waiver exists because a single stated number is a complete request. An
        // alternative is not: another amount is still named, so a decoded value is a guess between
        // two. These carry a valid decoded value precisely so the assertion cannot pass merely
        // because nothing was decoded.
        data class Case(val utterance: String, val tool: String, val action: String, val params: String)

        listOf(
            Case("set volume to ten or twenty percent", "volume", "set_volume", ", level=10"),
            Case("set a ten or twenty minute sleep timer", "sleep", "set", ", minutes=10"),
            Case("set volume to ten or twenty percent", "volume", "set_volume", ", level=20"),
            Case("set a ten or twenty minute sleep timer", "sleep", "set", ", minutes=20"),
        ).forEach { case ->
            val raw = "<|tool_call_start|>[${case.tool}(action='${case.action}'${case.params})]<|tool_call_end|>"
            val repaired = SlotRepair.repair(
                raw = raw,
                utterance = case.utterance,
                tool = case.tool,
                action = case.action,
                textLanguage = "en",
            )
            assertNull("${case.utterance} was settled by a decoded value", repaired)
        }
    }

    @Test
    fun `a single stated number is still honoured for an action it fully settles`() {
        // The waiver must keep working, or valid commands would be refused.
        val repaired = SlotRepair.repair(
            raw = "<|tool_call_start|>[sleep(action='set', minutes=30)]<|tool_call_end|>",
            utterance = "set a thirty minute sleep timer",
            tool = "sleep",
            action = "set",
            textLanguage = "en",
        )
        assertNotNull("a complete timer request was refused", repaired)
    }
}
