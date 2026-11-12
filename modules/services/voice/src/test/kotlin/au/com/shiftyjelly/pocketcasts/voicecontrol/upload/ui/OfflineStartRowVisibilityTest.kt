package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.ui

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingMutationResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment.EnrollmentController
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.InMemoryChoicePersistence
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoiceStore
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SharingChoiceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * THE ROW MUST SHOW WHEN THE DURABLE STATE IS KNOWN (spec's bot, non-blocking, owner un-deferred):
 * offline start with sharing OFF — no projection ever arrives — used to hide the Settings sharing
 * row, because the old `durableChoiceKnown` was derived from the EFFECTIVE-CONSENT read
 * (`enrolledChoiceFor`), which returns null for a WITHDRAWN record. But "sharing is off" is a known
 * durable state: the row must show, switch off. Visibility follows ANY durable record; the switch's
 * VALUE still comes from the effective-consent read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineStartRowVisibilityTest {
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

    private fun viewModel(
        store: SavedSharingChoiceStore,
        knownChoice: (suspend () -> au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SavedSharingChoice?)?,
    ): VoiceSharingSettingsViewModel = VoiceSharingSettingsViewModel(
        projection = MutableStateFlow(null), // OFFLINE: no projection ever arrives
        enrollmentController = EnrollmentController(
            enrollOperation = { _, _ -> VoiceSharingMutationResult.Failure },
            withdrawOperation = { _ -> VoiceSharingMutationResult.Failure },
            deleteOperation = { false },
            clearLocalPending = {},
            ownerSession = { 1L },
        ),
        ownerSession = { 1L },
        // Injected so the rule can cancel the VM's collectors before resetMain (no leaked Main access).
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + mainDispatcher).also { scopes += it },
        ioDispatcher = Dispatchers.Main, // keep the disk reads on the TEST scheduler (deterministic)
        savedChoice = VoiceSharingSettingsModule.effectiveConsentProvider(store) { "user@example.com" },
        knownChoice = knownChoice,
    )

    @Test
    fun `offline start with a durable WITHDRAWN record still shows the row with the switch off`() = runTest {
        val store = SavedSharingChoiceStore(InMemoryChoicePersistence())
        assertTrue("PREMISE: the withdrawn record persists", store.recordWithdrawn("user@example.com"))

        val vm = viewModel(
            store,
            VoiceSharingSettingsModule.knownChoiceProvider(store) { "user@example.com" },
        )
        mainDispatcher.scheduler.advanceUntilIdle()
        val state = vm.state.value
        assertTrue(
            "sharing OFF offline is a KNOWN durable state: the row must show",
            state.durableChoiceKnown,
        )
        assertFalse("and the switch reads off", state.enrolled)
    }

    @Test
    fun `offline start with no durable record at all still hides the row`() = runTest {
        val store = SavedSharingChoiceStore(InMemoryChoicePersistence())
        val vm = viewModel(store, VoiceSharingSettingsModule.knownChoiceProvider(store) { "user@example.com" })
        mainDispatcher.scheduler.advanceUntilIdle()
        assertFalse(
            "nothing is known about sharing yet: the row stays hidden until a projection or a record exists",
            vm.state.value.durableChoiceKnown,
        )
    }

    @Test
    fun `an enrolled durable record offline shows the row with the switch on`() = runTest {
        val store = SavedSharingChoiceStore(InMemoryChoicePersistence())
        assertTrue(store.recordEnrolled("user@example.com", "epoch-1", "v3"))
        val vm = viewModel(store, VoiceSharingSettingsModule.knownChoiceProvider(store) { "user@example.com" })
        mainDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value.durableChoiceKnown)
        assertTrue(vm.state.value.enrolled)
    }
}
