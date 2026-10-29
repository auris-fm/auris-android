package au.com.shiftyjelly.pocketcasts.settings.voice

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PINS THE PRODUCTION WIRING AXIS THE REVIEWER FOUND MISSING (0546969a), updated for the one
 * sharing surface: the reachable Settings section now delegates to `VoiceSharingSettingsViewModel`,
 * which owns the durable stop ordering, the fenced outcome effects and the presentation rules.
 *
 * The gate, the store and the controller were each correct while the user's tap reached NONE of
 * them: production called only `revokeAndPurge`, and the new gate read a flag nobody set. No unit
 * test of the parts can catch that, because the defect is the absence of a call site.
 *
 * This control asserts the CALL SITES exist in production source, in the required order — the stop
 * is recorded before the request is launched, a fresh opt-in is the only thing that releases it,
 * and every outcome-driven store mutation runs inside the controller's fence.
 *
 * SCOPE, stated honestly: this proves the calls are present and ordered, NOT that the UI renders or
 * that a device was exercised. The whole lane is source-only and compiled off.
 */
class SettingsStopWiringTest {

    private fun sourceAt(relative: String): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate.readText()
            dir = dir.parentFile
        }
        throw IllegalStateException("could not locate $relative from ${File(".").absoluteFile}")
    }

    private fun fragmentSource() = sourceAt(
        "modules/features/settings/src/main/java/au/com/shiftyjelly/pocketcasts/" +
            "settings/voice/VoiceControlSettingsFragment.kt",
    )

    private fun viewModelSource() = sourceAt(
        "modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/" +
            "voicecontrol/upload/ui/VoiceSharingSettingsViewModel.kt",
    )

    @Test
    fun `the fragment routes the sharing tap through the ViewModel, not its own stop handling`() {
        val source = fragmentSource()
        assertTrue(
            "the reachable section must delegate to the reviewed ViewModel (one sharing surface)",
            source.contains("voiceSharingSettings.enroll()") && source.contains("voiceSharingSettings.withdraw()"),
        )
        // The fragment must not mutate the stop store or own enrollment outcomes any more: the
        // fragment's old inline path was exactly how unfenced branches crept in (reviewer a65eef2f).
        val forbidden = listOf(
            "recordWithdrawalRequested()",
            "clearOnUserEnrollment()",
            "onServerAgrees()",
            "onWithdrawalFailed()",
            "applyForOutcome(",
            "EnrollmentOutcome.",
        ).filter { source.contains(it) }
        assertTrue(
            "the fragment must not keep its own stop/enrollment wiring after the binding: $forbidden",
            forbidden.isEmpty(),
        )
    }

    @Test
    fun `the off tap records the durable stop before the request is launched`() {
        val source = viewModelSource()
        val recordIndex = source.indexOf("withdrawalIntent.recordWithdrawalRequested()")
        assertTrue(
            "the settings Off path must reach the durable stop store; production previously called " +
                "only revokeAndPurge, so the collection gate read a flag nobody ever set",
            recordIndex >= 0,
        )
        val launchIndex = source.indexOf("scope.launch", recordIndex)
        assertTrue(
            "the stop must be recorded BEFORE the request coroutine is launched, or a scheduling " +
                "window exists in which collection could still admit a capture",
            launchIndex > recordIndex,
        )
        assertFalse(
            "the screen must not keep its own stop latch: the collection gate does not read it, so it " +
                "would look like enforcement without being any",
            source.contains("locallyStopped"),
        )
    }

    @Test
    fun `a fresh opt-in is the only path that releases the stop`() {
        val source = viewModelSource()
        val clearIndex = source.indexOf("withdrawalIntent.clearOnUserEnrollment()")
        assertTrue(
            "turning sharing back on must release the local stop once the server applied it, or a " +
                "user could never re-enable sharing without reinstalling",
            clearIndex >= 0,
        )
        // The release must sit behind a fenced Applied dispatch guarded on the ENROLL operation,
        // not merely in the same block: clearing on a failed enroll would resume collection the
        // user never consented to, and clearing on a withdrawal would un-stop an opt-out.
        val flat = source.replace(Regex("\\s+"), " ")
        val guard = flat.indexOf("if (operation == FailedOperation.ENROLL)")
        val fence = flat.indexOf("applyForOutcome(outcome)", guard)
        assertTrue(
            "the release must run inside the fence under the ENROLL-only guard",
            guard in 0 until clearIndex && fence in guard until clearIndex,
        )
    }

    @Test
    fun `every outcome-driven store mutation runs inside the controller's fence`() {
        val source = viewModelSource()
        assertTrue(
            "the fenced entry point must exist",
            source.contains("applyForOutcome(outcome)"),
        )
        // Each reconciliation call must be a lambda argument of applyForOutcome, not a bare call:
        // a direct call is an unfenced branch by definition (reviewer 60647af3).
        val flat = source.replace(Regex("\\s+"), " ")
        for (call in listOf("withdrawalIntent.onServerAgrees()", "withdrawalIntent.onWithdrawalFailed()")) {
            val idx = flat.indexOf(call)
            assertTrue("the $call reconciliation must exist in the ViewModel", idx >= 0)
            val fence = flat.lastIndexOf("applyForOutcome(outcome) {", idx)
            assertTrue(
                "the $call call must sit inside an applyForOutcome fence",
                fence in 0 until idx,
            )
        }
        // A superseded outcome must not dispatch anything: its branch touches no store.
        val superseded = flat.indexOf("EnrollmentOutcome.Superseded ->")
        assertTrue(superseded >= 0)
        val branch = flat.substring(superseded, minOf(flat.length, superseded + 400))
        assertFalse(
            "a superseded outcome must reach no stop store call",
            branch.contains("withdrawalIntent."),
        )
    }

    @Test
    fun `the toggle reads the durable stop, not only the server projection`() {
        val source = viewModelSource()
        val initIndex = source.indexOf("private fun isEnrolled(")
        assertTrue("the enrolled read must be findable", initIndex >= 0)
        val initBlock = source.substring(initIndex, minOf(source.length, initIndex + 900))
        assertTrue(
            "the initial state must consult the durable stop: reading the server projection " +
                "alone shows 'sharing on' to a user whose collection is actually stopped",
            initBlock.contains("collectionPermittedByLocalIntent"),
        )
        assertTrue(
            "the durable stop must gate the projection rather than replace it",
            initBlock.contains("voiceSharingEnrolled"),
        )
    }

    @Test
    fun `a failed withdrawal keeps the stop rather than clearing it`() {
        val source = viewModelSource()
        val failedIndex = source.indexOf("onWithdrawalFailed()")
        assertTrue(
            "the failure branch must go through onWithdrawalFailed(); a network failure is not " +
                "consent to resume collecting",
            failedIndex >= 0,
        )
        assertTrue(
            "the failure branch must not call the release",
            !source.substring(failedIndex).take(120).contains("clearOnUserEnrollment"),
        )
    }
}
