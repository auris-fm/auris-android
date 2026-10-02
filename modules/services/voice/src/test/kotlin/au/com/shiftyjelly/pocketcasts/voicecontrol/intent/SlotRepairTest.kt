package au.com.shiftyjelly.pocketcasts.voicecontrol.intent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SlotRepairTest {
    @Test
    fun collapseRepetition_collapsesRepeatedSuffix() {
        assertEquals(
            "the turning point",
            SlotRepair.collapseRepetition("the turning point the turning point"),
        )
    }

    @Test
    fun repair_seekRelativeFromMinuteUtterance_overridesWrongModelSlots() {
        val fromMinutes = SlotRepair.repair(
            raw = "<|tool_call_start|>[playback(action='seek_relative', minutes=1)]<|tool_call_end|>",
            utterance = "Could you just go back a minute, please?",
            tool = "playback",
            action = "seek_relative",
        )
        assertNotNull(fromMinutes)
        assertEquals(-60, fromMinutes!!.params["delta_seconds"])

        val fromWrongDelta = SlotRepair.repair(
            raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-1)]<|tool_call_end|>",
            utterance = "go back a minute",
            tool = "playback",
            action = "seek_relative",
        )
        assertNotNull(fromWrongDelta)
        assertEquals(-60, fromWrongDelta!!.params["delta_seconds"])
    }

    @Test
    fun repair_garbledTitle_restoredFromQuotedSpan() {
        val repaired = SlotRepair.repair(
            raw = "<|tool_call_start|>[dialog_control(action='provide_slot', target_tool='bookmark', target_action='rename', slot='title', value='Key Insuel')]<|tool_call_end|>",
            utterance = "Call it 'Key Insight'.",
            tool = "dialog_control",
            action = "provide_slot",
        )
        assertNotNull(repaired)
        assertEquals("Key Insight", repaired!!.params["value"])
    }

    @Test
    fun repair_noMatch_returnsEmptyParams() {
        val repaired = SlotRepair.repair(
            raw = "<|tool_call_start|>[no_match(action='')]<|tool_call_end|>",
            utterance = "hello there",
            tool = "no_match",
            action = "",
        )
        assertNotNull(repaired)
        assertEquals("no_match", repaired!!.name)
        assertEquals("", repaired.action)
        assertTrue(repaired.params.isEmpty())
    }

    @Test
    fun repair_neverChangesClassifierToolAndAction() {
        val repaired = SlotRepair.repair(
            raw = "<|tool_call_start|>[volume(action='set_volume', volume=50)]<|tool_call_end|>",
            utterance = "go back a minute",
            tool = "playback",
            action = "seek_relative",
        )
        assertNotNull(repaired)
        assertEquals("playback", repaired!!.name)
        assertEquals("seek_relative", repaired.action)
    }

    @Test
    fun repair_volumeKeepsVolumeSlot() {
        val repaired = SlotRepair.repair(
            raw = "<|tool_call_start|>[volume(action='set_volume', volume=50)]<|tool_call_end|>",
            utterance = "set volume to 50",
            tool = "volume",
            action = "set_volume",
        )
        assertNotNull(repaired)
        assertEquals("volume", repaired!!.name)
        assertEquals("set_volume", repaired.action)
        assertEquals(50, repaired.params["volume"])
    }

    @Test
    fun repair_seekRelativeWithoutDelta_fillsSignedDefaultFromWording() {
        val forward = SlotRepair.repair(
            raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>",
            utterance = "skip ahead",
            tool = "playback",
            action = "seek_relative",
        )
        assertEquals(30, forward!!.params["delta_seconds"])

        val backward = SlotRepair.repair(
            raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>",
            utterance = "skip back",
            tool = "playback",
            action = "seek_relative",
        )
        assertEquals(-30, backward!!.params["delta_seconds"])

        // `\bback\b` does not match inside "backwards" — must still fill -30.
        val backwards = SlotRepair.repair(
            raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>",
            utterance = "skip backwards",
            tool = "playback",
            action = "seek_relative",
        )
        assertEquals(-30, backwards!!.params["delta_seconds"])
    }

    // -- shared fixture: relative_seek_repair.json --

    @Test
    fun repair_preservesSign_whenDeltaNonZero() {
        // Case: "Nudge it backwards fifteen seconds" — predicted -1 → repaired -15
        val r = SlotRepair.repair(
            raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=-1)]<|tool_call_end|>",
            utterance = "Nudge it backwards fifteen seconds",
            tool = "playback",
            action = "seek_relative",
        )
        // Sign preserved, magnitude corrected from utterance
        assertEquals(-15, r!!.params["delta_seconds"])
    }

    @Test
    fun repair_correctsMagnitude_preservesPositiveSign() {
        // Case: "rewind fifteen seconds" — predicted 1 → repaired 15 (keep positive sign)
        val r = SlotRepair.repair(
            raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=1)]<|tool_call_end|>",
            utterance = "rewind fifteen seconds",
            tool = "playback",
            action = "seek_relative",
        )
        // Sign preserved (positive), magnitude corrected
        assertEquals(15, r!!.params["delta_seconds"])
    }

    @Test
    fun repair_fillsFromUtterance_whenDeltaZero() {
        // Case: "rewind fifteen seconds" — predicted 0 → repaired -15
        val r = SlotRepair.repair(
            raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=0)]<|tool_call_end|>",
            utterance = "rewind fifteen seconds",
            tool = "playback",
            action = "seek_relative",
        )
        // Zero has no sign — fill from utterance
        assertEquals(-15, r!!.params["delta_seconds"])
    }

    @Test
    fun repair_preservesDirection() {
        // Case: "go back" — predicted backward → repaired backward
        val r = SlotRepair.repair(
            raw = "<|tool_call_start|>[playback(action='seek_relative', direction='backward')]<|tool_call_end|>",
            utterance = "go back",
            tool = "playback",
            action = "seek_relative",
        )
        assertEquals("backward", r!!.params["direction"])
    }

    @Test
    fun repair_preservesDirection_withPositiveDelta() {
        // Case: "rewind fifteen seconds" — predicted delta=1, direction=backward → repaired delta=15, direction=backward
        val r = SlotRepair.repair(
            raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=1, direction='backward')]<|tool_call_end|>",
            utterance = "rewind fifteen seconds",
            tool = "playback",
            action = "seek_relative",
        )!!
        assertEquals(15, r.params["delta_seconds"])
        assertEquals("backward", r.params["direction"])
    }

    @Test
    fun repair_fillsDeltaFromDirection_whenAbsent() {
        // Case: "go back a minute" — predicted direction=backward → repaired direction=backward, delta=-60
        val r = SlotRepair.repair(
            raw = "<|tool_call_start|>[playback(action='seek_relative', direction='backward')]<|tool_call_end|>",
            utterance = "go back a minute",
            tool = "playback",
            action = "seek_relative",
        )!!
        assertEquals("backward", r.params["direction"])
        assertEquals(-60, r.params["delta_seconds"])
    }

    @Test
    fun repair_correctsMagnitude_preservesDirection() {
        // Case: "jump four minutes" — predicted delta=4 → repaired delta=240
        val r = SlotRepair.repair(
            raw = "<|tool_call_start|>[playback(action='seek_relative', delta_seconds=4)]<|tool_call_end|>",
            utterance = "jump four minutes",
            tool = "playback",
            action = "seek_relative",
        )
        assertEquals(240, r!!.params["delta_seconds"])
    }

    @Test
    fun repair_fillsDeltaFromDirectionDefault() {
        // Case: "back that up a minute" — predicted {} → repaired delta=-60
        val r = SlotRepair.repair(
            raw = "<|tool_call_start|>[playback(action='seek_relative')]<|tool_call_end|>",
            utterance = "back that up a minute",
            tool = "playback",
            action = "seek_relative",
        )
        assertEquals(-60, r!!.params["delta_seconds"])
    }
}
