package au.com.shiftyjelly.pocketcasts.voicecontrol.playback.appops

import java.security.MessageDigest
import org.json.JSONObject

/**
 * Durable execution boundary for app operations v1 (core `cloud-assistant.md`, "Durable execution
 * boundary", committed `bf5fb219`). Storage-agnostic: a [Store] supplies atomicity, this class
 * enforces the contract's ordering rules. It executes NOTHING and is not wired into any sink yet.
 *
 * Rules encoded here:
 * - a claim is keyed by authenticated owner + conversation + turn + operation ID and bound to the
 *   ORIGINAL serialized request bytes (their digest);
 * - an identical duplicate replays the stored outcome, reason and state without a second effect;
 * - an ID reused with DIFFERENT contents is refused without execution;
 * - a durable claim without an outcome resolves to `unknown` (`execution_unresolved`) and is never
 *   re-executed, whether the duplicate is concurrent or recovered after a restart;
 * - a claim that cannot be durably persisted refuses before execution;
 * - a settled result is immutable: re-settling it can only confirm identical contents.
 */
object AppOperationJournal {
    sealed class Verdict {
        /** First sighting: the caller may execute exactly once, after [Claim] is durable. */
        class Execute(val claim: Claim) : Verdict()

        /**
         * Exact duplicate: replay the recorded outcome/reason/state; do NOT execute or re-read.
         * [reason] is always a machine token for a non-success, including a recovered crash-unknown.
         */
        class Replay(val outcome: String, val reason: String?, val state: JSONObject?) : Verdict()

        /** Persistence or durability could not be established (`persistence_unavailable`): refuse. */
        object PersistenceUnavailable : Verdict()

        /** Same identity, different contents: refuse without execution. */
        object IdentityConflict : Verdict()

        /** Storage exists but cannot be trusted (corrupt/unreadable): refuse, never read as empty. */
        object StorageCorrupt : Verdict()
    }

    data class Claim(
        val owner: String,
        val conversation: String,
        val turn: String,
        val operationId: String,
    ) {
        val key: String get() = "$owner\u0000$conversation\u0000$turn\u0000$operationId"
    }

    /**
     * Atomic store. [claim] MUST be an exclusive create for the key across every instance sharing the
     * same storage root; [storeOutcome] MUST refuse to rewrite an already settled result.
     */
    interface Store {
        /** @return false when the key already exists or the durable write failed. */
        fun claim(claim: Claim, contentsDigest: String): Boolean

        /** @return false when the record is missing, unreadable or the durable write failed. */
        fun markInProgress(claim: Claim): Boolean

        /**
         * Records the observed outcome. @return false when the record is missing, the stored
         * identity/digest no longer matches, an already settled result differs, or the durable write
         * failed. A success without state is never recorded.
         */
        fun storeOutcome(
            claim: Claim,
            contentsDigest: String,
            outcome: String,
            reason: String?,
            state: JSONObject?,
        ): Boolean

        /** @return the record, or null when nothing is stored for this key. Corruption is not null. */
        fun read(claim: Claim): Record?

        /** Whether a new operation may be claimed at all under current capacity/retention limits. */
        fun mayClaimNew(): Boolean
    }

    /**
     * One stored record. [outcome] is null while the operation is claimed but not settled — the
     * crash window the contract calls `unknown`.
     */
    class Record(
        val contentsDigest: String,
        val inProgress: Boolean,
        val outcome: String?,
        val reason: String?,
        val state: JSONObject?,
        val session: String? = null,
        val settledBy: String? = null,
    )

    class CorruptStorageException(message: String) : Exception(message)

    /**
     * Machine reason returned for a durable claim that has no recorded outcome. The published
     * contract allows a concurrent duplicate to answer `unknown` instead of waiting, so one reason
     * covers both a concurrent duplicate and a claim recovered after a crash.
     */
    const val UNRESOLVED_REASON = "execution_unresolved"

    /** Stable digest of the ORIGINAL serialized request bytes; no JSON canonicalization is inferred. */
    fun digest(rawBytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(rawBytes).joinToString("") { "%02x".format(it) }

    /**
     * Admits an operation for execution or settles it as a duplicate. Owner/conversation/turn scoping
     * is what stops another turn — or a successor account — from reviving an operation's authority.
     */
    fun admit(claim: Claim, digest: String, store: Store): Verdict {
        val existing = try {
            store.read(claim)
        } catch (_: CorruptStorageException) {
            return Verdict.StorageCorrupt
        }
        if (existing != null) {
            if (existing.contentsDigest != digest) return Verdict.IdentityConflict
            if (existing.outcome != null) {
                return Verdict.Replay(existing.outcome, existing.reason, existing.state)
            }
            // A durable claim with no outcome: concurrent duplicate or recovered crash. The contract
            // permits answering unknown rather than waiting, and it is never re-executed.
            return Verdict.Replay(AppOperationV1.OUTCOME_UNKNOWN, UNRESOLVED_REASON, null)
        }
        if (!store.mayClaimNew()) return Verdict.PersistenceUnavailable
        if (!store.claim(claim, digest)) {
            // Lost a race against a concurrent claim in the shared store: re-read the winner.
            val raced = try {
                store.read(claim)
            } catch (_: CorruptStorageException) {
                return Verdict.StorageCorrupt
            } ?: return Verdict.PersistenceUnavailable
            if (raced.contentsDigest != digest) return Verdict.IdentityConflict
            return if (raced.outcome != null) {
                Verdict.Replay(raced.outcome, raced.reason, raced.state)
            } else {
                Verdict.Replay(AppOperationV1.OUTCOME_UNKNOWN, UNRESOLVED_REASON, null)
            }
        }
        if (!store.markInProgress(claim)) return Verdict.PersistenceUnavailable
        return Verdict.Execute(claim)
    }

    /**
     * Persists the observed outcome after the effect. A failed persist leaves the record in the crash
     * window, so later readers see `unknown` rather than a fabricated success.
     */
    fun settle(
        claim: Claim,
        digest: String,
        outcome: String,
        reason: String?,
        state: JSONObject?,
        store: Store,
    ): Boolean {
        // Same validator the codec uses to publish a result: outcome, reason token, state presence,
        // state size and collection bounds. An invalid result leaves the stored record unchanged.
        if (!AppOperationV1.isValidResultShape(outcome, reason, state)) return false
        return store.storeOutcome(claim, digest, outcome, reason, state?.let { JSONObject(it.toString()) })
    }
}
