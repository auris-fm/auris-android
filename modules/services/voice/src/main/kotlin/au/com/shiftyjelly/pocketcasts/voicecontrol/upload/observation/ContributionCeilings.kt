package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

/**
 * The compiled per-clip and per-metadata ceilings that bound ONE contribution record.
 *
 * These were previously carried on the server-issued allowance token, so a capture's bound was
 * whatever a provisioned batch selected. Core c74d7e30 removes the allowance entirely (spec
 * §"Actual upload usage"), and the contract keeps these bounds explicitly:
 *
 * > Bound each clip and the local queue independently: at most 60,000 ms and 1,920,044 WAV bytes
 * > (16 kHz mono PCM16 plus the 44-byte header), with at most 65,536 metadata bytes. These ceilings
 * > are not a reservation or a daily charge.
 *
 * So the bound did not disappear with its former carrier — it became a compiled constant again,
 * independent of any server response and therefore of any server round trip. Keeping them here
 * rather than inline at the observer means the value is stated once and cannot drift between the
 * admission path and the encoder.
 *
 * The daily limits (104,857,600 bytes / 1,000 records per UTC day) are deliberately NOT here:
 * they are enforced server-side at acceptance and are never consulted locally (spec, same section).
 */
object ContributionCeilings {

    /**
     * Maximum encoded-WAV bytes for one clip: 60,000 ms at 16 kHz mono PCM16, plus the 44-byte
     * WAV header. Not a reservation — a per-record bound on what may be retained.
     */
    const val MAX_CLIP_BYTES: Long = 1_920_044

    /** Maximum metadata-envelope bytes for one clip. */
    const val MAX_METADATA_BYTES: Int = 65_536
}
