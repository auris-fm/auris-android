package au.com.shiftyjelly.pocketcasts.voicecontrol.intent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FunctionGemmaPromptTest {
    @Test
    fun `static prefix contains declarations but no user turn`() {
        assertTrue(FunctionGemmaPrompt.staticPrefix.contains("<start_function_declaration>"))
        assertTrue(FunctionGemmaPrompt.staticPrefix.endsWith("<end_of_turn>"))
        assertFalse(FunctionGemmaPrompt.staticPrefix.contains("<start_of_turn>user"))
    }

    @Test
    fun `request suffix preserves the trained single-turn template`() {
        assertEquals(
            "\n<start_of_turn>user\nPause.<end_of_turn>\n<start_of_turn>model\n",
            FunctionGemmaPrompt.requestSuffix("Pause.", emptyList()),
        )
    }

    @Test
    fun `request suffix places history in order before the current user turn`() {
        val history = listOf(
            DialogPromptTurn("user", "Rename the bookmark."),
            DialogPromptTurn(
                "model",
                "<start_function_call>call:dialog_control{action:<escape>begin<escape>," +
                    "target_tool:<escape>bookmark<escape>,target_action:<escape>rename<escape>}" +
                    "<end_function_call>",
            ),
        )

        assertEquals(
            "\n<start_of_turn>user\nRename the bookmark.<end_of_turn>" +
                "\n<start_of_turn>model\n${history[1].content}<end_of_turn>" +
                "\n<start_of_turn>user\nThe second one.<end_of_turn>" +
                "\n<start_of_turn>model\n",
            FunctionGemmaPrompt.requestSuffix("The second one.", history),
        )
    }
}
