package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance

/**
 * Test authority that is ALWAYS prepaid: each take mints one synthetic token. Existing positive
 * controls inject this explicitly (reviewer 2164aee3: "existing positive controls should explicitly
 * inject a provisioned, prepaid owner"); it is NOT a production shape and refuses nothing.
 */
class PrepaidTestAllowance : CaptureAllowanceAuthority {
    var taken = 0
        private set
    val events = mutableListOf<String>()

    override fun takePrepaidToken(): CaptureAllowanceToken? {
        events += "take"
        taken += 1
        return CaptureAllowanceToken(generation = 0, limitsBinding = "test", windowBinding = "test")
    }
}
