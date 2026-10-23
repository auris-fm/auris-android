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
    fun `publication and release are serialised, so a release cannot interleave inside one`() {
        // A controlled interleaving rather than repeated scheduling: the assertion is that one
        // publication call runs to completion without a release landing inside it. The observer
        // exposes the boundary for exactly this, so the test does not depend on timing to arrange the
        // interleaving it is about -- a stress run that found no violation would show only that the
        // window was not hit, which is what @spec ruled out as evidence.
        val observer = RoutedOutputObserver()
        val claim = observer.observe()
        claim.publishType(5)
        assertEquals("precondition: the claim published a route", 5, observer.current())

        val interleaved = java.util.concurrent.atomic.AtomicBoolean(false)
        observer.runSerialisedForTest {
            // Inside the boundary, no release may make progress: start one and require it to block.
            val done = java.util.concurrent.CountDownLatch(1)
            Thread {
                claim.clear()
                done.countDown()
            }.start()
            // The releaser is serialised behind us, so it cannot have completed while we hold the
            // boundary. If it did, the two operations were not serialised and a release could clear
            // between a publication's check and its write.
            if (done.await(150, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                interleaved.set(true)
            }
        }
        assertFalse(
            "a release must not complete while a publication holds the boundary",
            interleaved.get(),
        )

        // And once the boundary is free, the release does complete -- the blocking above must be
        // serialisation, not a deadlock or a no-op clear.
        val settled = java.util.concurrent.CountDownLatch(1)
        Thread {
            claim.clear()
            settled.countDown()
        }.start()
        assertTrue("the release completes once the boundary is free", settled.await(2, java.util.concurrent.TimeUnit.SECONDS))
        assertNull("and the route is withdrawn", observer.current())
    }

    @Test
    fun `claiming is serialised with release, so a successor cannot claim inside one`() {
        // The same boundary as publication and release. Claiming mints a token and increments the
        // holder count, so if it could run inside a release's check-then-clear the retiring claim and
        // its successor would both act on a slot neither exclusively owns.
        val observer = RoutedOutputObserver()
        val retiring = observer.observe()
        retiring.publishType(2)

        val claimedInsideRelease = java.util.concurrent.atomic.AtomicBoolean(false)
        observer.runSerialisedForTest {
            val done = java.util.concurrent.CountDownLatch(1)
            Thread {
                observer.observe()
                done.countDown()
            }.start()
            if (done.await(150, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                claimedInsideRelease.set(true)
            }
        }
        assertFalse(
            "a claim must not be minted while another operation holds the boundary",
            claimedInsideRelease.get(),
        )

        // And once free, claiming proceeds: the blocking is serialisation, not a deadlock.
        val successor = java.util.concurrent.atomic.AtomicReference<RoutedOutputObserver.ObservedRoute?>()
        val claimed = java.util.concurrent.CountDownLatch(1)
        Thread {
            successor.set(observer.observe())
            claimed.countDown()
        }.start()
        assertTrue("claiming completes once the boundary is free", claimed.await(2, java.util.concurrent.TimeUnit.SECONDS))
        successor.get()!!.publishType(3)
        assertEquals("the successor owns the route", 3, observer.current())
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
