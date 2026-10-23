package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * THE PLATFORM DEFERRED-TRANSFER SCHEDULING (spec voice-recording-uploads.md §"Client queue and
 * scheduling", spec 89b4760b): uploading reuses the app's existing WorkManager infrastructure — no
 * foreground microphone service and no continuous background timer. The worker's ONLY job is to run
 * one drain attempt with the durable queue as its state:
 *
 *  - `ExistingWorkPolicy.KEEP`: one drain in flight at a time (a second request never stacks);
 *  - the period is the production cadence, so a suspended/killed process simply resumes from the
 *    durable queue at the next permitted execution (nothing is remembered in memory);
 *  - the required network type comes from the VALIDATED policy snapshot's `upload_network`, never a
 *    value invented here;
 *  - every eligibility/gate decision remains inside [ContributionUploadAssembly.poll], re-evaluated
 *    per attempt, so a closed policy still touches nothing.
 */
@HiltWorker
class VoiceUploadDrainWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val drain: VoiceUploadDrain,
) : Worker(appContext, params) {

    override fun doWork(): Result {
        // DRAIN is the versioned seam; the worker never mutates the queue itself.
        drain.drainOnce()
        return Result.success()
    }
}

/** The one operation the scheduled worker performs: drain the durable queue once. */
fun interface VoiceUploadDrain {
    fun drainOnce()
}

/**
 * The scheduler: ONE unique periodic drain, KEEP policy, `upload_network`-derived constraint. Every
 * input is injected so the production values are the tested values (no constants duplicated in tests).
 */
class VoiceUploadScheduler(
    private val workManager: WorkManager,
    private val intervalMinutes: Long = DEFAULT_INTERVAL_MINUTES,
) {
    init {
        require(intervalMinutes >= 15L) { "periodic drain interval must be >= 15 minutes" }
    }

    /**
     * Schedules the unique drain. [uploadNetwork] is the validated policy's allowed network; the
     * compiled default is [UploadNetwork.ANY] (connectivity only) — no stricter requirement is
     * invented here (spec 89b4760b).
     */
    fun schedule(uploadNetwork: UploadNetwork) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(uploadNetwork.toWorkNetworkType())
            .build()
        // No initial delay override: the platform places the first run at its own opportunity, and
        // a suspended/killed process resumes from the durable queue on the next one.
        val request = PeriodicWorkRequestBuilder<VoiceUploadDrainWorker>(
            intervalMinutes,
            TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .addTag(TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Cancels the scheduled drain (policy disable / withdrawal: collection-owned work stops). */
    fun cancel() {
        workManager.cancelUniqueWork(TAG)
    }

    /** One-shot drain enqueue for an explicit foreground trigger (KEEP: never stacks). */
    fun requestImmediateDrain() {
        val request = androidx.work.OneTimeWorkRequest.Builder(VoiceUploadDrainWorker::class.java)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        const val TAG = "voice-upload-drain"

        /** The validated default cadence. WorkManager rejects anything under its 15-minute floor,
         *  so this value is a compiled constant — an activation decision, not a remote one. */
        const val DEFAULT_INTERVAL_MINUTES = 15L
    }
}

/** Re-exported for callers that only need the scheduler surface. */
typealias UploadNetwork = au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy.UploadNetwork

/** The ONLY mapping from the policy's allowed network to the platform constraint. */
fun UploadNetwork.toWorkNetworkType(): NetworkType = when (this) {
    UploadNetwork.ANY -> NetworkType.CONNECTED
    UploadNetwork.UNMETERED -> NetworkType.UNMETERED
}
