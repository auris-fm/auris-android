package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.ui

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingMutationResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentController
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentDisclosure
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoice
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoiceStore
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.LocalWithdrawalIntentStore
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SharingChoiceState
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.WithdrawalIntentPersistence
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SharingChoicePersistence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * THE SWITCH READS EFFECTIVE CONSENT (Reviewer on `c2ce670cc7`): the Settings surface must use the
 * SAME read admission uses — [SavedSharingChoiceStore.enrolledChoiceFor], which refuses when the
 * account's persist FAILED — not the raw file read. A failed write can leave a stale ENROLLED file
 * on disk while the user's latest instruction did not land; admission refuses capture in that
 * state, so the switch must read off.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsSwitchEffectiveConsentTest {
    private var mainDispatcher = StandardTestDispatcher()

    @get:Rule
    val mainRule = object : TestWatcher() {
        override fun starting(description: Description) {
            mainDispatcher = StandardTestDispatcher()
            Dispatchers.setMain(mainDispatcher)
        }
        override fun finished(description: Description) {
            Dispatchers.resetMain()
        }
    }

    /** Holds a good ENROLLED record once, then refuses every further write. */
    private class FailsAfterFirstWrite : SharingChoicePersistence {
        private var writes = 0
        private var last: SavedSharingChoice? = null
        override fun read(account: String): SavedSharingChoice? = last?.takeIf { it.account == account }
        override fun write(choice: SavedSharingChoice): Boolean {
            writes += 1
            if (writes > 1) return false
            last = choice
            return true
        }
        override fun lastFailureReason(): String? = if (writes > 1) "injected persistence failure" else null
    }

    @Test
    fun `a failed persist leaves the switch off even though the file still holds ENROLLED`() = runTest {
        val store = SavedSharingChoiceStore(FailsAfterFirstWrite())
        assertTrue("PREMISE: the enroll lands", store.recordEnrolled("user@example.com", "epoch-1", EnrollmentDisclosure.VERSION))
        // The user's latest instruction (withdraw) FAILS to persist: the file still holds ENROLLED…
        assertFalse("PREMISE: the withdrawal write fails", store.recordWithdrawn("user@example.com"))
        // …and the effective-consent read refuses it, exactly as admission does.
        assertNull(store.enrolledChoiceFor("user@example.com"))

        // The provider below IS the production wiring's expression (VoiceSharingSettingsModule):
        // the switch must compose from enrolledChoiceFor, not read().
        val durableChoice: suspend () -> SavedSharingChoice? =
            VoiceSharingSettingsModule.effectiveConsentProvider(store) { "user@example.com" }
        val vm = VoiceSharingSettingsViewModel(
            projection = kotlinx.coroutines.flow.MutableStateFlow(null),
            enrollmentController = EnrollmentController(
                enrollOperation = { _, _ -> VoiceSharingMutationResult.Failure },
                withdrawOperation = { _ -> VoiceSharingMutationResult.Failure },
                deleteOperation = { false },
                clearLocalPending = {},
                ownerSession = { 1L },
            ),
            withdrawalIntent = LocalWithdrawalIntentStore(object : WithdrawalIntentPersistence {
                override fun read(): Boolean = false
                override fun write(requested: Boolean): Boolean = true
            }),
            ownerSession = { 1L },
            markerRead = { true },
            savedChoice = durableChoice,
        )
        mainDispatcher.scheduler.advanceUntilIdle()
        assertFalse(
            "admission refuses capture on a failed persist, so the switch must read off",
            vm.state.value.enrolled,
        )
    }
}

private fun assertNull(any: SavedSharingChoice?) = org.junit.Assert.assertNull(any)
