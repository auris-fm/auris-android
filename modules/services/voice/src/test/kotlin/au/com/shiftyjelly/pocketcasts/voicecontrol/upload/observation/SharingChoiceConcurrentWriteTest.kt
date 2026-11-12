package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * OVERLAPPING WRITES (owner's Pixel test, nine `choice persist: outcome=failed latched` lines during
 * fast toggle taps). `FileSharingChoicePersistence` writes through ONE fixed temp path per account
 * (`voice-sharing-choice-<sha>.tmp`) and then renames it over the record. Two writers sharing that
 * temp path interleave: writer A opens the temp, writer B TRUNCATES it, A's rename publishes a
 * half-written record (or fails with ENOENT because B already renamed the temp away). Either way the
 * caller truthfully reports a failed persist, and the previously good record is what survives - which
 * is exactly the observed "file lags the server's epochs" symptom.
 *
 * These controls fail on the current writer and must pass once each write owns its own temp path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharingChoiceConcurrentWriteTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "voice-sharing-choice-" + sha("owner@example.test")).delete()
        File(context.filesDir, "voice-sharing-choice-" + sha("owner@example.test") + ".tmp").delete()
        // Unique per-write temps carry `.tmp.<uuid>`: sweep ALL leftovers so a previous round's
        // crash cannot fail the no-leftovers control.
        context.filesDir.listFiles().orEmpty()
            .filter { it.name.startsWith("voice-sharing-choice-") && ".tmp." in it.name }
            .forEach { it.delete() }
    }

    private fun sha(text: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun choice(epoch: String) = SavedSharingChoice(
        account = "owner@example.test",
        state = SharingChoiceState.ENROLLED,
        epoch = epoch,
        acceptedDisclosureVersion = "voice-sharing-v1",
    )

    private fun noopDirectorySync() = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.fs.DirectorySync { }

    /**
     * PREMISE (holds today): one writer alone never fails, so a failure below is caused by the other
     * writer and not by the environment.
     */
    @Test
    fun `a single write still succeeds`() {
        val persistence = FileSharingChoicePersistence(context, noopDirectorySync())
        assertTrue("PREMISE: an uncontended write publishes", persistence.write(choice("epoch-alone")))
        assertEquals("epoch-alone", persistence.read("owner@example.test")?.epoch)
    }

    /**
     * Two writers race the same account. Neither may fail, and the record must be one of the two
     * epochs - never a truncated/partial one, never absent.
     */
    @Test
    fun `two concurrent writes both succeed and the record stays whole`() {
        val persistence = FileSharingChoicePersistence(context, noopDirectorySync())
        repeat(ROUNDS) { round ->
            val failures = AtomicInteger(0)
            val start = CountDownLatch(1)
            val workers = listOf("epoch-a-$round", "epoch-b-$round").map { epoch ->
                Thread {
                    start.await()
                    if (!persistence.write(choice(epoch))) failures.incrementAndGet()
                }.also { it.start() }
            }
            start.countDown()
            workers.forEach { it.join(TimeUnit.SECONDS.toMillis(30)) }

            assertEquals("round $round: no write may fail when the record can hold either value", 0, failures.get())
            val stored = persistence.read("owner@example.test")
            assertTrue(
                "round $round: the record must be one of the written epochs, got $stored",
                stored?.epoch == "epoch-a-$round" || stored?.epoch == "epoch-b-$round",
            )
        }
    }

    /** A surviving default temp path shared by both writers is the defect; after the fix none remains. */
    @Test
    fun `no temp file survives a completed write`() {
        val persistence = FileSharingChoicePersistence(context, noopDirectorySync())
        assertTrue(persistence.write(choice("epoch-clean")))
        val leftovers = context.filesDir.listFiles().orEmpty().filter { it.name.endsWith(".tmp") }
        assertTrue("temp files must not survive: ${leftovers.map { it.name }}", leftovers.isEmpty())
    }

    private companion object {
        const val ROUNDS = 40
    }
}
