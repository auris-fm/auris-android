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

    /**
     * Invoked INSIDE the ownership transaction after the attempt pickup consumed the attempt and
     * immediately BEFORE the transport launch decision (reviewer 279de1d4, spec c02328d1). A hook that
     * revokes the epoch here proves the launch re-checks authorization in the SAME atomic decision it
     * launches from: a launch moved outside the transaction would start the upload anyway. Production
     * wiring passes [NONE].
     */
    val beforeTransportLaunch: ((generation: Long, captureId: String) -> Unit)? = null,

    /**
     * Invoked inside the ownership transaction AFTER the epoch revalidation but immediately BEFORE the
     * lease is registered (spec ed9046dd): this is the M77b window. A test hook that blocks here while a
     * second thread revokes proves a launch that skips the registration transaction would still be
     * granted (and start network work) under the retired epoch, whereas the correct shape re-checks
     * NOTHING after the window - it was INSIDE the transaction, so the grant is refused by the re-check
     * on resume. Production wiring passes [NONE].
     */
    val onLeaseWindow: ((generation: Long, captureId: String) -> Unit)? = null,

    /**
     * Invoked while a purge or a recovery is actually mutating, AFTER it passed the ownership check and
     * while its lease is held (reviewer 72a35e19). A hook that blocks here proves close() waits for the
     * whole mutation instead of only fencing its entry: without the lease, the mutation would resume after
     * a successor took the directory. Production wiring passes [NONE].
     */
    val onPurgeOrRecoverMutation: ((String) -> Unit)? = null,
) {
    companion object {
        val NONE = StoreHooks()
    }
}
