package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.DeletionReceipt
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingMutationResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Serialization and stale-result fencing (reviewer 9156a8ea point 2).
 *
 * The scenarios below are the ones a user can actually produce by tapping twice or by switching
 * accounts while a request is in flight — not hypothetical interleavings. Each control asserts what
 * the user ends up seeing, because the defect was a UI that could report the WRONG operation as the
 * winner even though the server was fine.
 */
class MutationFenceTest {

    @Test
    fun `an off action supersedes an in-flight enrollment so its late result cannot win`() = runTest {
        val fence = MutationFence()
        val enrollment = fence.issue(ownerSession = 1L)

        // The user turns sharing off while the enrollment is still outstanding.
        fence.supersede()

        assertFalse(
            "a late enrollment result must be DROPPED, or the UI would flip back to sharing on after " +
                "the user already turned it off",
            fence.isCurrent(enrollment, { 1L }),
        )
    }

    @Test
    fun `an account switch drops results issued under the previous session`() = runTest {
        val fence = MutationFence()
        val previousAccount = fence.issue(ownerSession = 1L)

        // Account changed underneath: same fence, different live session.
        assertFalse(
            "one account's result must never be applied to another account's screen",
            fence.isCurrent(previousAccount, { 2L }),
        )
    }

    @Test
    fun `an unsuperseded attempt in the same session remains current`() = runTest {
        val fence = MutationFence()
        val attempt = fence.issue(ownerSession = 7L)
        assertTrue(
            "with nothing superseding it, the attempt must be applied normally",
            fence.isCurrent(attempt, { 7L }),
        )
    }

    @Test
    fun `server work is serialized so two mutations never overlap`() = runTest {
        val fence = MutationFence()
        val firstEntered = CompletableDeferred<Unit>()
        var concurrent = 0
        var maxConcurrent = 0

        val first = launch {
            fence.serialize {
                concurrent += 1
                maxConcurrent = maxOf(maxConcurrent, concurrent)
                firstEntered.complete(Unit)
                kotlinx.coroutines.delay(50)
                concurrent -= 1
            }
        }
        firstEntered.await()
        val second = launch {
            fence.serialize {
                concurrent += 1
                maxConcurrent = maxOf(maxConcurrent, concurrent)
                concurrent -= 1
            }
        }
        first.join()
        second.join()

        assertEquals(
            "only one membership mutation may be in flight at a time",
            1,
            maxConcurrent,
        )
    }

    @Test
    fun `turning off does not wait for an in-flight enrollment to finish`() = runTest {
        val fence = MutationFence()
        val enrollment = fence.issue(ownerSession = 1L)
        val enrollmentStarted = CompletableDeferred<Unit>()

        // An enrollment that will never complete on its own, standing in for a slow network.
        val inFlight = launch {
            fence.serialize {
                enrollmentStarted.complete(Unit)
                kotlinx.coroutines.delay(10_000)
            }
        }
        enrollmentStarted.await()

        // The off path must be able to act NOW, without acquiring the serialization lock.
        fence.supersede()
        assertFalse(
            "superseding must be immediate: the user's right to stop cannot queue behind a request",
            fence.isCurrent(enrollment, { 1L }),
        )

        inFlight.cancel()
    }
}
