package au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics

import android.os.Process
import android.os.SystemClock
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlGateState
import au.com.shiftyjelly.pocketcasts.voicecontrol.mode.ListeningMode
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Payload-free lifecycle evidence. Enqueue at the decision; never write a file under an owner lock. */
object VoiceLifecycleDiagnostics {
    enum class Source { Grace, Route, Devices, CommunicationDevice, ScoState, Acquisition, Foreground, Cloud, Gate, ServiceObserver, ServiceStop, CapturePipeline }
    internal enum class Kind { Window, Hold, Turn, Eligibility, Capture, Gate }
    internal enum class Reason {
        Wake,
        RecognizedCommand,
        SuccessorWake,
        SuccessorCommand,
        Extended,
        Timeout,
        RouteChanged,
        Backgrounded,
        BackgroundHoldPreserved,
        HoldAcquired,
        HoldRejectedPrivacy,
        HoldRejectedInactive,
        HoldRejectedGeneration,
        HoldReleased,
        HoldCancelled,
        HoldReleaseIgnored,
        TurnStarted,
        TurnEnded,
        TurnCancelled,
        TurnFailed,
        TurnAuthResolved,
        TurnSocketOpened,
        TurnSocketSendComplete,
        TurnTerminalReceived,
        TurnRetired,
        TurnFeedbackSelected,
        Observed,
        ContextLost,
        CaptureStarted,
        EligibilityOff,
        ServiceStopped,
        PipelineTerminated,
    }

    internal data class Event(
        val kind: Kind,
        val reason: Reason,
        val source: Source,
        val generation: Long? = null,
        val previousGeneration: Long? = null,
        val previousActive: Boolean? = null,
        val active: Boolean? = null,
        val holdsBefore: Int? = null,
        val holdsAfter: Int? = null,
        val owner: Long? = null,
        val requestedGeneration: Long? = null,
        val requestId: String? = null,
        val previousGate: Int? = null,
        val gate: Int? = null,
        val changedRules: Int? = null,
        val previousMode: ListeningMode? = null,
        val mode: ListeningMode? = null,
        val previousCapture: Boolean? = null,
        val capture: Boolean? = null,
        val route: au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRoute? = null,
        val availability: au.com.shiftyjelly.pocketcasts.voicecontrol.route.AudioRoute? = null,
        val acquiring: Boolean? = null,
        val errorCode: String? = null,
        val feedbackKind: String? = null,
    )

    private sealed interface Entry {
        data class Record(val line: String) : Entry
        data class Flush(val done: CompletableDeferred<Unit>) : Entry
    }
    private val sequence = AtomicLong()
    private val dropped = AtomicLong()
    private val queue = Channel<Entry>(
        capacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { entry ->
            when (entry) {
                is Entry.Record -> dropped.incrementAndGet()
                is Entry.Flush -> entry.done.complete(Unit)
            }
        },
    )
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uuid = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")

    init {
        writer.launch {
            for (entry in queue) {
                when (entry) {
                    is Entry.Record -> {
                        val claimed = dropped.getAndSet(0)
                        try {
                            LogBuffer.i("VoiceLifecycle", "%s dropped_before=%d", entry.line, claimed)
                        } catch (_: Exception) {
                            // The failed export carried these losses; return them with this record,
                            // preserving drops accumulated concurrently while the writer was running.
                            dropped.addAndGet(claimed + 1)
                        }
                    }

                    is Entry.Flush -> entry.done.complete(Unit)
                }
            }
        }
    }

    internal fun record(event: Event) {
        try {
            val id = event.requestId?.let { if (uuid.matches(it)) it.lowercase() else "invalid" } ?: "-"
            val line = "seq=${sequence.incrementAndGet()} elapsed_ms=${SystemClock.elapsedRealtime()} unix_ms=${System.currentTimeMillis()} pid=${Process.myPid()} " +
                "event=${event.kind} reason=${event.reason} source=${event.source} " +
                "window=${event.generation} prior_window=${event.previousGeneration} " +
                "prior_active=${event.previousActive} active=${event.active} " +
                "holds_before=${event.holdsBefore} holds_after=${event.holdsAfter} owner=${event.owner} " +
                "requested_window=${event.requestedGeneration} request_id=$id " +
                "prior_gate=${event.previousGate} gate=${event.gate} changed_rules=${event.changedRules} " +
                "prior_mode=${event.previousMode} mode=${event.mode} " +
                "prior_capture=${event.previousCapture} capture=${event.capture} route=${event.route} availability=${event.availability} acquiring=${event.acquiring} " +
                "error_code=${event.errorCode ?: "-"} feedback_kind=${event.feedbackKind ?: "-"}"
            if (queue.trySend(Entry.Record(line)).isFailure) dropped.incrementAndGet()
        } catch (_: Exception) {
            // A failed platform observation or diagnostic must never change the lifecycle decision.
            dropped.incrementAndGet()
        }
    }

    // A barrier through the actual writer, so tests inspect LogBuffer rather than a substitute callback.
    internal suspend fun flushForTest() {
        val done = CompletableDeferred<Unit>()
        queue.send(Entry.Flush(done))
        done.await()
    }

    /** Bit meanings are stable: setup=1, conflicts=2, context=4, overall allowed=8. */
    internal fun gateMask(state: VoiceControlGateState): Int = (if (state.setupPassed) 1 else 0) or (if (state.conflictsPassed) 2 else 0) or
        (if (state.contextPassed) 4 else 0) or (if (state.allowed) 8 else 0)

    // Stable rule bits. Unknown rules get one sentinel bit; arbitrary rule IDs/reasons are never logged.
    internal fun ruleMask(ids: Set<String>): Int {
        val known = listOf("user_not_disabled", "device_supported", "models_ready", "not_on_call", "not_casting", "battery_ok", "other_app_playing", "app_in_foreground", "playback_context", "grace_period_active")
        return ids.fold(0) { mask, id -> mask or (1 shl known.indexOf(id).let { if (it < 0) 15 else it }) }
    }
}
