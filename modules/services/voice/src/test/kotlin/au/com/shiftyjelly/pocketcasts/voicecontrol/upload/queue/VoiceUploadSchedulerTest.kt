package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.UploadNetwork
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PLATFORM DEFERRED-TRANSFER SCHEDULING (spec voice-recording-uploads.md §152, spec 89b4760b).
 *
 * These controls pin the properties the spec states, on the REAL WorkManager (test harness, no
 * device): one unique periodic drain, KEEP semantics so a request never stacks, the `upload_network`
 * constraint carried verbatim, and no unconditional charging/unmetered rule.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceUploadSchedulerTest {

    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
    }

    @Test
    fun `scheduling enqueues ONE unique periodic drain at the production cadence`() {
        val scheduler = VoiceUploadScheduler(workManager)
        scheduler.schedule(UploadNetwork.compiledDefault())

        val infos = workManager.getWorkInfosForUniqueWork(VoiceUploadScheduler.TAG).get(5, TimeUnit.SECONDS)
        assertEquals("exactly one drain is scheduled", 1, infos.size)
        val request = workManager.getWorkInfosForUniqueWork(VoiceUploadScheduler.TAG).get(5, TimeUnit.SECONDS).single()
        assertTrue("the drain is periodic", request.periodicityInfo != null)
        assertEquals(
            "the cadence is the compiled default",
            TimeUnit.MINUTES.toMillis(VoiceUploadScheduler.DEFAULT_INTERVAL_MINUTES),
            request.periodicityInfo!!.repeatIntervalMillis,
        )
        // A second schedule() with UPDATE must not stack another drain.
        scheduler.schedule(UploadNetwork.compiledDefault())
        assertEquals(
            "UPDATE: a repeated schedule never stacks",
            1,
            workManager.getWorkInfosForUniqueWork(VoiceUploadScheduler.TAG).get(5, TimeUnit.SECONDS).size,
        )
    }

    @Test
    fun `a policy network transition REWRITES the constraint and never stacks - KEEP would strand the stale rule`() {
        val scheduler = VoiceUploadScheduler(workManager)
        scheduler.schedule(UploadNetwork.ANY)
        // The transition the old KEEP semantics silently dropped:
        scheduler.schedule(UploadNetwork.UNMETERED)
        val infos = workManager.getWorkInfosForUniqueWork(VoiceUploadScheduler.TAG).get(5, TimeUnit.SECONDS)
        assertEquals("the transition never stacks a second drain", 1, infos.size)
        assertEquals(
            "the tightened upload_network is in force after the transition",
            NetworkType.UNMETERED,
            infos.single().constraints.requiredNetworkType,
        )
    }

    @Test
    fun `the schedule carries the upload_network constraint verbatim and adds nothing stricter`() {
        val scheduler = VoiceUploadScheduler(workManager)
        // The compiled default: connectivity only, no charging/unmetered rule invented.
        scheduler.schedule(UploadNetwork.ANY)
        val info = workManager.getWorkInfosForUniqueWork(VoiceUploadScheduler.TAG).get(5, TimeUnit.SECONDS).single()
        assertEquals("compiled default is connectivity-only", NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertTrue("no charging requirement is invented", !info.constraints.requiresCharging())
        assertTrue("no device-idle requirement is invented", !info.constraints.requiresDeviceIdle())

        scheduler.cancel()
        scheduler.schedule(UploadNetwork.UNMETERED)
        val unmetered = workManager.getWorkInfosForUniqueWork(VoiceUploadScheduler.TAG).get(5, TimeUnit.SECONDS).single()
        assertEquals("a validated policy's unmetered requirement is honoured", NetworkType.UNMETERED, unmetered.constraints.requiredNetworkType)
    }

    @Test
    fun `cancel removes the scheduled drain`() {
        val scheduler = VoiceUploadScheduler(workManager)
        scheduler.schedule(UploadNetwork.ANY)
        scheduler.cancel()
        val infos = workManager.getWorkInfosForUniqueWork(VoiceUploadScheduler.TAG).get(5, TimeUnit.SECONDS)
        assertTrue(
            "cancelled work is never ENQUEUED/RUNNING: ${infos.map { it.state }}",
            infos.all { it.state == WorkInfo.State.CANCELLED || it.state == WorkInfo.State.FAILED || it.state == WorkInfo.State.SUCCEEDED },
        )
    }

    @Test
    fun `the periodic cadence cannot go below the platform floor`() {
        var refused = false
        try {
            VoiceUploadScheduler(workManager, intervalMinutes = 1L)
        } catch (e: IllegalArgumentException) {
            refused = true
        }
        assertTrue("a sub-floor interval is refused at construction, not silently clamped", refused)
    }
}
