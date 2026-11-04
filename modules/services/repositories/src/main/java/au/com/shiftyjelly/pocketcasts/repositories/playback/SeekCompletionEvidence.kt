package au.com.shiftyjelly.pocketcasts.repositories.playback

import androidx.media3.common.Player

/**
 * Correlates a native engine's applied seek with the request that caused it.
 *
 * The recorder mirrors the engine's OWN supersede semantics: ExoPlayer coalesces pending seeks, so a
 * superseded request never produces its own seek-applied discontinuity -- only the most recently
 * issued request can land. An applied seek therefore settles the MOST RECENT issued token and
 * CLEARS older ones: they will never land, and leaving them addressable would let a later seek
 * misattribute. A superseded operation's honest outcome is its budget timeout (unknown), not
 * someone else's landing frame.
 */
class SeekCompletionEvidence {
    private val issued = ArrayDeque<String?>()

    /** The seek request was issued to the engine; its completion must come from the engine, not from the call. */
    fun onRequestIssued(completionToken: String?) {
        issued.addLast(completionToken)
    }

    /**
     * The engine APPLIED a seek (its own seek discontinuity fired). Returns the token of the request
     * that landed, or null when the evidence is unavailable or AMBIGUOUS.
     *
     * ExoPlayer coalesces pending seeks -- a superseded request never produces its own seek
     * discontinuity -- so when MORE THAN ONE request is outstanding when a discontinuity arrives,
     * ownership cannot be proven: the evidence is INVALIDATED (null) rather than assigned to the
     * newest token, and every outstanding request settles unknown by its budget. Never a guessed
     * assignment; a guessed landing frame would be exactly the false settling the token exists to
     * prevent.
     */
    fun onSeekApplied(): String? {
        if (issued.size == 1) return issued.removeLast()
        issued.clear()
        return null
    }
}

/**
 * THE NATIVE SEEK COMPLETION PATH, ON THE REAL ENGINE. ExoPlayer signals an applied seek with its
 * own discontinuity (reason SEEK); the landed item clock is the discontinuity's NEW position --
 * captured by the engine, never the requested target -- and the token is whatever the evidence
 * recorder can PROVE the application belongs to. Ambiguous ownership (several outstanding requests:
 * ExoPlayer coalesces and superseded requests never land) invalidates the evidence -- the completion
 * is emitted with NO token, which no operation can settle on, and the outstanding operations settle
 * unknown by their budgets instead of on a guessed assignment.
 *
 * The callback ordering/coalescing policy is the engine's documented behavior (androidx.media3
 * Player.seekTo supersedes any pending seek operation); this watcher trusts the ENGINE's
 * discontinuity for landing evidence and never self-acknowledges a request.
 */
internal class SeekAppliedWatcher(
    private val evidence: SeekCompletionEvidence,
    private val emit: (positionMs: Int, completionToken: String?) -> Unit,
) : Player.Listener {
    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason != Player.DISCONTINUITY_REASON_SEEK) return
        emit(newPosition.positionMs.toInt(), evidence.onSeekApplied())
    }
}
