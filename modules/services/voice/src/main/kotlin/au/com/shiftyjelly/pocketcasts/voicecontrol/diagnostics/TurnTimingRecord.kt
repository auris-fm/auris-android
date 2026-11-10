package au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics

import android.os.SystemClock
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer

/**
 * One turn's timing table, written to the persisted log.
 *
 * The plan asks for a per-step table so the conversational-latency work can be compared before and
 * after on a device. Every stage is recorded when the client OBSERVES it, never inferred: an
 * unmeasured stage is absent from the record rather than written as zero, because a zero would read
 * as instantaneous and would make a missing measurement look like a win.
 *
 * All instants come from one clock ([SystemClock.elapsedRealtime], passed in as [mark]), so the
 * differences between stages are real quantities. The table starts at the acoustic end of speech:
 * that is the instant the user finished and every later cost is the client's.
 */
class TurnTimingRecord(private val requestId: String) {

    /** The steps the plan names, in the order the client can expect to observe them. */
    enum class Stage(val wireName: String) {
        AcousticEnd("acoustic_end"),
        EndpointDecision("endpoint_decision"),
        WakeResult("wake_result"),
        AsrDone("asr_done"),
        RoutingDone("routing_done"),
        CloudRequestSent("cloud_request_sent"),

        /**
         * The turn's disposition on the wire. The wire name is supplied at [mark] time by the
         * caller, because a commit and a cancel are different events: one label for both would
         * make the table unable to say what the turn did.
         */
        CommitOrCancel("commit_or_cancel"),
        LocalActionExecuted("local_action_executed"),
        FirstAudible("first_audible"),
        ;

        val isMeasured: Boolean get() = wireName.isNotEmpty()
    }

    /** What the turn actually did, recorded separately from the timings. */
    enum class Outcome(val wireName: String) {
        ActionExecuted("action_executed"),
        ActionFailed("action_failed"),
        Gated("gated"),
        Committed("committed"),
        Cancelled("cancelled"),
    }

    private val marks = LinkedHashMap<Stage, Long>()
    private val outcomes = LinkedHashSet<Outcome>()

    /**
     * Record that [stage] was observed at [atMs] on the client's clock.
     *
     * The first observation wins: a stage arrives once, and re-marking it later would move the
     * measurement to whenever a caller happened to notice rather than when it happened.
     */
    fun mark(stage: Stage, atMs: Long) {
        marks.putIfAbsent(stage, atMs)
    }

    /**
     * Record the turn's wire disposition, named for what actually happened.
     *
     * [Stage.CommitOrCancel] alone cannot describe a turn: it covers both a commit and a cancel,
     * and a reader needs to know which. The stage's own instant is still recorded at the same
     * moment, so the table's arithmetic is unchanged.
     */
    fun markDisposition(atMs: Long, cancelled: Boolean) {
        mark(Stage.CommitOrCancel, atMs)
        disposition = if (cancelled) Outcome.Cancelled else Outcome.Committed
        outcomes += disposition!!
    }

    private var disposition: Outcome? = null

    fun markOutcome(outcome: Outcome) {
        outcomes += outcome
    }

    /** Milliseconds from the acoustic end to [stage], or null when either end was not measured. */
    fun elapsedMs(stage: Stage): Long? {
        val zero = marks[Stage.AcousticEnd] ?: return null
        val at = marks[stage] ?: return null
        return at - zero
    }

    /** How long [stage] itself took, measured against the stage before it that was recorded. */
    fun stageMs(stage: Stage): Long? {
        val at = marks[stage] ?: return null
        val previous = marks.keys.toList().let { keys ->
            val index = keys.indexOf(stage)
            if (index <= 0) null else keys[index - 1]
        } ?: return null
        return at - marks.getValue(previous)
    }

    /** One line per measured stage, oldest first, each carrying the turn's identity. */
    fun lines(): List<String> = marks.map { (stage, at) ->
        buildString {
            append("request_id=").append(requestId)
            // The disposition stage is named for what happened, not for the pair of possibilities.
            append(" stage=").append(
                if (stage == Stage.CommitOrCancel) disposition?.wireName ?: stage.wireName else stage.wireName,
            )
            append(" elapsed_ms=").append(at - (marks[Stage.AcousticEnd] ?: at))
            append(" outcome=").append(outcomes.joinToString(",") { it.wireName }.ifEmpty { "-" })
        }
    }

    /** The record as one newline-joined block, for a single log write per turn. */
    fun render(): String = lines().joinToString("\n")

    /** Write the table to the persisted log. Failures are swallowed: diagnostics never break a turn. */
    fun publish() {
        try {
            sink(TAG, render())
        } catch (_: Exception) {
            // A diagnostic that cannot be written must not fail the turn it describes.
        }
    }

    companion object {
        const val TAG = "TurnTiming"

        /**
         * Where a published table goes. Production writes the persisted log; a test substitutes
         * this so it can assert what was actually published rather than only what a record could
         * be made to say.
         */
        internal var sink: (String, String) -> Unit = { tag, message -> LogBuffer.i(tag, message) }
    }
}
