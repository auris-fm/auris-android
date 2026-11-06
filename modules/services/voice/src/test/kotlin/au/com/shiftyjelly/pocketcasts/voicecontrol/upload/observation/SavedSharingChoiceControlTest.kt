package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue.VoiceUploadCompositionFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * THE THREE BEHAVIOURS SPEC 1af5e705 REQUIRES OF THE SAVED CHOICE, each end to end through the
 * authority:
 *
 * 1. PERSISTED RESTART: a saved opt-in authorizes admission in a NEW process (fresh store over the
 *    same durable record) with no projection fetched in this process.
 * 2. EXPLICIT OFF: a recorded withdrawal refuses IMMEDIATELY and survives restart; the retired
 *    epoch's provenance is retained, never erased by the client.
 * 3. ACCOUNT REPLACEMENT: another account NEVER inherits the saved choice or epoch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SavedSharingChoiceControlTest {

    /** A store over the REAL file format, pointed at [dir] via a filesDir-overriding context. */
    private fun tmpDir(name: String): java.io.File = java.nio.file.Files.createTempDirectory(name).toFile()

    private fun fileStore(dir: java.io.File) = SavedSharingChoiceStore(
        FileSharingChoicePersistence(
            object : android.content.ContextWrapper(
                androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>(),
            ) {
                override fun getFilesDir(): java.io.File = dir
            },
            directorySync = { },
        ),
    )

    private fun authority(
        account: String,
        choice: SavedSharingChoiceStore,
        session: Long = 7L,
    ): CollectionAuthority {
        val startup = VoiceUploadCompositionFixtures.startup(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
        )
        val owner = startup.ownerForHandoff() as VoiceUploadCompositionFixtures.AppContextOwner
        owner.publish(account = account, session = session, bearer = "Bearer token")
        return CollectionAuthority(
            accountStartup = startup,
            collectionTx = CollectionAuthorityTransaction(
                ownership = startup.ownerForHandoff(),
                sharedLock = startup.sharedCollectionLock(),
            ),
            localWithdrawalIntent = LocalWithdrawalIntentStore(InMemoryIntentPersistence()),
            savedSharingChoice = choice,
        )
    }

    @Test
    fun `a saved opt-in admits in a fresh store over the same durable record - no in-process projection`() {
        val dir = tmpDir("choice-restart")
        fileStore(dir).recordEnrolled("a@x", "epoch-1", "1")

        // A NEW process: brand-new store over the same directory.
        val decision = authority("a@x", fileStore(dir)).decide()

        assertTrue(
            "the saved opt-in must authorize admission after restart without a projection refresh",
            decision.collectionPermitted,
        )
        assertNull("permitted admission carries no refusal reason", decision.refusalReason)
    }

    @Test
    fun `an explicit off refuses immediately and survives restart, retaining the retired epoch`() {
        val dir = tmpDir("choice-off")
        val store = fileStore(dir)
        assertTrue(store.recordEnrolled("a@x", "epoch-1", "1"))
        assertTrue(store.recordWithdrawn("a@x"))

        assertFalse(
            "the stop refuses in the SAME process, before any server round trip",
            authority("a@x", store).decide().collectionPermitted,
        )
        // Restart: a fresh store over the same record keeps refusing.
        val restarted = fileStore(dir)
        val decision = authority("a@x", restarted).decide()
        assertFalse(
            "the stop must survive restart",
            decision.collectionPermitted,
        )
        assertEquals(
            "the refusal names the missing choice, not the local stop (no stop marker here)",
            AdmissionRefusalReason.NO_SAVED_CHOICE,
            decision.refusalReason,
        )
        // Provenance is RETAINED: the withdrawn record keeps the real epoch the server issued.
        val stored = restarted.read()!!
        assertEquals(SharingChoiceState.WITHDRAWN, stored.state)
        assertEquals("epoch-1", stored.epoch)
    }

    @Test
    fun `another account never inherits the saved choice or epoch`() {
        val dir = tmpDir("choice-account")
        fileStore(dir).recordEnrolled("a@x", "epoch-1", "1")

        val decision = authority("b@x", fileStore(dir)).decide()

        assertFalse(
            "account b must not be admitted under account a's saved opt-in",
            decision.collectionPermitted,
        )
        assertEquals(
            AdmissionRefusalReason.NO_SAVED_CHOICE,
            decision.refusalReason,
        )
        // And b enrolling writes b's own record; a's restart afterwards refuses under the replaced
        // record, never under b's consent.
        val replaced = fileStore(dir).apply { recordEnrolled("b@x", "epoch-2", "2") }
        assertFalse(
            "a restarted session for account a must not run under b's enrollment",
            authority("a@x", replaced).decide().collectionPermitted,
        )
    }

    @Test
    fun `a corrupt or failed write fails closed and names the missing choice`() {
        val dir = tmpDir("choice-corrupt")
        java.io.File(dir, "voice-sharing-choice").writeText("garbage not a record")

        val decision = authority("a@x", fileStore(dir)).decide()
        assertFalse("an unreadable record must never read as consent", decision.collectionPermitted)
        assertEquals(AdmissionRefusalReason.NO_SAVED_CHOICE, decision.refusalReason)

        // A FAILED write is reported and leaves admission refused.
        val failing = SavedSharingChoiceStore(InMemoryChoicePersistence().apply { failWrites = true })
        assertFalse("a write that cannot be made durable must report failure", failing.recordEnrolled("a@x", "epoch-1", "1"))
        assertFalse(
            "a failed persist must not admit on a choice that is not on disk",
            authority("a@x", failing).decide().collectionPermitted,
        )
    }
}
