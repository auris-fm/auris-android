package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job

/**
 * THE SERVICE LIFECYCLE SEAM.
 *
 * [VoiceControlService] owns an Android `Service` lifetime, which source-level controls cannot enter.
 * This interface IS that lifetime, narrowed to the decisions the service makes and, critically,
 * REPORTED FROM THE AUTHORITY that actually makes them:
 *
 * - [start] is the only entry point that may start the pipeline. The service's own `onStartCommand`
 *   calls it, so there is exactly one start authority; a start already in flight is not repeated.
 * - [stop] is the only entry point that may tear down. It returns a job that completes when teardown
 *   has actually finished, so a caller awaiting it is not lied to.
 * - [isStarted] reports whether a start is IN FLIGHT OR RUNNING, not just whether capture is up. The
 *   engine's `isStarted` alone is false across the whole model-readiness window, which would let a
 *   second start slip in.
 */
internal interface VoiceServiceLifecycle {
    /** True once a start has been accepted, including while it is still waiting for readiness. */
    val isStarted: Boolean

    /** The scope a stop cancels. */
    val lifetime: LifetimeScope

    /** Start the pipeline unless one is already running or in flight. Returns false if it did not. */
    fun start(): Boolean

    /**
     * Stop the pipeline; the returned job completes when teardown has really finished.
     *
     * The result is NOT discarded (reviewer 47a3a267): [TeardownResult] distinguishes a clean
     * release from a teardown that deliberately HELD resources because the engine could not be
     * proven settled. A caller that awaits only the Job cannot tell those apart.
     */
    fun stop(): Deferred<TeardownResult>

    interface LifetimeScope {
        val isActive: Boolean
    }

    /**
     * The production implementation, supplied BY THE SERVICE with accessors over its own state. It
     * holds no policy: every decision it reports is the service's.
     *
     * [startedOrStarting] must answer true while a start is in flight, not only when capture is up.
     * [stopPipeline] must return a job that completes after the pipeline has finished tearing down —
     * a job completed merely because `stop()` returned is NOT acceptable, which is exactly what the
     * previous implementation did.
     */
    class FromServiceState(
        private val startedOrStarting: () -> Boolean,
        private val scope: LifetimeScope,
        private val startPipeline: () -> Boolean,
        private val stopPipeline: () -> Deferred<TeardownResult>,
    ) : VoiceServiceLifecycle {
        override val isStarted: Boolean get() = startedOrStarting()
        override val lifetime: LifetimeScope get() = scope

        override fun start(): Boolean {
            // The authority is consulted here AND by the service, which is the same flag, so a
            // racing second start cannot get past both.
            if (startedOrStarting()) return false
            return startPipeline()
        }

        override fun stop(): Deferred<TeardownResult> = stopPipeline()
    }
}
