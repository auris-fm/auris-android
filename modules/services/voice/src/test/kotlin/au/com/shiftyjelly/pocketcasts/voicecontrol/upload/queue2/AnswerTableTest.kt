package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue2

import org.junit.Assert.assertEquals
import org.junit.Test

class AnswerTableTest {
    @Test
    fun `an accepted recording is deleted`() {
        assertEquals(Answer.Accepted, AnswerTable.classify(201))
        assertEquals(Answer.Accepted, AnswerTable.classify(200))
    }

    @Test
    fun `401 refreshes sign-in rather than deleting`() {
        // 401 is a 4xx. If the 4xx rule were checked first this would delete the user's audio
        // every time their token expired.
        assertEquals(Answer.RefreshSignIn, AnswerTable.classify(401))
    }

    @Test
    fun `every other 4xx is a final refusal`() {
        for (status in listOf(400, 403, 404, 409, 410, 413, 415, 422, 429, 451, 499)) {
            assertEquals("status $status", Answer.Delete, AnswerTable.classify(status))
        }
    }

    @Test
    fun `5xx and anything else retries`() {
        for (status in listOf(500, 502, 503, 504, 301, 100)) {
            assertEquals("status $status", Answer.Retry, AnswerTable.classify(status))
        }
    }

    @Test
    fun `no answer at all retries`() {
        // A thrown request has no status. It says nothing about the recording.
        assertEquals(Answer.Retry, AnswerTable.classify(null))
    }
}
