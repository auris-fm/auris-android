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

    /**
     * Invoked for each directory entry the footprint measurement enumerates, with the number already
     * yielded. Reviewer e4c9a80d requires a control that fails AFTER a child was yielded, not only at open:
     * iteration and close failures must still become unknown accounting. A hook that throws here simulates
     * exactly that.
     */
    val onMeasurementEntry: ((Int) -> Unit)? = null,

    /**
     * Overrides the deletion-settlement absence verdict for one call, returning one of 0=ABSENT, 1=PRESENT,
     * 2=UNKNOWN, or null to use the real no-follow stat. Reviewer 7e09c62b/567c0b94 require an
     * unknown-then-measurable control on the SAME store; filesystem permissions cannot reliably reach the
     * UNKNOWN verdict on every platform, so the reviewer explicitly authorized this narrow injectable seam.
     * Production wiring passes [NONE].
     */
    val settlementAbsenceOverride: ((java.io.File) -> Int?)? = null,
) {
    companion object {
        val NONE = StoreHooks()
    }
}
