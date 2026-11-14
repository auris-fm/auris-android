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
 * All instants come from ONE monotonic clock: [clockNanos] (System.nanoTime). Callers take the
 * instant from the record's own clock and pass it to [mark]; the nanos-to-milliseconds conversion
 * happens in exactly one place inside the record. A mixed-clock table (one stage from
 * elapsedRealtime, the next from nanoTime) would report meaningless deltas after any boot-time
 * adjustment, so the record does not accept caller-chosen clocks.
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

    // Marks and outcomes are written from several threads (engine, route sink, executor, audio
    // player) while publish renders them, so every access holds this lock: a concurrent first
    // audible during publish would otherwise throw inside the catch and discard the table.
    // THE LOCK IS THE GUARANTEE, NOT THE COLLECTIONS: LinkedHashMap/LinkedHashSet are not
    // thread-safe, and this path cannot be tested deterministically for the race, so no reader
    // or writer may touch `marks`, `outcomes` or `disposition` without holding [lock].
    private val lock = Any()
    private val marks = LinkedHashMap<Stage, Long>()
    private val outcomes = LinkedHashSet<Outcome>()

    /**
     * Record that [stage] was observed at [atMs] on the client's clock.
     *
     * The first observation wins: a stage arrives once, and re-marking it later would move the
     * measurement to whenever a caller happened to notice rather than when it happened.
     */
    fun mark(stage: Stage, atNanos: Long) {
        synchronized(lock) { marks.putIfAbsent(stage, atNanos) }
    }

    /**
     * Record the turn's wire disposition, named for what actually happened.
     *
     * [Stage.CommitOrCancel] alone cannot describe a turn: it covers both a commit and a cancel,
     * and a reader needs to know which. The stage's own instant is still recorded at the same
     * moment, so the table's arithmetic is unchanged.
     */
    fun markDisposition(atNanos: Long, cancelled: Boolean) {
        mark(Stage.CommitOrCancel, atNanos)
        synchronized(lock) {
            disposition = if (cancelled) Outcome.Cancelled else Outcome.Committed
            outcomes += disposition!!
        }
    }

    private var disposition: Outcome? = null

    fun markOutcome(outcome: Outcome) {
        synchronized(lock) { outcomes += outcome }
    }

    /** Milliseconds from the acoustic end to [stage], or null when either end was not measured. */
    fun elapsedMs(stage: Stage): Long? = synchronized(lock) {
        val zero = marks[Stage.AcousticEnd] ?: return null
        val at = marks[stage] ?: return null
        nanosToMs(at - zero)
    }

    /** How long [stage] itself took, measured against the stage before it that was recorded. */
    fun stageMs(stage: Stage): Long? = synchronized(lock) {
        val at = marks[stage] ?: return null
        val previous = marks.keys.toList().let { keys ->
            val index = keys.indexOf(stage)
            if (index <= 0) null else keys[index - 1]
        } ?: return null
        nanosToMs(at - marks.getValue(previous))
    }

    /** One line per measured stage, oldest first, each carrying the turn's identity. */
    fun lines(): List<String> = synchronized(lock) {
        marks.map { (stage, at) ->
            buildString {
                append("request_id=").append(requestId)
                // The disposition stage is named for what happened, not for the pair of possibilities.
                append(" stage=").append(
                    if (stage == Stage.CommitOrCancel) disposition?.wireName ?: stage.wireName else stage.wireName,
                )
                append(" elapsed_ms=").append(nanosToMs(at - (marks[Stage.AcousticEnd] ?: at)))
                append(" outcome=").append(outcomes.joinToString(",") { it.wireName }.ifEmpty { "-" })
            }
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
        // A published turn is no longer live: later marks for its id must not resurrect it. The
        // registration id is the turn's admitted id, which differs from this record's own request id.
        registeredId?.let { unregister(it) }
    }

    /** The id this record was registered under, when a producer outside the engine marks it. */
    internal var registeredId: String? = null

    companion object {
        const val TAG = "TurnTiming"

        /**
         * THE clock for timing marks. Every stage instant comes from here — never from a caller's
         * own clock — so deltas between stages are always same-clock quantities. The single
         * nanos-to-ms conversion lives in [nanosToMs]. Overridable so a test can pin instants.
         */
        internal var clockNanos: () -> Long = { System.nanoTime() }

        /** The record's ONLY nanos-to-milliseconds conversion. */
        private fun nanosToMs(nanos: Long): Long = nanos / 1_000_000L

        /**
         * Where a published table goes. Production writes the persisted log; a test substitutes
         * this so it can assert what was actually published rather than only what a record could
         * be made to say.
         */
        internal var sink: (String, String) -> Unit = { tag, message -> LogBuffer.i(tag, message) }

        /**
         * Records for turns that are LIVE OUTSIDE THE ENGINE, by the turn's request id.
         *
         * CloudRequestSent, LocalActionExecuted and FirstAudible are observed by producers far from
         * the engine that created the record — the route sink, the intent executor, the audio
         * player. They cannot hold a reference to a record they do not own, so the registry lets
         * them mark by id instead. The engine registers when it mints the turn's admitted id; the
         * record unregisters itself when it publishes.
         */
        private val active = java.util.concurrent.ConcurrentHashMap<String, TurnTimingRecord>()

        /** Make [record] markable by id. Registration is per live turn: a re-registration replaces. */
        fun registerFor(id: String, record: TurnTimingRecord) {
            record.registeredId = id
            active[id] = record
        }

        /** Mark the live turn [id] at [stage], on the caller's clock, or do nothing if it is gone. */
        fun markFor(id: String, stage: Stage, atNanos: Long) {
            active[id]?.mark(stage, atNanos)
        }

        /** Test seam: whether [id] still has a live record (a published one removes itself). */
        internal fun isActive(id: String): Boolean = active.containsKey(id)

        fun unregister(id: String) {
            active.remove(id)
        }

        /**
         * Publish the live turn [id]'s table with its final disposition, at the async producer's
         * real end. THE RECORD LIFETIME RULE: the engine hands the record off with the intent and
         * does NOT publish at handoff — publish() unregisters, and the downstream producers
         * (executor, route sink, audio player) still mark through the registry after that point.
         * The producer that owns the turn's end calls this: on normal completion, cancellation or
         * failure alike, so a cancelled turn still publishes what it measured. No-op when the id
         * is unknown.
         */
        fun finishFor(id: String?, cancelled: Boolean, failed: Boolean) {
            if (id == null) return
            active[id]?.let { record ->
                record.markOutcome(
                    when {
                        cancelled -> Outcome.Cancelled
                        failed -> Outcome.ActionFailed
                        else -> Outcome.Committed
                    },
                )
                record.publish()
            }
        }
    }
}
