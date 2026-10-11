package au.com.shiftyjelly.pocketcasts.voicecontrol.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The observer's contract, which is what keeps an unknown route unknown: a stream publishes where its
 * output goes, and a retiring stream clears only its own observation so a replacement is not erased by
 * its predecessor.
 */
class RoutedOutputObserverTest {

    @Test
    fun `with no stream reporting the observation is absent rather than a default route`() {
        val observer = RoutedOutputObserver()
        assertNull("nothing publishing must read as unknown, not as a route", observer.current())
    }

    @Test
    fun `a publishing stream supplies the routed output and clearing withdraws it`() {
        val observer = RoutedOutputObserver()
        val stream = observer.observe()

        stream.publishType(2)
        assertEquals("the published output must be visible", 2, observer.current())

        // An idle or stopped stream withdraws its answer; the route must go back to unknown rather than
        // keep the last one a stopped stream happened to report.
        stream.clear()
        assertNull("a withdrawn observation must read as unknown", observer.current())
    }

    @Test
    fun `a superseded stream cannot clear its replacement's observation`() {
        // Replacement order: a new player claims the slot, then the old one retires. If the retiring
        // stream could clear the slot it no longer owns, the successor's route would vanish and route
        // decisions would fall back to unknown while audio is still sounding.
        val observer = RoutedOutputObserver()
        val first = observer.observe()
        first.publishType(2)

        val second = observer.observe()
        second.publishType(3)
        assertEquals("the replacement's route must be the current one", 3, observer.current())

        first.clear()
        assertEquals("a superseded stream must not clear its replacement", 3, observer.current())

        second.clear()
        assertNull("and the owner can still withdraw it", observer.current())
    }

    @Test
    fun `clearing twice from one owner does not double-count the claim`() {
        // The stop() -> release() production order clears the same stream twice: stop() withdraws the
        // route, then release() clears again from its own first line. If a second clear from the same
        // owner decremented the claim counter again, claimHolders would go negative and the claim would
        // read as unheld while a stream still holds it.
        val observer = RoutedOutputObserver()
        val stream = observer.observe()
        stream.publishType(2)

        stream.clear()
        stream.clear()

        assertNull("the route stays withdrawn", observer.current())
        assertFalse(
            "a second clear from the same owner must not un-hold the claim it already released",
            observer.holdsAClaim(),
        )
    }

    @Test
    fun `one claim is reusable across sessions and withdraws each session's route`() {
        // A player is a singleton that serves several turns, so its claim must be reusable: publish,
        // clear, publish again, clear again -- and the SECOND route must be withdrawn too. A claim that
        // can only release once leaves the second route permanently published, which is the same defect
        // as never clearing it.
        val observer = RoutedOutputObserver()

        // session 0
        val claim0 = observer.observe()
        claim0.publishType(2)
        assertEquals("session 0 route is visible", 2, observer.current())
        claim0.clear()
        assertNull("session 0 route is withdrawn", observer.current())
        assertFalse("session 0 released its claim", observer.holdsAClaim())

        // session 1: a fresh claim on the same observer, which must be equally withdrawable.
        val claim1 = observer.observe()
        claim1.publishType(3)
        assertEquals("session 1 route must be visible on a re-taken claim", 3, observer.current())
        claim1.clear()
        assertNull(
            "session 1 route must be withdrawn too -- a claim that releases only once would " +
                "leave this route published forever",
            observer.current(),
        )
        assertFalse("session 1 released its claim", observer.holdsAClaim())
    }

    @Test
    fun `a cleared claim cannot republish without being re-taken`() {
        // The ownership token stays equal to holders forever, so it alone cannot tell a live claim from
        // a spent one. A cleared claim that could still write `type` would publish a route nothing can
        // withdraw.
        val observer = RoutedOutputObserver()
        val claim = observer.observe()
        claim.clear()

        claim.publishType(7)
        assertNull("a cleared claim must not publish a route", observer.current())
    }

    @Test
    fun `a route published from the heartbeat path cannot outlive a concurrent release`() {
        // Publication and release both run on the player's lifecycle, and the production reachability
        // of the interleaving is a property of the call graph rather than of this test. What the test
        // CAN establish is the invariant: whatever the interleaving, a route is never left published
        // by a claim that is no longer held. Publication is taken on one thread while the claim is
        // released on another; the assertion is the invariant, so it holds for every outcome except
        // the one where the write wins after the clear.
        val observer = RoutedOutputObserver()
        val iterations = 3_000
        var violations = 0
        repeat(iterations) {
            val claim = observer.observe()
            val start = java.util.concurrent.CountDownLatch(1)
            val writer = Thread {
                start.await()
                claim.publishType(5)
            }
            writer.start()
            start.countDown()
            claim.clear()
            writer.join()
            if (!observer.holdsAClaim() && observer.current() != null) {
                violations += 1
            }
        }
        assertEquals(
            "no iteration may leave a route published by a released claim",
            0,
            violations,
        )
    }

    @Test
    fun `a fresh observation after a stop then release is held by exactly one stream`() {
        // The reachable production lifecycle that underflowed the counter: a player stops, is
        // released, and the next turn claims the slot. The new stream must own the observation, and
        // the retired one must not be able to clear it.
        val observer = RoutedOutputObserver()
        val first = observer.observe()
        first.publishType(2)
        first.clear() // stop()
        first.clear() // release()

        val second = observer.observe()
        second.publishType(3)
        assertTrue("the new stream holds the observation", observer.holdsAClaim())
        assertEquals("the new stream's route is current", 3, observer.current())

        first.clear()
        assertEquals("the retired stream must not clear its successor", 3, observer.current())
        assertTrue("and the successor still holds the claim", observer.holdsAClaim())
    }

    @Test
    fun `a superseded stream cannot publish over its replacement`() {
        val observer = RoutedOutputObserver()
        val first = observer.observe()
        val second = observer.observe()
        second.publishType(3)

        first.publishType(5)
        assertEquals("a stale stream must not overwrite the current route", 3, observer.current())
    }
}
