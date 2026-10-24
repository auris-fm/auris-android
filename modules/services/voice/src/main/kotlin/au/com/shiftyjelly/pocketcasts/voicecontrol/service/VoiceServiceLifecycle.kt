package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import kotlinx.coroutines.Job

/**
 * THE SERVICE LIFECYCLE SEAM.
 *
 * [VoiceControlService] owns an Android `Service` lifetime, which unit tests cannot enter without an
 * instrumentation run. This interface IS that lifetime, narrowed to the decisions the service makes:
 * start the pipeline once, stop it once, and bind the stop to the service scope. The Android class
 * supplies the platform behaviour; a source-level control supplies the same contract and drives the
 * SAME [VoiceIntentDispatcher] and engine, so cancellation and dispatch ordering can be exercised
 * without pretending an instrumentation run happened.
 *
 * Deliberately NOT a policy: no decision about WHEN to start or stop lives here. If a branch is added
 * to this interface, the production service and every control must both adopt it.
 */
interface VoiceServiceLifecycle {
    /** True while the capture pipeline is running under this lifetime. */
    val isStarted: Boolean

    /** The scope the service cancels on stop; dispatch is bound to it. */
    val lifetime: LifetimeScope

    /**
     * Start the pipeline. Returns false when it was already started — a second start must never
     * produce a second pipeline.
     */
    fun start(): Boolean

    /**
     * Stop the pipeline exactly once. The returned job completes when teardown is finished, so a
     * control can await the real ordering rather than sleeping.
     */
    fun stop(): Job

    /** The scope a stop cancels, exposed for the cancellation-ordering control. */
    interface LifetimeScope {
        val isActive: Boolean
    }
}
