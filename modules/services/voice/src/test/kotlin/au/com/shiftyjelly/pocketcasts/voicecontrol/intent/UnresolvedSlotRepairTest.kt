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
 * Two limits keep the guard from refusing valid requests. An action whose number is a duration
 * or an insertion point is left alone, because such a call is complete as soon as the number is
 * present. A quoted span is treated as a name rather than as wording that says when to act, so a
 * chapter titled "When This Episode Ends" is still reachable and "if you could, please" is
 * politeness rather than a condition.
 */
@RunWith(RobolectricTestRunner::class)
class UnresolvedSlotRepairTest {

    @Test
    fun `nov09o_root_001_en_decoded_0`() {
        // Two alternative amounts are offered without choosing either. One unresolved action, not two affirmed seeks; do
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "rewind ten or twenty seconds", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `nov09o_root_001_en_decoded_1`() {
        // Two alternative amounts are offered without choosing either. One unresolved action, not two affirmed seeks; do
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "rewind ten or twenty seconds", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `nov09o_root_001_en_decoded_2`() {
        // Two alternative amounts are offered without choosing either. One unresolved action, not two affirmed seeks; do
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "rewind ten or twenty seconds", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `nov09o_root_001_zh_decoded_0`() {
        // Two alternative amounts are offered without choosing either. One unresolved action, not two affirmed seeks; do
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "往回跳十秒或者二十秒", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `nov09o_root_001_zh_decoded_1`() {
        // Two alternative amounts are offered without choosing either. One unresolved action, not two affirmed seeks; do
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "往回跳十秒或者二十秒", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `nov09o_root_001_zh_decoded_2`() {
        // Two alternative amounts are offered without choosing either. One unresolved action, not two affirmed seeks; do
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "往回跳十秒或者二十秒", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `nov09o_root_001_ja_decoded_0`() {
        // Two alternative amounts are offered without choosing either. One unresolved action, not two affirmed seeks; do
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "10秒か20秒戻して", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `nov09o_root_001_ja_decoded_1`() {
        // Two alternative amounts are offered without choosing either. One unresolved action, not two affirmed seeks; do
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "10秒か20秒戻して", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `nov09o_root_001_ja_decoded_2`() {
        // Two alternative amounts are offered without choosing either. One unresolved action, not two affirmed seeks; do
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "10秒か20秒戻して", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `nov09o_root_001_es_decoded_0`() {
        // Two alternative amounts are offered without choosing either. One unresolved action, not two affirmed seeks; do
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "retrocede diez o veinte segundos", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `nov09o_root_001_es_decoded_1`() {
        // Two alternative amounts are offered without choosing either. One unresolved action, not two affirmed seeks; do
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "retrocede diez o veinte segundos", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `nov09o_root_001_es_decoded_2`() {
        // Two alternative amounts are offered without choosing either. One unresolved action, not two affirmed seeks; do
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "retrocede diez o veinte segundos", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `nov09o_root_002_en_decoded_0`() {
        // The negative clause excludes ten and leaves twenty as the single affirmed amount: one backward displacement of
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-20)]<|tool_call_end|>", utterance = "rewind twenty seconds, not ten", tool = "playback", action = "seek_relative")
        assertNotNull(repaired)
        assertEquals(-20, repaired?.params?.get("delta_seconds"))
    }

    @Test
    fun `nov09o_root_002_zh_decoded_0`() {
        // The negative clause excludes ten and leaves twenty as the single affirmed amount: one backward displacement of
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-20)]<|tool_call_end|>", utterance = "往回跳二十秒，不是十秒", tool = "playback", action = "seek_relative")
        assertNotNull(repaired)
        assertEquals(-20, repaired?.params?.get("delta_seconds"))
    }

    @Test
    fun `nov09o_root_002_ja_decoded_0`() {
        // The negative clause excludes ten and leaves twenty as the single affirmed amount: one backward displacement of
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-20)]<|tool_call_end|>", utterance = "10秒ではなく20秒戻して", tool = "playback", action = "seek_relative")
        assertNotNull(repaired)
        assertEquals(-20, repaired?.params?.get("delta_seconds"))
    }

    @Test
    fun `nov09o_root_002_es_decoded_0`() {
        // The negative clause excludes ten and leaves twenty as the single affirmed amount: one backward displacement of
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-20)]<|tool_call_end|>", utterance = "retrocede veinte segundos, no diez", tool = "playback", action = "seek_relative")
        assertNotNull(repaired)
        assertEquals(-20, repaired?.params?.get("delta_seconds"))
    }

    @Test
    fun `nov09o_root_003_en_decoded_0`() {
        // At least ten is a fully spoken lower bound, not exactly ten. No unique displacement is supplied; do not substi
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "rewind at least ten seconds", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `nov09o_root_003_en_decoded_1`() {
        // At least ten is a fully spoken lower bound, not exactly ten. No unique displacement is supplied; do not substi
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "rewind at least ten seconds", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `nov09o_root_003_en_decoded_2`() {
        // At least ten is a fully spoken lower bound, not exactly ten. No unique displacement is supplied; do not substi
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "rewind at least ten seconds", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `nov09o_root_003_zh_decoded_0`() {
        // At least ten is a fully spoken lower bound, not exactly ten. No unique displacement is supplied; do not substi
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "往回跳至少十秒", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `nov09o_root_003_zh_decoded_1`() {
        // At least ten is a fully spoken lower bound, not exactly ten. No unique displacement is supplied; do not substi
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "往回跳至少十秒", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `nov09o_root_003_zh_decoded_2`() {
        // At least ten is a fully spoken lower bound, not exactly ten. No unique displacement is supplied; do not substi
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "往回跳至少十秒", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `nov09o_root_003_ja_decoded_0`() {
        // At least ten is a fully spoken lower bound, not exactly ten. No unique displacement is supplied; do not substi
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "少なくとも10秒戻して", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `nov09o_root_003_ja_decoded_1`() {
        // At least ten is a fully spoken lower bound, not exactly ten. No unique displacement is supplied; do not substi
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "少なくとも10秒戻して", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `nov09o_root_003_ja_decoded_2`() {
        // At least ten is a fully spoken lower bound, not exactly ten. No unique displacement is supplied; do not substi
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "少なくとも10秒戻して", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `nov09o_root_003_es_decoded_0`() {
        // At least ten is a fully spoken lower bound, not exactly ten. No unique displacement is supplied; do not substi
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "retrocede al menos diez segundos", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `nov09o_root_003_es_decoded_1`() {
        // At least ten is a fully spoken lower bound, not exactly ten. No unique displacement is supplied; do not substi
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "retrocede al menos diez segundos", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `nov09o_root_003_es_decoded_2`() {
        // At least ten is a fully spoken lower bound, not exactly ten. No unique displacement is supplied; do not substi
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "retrocede al menos diez segundos", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `nov09o_root_004_en_decoded_0`() {
        // Exactly ten supplies a unique, representable integer-second backward displacement.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "rewind exactly ten seconds", tool = "playback", action = "seek_relative")
        assertNotNull(repaired)
        assertEquals(-10, repaired?.params?.get("delta_seconds"))
    }

    @Test
    fun `nov09o_root_004_zh_decoded_0`() {
        // Exactly ten supplies a unique, representable integer-second backward displacement.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "往回跳正好十秒", tool = "playback", action = "seek_relative")
        assertNotNull(repaired)
        assertEquals(-10, repaired?.params?.get("delta_seconds"))
    }

    @Test
    fun `nov09o_root_004_ja_decoded_0`() {
        // Exactly ten supplies a unique, representable integer-second backward displacement.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "ちょうど10秒戻して", tool = "playback", action = "seek_relative")
        assertNotNull(repaired)
        assertEquals(-10, repaired?.params?.get("delta_seconds"))
    }

    @Test
    fun `nov09o_root_004_es_decoded_0`() {
        // Exactly ten supplies a unique, representable integer-second backward displacement.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "retrocede exactamente diez segundos", tool = "playback", action = "seek_relative")
        assertNotNull(repaired)
        assertEquals(-10, repaired?.params?.get("delta_seconds"))
    }

    @Test
    fun `nov09o_root_005_en_decoded_0`() {
        // The seek is conditional on a future episode-end event. No history says that event occurred. Immediate local ex
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "when this episode ends, rewind ten seconds", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `nov09o_root_005_en_decoded_1`() {
        // The seek is conditional on a future episode-end event. No history says that event occurred. Immediate local ex
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "when this episode ends, rewind ten seconds", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `nov09o_root_005_en_decoded_2`() {
        // The seek is conditional on a future episode-end event. No history says that event occurred. Immediate local ex
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "when this episode ends, rewind ten seconds", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `nov09o_root_005_zh_decoded_0`() {
        // The seek is conditional on a future episode-end event. No history says that event occurred. Immediate local ex
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "等这集结束后，再往回跳十秒", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `nov09o_root_005_zh_decoded_1`() {
        // The seek is conditional on a future episode-end event. No history says that event occurred. Immediate local ex
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "等这集结束后，再往回跳十秒", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `nov09o_root_005_zh_decoded_2`() {
        // The seek is conditional on a future episode-end event. No history says that event occurred. Immediate local ex
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "等这集结束后，再往回跳十秒", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `nov09o_root_005_ja_decoded_0`() {
        // The seek is conditional on a future episode-end event. No history says that event occurred. Immediate local ex
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "このエピソードが終わったら10秒戻して", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `nov09o_root_005_ja_decoded_1`() {
        // The seek is conditional on a future episode-end event. No history says that event occurred. Immediate local ex
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "このエピソードが終わったら10秒戻して", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `nov09o_root_005_ja_decoded_2`() {
        // The seek is conditional on a future episode-end event. No history says that event occurred. Immediate local ex
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "このエピソードが終わったら10秒戻して", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `nov09o_root_005_es_decoded_0`() {
        // The seek is conditional on a future episode-end event. No history says that event occurred. Immediate local ex
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "cuando termine este episodio, retrocede diez segundos", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `nov09o_root_005_es_decoded_1`() {
        // The seek is conditional on a future episode-end event. No history says that event occurred. Immediate local ex
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "cuando termine este episodio, retrocede diez segundos", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `nov09o_root_005_es_decoded_2`() {
        // The seek is conditional on a future episode-end event. No history says that event occurred. Immediate local ex
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "cuando termine este episodio, retrocede diez segundos", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `nov09o_root_006_en_decoded_0`() {
        // Now requests immediate backward displacement of ten seconds with no future trigger.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "rewind ten seconds now", tool = "playback", action = "seek_relative")
        assertNotNull(repaired)
        assertEquals(-10, repaired?.params?.get("delta_seconds"))
    }

    @Test
    fun `nov09o_root_006_zh_decoded_0`() {
        // Now requests immediate backward displacement of ten seconds with no future trigger.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "现在往回跳十秒", tool = "playback", action = "seek_relative")
        assertNotNull(repaired)
        assertEquals(-10, repaired?.params?.get("delta_seconds"))
    }

    @Test
    fun `nov09o_root_006_ja_decoded_0`() {
        // Now requests immediate backward displacement of ten seconds with no future trigger.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "今すぐ10秒戻して", tool = "playback", action = "seek_relative")
        assertNotNull(repaired)
        assertEquals(-10, repaired?.params?.get("delta_seconds"))
    }

    @Test
    fun `nov09o_root_006_es_decoded_0`() {
        // Now requests immediate backward displacement of ten seconds with no future trigger.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "retrocede diez segundos ahora", tool = "playback", action = "seek_relative")
        assertNotNull(repaired)
        assertEquals(-10, repaired?.params?.get("delta_seconds"))
    }

    @Test
    fun `unresolved_precedence_1_en_0`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "rewind ten or twenty seconds now", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `unresolved_precedence_1_en_1`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "rewind ten or twenty seconds now", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `unresolved_precedence_1_en_2`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "rewind ten or twenty seconds now", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `unresolved_precedence_1_zh_0`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "现在往回跳十秒或者二十秒", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `unresolved_precedence_1_zh_1`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "现在往回跳十秒或者二十秒", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `unresolved_precedence_1_zh_2`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "现在往回跳十秒或者二十秒", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `unresolved_precedence_1_ja_0`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "今すぐ10秒か20秒戻して", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `unresolved_precedence_1_ja_1`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "今すぐ10秒か20秒戻して", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `unresolved_precedence_1_ja_2`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "今すぐ10秒か20秒戻して", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `unresolved_precedence_1_es_0`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "retrocede diez o veinte segundos ahora", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `unresolved_precedence_1_es_1`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "retrocede diez o veinte segundos ahora", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `unresolved_precedence_1_es_2`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "retrocede diez o veinte segundos ahora", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `unresolved_precedence_2_en_0`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "when this ends, rewind exactly ten seconds", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `unresolved_precedence_2_en_1`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "when this ends, rewind exactly ten seconds", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `unresolved_precedence_2_en_2`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "when this ends, rewind exactly ten seconds", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `unresolved_precedence_2_zh_0`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "等这集结束后，再往回跳正好十秒", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `unresolved_precedence_2_zh_1`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "等这集结束后，再往回跳正好十秒", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `unresolved_precedence_2_zh_2`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "等这集结束后，再往回跳正好十秒", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `unresolved_precedence_2_ja_0`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "このエピソードが終わったらちょうど10秒戻して", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `unresolved_precedence_2_ja_1`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "このエピソードが終わったらちょうど10秒戻して", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `unresolved_precedence_2_ja_2`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "このエピソードが終わったらちょうど10秒戻して", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `unresolved_precedence_2_es_0`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "cuando termine esto, retrocede exactamente diez segundos", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `unresolved_precedence_2_es_1`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "cuando termine esto, retrocede exactamente diez segundos", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `unresolved_precedence_2_es_2`() {
        // Now or exactly cannot settle a remaining alternative or future execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "cuando termine esto, retrocede exactamente diez segundos", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `chapter_alternatives_0`() {
        // The unresolved alternative/event must refuse the whole classified call.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[chapter(action='by_index', index=10)]<|tool_call_end|>", utterance = "go to chapter ten or twenty", tool = "chapter", action = "by_index"))
    }

    @Test
    fun `chapter_alternatives_1`() {
        // The unresolved alternative/event must refuse the whole classified call.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[chapter(action='by_index')]<|tool_call_end|>", utterance = "go to chapter ten or twenty", tool = "chapter", action = "by_index"))
    }

    @Test
    fun `chapter_alternatives_2`() {
        // The unresolved alternative/event must refuse the whole classified call.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[chapter(action='by_index', index=20)]<|tool_call_end|>", utterance = "go to chapter ten or twenty", tool = "chapter", action = "by_index"))
    }

    @Test
    fun `fraction_alternatives_0`() {
        // The unresolved alternative/event must refuse the whole classified call.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-15)]<|tool_call_end|>", utterance = "rewind half or a quarter of a minute", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `fraction_alternatives_1`() {
        // The unresolved alternative/event must refuse the whole classified call.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "rewind half or a quarter of a minute", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `fraction_alternatives_2`() {
        // The unresolved alternative/event must refuse the whole classified call.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "rewind half or a quarter of a minute", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `event_first_condition_0`() {
        // The unresolved alternative/event must refuse the whole classified call.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-10)]<|tool_call_end|>", utterance = "这集结束后倒回十秒", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `event_first_condition_1`() {
        // The unresolved alternative/event must refuse the whole classified call.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>", utterance = "这集结束后倒回十秒", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `event_first_condition_2`() {
        // The unresolved alternative/event must refuse the whole classified call.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', direction=\"backward\")]<|tool_call_end|>", utterance = "这集结束后倒回十秒", tool = "playback", action = "seek_relative"))
    }

    @Test
    fun `fractional_timer`() {
        // The known duration or encoded timer target is resolved.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[sleep(action='set', minutes=30)]<|tool_call_end|>", utterance = "set a sleep timer after half an hour", tool = "sleep", action = "set")
        assertNotNull(repaired)
        assertEquals(30, repaired?.params?.get("minutes"))
    }

    @Test
    fun `spanish_eleven`() {
        // The known duration or encoded timer target is resolved.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-11)]<|tool_call_end|>", utterance = "retrocede once segundos", tool = "playback", action = "seek_relative")
        assertNotNull(repaired)
        assertEquals(-11, repaired?.params?.get("delta_seconds"))
    }

    @Test
    fun `spanish_exact_eleven`() {
        // The known duration or encoded timer target is resolved.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-11)]<|tool_call_end|>", utterance = "retrocede exactamente once segundos", tool = "playback", action = "seek_relative")
        assertNotNull(repaired)
        assertEquals(-11, repaired?.params?.get("delta_seconds"))
    }

    @Test
    fun `chapter_coordinate_previous`() {
        // The relation identifies a chapter target, not a future execution condition.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[chapter(action='previous')]<|tool_call_end|>", utterance = "go to the chapter before this one", tool = "chapter", action = "previous")
        assertNotNull(repaired)
    }

    @Test
    fun `chapter_coordinate_next`() {
        // The relation identifies a chapter target, not a future execution condition.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[chapter(action='next')]<|tool_call_end|>", utterance = "go to the chapter after this one", tool = "chapter", action = "next")
        assertNotNull(repaired)
    }

    @Test
    fun `chapter_future_after_completion`() {
        // An explicit ending predicate makes this an execution condition, not an adjacent-chapter coordinate.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[chapter(action='next')]<|tool_call_end|>", utterance = "go to the chapter after this one ends", tool = "chapter", action = "next"))
    }

    @Test
    fun `end_coordinate_zh`() {
        // The temporal relation encodes an episode position, not an execution trigger.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_to', position_seconds=-10)]<|tool_call_end|>", utterance = "跳到结束前十秒", tool = "playback", action = "seek_to")
        assertNotNull(repaired)
        assertEquals(-10, repaired?.params?.get("position_seconds"))
    }

    @Test
    fun `end_coordinate_ja`() {
        // The temporal relation encodes an episode position, not an execution trigger.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_to', position_seconds=-10)]<|tool_call_end|>", utterance = "終了の10秒前に移動して", tool = "playback", action = "seek_to")
        assertNotNull(repaired)
        assertEquals(-10, repaired?.params?.get("position_seconds"))
    }

    @Test
    fun `end_coordinate_es`() {
        // The temporal relation encodes an episode position, not an execution trigger.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_to', position_seconds=-10)]<|tool_call_end|>", utterance = "ve a diez segundos antes del final", tool = "playback", action = "seek_to")
        assertNotNull(repaired)
        assertEquals(-10, repaired?.params?.get("position_seconds"))
    }

    @Test
    fun `future_with_end_coordinate_zh`() {
        // A resolved target position does not erase the separate execution condition.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[playback(action='seek_to', position_seconds=-10)]<|tool_call_end|>", utterance = "这集结束后跳到结束前十秒", tool = "playback", action = "seek_to"))
    }

    @Test
    fun `title_execution_condition`() {
        // An execution condition outside quoted title/reference content must defer; quoted condition words alone name da
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[chapter(action='by_title', query=\"Introduction\")]<|tool_call_end|>", utterance = "when this episode ends, go to chapter 'Introduction'", tool = "chapter", action = "by_title"))
    }

    @Test
    fun `bookmark_execution_condition`() {
        // An execution condition outside quoted title/reference content must defer; quoted condition words alone name da
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[bookmark(action='play', ref=\"my bookmark\")]<|tool_call_end|>", utterance = "when this episode ends, play my bookmark", tool = "bookmark", action = "play"))
    }

    @Test
    fun `quoted_title_condition_words`() {
        // An execution condition outside quoted title/reference content must defer; quoted condition words alone name da
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[chapter(action='by_title', query=\"When This Episode Ends\")]<|tool_call_end|>", utterance = "go to chapter 'When This Episode Ends'", tool = "chapter", action = "by_title")
        assertNotNull(repaired)
        // The quoted name must survive; the exact quoting of a string param is
        // repaired elsewhere and is not what this guard decides.
        assert(repaired?.params?.get("query").toString().contains("When This Episode Ends"))
    }

    @Test
    fun `quoted_reference_condition_words`() {
        // An execution condition outside quoted title/reference content must defer; quoted condition words alone name da
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[bookmark(action='play', ref=\"When This Episode Ends\")]<|tool_call_end|>", utterance = "play bookmark 'When This Episode Ends'", tool = "bookmark", action = "play")
        assertNotNull(repaired)
        assertEquals("When This Episode Ends", repaired?.params?.get("ref"))
    }

    @Test
    fun `condition_outside_matching_title`() {
        // An execution condition outside quoted title/reference content must defer; quoted condition words alone name da
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[chapter(action='by_title', query=\"when this episode ends\")]<|tool_call_end|>", utterance = "when this episode ends, go to chapter 'when this episode ends'", tool = "chapter", action = "by_title"))
    }

    @Test
    fun `queue_order_not_trigger`() {
        // A queue position or polite request is resolved; a separate future execution condition is not.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[queue(action='add_top', episode=\"Introduction\")]<|tool_call_end|>", utterance = "queue 'Introduction' right after this episode", tool = "queue", action = "add_top")
        assertNotNull(repaired)
        // The quoted name must survive; the exact quoting of a string param is
        // repaired elsewhere and is not what this guard decides.
        assert(repaired?.params?.get("episode").toString().contains("Introduction"))
    }

    @Test
    fun `polite_if_request`() {
        // A queue position or polite request is resolved; a separate future execution condition is not.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[queue(action='move_to_bottom', episode=\"Introduction\")]<|tool_call_end|>", utterance = "If you could, please append 'Introduction' to the very end", tool = "queue", action = "move_to_bottom")
        assertNotNull(repaired)
        // The quoted name must survive; the exact quoting of a string param is
        // repaired elsewhere and is not what this guard decides.
        assert(repaired?.params?.get("episode").toString().contains("Introduction"))
    }

    @Test
    fun `queue_future_trigger`() {
        // A queue position or polite request is resolved; a separate future execution condition is not.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[queue(action='add_top', episode=\"Introduction\")]<|tool_call_end|>", utterance = "when this episode ends, queue 'Introduction'", tool = "queue", action = "add_top"))
    }

    @Test
    fun `future_read_only_query`() {
        // A future execution condition also constrains a query; an immediate temporal question does not.
        assertNull(SlotRepair.repair(raw = "<|tool_call_start|>[volume(action='query')]<|tool_call_end|>", utterance = "when this episode ends, tell me the volume", tool = "volume", action = "query"))
    }

    @Test
    fun `current_when_question`() {
        // A future execution condition also constrains a query; an immediate temporal question does not.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[stats_query(action='time_since_last_listen')]<|tool_call_end|>", utterance = "when did I last listen", tool = "stats_query", action = "time_since_last_listen")
        assertNotNull(repaired)
    }

    @Test
    fun `query_next_order`() {
        // This asks now about an encoded ordering/time target; it does not schedule the query.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[queue(action='query_next')]<|tool_call_end|>", utterance = "what episode plays after this one", tool = "queue", action = "query_next")
        assertNotNull(repaired)
    }

    @Test
    fun `remaining_time_question`() {
        // This asks now about an encoded ordering/time target; it does not schedule the query.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[playback_query(action='time_remaining')]<|tool_call_end|>", utterance = "how much longer until this episode is done", tool = "playback_query", action = "time_remaining")
        assertNotNull(repaired)
    }

    @Test
    fun `timer_remaining_question`() {
        // This asks now about an encoded ordering/time target; it does not schedule the query.
        val repaired = SlotRepair.repair(raw = "<|tool_call_start|>[sleep(action='query')]<|tool_call_end|>", utterance = "how much longer until the sleep timer goes off", tool = "sleep", action = "query")
        assertNotNull(repaired)
    }
}
