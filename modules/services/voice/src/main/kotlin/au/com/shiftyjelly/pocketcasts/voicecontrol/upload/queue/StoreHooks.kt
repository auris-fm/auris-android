package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

/**
 * Deterministic interleaving hooks for the queue's real I/O and publication points.
 *
 * Reviewer 32d8012c requires controls that hold the store DURING an actual write or a late callback,
 * rather than proxies that refuse before writing or act sequentially after a purge. These seams let a
 * test fire an interleaving at the exact moment of interest so the outcome is forced, not scheduled.
 * Production wiring passes [NONE]; hooks never change behavior by themselves.
 */
class StoreHooks(
    /** Invoked while the `.wav.part` stream is open (bytes already written), with the active generation. */
    val onWavPartWrite: ((Long) -> Unit)? = null,

    /** Invoked immediately BEFORE the fenced publication attempt, with the captured generation. */
    val beforePublication: ((Long) -> Unit)? = null,
) {
    companion object {
        val NONE = StoreHooks()
    }
}
