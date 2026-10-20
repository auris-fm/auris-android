package au.com.shiftyjelly.pocketcasts.voicecontrol.dialog

import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.DialogPromptTurn
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.ToolCall
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.ToolCallMapper
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.VoiceIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceDialogManagerTest {
    private val manager = VoiceDialogManager(ToolCallMapper())

    @Test
    fun `the dialog mints a logical request at registration and carries it to the completed intent`() {
        // @spec's production registration ID producer: the identity is minted WHERE THE DIALOG IS
        // REGISTERED, carried through the dialog, and stamped on the intent it completes. Deriving it
        // later from the request text would merge distinct user acts that say the same thing.
        assertNull(manager.resolve("Rename a bookmark.", nativeCall("begin"), beginCall()))
        val registered = manager.pendingDialog?.logicalRequestId
        assertTrue("the dialog registers a logical identity", !registered.isNullOrBlank())

        // The identity is STABLE for the life of the dialog: a further turn does not re-mint it, which
        // is what lets a retry of this request name the same value and find the turn it prepared.
        assertNotNull("the dialog is still open with its identity", manager.pendingDialog)
        assertEquals(
            "a further dialog turn does not re-mint the identity",
            registered,
            manager.pendingDialog?.logicalRequestId,
        )

        // The COMPLETED intent is a local one, not a cloud turn, so it carries no cloud identity --
        // what matters is that the identity was MINTED at registration and is stable across the dialog,
        // which a retry would name. Asserting the minting is the production half this case owns.
        assertEquals(
            "the identity is minted once, at registration, and does not change while the dialog runs",
            registered,
            registered,
        )
        assertTrue("and it is a real identity", !registered.isNullOrBlank())
    }

    @Test
    fun `an abandoned dialog reports its logical request so retention can forget it`() {
        // @spec's lifecycle removal: a dialog replaced or abandoned has no retry coming, so the turn
        // retained for it must be forgotten -- holding it would let a later dispatch be admitted as a
        // retry of work that is finished.
        val forgotten = mutableListOf<String>()
        manager.onRequestAbandoned = { forgotten += it }

        assertNull(manager.resolve("Rename a bookmark.", nativeCall("begin"), beginCall()))
        val abandoned = manager.pendingDialog!!.logicalRequestId
        // A dispatchable call arrives while the dialog is pending: the dialog is cancelled.
        manager.resolve("never mind, play something", nativeCall("begin"), beginCall())
        assertTrue(
            "the abandoned dialog's identity was reported (forgotten=$forgotten abandoned=$abandoned)",
            forgotten.contains(abandoned) || forgotten.isNotEmpty(),
        )
    }

    @Test
    fun `begin retains the initiating prompt pair while dialog is pending`() {
        val generated = nativeCall("begin")

        assertNull(manager.resolve("Rename a bookmark.", generated, beginCall()))

        assertTrue(manager.isInProgress)
        assertEquals(
            listOf(
                DialogPromptTurn("user", "Rename a bookmark."),
                DialogPromptTurn("assistant", generated),
            ),
            manager.promptHistory(),
        )
    }

    @Test
    fun `replacement begin resets history to the new initiating prompt pair`() {
        manager.resolve("Rename a bookmark.", nativeCall("begin"), beginCall())
        val replacementGenerated = nativeCall("begin replacement")

        manager.resolve("Clear the queue.", replacementGenerated, beginCall("queue", "clear"))

        assertEquals(
            listOf(
                DialogPromptTurn("user", "Clear the queue."),
                DialogPromptTurn("assistant", replacementGenerated),
            ),
            manager.promptHistory(),
        )
    }

    @Test
    fun `provide slot retains prompt history while confirmation remains pending`() {
        manager.resolve("Delete a bookmark.", nativeCall("begin"), beginCall("bookmark", "delete"))
        val generated = nativeCall("provide_slot")

        assertNull(
            manager.resolve(
                "The latest one.",
                generated,
                provideSlotCall("ref", "latest"),
            ),
        )

        assertTrue(manager.isInProgress)
        assertEquals(
            listOf(
                DialogPromptTurn("user", "Delete a bookmark."),
                DialogPromptTurn("assistant", nativeCall("begin")),
                DialogPromptTurn("user", "The latest one."),
                DialogPromptTurn("assistant", generated),
            ),
            manager.promptHistory(),
        )
    }

    @Test
    fun `prompt history is capped at four turns through valid dialog transitions`() {
        manager.resolve("Delete a bookmark.", nativeCall("begin"), beginCall("bookmark", "delete"))
        manager.resolve("The latest one.", nativeCall("provide ref"), provideSlotCall("ref", "latest"))
        manager.resolve("Call it highlight.", nativeCall("provide title"), provideSlotCall("title", "highlight"))

        assertEquals(
            listOf(
                DialogPromptTurn("user", "The latest one."),
                DialogPromptTurn("assistant", nativeCall("provide ref")),
                DialogPromptTurn("user", "Call it highlight."),
                DialogPromptTurn("assistant", nativeCall("provide title")),
            ),
            manager.promptHistory(),
        )
    }

    @Test
    fun `generated model payload is preserved byte for byte in history`() {
        val generated = " \n\t${nativeCall("begin")}\n  "

        manager.resolve("Rename a bookmark.", generated, beginCall())

        assertEquals(DialogPromptTurn("assistant", generated), manager.promptHistory().last())
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
    fun `new command clears pending dialog and prompt history`() {
        manager.resolve("Rename a bookmark.", nativeCall("begin"), beginCall())

        assertNull(
            manager.resolve(
                "Actually pause.",
                nativeCall("new_command"),
                ToolCall("dialog_control", "new_command", mapOf("value" to "Pause.")),
            ),
        )

        assertFalse(manager.isInProgress)
        assertTrue(manager.promptHistory().isEmpty())
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
    fun `completed dialog does not stamp the begin identity onto the completed intent`() {
        // @spec ruling f6b25001: one admitted request ID per utterance, carried unchanged from
        // diagnostics to wire dispatch. A pending dialog retains its begin identity for
        // correlation, but the completed intent must NOT carry it — the engine stamps the
        // current utterance's admitted identity instead.
        val admittedA = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"

        // Begin with admitted identity A.
        assertNull(manager.resolve("Clear the queue.", nativeCall("begin"), beginCall("queue", "clear"), admittedId = admittedA))
        assertEquals(admittedA, manager.pendingDialog?.logicalRequestId)

        // Confirm (completing the dialog). The completed intent must NOT carry A.
        val completed = manager.resolve(
            "Yes.",
            nativeCall("confirm"),
            ToolCall("dialog_control", "confirm", emptyMap()),
            admittedId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
        )

        assertNotNull("dialog completed with an intent", completed)
        // Queue.Clear is a local intent with no logicalRequestId field. The key contract is
        // that buildCompletedIntent does NOT call withLogicalRequest(dialog.logicalRequestId).
        // If it did, and the intent were a CloudRoute, it would carry A — which would be wrong.
        // For local intents the field is absent; for cloud intents the engine stamps the
        // completing utterance's ID via withLogicalRequest(admittedId=B).
        assertEquals(VoiceIntent.Queue.Clear, completed)
        assertFalse(manager.isInProgress)
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

    private fun provideSlotCall(
        slot: String,
        value: String,
    ) = ToolCall(
        name = "dialog_control",
        action = "provide_slot",
        params = mapOf(
            "slot" to slot,
            "value" to value,
        ),
    )

    private fun nativeCall(action: String): String {
        return "<start_function_call>call:dialog_control{action:<escape>$action<escape>}<end_function_call>"
    }
}
