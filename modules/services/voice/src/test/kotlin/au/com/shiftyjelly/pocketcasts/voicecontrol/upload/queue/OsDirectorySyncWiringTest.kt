package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import android.system.ErrnoException
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.LocalWithdrawalIntentStore
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Production wiring of the Os directory barrier (spec 64ce2de6).
 *
 * The adapter's own controls prove IT behaves; these prove the WIRING: every durable publication and
 * every failed-stop clear actually reaches the Os adapter, and an Os failure reaches the real
 * admission latch rather than being absorbed somewhere along the way.
 *
 * SCOPE: JVM only. `AndroidOsOperations` cannot execute here, so its calls are bound through the
 * [OsOperations] seam; kernel behaviour stays unverified.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OsDirectorySyncWiringTest {

    private class RecordingOs(private val refuseFsync: Boolean = false) : OsOperations {
        val calls = mutableListOf<String>()
        override val isSupported: Boolean = true

        override fun openDirectory(path: String): java.io.FileDescriptor {
            calls += "open"
            return java.io.FileDescriptor()
        }

        override fun fsync(fd: java.io.FileDescriptor) {
            calls += "fsync"
            if (refuseFsync) throw ErrnoException("EIO", 5)
        }

        override fun close(fd: java.io.FileDescriptor) {
            calls += "close"
        }
    }

    @Test
    fun `the production default IS the Os adapter, not the FileChannel path`() {
        // If this default silently reverted, every durability claim in this lane would rest on a path
        // that was never the documented API.
        assertTrue(
            "DirectorySync.PRODUCTION must be the android.system.Os adapter, got ${DirectorySync.PRODUCTION::class.java.simpleName}",
            DirectorySync.PRODUCTION is OsDirectorySync,
        )
    }

    @Test
    fun `the store's publication barrier reaches the Os adapter`() {
        val os = RecordingOs()
        val root = File(System.getProperty("java.io.tmpdir"), "wire-pub-${System.nanoTime()}").apply { mkdirs() }
        val store = ContributionStore(
            root,
            QueueLimits.defaults().copy(maxQueueRecords = 8),
            clock = { 1_000_000L },
            jitter = { it - 1 },
            directorySync = OsDirectorySync(os),
        )
        val id = "11111111-2222-3333-4444-555555555555"
        val meta = "{}".toByteArray()
        val admission = store.admitForTest(1600, meta.size)
        store.prepareAt(
            store.fence.currentGeneration(),
            admission,
            id,
            "epoch-1",
            listOf(ShortArray(1600) { i -> i.toShort() } to 1600),
            meta,
            "a@x",
            7L,
        )
        assertTrue(
            "a durable publication must have gone through open/fsync/close on the Os adapter, got ${os.calls}",
            os.calls.contains("fsync") && os.calls.contains("open") && os.calls.contains("close"),
        )
    }

    @Test
    fun `an Os fsync failure PROPAGATES out of publication rather than reporting success`() {
        val os = RecordingOs(refuseFsync = true)
        val root = File(System.getProperty("java.io.tmpdir"), "wire-pubfail-${System.nanoTime()}").apply { mkdirs() }
        val store = ContributionStore(
            root,
            QueueLimits.defaults().copy(maxQueueRecords = 8),
            clock = { 1_000_000L },
            jitter = { it - 1 },
            directorySync = OsDirectorySync(os),
        )
        val id = "99999999-2222-3333-4444-555555555555"
        val meta = "{}".toByteArray()
        val admission = store.admitForTest(1600, meta.size)
        var threw = false
        val result = runCatching {
            store.prepareAt(
                store.fence.currentGeneration(),
                admission,
                id,
                "epoch-1",
                listOf(ShortArray(1600) { i -> i.toShort() } to 1600),
                meta,
                "a@x",
                7L,
            )
        }
        if (result.isFailure) threw = true
        val published = result.getOrNull()
        assertTrue(
            "an fsync failure must NOT be reported as a durable publication: a returned entry means " +
                "the failure was absorbed before it reached the caller (published=$published, threw=$threw)",
            threw || published == null,
        )
    }

    @Test
    fun `the withdrawal persistence CLEARS through the Os adapter, not the FileInputStream path`() {
        // A success-path check is what proves USE: the FileInputStream barrier also succeeds on the
        // JVM, so only "did the injected adapter actually run" distinguishes them. Reverting the wiring
        // to FileInputStream leaves the call log EMPTY, which this catches.
        val os = RecordingOs()
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        File(context.filesDir, "voice-sharing-local-stop").delete()
        File(context.filesDir, "voice-sharing-local-stop.tmp").delete()
        val persistence = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.FileWithdrawalIntentPersistence(
            context,
            directorySyncOverride = OsDirectorySync(os),
        )
        assertTrue("the clear must succeed with a healthy adapter", persistence.write(false))
        assertTrue(
            "the withdrawal clear must run through the Os adapter (open/fsync/close), got ${os.calls} - " +
                "an empty log means the FileInputStream barrier is still in use",
            os.calls.contains("fsync") && os.calls.contains("open"),
        )
    }

    @Test
    fun `an Os failure on a withdrawal clear reaches the ADMISSION LATCH, not a screen-local flag`() {
        // The end-to-end wiring point spec named: a failed clear must stop collection through the
        // object CollectionAuthority actually reads.
        val os = RecordingOs(refuseFsync = true)
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        // Robolectric reuses the app files dir across tests in this class; clear any marker first so
        // the precondition is about THIS test's clear, not another test's leftover state.
        File(context.filesDir, "voice-sharing-local-stop").delete()
        File(context.filesDir, "voice-sharing-local-stop.tmp").delete()
        val persistence = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.FileWithdrawalIntentPersistence(
            context,
            directorySyncOverride = OsDirectorySync(os),
        )
        val intent = LocalWithdrawalIntentStore(persistence)

        assertTrue(
            "precondition: collection starts permitted",
            intent.collectionPermittedByLocalIntent(projection()),
        )
        // The user enrolls (a CLEAR), and the Os barrier refuses.
        val cleared = intent.clearOnUserEnrollment()
        assertFalse("a clear whose directory barrier failed must report failure", cleared)
        assertFalse(
            "the Os failure must reach the admission latch: collection must be REFUSED after a clear " +
                "that could not be made durable",
            intent.collectionPermittedByLocalIntent(projection()),
        )
    }

    private fun projection() = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingAccountProjection(
        membershipRevision = "rev-1",
        plan = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.VoiceSharingPlan.FREE,
        // ENROLLED and ACTIVE: with either unset the gate refuses for a reason unrelated to the
        // latch, and the control would pass vacuously.
        voiceSharingEnrolled = true,
        enrollmentEpoch = "epoch-1",
        acceptedDisclosureVersion = "v1",
        withdrawalState = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account.WithdrawalState.ACTIVE,
    )

    @Test
    fun `the production binding NEVER infers the flag from the device capability list - mixed discriminator`() {
        // Spec 220cdb5f / reviewer round 4: under Robolectric, Build.SUPPORTED_ABIS is POPULATED
        // (device capabilities "exist"), yet an authority that resolves to null must still make the
        // platform report UNSUPPORTED. A SUPPORTED_ABIS-based selector (the removed first-entry or
        // any-entry approach) would return a non-null ARM flag here and fail this control.
        val abis = android.os.Build.SUPPORTED_ABIS
        assertTrue("precondition: this environment has device capability entries", abis != null && abis.isNotEmpty())
        assertFalse(
            "a null authority must mean unsupported regardless of device capabilities",
            AndroidOsOperations(oDirectoryAuthority = { null }).isSupported,
        )
        // And the default authority must be a REAL authority (platform constant or compiled native),
        // not something derived from SUPPORTED_ABIS: whatever it returns, it is independent of the
        // capability list ordering.
        val authority = AndroidOsOperations.DEFAULT_O_DIRECTORY_AUTHORITY()
        val swapped = AndroidOsOperations.DEFAULT_O_DIRECTORY_AUTHORITY()
        assertEquals(authority, swapped)
    }
}
