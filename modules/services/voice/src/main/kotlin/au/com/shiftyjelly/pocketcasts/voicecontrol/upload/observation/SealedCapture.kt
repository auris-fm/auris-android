package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation
import java.util.UUID
class SealedCapture(
    val captureId: UUID,
    val context: CaptureContext,
    val pcm: ShortArray,
    val sampleRateHz: Int,
    val wakeState: SegmentObservation.WakeState,
    val wakeMeasurement: SegmentObservation.WakeMeasurement? = null,
    val outcome: CollectionOutcome?,
    val stageHistory: List<CollectionOutcome>,
    val translation: TranslationProvenance?,
    val asrInvocationModel: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.model.ModelRef?,
    val routerInvocationModel: au.com.shiftyjelly.pocketcasts.voicecontrol.upload.model.ModelRef?,
    val executionOutcome: ExecutionOutcome?,
    val allowanceMetadataCap: Int?,
    val cause: String,
) {
    companion object {
        const val CAUSE_TERMINAL = "terminal"
        const val CAUSE_OUTCOME_WAIT_EXPIRED = "outcome_wait_expired"
    }
}
