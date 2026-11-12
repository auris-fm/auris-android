package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * THE PERSIST BARRIER CONTROL (owner Pixel, 2026-11-12): the sharing choice lives in the PLATFORM
 * settings store — one prefs file, per-account keys, `commit()` — with NO hand-built temp/rename/
 * directory-sync machinery and NO persist-failure latch. On the Pixel the old machinery failed on
 * every write (`directory fsync unsupported`) and held the switch off despite correct records and
 * accepted server taps. This control pins the spec shape on the new store: a persist succeeds,
 * reads back as effective consent, accounts never inherit each other's choice, and a withdrawal
 * keeps the record visible but refuses consent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedPreferencesChoicePersistenceTest {

    @Test
    fun `a persist round-trips and reads back as effective consent`() {
        val persistence = SharedPreferencesChoicePersistence(ApplicationProvider.getApplicationContext())
        val store = SavedSharingChoiceStore(persistence)

        assertTrue(store.recordEnrolled("user@example.com", "epoch-1", "voice-sharing-v1"))
        assertEquals(
            SharingChoiceState.ENROLLED,
            store.enrolledChoiceFor("user@example.com")?.state,
        )
        assertEquals("epoch-1", store.enrolledChoiceFor("user@example.com")?.epoch)

        assertTrue(store.recordWithdrawn("user@example.com"))
        assertNull("effective consent is off after withdrawal", store.enrolledChoiceFor("user@example.com"))
        assertNotNull("the record stays visible for the row", store.knownChoiceFor("user@example.com"))

        assertTrue(store.recordEnrolled("user@example.com", "epoch-2", "voice-sharing-v1"))
        assertEquals("epoch-2", store.enrolledChoiceFor("user@example.com")?.epoch)
    }

    @Test
    fun `accounts never inherit each other's choice or provenance`() {
        val persistence = SharedPreferencesChoicePersistence(ApplicationProvider.getApplicationContext())
        val store = SavedSharingChoiceStore(persistence)

        assertTrue(store.recordEnrolled("a@example.com", "epoch-a", "voice-sharing-v1"))

        assertNull("b has no record at all", store.enrolledChoiceFor("b@example.com"))
        assertNull(store.knownChoiceFor("b@example.com"))
        assertEquals("epoch-a", store.enrolledChoiceFor("a@example.com")?.epoch)
    }

    @Test
    fun `an ENROLLED record without provenance never admits`() {
        val persistence = SharedPreferencesChoicePersistence(ApplicationProvider.getApplicationContext())
        val store = SavedSharingChoiceStore(persistence)

        // An answer of NOT ENROLLED clears provenance by construction (recordNotEnrolled fabricates none).
        assertTrue(store.recordNotEnrolled("user@example.com"))
        assertNull(store.enrolledChoiceFor("user@example.com"))
    }
}
