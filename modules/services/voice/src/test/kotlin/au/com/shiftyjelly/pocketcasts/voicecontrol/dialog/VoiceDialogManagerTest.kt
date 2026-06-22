package au.com.shiftyjelly.pocketcasts.voicecontrol.dialog

import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.DialogPromptTurn
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.ToolCall
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.ToolCallMapper
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceDialogManagerTest {
    private val manager = VoiceDialogManager(ToolCallMapper())

    @Test
    fun `begin retains the initiating prompt pair while dialog is pending`() {
        val generated = nativeCall("begin")

        assertNull(manager.resolve("Rename a bookmark.", generated, beginCall()))

        assertTrue(manager.isInProgress)
        assertEquals(
            listOf(
                DialogPromptTurn("user", "Rename a bookmark."),
                DialogPromptTurn("model", generated),
            ),
            manager.promptHistory(),
        )
    }

    @Test
    fun `prompt history is capped at four turns and preserves generated text`() {
        manager.resolve("first", nativeCall("begin"), beginCall())

        repeat(3) { index ->
            val generated = nativeCall("unknown_$index")
            manager.resolve("user_$index", generated, ToolCall("dialog_control", "unknown_$index", emptyMap()))
        }

        assertEquals(
            listOf(
                DialogPromptTurn("user", "user_1"),
                DialogPromptTurn("model", nativeCall("unknown_1")),
                DialogPromptTurn("user", "user_2"),
                DialogPromptTurn("model", nativeCall("unknown_2")),
            ),
            manager.promptHistory(),
        )
    }

    @Test
    fun `completion clears pending dialog and prompt history`() {
        manager.resolve("Clear the queue.", nativeCall("begin"), beginCall("queue", "clear"))

        val result = manager.resolve("Yes.", nativeCall("confirm"), ToolCall("dialog_control", "confirm", emptyMap()))

        assertEquals(VoiceIntent.Queue.Clear, result)
        assertFalse(manager.isInProgress)
        assertTrue(manager.promptHistory().isEmpty())
    }

    @Test
    fun `cancel and deny clear pending dialog and prompt history`() {
        listOf("cancel", "deny").forEach { action ->
            manager.resolve("Rename a bookmark.", nativeCall("begin"), beginCall())

            assertNull(manager.resolve(action, nativeCall(action), ToolCall("dialog_control", action, emptyMap())))
            assertFalse(manager.isInProgress)
            assertTrue(manager.promptHistory().isEmpty())
        }
    }

    @Test
    fun `new dispatch clears pending dialog and maps the new intent`() {
        manager.resolve("Rename a bookmark.", nativeCall("begin"), beginCall())

        val result = manager.resolve(
            "Pause.",
            nativeCall("pause"),
            ToolCall("playback", "pause", emptyMap()),
        )

        assertEquals(VoiceIntent.Playback.Pause, result)
        assertFalse(manager.isInProgress)
        assertTrue(manager.promptHistory().isEmpty())
    }

    @Test
    fun `existing resolve completes destructive dialog`() {
        assertNull(manager.resolve(beginCall("queue", "clear")))

        val result = manager.resolve(ToolCall("dialog_control", "confirm", emptyMap()))

        assertEquals(VoiceIntent.Queue.Clear, result)
        assertFalse(manager.isInProgress)
    }

    private fun beginCall(
        targetTool: String = "bookmark",
        targetAction: String = "rename",
    ) = ToolCall(
        name = "dialog_control",
        action = "begin",
        params = mapOf(
            "target_tool" to targetTool,
            "target_action" to targetAction,
        ),
    )

    private fun nativeCall(action: String): String {
        return "<start_function_call>call:dialog_control{action:<escape>$action<escape>}<end_function_call>"
    }
}
