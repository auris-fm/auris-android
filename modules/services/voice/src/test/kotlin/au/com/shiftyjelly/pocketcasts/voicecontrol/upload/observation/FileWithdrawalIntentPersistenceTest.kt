package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
@Config(sdk = [35])
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
    fun `updating the marker never leaves the authoritative file missing or partial`() {
        // THE WINDOW THE REVIEWER NAMED, and the reason this control cannot be written by inspecting
        // the file after write() returns: by then every strategy has settled on a good final state.
        // delete-then-rename produces a good final state too - the harm is DURING the update, when an
        // absent file reads as PERMITTED. So the check has to run INSIDE the update, from the reader's
        // point of view. A watcher polls the path while writes are in flight.
        val subject = persistence()
        subject.write(requested = true)
        assertTrue("precondition: the stop must be recorded", subject.read())

        val observedAbsent = java.util.concurrent.atomic.AtomicBoolean(false)
        val observedPartial = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val watcher = Thread {
            while (running.get()) {
                val text = if (file.isFile) file.readText() else null
                when {
                    text == null -> observedAbsent.set(true)
                    text !in setOf("withdrawal_requested", "collection_permitted") -> observedPartial.set(text)
                }
            }
        }
        watcher.start()
        try {
            repeat(400) { step -> subject.write(requested = step % 2 == 0) }
        } finally {
            running.set(false)
            watcher.join()
        }

        assertFalse(
            "a concurrent reader observed the marker file ABSENT during an update. An absent file " +
                "reads as PERMITTED, so this window resumes collection for a user who asked to stop",
            observedAbsent.get(),
        )
        assertNull(
            "a concurrent reader observed a PARTIAL marker during an update: ${observedPartial.get()?.length} bytes",
            observedPartial.get(),
        )
        assertFalse(
            "the temp file must not survive a successful write",
            File(context.filesDir, "voice-sharing-local-stop.tmp").exists(),
        )
    }

    @Test
    fun `a stop is never lost because a previous clear marker was in place`() {
        // Turns the same property into the user-facing consequence: a running sequence of toggles
        // ending in STOP must leave a stop, whatever the filesystem did in between.
        val subject = persistence()
        subject.write(requested = false)
        subject.write(requested = true)
        assertTrue(
            "after clearing then stopping, collection must be refused",
            subject.read(),
        )
    }

    @Test
    fun `a stop whose atomic write fails does NOT leave the previous permitted marker behind`() {
        // Reviewer 60647af3. The rename used to fail silently and the caller believed the stop landed.
        // The dangerous sequence: a permitted marker is on disk, the user stops, the rename fails, and
        // reading back still says PERMITTED - so collection resumes on the next launch.
        val subject = persistence()
        subject.write(requested = false)
        assertFalse("precondition: the marker starts as PERMITTED", subject.read())

        // Force the atomic path to fail by making the temp path unusable: a DIRECTORY at the temp
        // location cannot be opened for writing, and cannot be renamed over the target.
        val temp = File(context.filesDir, "voice-sharing-local-stop.tmp")
        temp.delete()
        assertTrue("precondition: the blocking directory must be creatable", temp.mkdirs())
        try {
            val reported = subject.write(requested = true)

            assertTrue(
                "the injected failure must actually reach the non-atomic path, otherwise this " +
                    "control proves nothing about it",
                reported.not(),
            )
            assertFalse(
                "write(true) must report failure when it could not install the marker durably",
                reported,
            )
            assertTrue(
                "after a failed stop write the marker must NOT still read as PERMITTED - an " +
                    "unchanged permitted marker resumes collection for a user who asked to stop",
                subject.read(),
            )
        } finally {
            temp.delete()
        }
    }

    @Test
    fun `a write that cannot complete atomically still reports that it did not`() {
        // The reviewer's defect was that a failed atomic path reported SUCCESS, so the caller believed
        // a stop had landed. The directory-at-temp injection above exercises one failure mode; this
        // covers the contract itself, at the layer callers consume: when the atomic path does not
        // complete, write() must say so.
        //
        // COVERAGE, stated precisely because it is partial: this injects an EXCEPTION from the temp
        // path (a directory cannot be opened as a file stream), and mutation-proof confirms all three
        // failure-path controls go red when that exception is swallowed as success. A REFUSED RENAME
        // is NOT reachable by this injection under Robolectric - renameTo consumes the directory onto
        // the target instead of returning false - so that distinct branch is covered only by the
        // shared return contract, not by a control that reaches it. Recorded rather than claimed.
        val subject = persistence()
        subject.write(requested = false)
        val temp = File(context.filesDir, "voice-sharing-local-stop.tmp")
        temp.delete()
        assertTrue(temp.mkdirs())
        try {
            assertFalse(
                "an unwritable temp path must be reported as a failed write",
                subject.write(requested = true),
            )
        } finally {
            temp.delete()
        }
    }

    @Test
    fun `a failed clear reports failure rather than claiming the stop was released`() {
        val subject = persistence()
        subject.write(requested = true)
        val temp = File(context.filesDir, "voice-sharing-local-stop.tmp")
        temp.delete()
        assertTrue(temp.mkdirs())
        try {
            assertFalse(
                "a clear that could not be installed must report failure",
                subject.write(requested = false),
            )
            assertTrue(
                "a failed clear must leave the user STOPPED - the safe direction",
                subject.read(),
            )
        } finally {
            temp.delete()
        }
    }

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
