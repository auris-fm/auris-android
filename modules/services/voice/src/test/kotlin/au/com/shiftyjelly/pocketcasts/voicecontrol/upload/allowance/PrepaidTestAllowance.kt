package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance

/**
 * SYNTHETIC test authority that is always prepaid: each take mints one synthetic token so
 * unrelated positive controls can exercise admission without composing a real owner. These
 * synthetic tokens are NOT evidence of real provisioning — no ledger, window or production
 * authority is involved (reviewer 174083e0). Proof-bearing controls use the REAL provisioned
 * [CaptureAllowanceOwner] (see CaptureAllowanceAdmissionTest).
 */
class PrepaidTestAllowance(
    private val maxClipBytes: Long = 8L * 1024 * 1024,
    private val maxMetadataBytes: Int = 8_192,
) : CaptureAllowanceAuthority {
    var taken = 0
        private set
    val events = mutableListOf<String>()

    override fun takePrepaidToken(): CaptureAllowanceToken? {
        events += "take"
        taken += 1
        return CaptureAllowanceToken(generation = 0, limitsBinding = "test", windowBinding = "test", selectedMaxClipBytes = maxClipBytes, selectedMaxMetadataBytes = maxMetadataBytes)
    }
}
