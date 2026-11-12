package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.ui

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingMutationResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentController
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentDisclosure
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.InMemoryChoicePersistence
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoice
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoiceStore
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SharingChoiceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * THE SWITCH READS EFFECTIVE CONSENT (Reviewer on `c2ce670cc7`): the Settings surface uses the
 * SAME read admission uses — [SavedSharingChoiceStore.enrolledChoiceFor], which admits only a real
 * ENROLLED record with epoch and accepted disclosure. The store carries no persist latch since the
 * SharedPreferences migration (2026-11-12): a failed `commit()` is logged, the in-memory value
 * stands, and there is nothing to refuse. What admission refuses — no record, or a record without
 * provenance — the switch must also show off.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsSwitchEffectiveConsentTest {
    private var mainDispatcher = StandardTestDispatcher()
    private val scopes = mutableListOf<kotlinx.coroutines.CoroutineScope>()

    @get:Rule
    val mainRule = object : TestWatcher() {
        override fun starting(description: Description) {
            mainDispatcher = StandardTestDispatcher()
            Dispatchers.setMain(mainDispatcher)
        }
        override fun finished(description: Description) {
            scopes.forEach { it.cancel() }
            // Drain BEFORE resetMain: a leaked VM collector touching Dispatchers.Main after reset
            // throws and is attributed to the next test (CI flake on 7a9664f95e).
            mainDispatcher.scheduler.runCurrent()
            scopes.clear()
            Dispatchers.resetMain()
        }
    }

    private fun viewModelWith(store: SavedSharingChoiceStore): VoiceSharingSettingsViewModel =
        VoiceSharingSettingsViewModel(
            projection = kotlinx.coroutines.flow.MutableStateFlow(null),
            // Injected so the rule can cancel the VM's collectors before resetMain (no leaked Main access).
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + mainDispatcher).also { scopes += it },
            enrollmentController = EnrollmentController(
                enrollOperation = { _, _ -> VoiceSharingMutationResult.Failure },
                withdrawOperation = { _ -> VoiceSharingMutationResult.Failure },
                deleteOperation = { false },
                clearLocalPending = {},
                ownerSession = { 1L },
            ),
            ownerSession = { 1L },
            ioDispatcher = Dispatchers.Main,
            savedChoice = VoiceSharingSettingsModule.effectiveConsentProvider(store) { "user@example.com" },
            knownChoice = VoiceSharingSettingsModule.knownChoiceProvider(store) { "user@example.com" },
        )

    @Test
    fun `an ENROLLED record with real provenance shows the switch on`() = runTest {
        val store = SavedSharingChoiceStore(InMemoryChoicePersistence())
        assertTrue(store.recordEnrolled("user@example.com", "epoch-1", EnrollmentDisclosure.VERSION))
        val vm = viewModelWith(store)
        mainDispatcher.scheduler.advanceUntilIdle()
        assertTrue(
            "the same record admission acts on shows the switch on",
            vm.state.value.enrolled,
        )
    }

    @Test
    fun `a state without provenance never shows the switch on`() = runTest {
        val store = SavedSharingChoiceStore(InMemoryChoicePersistence())
        assertTrue(store.recordNotEnrolled("user@example.com"))
        assertNull("admission refuses: no provenance", store.enrolledChoiceFor("user@example.com"))
        val vm = viewModelWith(store)
        mainDispatcher.scheduler.advanceUntilIdle()
        assertFalse("the switch reads what admission reads: off", vm.state.value.enrolled)
    }

    @Test
    fun `a withdrawal keeps the record visible but the switch off`() = runTest {
        val store = SavedSharingChoiceStore(InMemoryChoicePersistence())
        assertTrue(store.recordEnrolled("user@example.com", "epoch-1", EnrollmentDisclosure.VERSION))
        assertTrue(store.recordWithdrawn("user@example.com"))
        assertNull(store.enrolledChoiceFor("user@example.com"))
        val vm = viewModelWith(store)
        mainDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.state.value.enrolled)
    }
}

private fun assertNull(any: SavedSharingChoice?) = org.junit.Assert.assertNull(any)
