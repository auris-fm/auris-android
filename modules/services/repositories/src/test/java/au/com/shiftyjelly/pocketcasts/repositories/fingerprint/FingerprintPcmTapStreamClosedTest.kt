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
}
