package au.com.shiftyjelly.pocketcasts.repositories.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M2: an early connection belongs to the utterance that opened it.
 *
 * The rule is that the tag travels with the utterance rather than living in a shared slot. Most of
 * these tests are about what happens when a second utterance shows up while the first one's
 * connection is still held: it must not be able to use it, and it must not silently destroy it
 * either.
 */
class EarlyConnectionTest {

    private fun opened(tag: String) = EarlyConnection(ownerTag = tag)

    @Test
    fun `a connection is offered only to the utterance that opened it`() {
        val owner = opened("utt-1")
        val holder = EarlyConnectionHolder()

        holder.hold(owner)

        assertSame(owner, holder.takeFor("utt-1"))
    }

    @Test
    fun `a different utterance cannot use a held connection`() {
        // The dangerous case: the next wake word arrives while the previous connection is still
        // open, and whoever checks the holder is not the utterance that opened it.
        val holder = EarlyConnectionHolder()
        holder.hold(opened("utt-1"))

        assertNull(holder.takeFor("utt-2"))
    }

    @Test
    fun `taking a connection transfers it, so it is used at most once`() {
        val holder = EarlyConnectionHolder()
        holder.hold(opened("utt-1"))

        assertNotNull(holder.takeFor("utt-1"))
        assertNull("a consumed connection cannot be used twice", holder.takeFor("utt-1"))
    }

    @Test
    fun `opening a new connection replaces the held one`() {
        // Replacement is the only way a held connection is closed early. The alternative -- closing
        // on every routing exit -- is what the spec removed, because it closed connections that
        // were still about to be used.
        val previous = opened("utt-1")
        val holder = EarlyConnectionHolder()
        holder.hold(previous)

        val replacement = opened("utt-2")
        holder.hold(replacement)

        assertTrue("the replaced connection is cancelled", previous.isCancelled)
        assertSame(replacement, holder.takeFor("utt-2"))
    }

    @Test
    fun `a connection its utterance never used is left alone`() {
        // No client cleanup on every exit: the server's own limit closes it. A turn that routes
        // somewhere local never touches the socket, and closing it on that path is what the spec
        // removed.
        val unused = opened("utt-1")
        val holder = EarlyConnectionHolder()

        holder.hold(unused)
        holder.releaseIfUnused(unused)

        assertTrue("an unused connection is left to the server's limit", !unused.isCancelled)
    }

    @Test
    fun `releasing a connection that was already replaced does not cancel the replacement`() {
        // Late cleanup for the old utterance must not reach through and close the new connection.
        val holder = EarlyConnectionHolder()
        val first = opened("utt-1")
        holder.hold(first)
        val second = opened("utt-2")
        holder.hold(second)

        holder.releaseIfUnused(first)

        assertTrue("the replacement survives cleanup for the previous utterance", !second.isCancelled)
    }

    @Test
    fun `the tag travels with the connection rather than being read from a shared slot`() {
        // Two connections can be in flight during replacement. Each answers for its own tag, so a
        // checker cannot pick up the wrong utterance's identity by looking at "the" current tag.
        val first = opened("utt-1")
        val second = opened("utt-2")

        assertEquals("utt-1", first.ownerTag)
        assertEquals("utt-2", second.ownerTag)
        assertTrue(first.ownerTag != second.ownerTag)
    }
}
