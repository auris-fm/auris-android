package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The on-disk stop flag must fail CLOSED (spec 19:10Z checklist).
 *
 * The asymmetric cost drives every case here: reading a corrupt file as "no stop" resumes
 * contribution for someone who asked us to stop, while reading it as "stop" only costs a user one
 * extra opt-in. So every unreadable, truncated or unrecognised file must read as STOPPED.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileWithdrawalIntentPersistenceTest {

    private lateinit var context: Context
    private lateinit var file: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        file = File(context.filesDir, "voice-sharing-local-stop")
        file.delete()
        File(context.filesDir, "voice-sharing-local-stop.tmp").delete()
    }

    private fun persistence() = FileWithdrawalIntentPersistence(context)

    @Test
    fun `a missing file means no stop was ever requested`() {
        assertFalse(
            "a fresh install must not be treated as already stopped",
            persistence().read(),
        )
    }

    @Test
    fun `a written stop survives and reads back as stopped`() {
        persistence().write(true)
        assertTrue("a recorded stop must read back as stopped", persistence().read())
    }

    @Test
    fun `an explicit clear reads back as permitted`() {
        persistence().write(true)
        persistence().write(false)
        assertFalse("an explicit opt-in must clear the stop", persistence().read())
    }

    @Test
    fun `a truncated file fails closed`() {
        // What a power loss mid-write could leave behind: a prefix of the marker.
        file.writeText("withdrawal_req")
        assertTrue(
            "a truncated marker must read as STOPPED: assuming 'permitted' would resume collection " +
                "for someone who asked to stop",
            persistence().read(),
        )
    }

    @Test
    fun `a garbage file fails closed`() {
        file.writeText("\u0000\u0000binary noise\u0000")
        assertTrue(
            "an unrecognised payload must read as STOPPED, never as permitted",
            persistence().read(),
        )
    }

    @Test
    fun `an empty file fails closed`() {
        file.writeText("")
        assertTrue(
            "an empty file is not a written permission; it must read as STOPPED",
            persistence().read(),
        )
    }

    @Test
    fun `a stop write leaves no temp file behind to be misread later`() {
        persistence().write(true)
        assertFalse(
            "the temp file must not survive a successful write",
            File(context.filesDir, "voice-sharing-local-stop.tmp").exists(),
        )
    }
}
