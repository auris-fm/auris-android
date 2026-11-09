package au.com.shiftyjelly.pocketcasts.voicecontrol.intent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The shared multilingual fixtures for unresolved slot repair, from the core repository's
 * training fixtures.
 *
 * A request carrying a remaining alternative amount ("ten or twenty"), a spoken lower bound
 * ("at least ten") or an execution condition tied to a future event ("when this episode ends")
 * is a slot failure: the whole call defers, even when the decoder supplied a valid amount or a
 * default interval could have been used. "now" and "exactly" do not settle a remaining
 * alternative or condition.
 *
 * Each case states the language of the text being repaired, which is not always the language the
 * user spoke: an English translation of a Spanish request is repaired as English. The language
 * decides words that mean different things in different languages, such as "once".
 *
 * String parameters are compared with surrounding quotes trimmed. Repair leaves the quotes on a
 * `query` value it does not rewrite, which is outside what this guard decides.
 */
@RunWith(RobolectricTestRunner::class)
class UnresolvedSlotRepairTest {

    @Test
    fun `nov09o_root_001_en_decoded_0`() {
        // Two alternative amounts are offered without choosing either. One unresolved action, not two affirmed
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "rewind ten or twenty seconds", tool = "playback", action = "seek_relative", textLanguage = "en"))
    }

    @Test
    fun `nov09o_root_001_en_decoded_1`() {
        // Two alternative amounts are offered without choosing either. One unresolved action, not two affirmed
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "rewind ten or twenty seconds", tool = "playback", action = "seek_relative", textLanguage = "en"))
    }

    @Test
    fun `nov09o_root_001_en_decoded_2`() {
        // Two alternative amounts are offered without choosing either. One unresolved action, not two affirmed
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "rewind ten or twenty seconds", tool = "playback", action = "seek_relative", textLanguage = "en"))
    }

    @Test
    fun `nov09o_root_001_zh_decoded_0`() {
        // Two alternative amounts are offered without choosing either. One unresolved action, not two affirmed
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "往回跳十秒或者二十秒", tool = "playback", action = "seek_relative", textLanguage = "zh"))
    }

    @Test
    fun `nov09o_root_001_zh_decoded_1`() {
        // Two alternative amounts are offered without choosing either. One unresolved action, not two affirmed
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "往回跳十秒或者二十秒", tool = "playback", action = "seek_relative", textLanguage = "zh"))
    }

    @Test
    fun `nov09o_root_001_zh_decoded_2`() {
        // Two alternative amounts are offered without choosing either. One unresolved action, not two affirmed
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "往回跳十秒或者二十秒", tool = "playback", action = "seek_relative", textLanguage = "zh"))
    }

    @Test
    fun `nov09o_root_001_ja_decoded_0`() {
        // Two alternative amounts are offered without choosing either. One unresolved action, not two affirmed
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "10秒か20秒戻して", tool = "playback", action = "seek_relative", textLanguage = "ja"))
    }

    @Test
    fun `nov09o_root_001_ja_decoded_1`() {
        // Two alternative amounts are offered without choosing either. One unresolved action, not two affirmed
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "10秒か20秒戻して", tool = "playback", action = "seek_relative", textLanguage = "ja"))
    }

    @Test
    fun `nov09o_root_001_ja_decoded_2`() {
        // Two alternative amounts are offered without choosing either. One unresolved action, not two affirmed
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "10秒か20秒戻して", tool = "playback", action = "seek_relative", textLanguage = "ja"))
    }

    @Test
    fun `nov09o_root_001_es_decoded_0`() {
        // Two alternative amounts are offered without choosing either. One unresolved action, not two affirmed
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "retrocede diez o veinte segundos", tool = "playback", action = "seek_relative", textLanguage = "es"))
    }

    @Test
    fun `nov09o_root_001_es_decoded_1`() {
        // Two alternative amounts are offered without choosing either. One unresolved action, not two affirmed
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "retrocede diez o veinte segundos", tool = "playback", action = "seek_relative", textLanguage = "es"))
    }

    @Test
    fun `nov09o_root_001_es_decoded_2`() {
        // Two alternative amounts are offered without choosing either. One unresolved action, not two affirmed
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "retrocede diez o veinte segundos", tool = "playback", action = "seek_relative", textLanguage = "es"))
    }

    @Test
    fun `nov09o_root_002_en_decoded_0`() {
        // The negative clause excludes ten and leaves twenty as the single affirmed amount: one backward displ
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-20)]<|tool_call_end|>", utterance = "rewind twenty seconds, not ten", tool = "playback", action = "seek_relative", textLanguage = "en")
        assertNotNull(repaired)
        assertEquals(-20, repaired?.params?.get("delta_seconds").let { if (it is String) it.trim('"') else it })
    }

    @Test
    fun `nov09o_root_002_zh_decoded_0`() {
        // The negative clause excludes ten and leaves twenty as the single affirmed amount: one backward displ
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-20)]<|tool_call_end|>", utterance = "往回跳二十秒，不是十秒", tool = "playback", action = "seek_relative", textLanguage = "zh")
        assertNotNull(repaired)
        assertEquals(-20, repaired?.params?.get("delta_seconds").let { if (it is String) it.trim('"') else it })
    }

    @Test
    fun `nov09o_root_002_ja_decoded_0`() {
        // The negative clause excludes ten and leaves twenty as the single affirmed amount: one backward displ
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-20)]<|tool_call_end|>", utterance = "10秒ではなく20秒戻して", tool = "playback", action = "seek_relative", textLanguage = "ja")
        assertNotNull(repaired)
        assertEquals(-20, repaired?.params?.get("delta_seconds").let { if (it is String) it.trim('"') else it })
    }

    @Test
    fun `nov09o_root_002_es_decoded_0`() {
        // The negative clause excludes ten and leaves twenty as the single affirmed amount: one backward displ
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-20)]<|tool_call_end|>", utterance = "retrocede veinte segundos, no diez", tool = "playback", action = "seek_relative", textLanguage = "es")
        assertNotNull(repaired)
        assertEquals(-20, repaired?.params?.get("delta_seconds").let { if (it is String) it.trim('"') else it })
    }

    @Test
    fun `nov09o_root_003_en_decoded_0`() {
        // At least ten is a fully spoken lower bound, not exactly ten. No unique displacement is supplied; do
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "rewind at least ten seconds", tool = "playback", action = "seek_relative", textLanguage = "en"))
    }

    @Test
    fun `nov09o_root_003_en_decoded_1`() {
        // At least ten is a fully spoken lower bound, not exactly ten. No unique displacement is supplied; do
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "rewind at least ten seconds", tool = "playback", action = "seek_relative", textLanguage = "en"))
    }

    @Test
    fun `nov09o_root_003_en_decoded_2`() {
        // At least ten is a fully spoken lower bound, not exactly ten. No unique displacement is supplied; do
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "rewind at least ten seconds", tool = "playback", action = "seek_relative", textLanguage = "en"))
    }

    @Test
    fun `nov09o_root_003_zh_decoded_0`() {
        // At least ten is a fully spoken lower bound, not exactly ten. No unique displacement is supplied; do
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "往回跳至少十秒", tool = "playback", action = "seek_relative", textLanguage = "zh"))
    }

    @Test
    fun `nov09o_root_003_zh_decoded_1`() {
        // At least ten is a fully spoken lower bound, not exactly ten. No unique displacement is supplied; do
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "往回跳至少十秒", tool = "playback", action = "seek_relative", textLanguage = "zh"))
    }

    @Test
    fun `nov09o_root_003_zh_decoded_2`() {
        // At least ten is a fully spoken lower bound, not exactly ten. No unique displacement is supplied; do
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "往回跳至少十秒", tool = "playback", action = "seek_relative", textLanguage = "zh"))
    }

    @Test
    fun `nov09o_root_003_ja_decoded_0`() {
        // At least ten is a fully spoken lower bound, not exactly ten. No unique displacement is supplied; do
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "少なくとも10秒戻して", tool = "playback", action = "seek_relative", textLanguage = "ja"))
    }

    @Test
    fun `nov09o_root_003_ja_decoded_1`() {
        // At least ten is a fully spoken lower bound, not exactly ten. No unique displacement is supplied; do
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "少なくとも10秒戻して", tool = "playback", action = "seek_relative", textLanguage = "ja"))
    }

    @Test
    fun `nov09o_root_003_ja_decoded_2`() {
        // At least ten is a fully spoken lower bound, not exactly ten. No unique displacement is supplied; do
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "少なくとも10秒戻して", tool = "playback", action = "seek_relative", textLanguage = "ja"))
    }

    @Test
    fun `nov09o_root_003_es_decoded_0`() {
        // At least ten is a fully spoken lower bound, not exactly ten. No unique displacement is supplied; do
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "retrocede al menos diez segundos", tool = "playback", action = "seek_relative", textLanguage = "es"))
    }

    @Test
    fun `nov09o_root_003_es_decoded_1`() {
        // At least ten is a fully spoken lower bound, not exactly ten. No unique displacement is supplied; do
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "retrocede al menos diez segundos", tool = "playback", action = "seek_relative", textLanguage = "es"))
    }

    @Test
    fun `nov09o_root_003_es_decoded_2`() {
        // At least ten is a fully spoken lower bound, not exactly ten. No unique displacement is supplied; do
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "retrocede al menos diez segundos", tool = "playback", action = "seek_relative", textLanguage = "es"))
    }

    @Test
    fun `nov09o_root_004_en_decoded_0`() {
        // Exactly ten supplies a unique, representable integer-second backward displacement.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "rewind exactly ten seconds", tool = "playback", action = "seek_relative", textLanguage = "en")
        assertNotNull(repaired)
        assertEquals(-10, repaired?.params?.get("delta_seconds").let { if (it is String) it.trim('"') else it })
    }

    @Test
    fun `nov09o_root_004_zh_decoded_0`() {
        // Exactly ten supplies a unique, representable integer-second backward displacement.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "往回跳正好十秒", tool = "playback", action = "seek_relative", textLanguage = "zh")
        assertNotNull(repaired)
        assertEquals(-10, repaired?.params?.get("delta_seconds").let { if (it is String) it.trim('"') else it })
    }

    @Test
    fun `nov09o_root_004_ja_decoded_0`() {
        // Exactly ten supplies a unique, representable integer-second backward displacement.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "ちょうど10秒戻して", tool = "playback", action = "seek_relative", textLanguage = "ja")
        assertNotNull(repaired)
        assertEquals(-10, repaired?.params?.get("delta_seconds").let { if (it is String) it.trim('"') else it })
    }

    @Test
    fun `nov09o_root_004_es_decoded_0`() {
        // Exactly ten supplies a unique, representable integer-second backward displacement.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "retrocede exactamente diez segundos", tool = "playback", action = "seek_relative", textLanguage = "es")
        assertNotNull(repaired)
        assertEquals(-10, repaired?.params?.get("delta_seconds").let { if (it is String) it.trim('"') else it })
    }

    @Test
    fun `nov09o_root_005_en_decoded_0`() {
        // The seek is conditional on a future episode-end event. No history says that event occurred. Immediat
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "when this episode ends, rewind ten seconds", tool = "playback", action = "seek_relative", textLanguage = "en"))
    }

    @Test
    fun `nov09o_root_005_en_decoded_1`() {
        // The seek is conditional on a future episode-end event. No history says that event occurred. Immediat
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "when this episode ends, rewind ten seconds", tool = "playback", action = "seek_relative", textLanguage = "en"))
    }

    @Test
    fun `nov09o_root_005_en_decoded_2`() {
        // The seek is conditional on a future episode-end event. No history says that event occurred. Immediat
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "when this episode ends, rewind ten seconds", tool = "playback", action = "seek_relative", textLanguage = "en"))
    }

    @Test
    fun `nov09o_root_005_zh_decoded_0`() {
        // The seek is conditional on a future episode-end event. No history says that event occurred. Immediat
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "等这集结束后，再往回跳十秒", tool = "playback", action = "seek_relative", textLanguage = "zh"))
    }

    @Test
    fun `nov09o_root_005_zh_decoded_1`() {
        // The seek is conditional on a future episode-end event. No history says that event occurred. Immediat
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "等这集结束后，再往回跳十秒", tool = "playback", action = "seek_relative", textLanguage = "zh"))
    }

    @Test
    fun `nov09o_root_005_zh_decoded_2`() {
        // The seek is conditional on a future episode-end event. No history says that event occurred. Immediat
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "等这集结束后，再往回跳十秒", tool = "playback", action = "seek_relative", textLanguage = "zh"))
    }

    @Test
    fun `nov09o_root_005_ja_decoded_0`() {
        // The seek is conditional on a future episode-end event. No history says that event occurred. Immediat
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "このエピソードが終わったら10秒戻して", tool = "playback", action = "seek_relative", textLanguage = "ja"))
    }

    @Test
    fun `nov09o_root_005_ja_decoded_1`() {
        // The seek is conditional on a future episode-end event. No history says that event occurred. Immediat
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "このエピソードが終わったら10秒戻して", tool = "playback", action = "seek_relative", textLanguage = "ja"))
    }

    @Test
    fun `nov09o_root_005_ja_decoded_2`() {
        // The seek is conditional on a future episode-end event. No history says that event occurred. Immediat
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "このエピソードが終わったら10秒戻して", tool = "playback", action = "seek_relative", textLanguage = "ja"))
    }

    @Test
    fun `nov09o_root_005_es_decoded_0`() {
        // The seek is conditional on a future episode-end event. No history says that event occurred. Immediat
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "cuando termine este episodio, retrocede diez segundos", tool = "playback", action = "seek_relative", textLanguage = "es"))
    }

    @Test
    fun `nov09o_root_005_es_decoded_1`() {
        // The seek is conditional on a future episode-end event. No history says that event occurred. Immediat
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "cuando termine este episodio, retrocede diez segundos", tool = "playback", action = "seek_relative", textLanguage = "es"))
    }

    @Test
    fun `nov09o_root_005_es_decoded_2`() {
        // The seek is conditional on a future episode-end event. No history says that event occurred. Immediat
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "cuando termine este episodio, retrocede diez segundos", tool = "playback", action = "seek_relative", textLanguage = "es"))
    }

    @Test
    fun `nov09o_root_006_en_decoded_0`() {
        // Now requests immediate backward displacement of ten seconds with no future trigger.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "rewind ten seconds now", tool = "playback", action = "seek_relative", textLanguage = "en")
        assertNotNull(repaired)
        assertEquals(-10, repaired?.params?.get("delta_seconds").let { if (it is String) it.trim('"') else it })
    }

    @Test
    fun `nov09o_root_006_zh_decoded_0`() {
        // Now requests immediate backward displacement of ten seconds with no future trigger.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "现在往回跳十秒", tool = "playback", action = "seek_relative", textLanguage = "zh")
        assertNotNull(repaired)
        assertEquals(-10, repaired?.params?.get("delta_seconds").let { if (it is String) it.trim('"') else it })
    }

    @Test
    fun `nov09o_root_006_ja_decoded_0`() {
        // Now requests immediate backward displacement of ten seconds with no future trigger.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "今すぐ10秒戻して", tool = "playback", action = "seek_relative", textLanguage = "ja")
        assertNotNull(repaired)
        assertEquals(-10, repaired?.params?.get("delta_seconds").let { if (it is String) it.trim('"') else it })
    }

    @Test
    fun `nov09o_root_006_es_decoded_0`() {
        // Now requests immediate backward displacement of ten seconds with no future trigger.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "retrocede diez segundos ahora", tool = "playback", action = "seek_relative", textLanguage = "es")
        assertNotNull(repaired)
        assertEquals(-10, repaired?.params?.get("delta_seconds").let { if (it is String) it.trim('"') else it })
    }

    @Test
    fun `unresolved_precedence_1_en_0`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "rewind ten or twenty seconds now", tool = "playback", action = "seek_relative", textLanguage = "en"))
    }

    @Test
    fun `unresolved_precedence_1_en_1`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "rewind ten or twenty seconds now", tool = "playback", action = "seek_relative", textLanguage = "en"))
    }

    @Test
    fun `unresolved_precedence_1_en_2`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "rewind ten or twenty seconds now", tool = "playback", action = "seek_relative", textLanguage = "en"))
    }

    @Test
    fun `unresolved_precedence_1_zh_0`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "现在往回跳十秒或者二十秒", tool = "playback", action = "seek_relative", textLanguage = "zh"))
    }

    @Test
    fun `unresolved_precedence_1_zh_1`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "现在往回跳十秒或者二十秒", tool = "playback", action = "seek_relative", textLanguage = "zh"))
    }

    @Test
    fun `unresolved_precedence_1_zh_2`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "现在往回跳十秒或者二十秒", tool = "playback", action = "seek_relative", textLanguage = "zh"))
    }

    @Test
    fun `unresolved_precedence_1_ja_0`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "今すぐ10秒か20秒戻して", tool = "playback", action = "seek_relative", textLanguage = "ja"))
    }

    @Test
    fun `unresolved_precedence_1_ja_1`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "今すぐ10秒か20秒戻して", tool = "playback", action = "seek_relative", textLanguage = "ja"))
    }

    @Test
    fun `unresolved_precedence_1_ja_2`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "今すぐ10秒か20秒戻して", tool = "playback", action = "seek_relative", textLanguage = "ja"))
    }

    @Test
    fun `unresolved_precedence_1_es_0`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "retrocede diez o veinte segundos ahora", tool = "playback", action = "seek_relative", textLanguage = "es"))
    }

    @Test
    fun `unresolved_precedence_1_es_1`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "retrocede diez o veinte segundos ahora", tool = "playback", action = "seek_relative", textLanguage = "es"))
    }

    @Test
    fun `unresolved_precedence_1_es_2`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "retrocede diez o veinte segundos ahora", tool = "playback", action = "seek_relative", textLanguage = "es"))
    }

    @Test
    fun `unresolved_precedence_2_en_0`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "when this ends, rewind exactly ten seconds", tool = "playback", action = "seek_relative", textLanguage = "en"))
    }

    @Test
    fun `unresolved_precedence_2_en_1`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "when this ends, rewind exactly ten seconds", tool = "playback", action = "seek_relative", textLanguage = "en"))
    }

    @Test
    fun `unresolved_precedence_2_en_2`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "when this ends, rewind exactly ten seconds", tool = "playback", action = "seek_relative", textLanguage = "en"))
    }

    @Test
    fun `unresolved_precedence_2_zh_0`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "等这集结束后，再往回跳正好十秒", tool = "playback", action = "seek_relative", textLanguage = "zh"))
    }

    @Test
    fun `unresolved_precedence_2_zh_1`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "等这集结束后，再往回跳正好十秒", tool = "playback", action = "seek_relative", textLanguage = "zh"))
    }

    @Test
    fun `unresolved_precedence_2_zh_2`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "等这集结束后，再往回跳正好十秒", tool = "playback", action = "seek_relative", textLanguage = "zh"))
    }

    @Test
    fun `unresolved_precedence_2_ja_0`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "このエピソードが終わったらちょうど10秒戻して", tool = "playback", action = "seek_relative", textLanguage = "ja"))
    }

    @Test
    fun `unresolved_precedence_2_ja_1`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "このエピソードが終わったらちょうど10秒戻して", tool = "playback", action = "seek_relative", textLanguage = "ja"))
    }

    @Test
    fun `unresolved_precedence_2_ja_2`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "このエピソードが終わったらちょうど10秒戻して", tool = "playback", action = "seek_relative", textLanguage = "ja"))
    }

    @Test
    fun `unresolved_precedence_2_es_0`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "cuando termine esto, retrocede exactamente diez segundos", tool = "playback", action = "seek_relative", textLanguage = "es"))
    }

    @Test
    fun `unresolved_precedence_2_es_1`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "cuando termine esto, retrocede exactamente diez segundos", tool = "playback", action = "seek_relative", textLanguage = "es"))
    }

    @Test
    fun `unresolved_precedence_2_es_2`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "cuando termine esto, retrocede exactamente diez segundos", tool = "playback", action = "seek_relative", textLanguage = "es"))
    }

    @Test
    fun `chapter_alternatives_0`() {
        // The unresolved alternative/event must refuse the whole classified call.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[chapter(action='by_index', index=10)]<|tool_call_end|>", utterance = "go to chapter ten or twenty", tool = "chapter", action = "by_index", textLanguage = "en"))
    }

    @Test
    fun `chapter_alternatives_1`() {
        // The unresolved alternative/event must refuse the whole classified call.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[chapter(action='by_index')]<|tool_call_end|>", utterance = "go to chapter ten or twenty", tool = "chapter", action = "by_index", textLanguage = "en"))
    }

    @Test
    fun `chapter_alternatives_2`() {
        // The unresolved alternative/event must refuse the whole classified call.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[chapter(action='by_index', index=20)]<|tool_call_end|>", utterance = "go to chapter ten or twenty", tool = "chapter", action = "by_index", textLanguage = "en"))
    }

    @Test
    fun `fraction_alternatives_0`() {
        // The unresolved alternative/event must refuse the whole classified call.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-15)]<|tool_call_end|>", utterance = "rewind half or a quarter of a minute", tool = "playback", action = "seek_relative", textLanguage = "en"))
    }

    @Test
    fun `fraction_alternatives_1`() {
        // The unresolved alternative/event must refuse the whole classified call.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "rewind half or a quarter of a minute", tool = "playback", action = "seek_relative", textLanguage = "en"))
    }

    @Test
    fun `fraction_alternatives_2`() {
        // The unresolved alternative/event must refuse the whole classified call.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "rewind half or a quarter of a minute", tool = "playback", action = "seek_relative", textLanguage = "en"))
    }

    @Test
    fun `event_first_condition_0`() {
        // The unresolved alternative/event must refuse the whole classified call.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "这集结束后倒回十秒", tool = "playback", action = "seek_relative", textLanguage = "zh"))
    }

    @Test
    fun `event_first_condition_1`() {
        // The unresolved alternative/event must refuse the whole classified call.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "这集结束后倒回十秒", tool = "playback", action = "seek_relative", textLanguage = "zh"))
    }

    @Test
    fun `event_first_condition_2`() {
        // The unresolved alternative/event must refuse the whole classified call.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "这集结束后倒回十秒", tool = "playback", action = "seek_relative", textLanguage = "zh"))
    }

    @Test
    fun `fractional_timer`() {
        // The known duration or encoded timer target is resolved.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[sleep(action='set', minutes=30)]<|tool_call_end|>", utterance = "set a sleep timer after half an hour", tool = "sleep", action = "set", textLanguage = "en")
        assertNotNull(repaired)
        assertEquals(30, repaired?.params?.get("minutes").let { if (it is String) it.trim('"') else it })
    }

    @Test
    fun `spanish_eleven`() {
        // The known duration or encoded timer target is resolved.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-11)]<|tool_call_end|>", utterance = "retrocede once segundos", tool = "playback", action = "seek_relative", textLanguage = "es")
        assertNotNull(repaired)
        assertEquals(-11, repaired?.params?.get("delta_seconds").let { if (it is String) it.trim('"') else it })
    }

    @Test
    fun `spanish_exact_eleven`() {
        // The known duration or encoded timer target is resolved.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-11)]<|tool_call_end|>", utterance = "retrocede exactamente once segundos", tool = "playback", action = "seek_relative", textLanguage = "es")
        assertNotNull(repaired)
        assertEquals(-11, repaired?.params?.get("delta_seconds").let { if (it is String) it.trim('"') else it })
    }

    @Test
    fun `chapter_coordinate_previous`() {
        // The relation identifies a chapter target, not a future execution condition.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[chapter(action='previous')]<|tool_call_end|>", utterance = "go to the chapter before this one", tool = "chapter", action = "previous", textLanguage = "en")
        assertNotNull(repaired)
    }

    @Test
    fun `chapter_coordinate_next`() {
        // The relation identifies a chapter target, not a future execution condition.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[chapter(action='next')]<|tool_call_end|>", utterance = "go to the chapter after this one", tool = "chapter", action = "next", textLanguage = "en")
        assertNotNull(repaired)
    }

    @Test
    fun `chapter_future_after_completion`() {
        // An explicit ending predicate makes this an execution condition, not an adjacent-chapter coordinate.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[chapter(action='next')]<|tool_call_end|>", utterance = "go to the chapter after this one ends", tool = "chapter", action = "next", textLanguage = "en"))
    }

    @Test
    fun `end_coordinate_zh`() {
        // The temporal relation encodes an episode position, not an execution trigger.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_to', position_seconds=-10)]<|tool_call_end|>", utterance = "跳到结束前十秒", tool = "playback", action = "seek_to", textLanguage = "zh")
        assertNotNull(repaired)
        assertEquals(-10, repaired?.params?.get("position_seconds").let { if (it is String) it.trim('"') else it })
    }

    @Test
    fun `end_coordinate_ja`() {
        // The temporal relation encodes an episode position, not an execution trigger.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_to', position_seconds=-10)]<|tool_call_end|>", utterance = "終了の10秒前に移動して", tool = "playback", action = "seek_to", textLanguage = "ja")
        assertNotNull(repaired)
        assertEquals(-10, repaired?.params?.get("position_seconds").let { if (it is String) it.trim('"') else it })
    }

    @Test
    fun `end_coordinate_es`() {
        // The temporal relation encodes an episode position, not an execution trigger.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_to', position_seconds=-10)]<|tool_call_end|>", utterance = "ve a diez segundos antes del final", tool = "playback", action = "seek_to", textLanguage = "es")
        assertNotNull(repaired)
        assertEquals(-10, repaired?.params?.get("position_seconds").let { if (it is String) it.trim('"') else it })
    }

    @Test
    fun `future_with_end_coordinate_zh`() {
        // A resolved target position does not erase the separate execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_to', position_seconds=-10)]<|tool_call_end|>", utterance = "这集结束后跳到结束前十秒", tool = "playback", action = "seek_to", textLanguage = "zh"))
    }

    @Test
    fun `title_execution_condition`() {
        // An execution condition outside quoted title/reference content must defer; quoted condition words alo
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[chapter(action='by_title', query=\"Introduction\")]<|tool_call_end|>", utterance = "when this episode ends, go to chapter 'Introduction'", tool = "chapter", action = "by_title", textLanguage = "en"))
    }

    @Test
    fun `bookmark_execution_condition`() {
        // An execution condition outside quoted title/reference content must defer; quoted condition words alo
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[bookmark(action='play', ref=\"my bookmark\")]<|tool_call_end|>", utterance = "when this episode ends, play my bookmark", tool = "bookmark", action = "play", textLanguage = "en"))
    }

    @Test
    fun `quoted_title_condition_words`() {
        // An execution condition outside quoted title/reference content must defer; quoted condition words alo
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[chapter(action='by_title', query=\"When This Episode Ends\")]<|tool_call_end|>", utterance = "go to chapter 'When This Episode Ends'", tool = "chapter", action = "by_title", textLanguage = "en")
        assertNotNull(repaired)
        assertEquals("When This Episode Ends", repaired?.params?.get("query").let { if (it is String) it.trim('"') else it })
    }

    @Test
    fun `quoted_reference_condition_words`() {
        // An execution condition outside quoted title/reference content must defer; quoted condition words alo
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[bookmark(action='play', ref=\"When This Episode Ends\")]<|tool_call_end|>", utterance = "play bookmark 'When This Episode Ends'", tool = "bookmark", action = "play", textLanguage = "en")
        assertNotNull(repaired)
        assertEquals("When This Episode Ends", repaired?.params?.get("ref").let { if (it is String) it.trim('"') else it })
    }

    @Test
    fun `condition_outside_matching_title`() {
        // An execution condition outside quoted title/reference content must defer; quoted condition words alo
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[chapter(action='by_title', query=\"when this episode ends\")]<|tool_call_end|>", utterance = "when this episode ends, go to chapter 'when this episode ends'", tool = "chapter", action = "by_title", textLanguage = "en"))
    }

    @Test
    fun `queue_order_not_trigger`() {
        // A queue position or polite request is resolved; a separate future execution condition is not.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[queue(action='add_top', episode=\"Introduction\")]<|tool_call_end|>", utterance = "queue 'Introduction' right after this episode", tool = "queue", action = "add_top", textLanguage = "en")
        assertNotNull(repaired)
        assertEquals("Introduction", repaired?.params?.get("episode").let { if (it is String) it.trim('"') else it })
    }

    @Test
    fun `polite_if_request`() {
        // A queue position or polite request is resolved; a separate future execution condition is not.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[queue(action='move_to_bottom', episode=\"Introduction\")]<|tool_call_end|>", utterance = "If you could, please append 'Introduction' to the very end", tool = "queue", action = "move_to_bottom", textLanguage = "en")
        assertNotNull(repaired)
        assertEquals("Introduction", repaired?.params?.get("episode").let { if (it is String) it.trim('"') else it })
    }

    @Test
    fun `queue_future_trigger`() {
        // A queue position or polite request is resolved; a separate future execution condition is not.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[queue(action='add_top', episode=\"Introduction\")]<|tool_call_end|>", utterance = "when this episode ends, queue 'Introduction'", tool = "queue", action = "add_top", textLanguage = "en"))
    }

    @Test
    fun `future_read_only_query`() {
        // A future execution condition also constrains a query; an immediate temporal question does not.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[volume(action='query')]<|tool_call_end|>", utterance = "when this episode ends, tell me the volume", tool = "volume", action = "query", textLanguage = "en"))
    }

    @Test
    fun `current_when_question`() {
        // A future execution condition also constrains a query; an immediate temporal question does not.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[stats_query(action='time_since_last_listen')]<|tool_call_end|>", utterance = "when did I last listen", tool = "stats_query", action = "time_since_last_listen", textLanguage = "en")
        assertNotNull(repaired)
    }

    @Test
    fun `query_next_order`() {
        // This asks now about an encoded ordering/time target; it does not schedule the query.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[queue(action='query_next')]<|tool_call_end|>", utterance = "what episode plays after this one", tool = "queue", action = "query_next", textLanguage = "en")
        assertNotNull(repaired)
    }

    @Test
    fun `remaining_time_question`() {
        // This asks now about an encoded ordering/time target; it does not schedule the query.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[playback_query(action='time_remaining')]<|tool_call_end|>", utterance = "how much longer until this episode is done", tool = "playback_query", action = "time_remaining", textLanguage = "en")
        assertNotNull(repaired)
    }

    @Test
    fun `timer_remaining_question`() {
        // This asks now about an encoded ordering/time target; it does not schedule the query.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[sleep(action='query')]<|tool_call_end|>", utterance = "how much longer until the sleep timer goes off", tool = "sleep", action = "query", textLanguage = "en")
        assertNotNull(repaired)
    }

    @Test
    fun `es_eleven_volume`() {
        // Eleven in a numeric context or once as a repetition count does not introduce an execution clause.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[volume(action='set_volume', volume=11)]<|tool_call_end|>", utterance = "pon el volumen a once por ciento", tool = "volume", action = "set_volume", textLanguage = "es")
        assertNotNull(repaired)
        assertEquals(11, repaired?.params?.get("volume").let { if (it is String) it.trim('"') else it })
    }

    @Test
    fun `es_exact_eleven_volume`() {
        // Eleven in a numeric context or once as a repetition count does not introduce an execution clause.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[volume(action='set_volume', volume=11)]<|tool_call_end|>", utterance = "pon el volumen exactamente a once por ciento", tool = "volume", action = "set_volume", textLanguage = "es")
        assertNotNull(repaired)
        assertEquals(11, repaired?.params?.get("volume").let { if (it is String) it.trim('"') else it })
    }

    @Test
    fun `es_eleven_adjustment`() {
        // Eleven in a numeric context or once as a repetition count does not introduce an execution clause.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[volume(action='adjust_volume', delta=11)]<|tool_call_end|>", utterance = "sube el volumen once puntos", tool = "volume", action = "adjust_volume", textLanguage = "es")
        assertNotNull(repaired)
        assertEquals(11, repaired?.params?.get("delta").let { if (it is String) it.trim('"') else it })
    }

    @Test
    fun `es_fractional_rate_eleven`() {
        // Eleven in a numeric context or once as a repetition count does not introduce an execution clause.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[effects(action='set_speed', speed=1.11)]<|tool_call_end|>", utterance = "pon la velocidad en uno coma once veces", tool = "effects", action = "set_speed", textLanguage = "es")
        assertNotNull(repaired)
        assertEquals(1.11, repaired?.params?.get("speed").let { if (it is String) it.trim('"') else it })
    }

    @Test
    fun `es_eleven_chapter_now`() {
        // Eleven in a numeric context or once as a repetition count does not introduce an execution clause.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[chapter(action='by_index', index=11)]<|tool_call_end|>", utterance = "ve al capítulo once ahora", tool = "chapter", action = "by_index", textLanguage = "es")
        assertNotNull(repaired)
        assertEquals(11, repaired?.params?.get("index").let { if (it is String) it.trim('"') else it })
    }

    @Test
    fun `es_eleven_timer`() {
        // Eleven in a numeric context or once as a repetition count does not introduce an execution clause.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[sleep(action='set', minutes=11)]<|tool_call_end|>", utterance = "pon un temporizador de once minutos", tool = "sleep", action = "set", textLanguage = "es")
        assertNotNull(repaired)
        assertEquals(11, repaired?.params?.get("minutes").let { if (it is String) it.trim('"') else it })
    }

    @Test
    fun `es_eleven_position`() {
        // Eleven in a numeric context or once as a repetition count does not introduce an execution clause.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_to', position_seconds=11)]<|tool_call_end|>", utterance = "ve al segundo once ahora", tool = "playback", action = "seek_to", textLanguage = "es")
        assertNotNull(repaired)
        assertEquals(11, repaired?.params?.get("position_seconds").let { if (it is String) it.trim('"') else it })
    }

    @Test
    fun `en_once_count_please`() {
        // Eleven in a numeric context or once as a repetition count does not introduce an execution clause.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "rewind ten seconds once please", tool = "playback", action = "seek_relative", textLanguage = "en")
        assertNotNull(repaired)
        assertEquals(-10, repaired?.params?.get("delta_seconds").let { if (it is String) it.trim('"') else it })
    }

    @Test
    fun `en_once_episode`() {
        // Once introduces an English event/state clause that conditions execution.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "once this episode ends, rewind ten seconds", tool = "playback", action = "seek_relative", textLanguage = "en"))
    }

    @Test
    fun `en_once_paused`() {
        // Once introduces an English event/state clause that conditions execution.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "rewind ten seconds once playback is paused", tool = "playback", action = "seek_relative", textLanguage = "en"))
    }

    @Test
    fun `en_once_download`() {
        // Once introduces an English event/state clause that conditions execution.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[bookmark(action='play', ref=\"my bookmark\")]<|tool_call_end|>", utterance = "once the download completes, play my bookmark", tool = "bookmark", action = "play", textLanguage = "en"))
    }

    @Test
    fun `en_once_ready`() {
        // Once introduces an English event/state clause that conditions execution.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[chapter(action='by_title', query=\"Introduction\")]<|tool_call_end|>", utterance = "once ready, go to chapter Introduction", tool = "chapter", action = "by_title", textLanguage = "en"))
    }

    @Test
    fun `en_once_plural_subject`() {
        // An English event predicate after once conditions execution regardless of the subject form.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "once both chapters finish, rewind ten seconds", tool = "playback", action = "seek_relative", textLanguage = "en"))
    }

    @Test
    fun `en_once_named_subject`() {
        // An English event predicate after once conditions execution regardless of the subject form.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "once Alex finishes, rewind ten seconds", tool = "playback", action = "seek_relative", textLanguage = "en"))
    }

    @Test
    fun `en_once_elapsed_duration`() {
        // An English event predicate after once conditions execution regardless of the subject form.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "once thirty seconds elapse, rewind ten seconds", tool = "playback", action = "seek_relative", textLanguage = "en"))
    }

    @Test
    fun `en_once_say_go`() {
        // An unresolved English once clause defers regardless of its event verb.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "once I say go, rewind ten seconds", tool = "playback", action = "seek_relative", textLanguage = "en"))
    }

    @Test
    fun `en_once_music_fades`() {
        // An unresolved English once clause defers regardless of its event verb.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "once the intro music fades, rewind ten seconds", tool = "playback", action = "seek_relative", textLanguage = "en"))
    }

    @Test
    fun `translated_once_english_source_es`() {
        // Disambiguation uses the exact repair text language, not its ASR source; unknown is conservative.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "once I say go, rewind ten seconds", tool = "playback", action = "seek_relative", textLanguage = "en"))
    }

    @Test
    fun `unknown_once_english_source_es`() {
        // Disambiguation uses the exact repair text language, not its ASR source; unknown is conservative.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "once I say go, rewind ten seconds", tool = "playback", action = "seek_relative", textLanguage = null))
    }

    @Test
    fun `translated_once_inline_english`() {
        // Disambiguation uses the exact repair text language, not its ASR source; unknown is conservative.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "rewind ten seconds once the intro music fades", tool = "playback", action = "seek_relative", textLanguage = "en"))
    }

    @Test
    fun `unknown_text_native_spanish`() {
        // Disambiguation uses the exact repair text language, not its ASR source; unknown is conservative.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[volume(action='set_volume', volume=11)]<|tool_call_end|>", utterance = "pon el volumen a once por ciento", tool = "volume", action = "set_volume", textLanguage = null))
    }

    @Test
    fun `spanish_regional_eleven`() {
        // Disambiguation uses the exact repair text language, not its ASR source; unknown is conservative.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[volume(action='set_volume', volume=11)]<|tool_call_end|>", utterance = "pon el volumen a once por ciento", tool = "volume", action = "set_volume", textLanguage = "es-ES")
        assertNotNull(repaired)
        assertEquals(11, repaired?.params?.get("volume").let { if (it is String) it.trim('"') else it })
    }

    @Test
    fun `english_regional_condition`() {
        // Disambiguation uses the exact repair text language, not its ASR source; unknown is conservative.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "once I say go, rewind ten seconds", tool = "playback", action = "seek_relative", textLanguage = "en-US"))
    }

    @Test
    fun `es_una_vez_execution_condition`() {
        // Spanish eleven is numeric; the distinct una vez que conjunction still conditions execution.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "una vez que termine este episodio, retrocede diez segundos", tool = "playback", action = "seek_relative", textLanguage = "es"))
    }
}
