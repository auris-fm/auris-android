package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

/**
 * Hands the REAL joined-path record from the composition control to the Worker parity control.
 *
 * The two controls must observe the SAME bytes for parity to mean anything: parity against a
 * hand-built record proves the schema agrees with a fixture I wrote, not that it agrees with what the
 * engine emitted. The composition control records the bytes it got from the store (which only serves
 * them after re-verifying its own digest and audio binding); parity then submits exactly those bytes.
 *
 * ORDERING. Gradle runs test classes in a fixed but not alphabetical order in practice, so parity
 * cannot rely on the producer having run first. Instead the holder can PRODUCE on demand
 * ([producer]): the first caller to need the bytes runs the composition control's own join, and every
 * later caller reuses the result. This is why the parity control needs no assumption about ordering,
 * and why the bytes it measures are always the joined path's output rather than a hand-built record.
 * If production cannot run (no producer installed, or the joined path emitted nothing) parity FAILS —
 * an unproduced record is never a passing parity claim.
 */
internal object EmittedJoinedRecordHolder {
    @Volatile
    var bytes: String? = null

    @Volatile
    var sha256: String? = null

    /**
     * Installs the joined-path producer (the composition control registers its own emitter). Returns
     * the produced bytes, or null when nothing could be produced.
     */
    @Synchronized
    fun bytesOrProduce(): Pair<String, String>? {
        val existing = bytes
        val existingSha = sha256
        if (existing != null && existingSha != null) return existing to existingSha
        val installed = producer ?: run {
            noProducer = true
            return null
        }
        val emitted = installed() ?: return null
        bytes = emitted.first
        sha256 = emitted.second
        return emitted
    }

    /** Why production failed, if it did — a bare null would hide the reason behind the assertion. */
    @Volatile
    var productionFailure: Throwable? = null

    /** True when nothing ever installed a producer in this JVM (the producer class never ran). */
    @Volatile
    var noProducer: Boolean = false

    @Volatile
    private var producer: (() -> Pair<String, String>?)? = null

    @Synchronized
    fun installProducer(block: () -> Pair<String, String>?) {
        producer = block
    }
}
