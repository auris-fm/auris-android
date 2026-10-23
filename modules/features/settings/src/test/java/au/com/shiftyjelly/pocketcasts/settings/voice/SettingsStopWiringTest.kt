package au.com.shiftyjelly.pocketcasts.settings.voice

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PINS THE PRODUCTION WIRING AXIS THE REVIEWER FOUND MISSING (0546969a).
 *
 * The gate, the store and the controller were each correct while the user's tap reached NONE of
 * them: production called only `revokeAndPurge`, and the new gate read a flag nobody set. No unit
 * test of the parts can catch that, because the defect is the absence of a call site.
 *
 * The reviewer's own catch was made the same way — grepping the exact head — so this control uses the
 * same axis, deliberately, rather than pretending a behavioural test covers it. It asserts the CALL
 * SITES exist in production source, in the required order: the stop is recorded before the request is
 * launched, and a fresh opt-in is the only thing that releases it.
 *
 * SCOPE, stated honestly: this proves the calls are present and ordered, NOT that the UI renders or
 * that a device was exercised. The whole lane is source-only and compiled off.
 */
class SettingsStopWiringTest {

    private fun fragmentSource(): String {
        // Walk up from the test working directory to the repository root, so the control works from
        // the module directory Gradle uses without hard-coding an absolute path.
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(
                dir,
                "modules/features/settings/src/main/java/au/com/shiftyjelly/pocketcasts/" +
                    "settings/voice/VoiceControlSettingsFragment.kt",
            )
            if (candidate.exists()) return candidate.readText()
            dir = dir.parentFile
        }
        throw IllegalStateException("could not locate VoiceControlSettingsFragment.kt from ${File(".").absoluteFile}")
    }

    @Test
    fun `the off tap records the durable stop before the request is launched`() {
        val source = fragmentSource()
        val recordIndex = source.indexOf("recordWithdrawalRequested()")
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
        assertTrue(
            "the stop must be recorded only on the OFF branch, never on a turn-on",
            source.contains("if (!checked) localWithdrawalIntent.recordWithdrawalRequested()"),
        )
    }

    @Test
    fun `a fresh opt-in is the only path that releases the stop`() {
        val source = fragmentSource()
        val clearIndex = source.indexOf("clearOnUserEnrollment()")
        assertTrue(
            "turning sharing back on must release the local stop once the server applied it, or a " +
                "user could never re-enable sharing without reinstalling",
            clearIndex >= 0,
        )
        // The release must sit behind the Applied check, not merely in the same block.
        val appliedIndex = source.lastIndexOf("outcome is EnrollmentOutcome.Applied", clearIndex)
        assertTrue(
            "the release must be guarded by a SUCCESSFUL apply: clearing on a failed enroll would " +
                "resume collection that the user never consented to",
            appliedIndex >= 0 && appliedIndex < clearIndex,
        )
    }

    @Test
    fun `the toggle initializes from the durable stop, not only the server projection`() {
        val source = fragmentSource()
        val initIndex = source.indexOf("var locallySharing by remember")
        assertTrue("the toggle initialization must be findable", initIndex >= 0)
        val initBlock = source.substring(initIndex, minOf(source.length, initIndex + 700))
        assertTrue(
            "the initial state must consult the durable stop: initializing from the server projection " +
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
        val source = fragmentSource()
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
