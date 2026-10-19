package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

/**
 * Deterministic interleaving seams for the queue's real I/O and ownership points.
 *
 * Reviewer 32d8012c requires controls that hold the store DURING an actual write or a late callback rather
 * than proxies that refuse before writing or act sequentially after a purge; reviewer ee26880c then requires
 * those interleavings to land INSIDE the ownership transaction, so a test can prove an external revocation
 * cannot slip between validation and mutation. Production wiring passes [NONE]; hooks never change behavior
 * by themselves.
 */
class StoreHooks(
    /** Invoked while the `.wav.part` stream is open (bytes already written), with the active generation. */
    val onWavPartWrite: ((Long) -> Unit)? = null,

    /** Invoked inside the ownership section immediately before the publication decision. */
    val beforePublication: ((Long) -> Unit)? = null,

    /** Invoked inside the ownership section immediately before the durable attempts sidecar write. */
    val beforeAttemptWrite: ((Long) -> Unit)? = null,
) {
    companion object {
        val NONE = StoreHooks()
    }
}
