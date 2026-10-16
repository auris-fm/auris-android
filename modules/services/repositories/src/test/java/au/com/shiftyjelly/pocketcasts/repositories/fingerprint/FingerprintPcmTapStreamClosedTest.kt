package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import org.junit.Assert.assertEquals
import org.junit.Test

// REGISTRATION/TEARDOWN BOUNDARY (@reviewer): onSinkFlush snapshots streamClosedCallbacks UNDER the
// registry monitor and invokes OUTSIDE it -- a register/unregister concurrent with a flush can neither
// see a half-mutated list nor block the playback thread on subscriber retirement code.
class FingerprintPcmTapStreamClosedTest {
    @Test
    fun `flush fires registered callbacks once and unregister stops further firing`() {
        val tap = FingerprintPcmTap()
        var fires = 0
        val callback = { fires += 1 }
        tap.registerStreamClosed(callback)
        tap.registerStreamClosed(callback) // idempotent: same instance registers once
        tap.onSinkFlush()
        assertEquals(1, fires)
        tap.unregisterStreamClosed(callback)
        tap.onSinkFlush()
        assertEquals("unregistered callbacks do not fire on later flushes", 1, fires)
    }

    @Test
    fun `flush invokes outside the registry monitor - a register concurrent with the snapshot is visible or not but never crashes`() {
        val tap = FingerprintPcmTap()
        var fires = 0
        tap.registerStreamClosed {
            fires++
            // A subscriber that REGISTERS while the flush iterates: must not deadlock or
            // ConcurrentModify -- the snapshot was taken under the monitor before invocation.
            tap.registerStreamClosed {}
        }
        tap.onSinkFlush()
        assertEquals(1, fires)
        // The callback registered DURING the flush fires on the NEXT flush.
        tap.onSinkFlush()
    }

    // HELD FLUSH ACROSS REPLACEMENT (@reviewer): a flush IN PROGRESS when the subscription is
    // replaced fires the OLD callback (it was in the snapshot taken under the monitor) exactly once,
    // and the NEW callback first fires on a LATER flush. The unregister of the old callback happening
    // DURING the held flush must not double-fire or skip it.
    @Test
    fun `a flush held across replacement fires the old callback once and the new one only on later flushes`() {
        val tap = FingerprintPcmTap()
        var oldFires = 0
        var newFires = 0
        var unregisteredOld = false
        var oldCallback: (() -> Unit)? = null
        oldCallback = {
            oldFires++
            // The replacement happens WHILE this flush iterates: the old callback unregisters itself
            // and the new owner registers, both mid-flush.
            if (!unregisteredOld) {
                unregisteredOld = true
                oldCallback?.let { tap.unregisterStreamClosed(it) }
                tap.registerStreamClosed({ newFires++ })
            }
        }
        tap.registerStreamClosed(oldCallback)
        tap.onSinkFlush()
        assertEquals(
            "the held flush fires the old callback exactly once, even though it unregistered mid-flush",
            1,
            oldFires,
        )
        assertEquals(
            "the new callback was registered mid-flush: it must NOT fire in that same flush",
            0,
            newFires,
        )
        tap.onSinkFlush()
        assertEquals(
            "after replacement, only the new callback fires",
            1,
            newFires,
        )
        assertEquals("the unregistered old callback never fires again", 1, oldFires)
    }
}
