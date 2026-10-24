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
    fun `a stale labelling check must not invalidate a newer attempt`() {
        // Reviewer 8ef73f3f: isCurrent claimed to be read-only but bumped generation / rebound the
        // session from a possibly-STALE owner snapshot. An OLD attempt's check, arriving after an
        // account switch or after a newer attempt was issued, could then invalidate the newer
        // attempt it had no business touching. The labelling check must be pure: it answers for the
        // attempt it was given and changes nothing.
        val fence = MutationFence()
        val old = fence.issue(ownerSession = 7L)
        // A NEWER action is issued (e.g. the user tapped again before the old result landed).
        val newer = fence.supersedeAndIssue(ownerSession = 7L)

        // The old check reads a session value captured BEFORE the newer attempt existed. Under the
        // old implementation this call bumped generation, silently invalidating `newer`.
        val staleSnapshot = java.util.concurrent.atomic.AtomicLong(7L)
        assertFalse(
            "the old attempt must not be current",
            fence.isCurrent(old) { staleSnapshot.get() },
        )
        assertTrue(
            "a stale labelling check for the OLD attempt must not invalidate the NEWER attempt " +
                "issued after the snapshot was taken",
            fence.isCurrent(newer) { staleSnapshot.get() },
        )

        // Same for an account switch seen by a stale check: it answers false for the old attempt and
        // leaves everyone else's state alone.
        staleSnapshot.set(99L)
        assertFalse(fence.isCurrent(old) { staleSnapshot.get() })
        // `newer` was issued under session 7, so it is not current under 99 either - but the point is
        // that the CHECK ITSELF must not have moved generation; a check for the old attempt cannot
        // decide the fate of `newer`'s generation. Restore the snapshot to the issuing session: if
        // the stale check had mutated, `newer` would now be dead.
        staleSnapshot.set(7L)
        assertTrue(
            "the newer attempt must survive checks made for the old attempt",
            fence.isCurrent(newer) { staleSnapshot.get() },
        )
    }

    @Test
    fun `the scoped path and isCurrent cannot deadlock against each other`() {
        // Reviewer 445faa5c: applyIfCurrent with an owner scope takes owner -> fence, while the old
        // isCurrent took fence -> owner (it read liveSession() INSIDE synchronized(this)). Two
        // threads on those paths could each hold one lock and wait for the other. This control
        // forces BOTH placements deterministically:
        //
        //   T1 holds the owner lock, then asks the fence to apply (needs the fence lock).
        //   T2 is made to HOLD the fence lock while reading the session (which needs the owner lock).
        //
        // Under the old order T2 is inside the fence critical section waiting for the owner lock T1
        // holds, and T1 is waiting for the fence lock T2 holds - both hang. Under the fixed order the
        // session snapshot happens BEFORE the fence lock, so T1 acquires the fence freely, finishes,
        // and T2 proceeds. Both threads must COMPLETE within the deadline; the assertion is that
        // neither hangs.
        val fence = MutationFence()
        val attempt = fence.issue(ownerSession = 7L)
        val ownerLock = Any()

        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        val t1OwnerHeld = java.util.concurrent.CountDownLatch(1)
        val t2FenceHeld = java.util.concurrent.CountDownLatch(1)
        val t1Done = java.util.concurrent.CountDownLatch(1)
        val t2Done = java.util.concurrent.CountDownLatch(1)

        val t1 = Thread {
            synchronized(ownerLock) {
                t1OwnerHeld.countDown()
                // Wait until T2 is (under the old order) holding the fence lock and wanting ours.
                t2FenceHeld.await()
                val ran = fence.applyIfCurrent(
                    attempt = attempt,
                    liveSession = { 7L },
                    underOwnerTransaction = { block -> block() },
                    effect = { true },
                )
                check(ran == true) { "the attempt must be current on T1" }
            }
            t1Done.countDown()
        }
        val t2 = Thread {
            // Under the OLD order, liveSession() ran INSIDE synchronized(fence): this thread holds
            // the fence lock at the moment it signals t2FenceHeld, and then waits for the owner lock
            // T1 holds. Under the FIXED order the snapshot happens before the lock, so signalling
            // happens outside the fence and no cycle can form.
            val current = fence.isCurrent(attempt) {
                t1OwnerHeld.await()
                t2FenceHeld.countDown()
                // Give the deadlock a chance to form before we release anything: under the old order
                // T1 is now blocked on the fence we (old) hold, and we block below on t1Done.
                // Unbounded on purpose: under the OLD order this thread holds the fence lock while
                // waiting for T1, and T1 waits for the fence - only the outer deadline below may
                // end the test, and a bounded wait here would let the cycle dissolve itself.
                t1Done.await()
                7L
            }
            check(current) { "the attempt must be current on T2" }
            t2Done.countDown()
        }
        t1.isDaemon = true
        t2.isDaemon = true
        t1.start()
        t2.start()

        val bothFinished = t1Done.await(5, java.util.concurrent.TimeUnit.SECONDS) &&
            t2Done.await(1, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(
            "one of the two lock-order paths hung: the fence reads the owner session while holding " +
                "its own lock while the scoped apply path takes the locks in the opposite order - " +
                "that is the reviewer 445faa5c deadlock; the session snapshot must happen BEFORE the " +
                "fence lock on every path",
            bothFinished,
        )
        t1.join(1_000)
        t2.join(1_000)
    }

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
    fun `a second enroll in the same session makes the first result stale`() = runTest {
        // Reviewer d47d2326: successive same-session attempts shared one generation, so a queued
        // older response remained "current" even though the user had since asked for something else.
        val fence = MutationFence()
        val first = fence.issue(ownerSession = 1L)
        val second = fence.issue(ownerSession = 1L)

        assertFalse(
            "the earlier of two queued enrolls must be stale once the later one is issued",
            fence.isCurrent(first, { 1L }),
        )
        assertTrue(
            "the newest issue is the authoritative one and must still apply",
            fence.isCurrent(second, { 1L }),
        )
    }

    @Test
    fun `the newest of three queued enrolls wins`() = runTest {
        val fence = MutationFence()
        val attempts = (1..3).map { fence.issue(ownerSession = 5L) }
        val current = attempts.filter { fence.isCurrent(it, { 5L }) }
        assertEquals(
            "exactly one queued attempt may remain current, got: ${current.size}",
            1,
            current.size,
        )
        assertEquals("the surviving attempt must be the newest", attempts.last(), current.single())
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
