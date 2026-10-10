package au.com.shiftyjelly.pocketcasts.voicecontrol.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `a superseded stream cannot publish over its replacement`() {
        val observer = RoutedOutputObserver()
        val first = observer.observe()
        val second = observer.observe()
        second.publishType(3)

        first.publishType(5)
        assertEquals("a stale stream must not overwrite the current route", 3, observer.current())
    }
}
