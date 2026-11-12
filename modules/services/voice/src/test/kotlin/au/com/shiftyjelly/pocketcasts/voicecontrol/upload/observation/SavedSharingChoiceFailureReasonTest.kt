package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FAILURE DIAGNOSTICS ON EVERY RECORD PATH (Reviewer on `c2ce670cc7`): `lastFailureReason` must be
 * set by a failed [SavedSharingChoiceStore.recordWithdrawn] and
 * [SavedSharingChoiceStore.recordNotEnrolled] just as by a failed enroll, and CLEARED by a
 * successful write on any path. A latch that only records why the ENROLL failed cannot be
 * diagnosed when the withdrawal or the not-enrolled record is what failed.
 */
class SavedSharingChoiceFailureReasonTest {

    /** Records what the store asks it to; fails on demand with a reason. */
    private class ScriptedPersistence(
        private val failures: Set<SharingChoiceState>,
    ) : SharingChoicePersistence {
        var last: SavedSharingChoice? = null
        private var lastWriteFailed: Boolean = false
        private var lastFailedState: SharingChoiceState? = null
        override fun read(account: String): SavedSharingChoice? = last?.takeIf { it.account == account }
        override fun write(choice: SavedSharingChoice): Boolean {
            return if (choice.state in failures) {
                lastWriteFailed = true
                lastFailedState = choice.state
                false
            } else {
                lastWriteFailed = false
                lastFailedState = null
                last = choice
                true
            }
        }
        override fun lastFailureReason(): String? =
            if (lastWriteFailed) "injected failure for $lastFailedState" else null
    }

    private fun store(vararg failing: SharingChoiceState) = SavedSharingChoiceStore(ScriptedPersistence(failing.toSet()))

    @Test
    fun `a failed withdrawal records its reason`() {
        val subject = store(SharingChoiceState.WITHDRAWN)
        assertTrue("PREMISE: an enrolled record exists", subject.recordEnrolled("a@b.c", "epoch-1", "v1"))
        assertFalse(subject.recordWithdrawn("a@b.c"))
        assertTrue(
            "the failed withdrawal must record why: ${subject.lastFailureReason}",
            subject.lastFailureReason?.contains("WITHDRAWN") == true,
        )
    }

    @Test
    fun `a failed not-enrolled record records its reason`() {
        val subject = store(SharingChoiceState.NOT_ENROLLED)
        assertFalse(subject.recordNotEnrolled("a@b.c"))
        assertTrue(
            "the failed not-enrolled write must record why: ${subject.lastFailureReason}",
            subject.lastFailureReason?.contains("NOT_ENROLLED") == true,
        )
    }

    @Test
    fun `a later successful write on any path clears the reason`() {
        val subject = store(SharingChoiceState.WITHDRAWN)
        subject.recordEnrolled("a@b.c", "epoch-1", "v1")
        assertFalse(subject.recordWithdrawn("a@b.c"))
        assertTrue(subject.lastFailureReason != null)
        // The SAME failing set now passes because the scripted set is per-state: use a fresh write
        // of NOT_ENROLLED, which is not in the failing set, to clear.
        assertTrue(subject.recordNotEnrolled("a@b.c"))
        assertNull("a successful write must clear the reason", subject.lastFailureReason)
    }

    @Test
    fun `a blank account refuses and records why`() {
        val subject = store()
        assertFalse(subject.recordWithdrawn("  "))
        assertEquals("blank account", subject.lastFailureReason)
    }
}
