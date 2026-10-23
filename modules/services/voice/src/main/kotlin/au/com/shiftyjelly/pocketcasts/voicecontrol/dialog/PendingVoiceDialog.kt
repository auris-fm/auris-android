package au.com.shiftyjelly.pocketcasts.voicecontrol.dialog

import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.DialogPromptTurn

data class PendingVoiceDialog(
    /**
     * The LOGICAL REQUEST this dialog belongs to, minted when the dialog BEGINS.
     *
     * It is the identity a retry names. Minting it at registration -- rather than deriving it from the
     * request text later -- is what lets two user acts that say the same thing stay distinct, and lets
     * one act be retried while its successor is never mistaken for it.
     */
    val logicalRequestId: String,
    val targetTool: String,
    val targetAction: String,
    val filledSlots: Map<String, String> = emptyMap(),
    val missingSlots: Set<String> = emptySet(),
    val lastQuestion: String = "",
    val requiresConfirmation: Boolean = false,
    val promptHistory: List<DialogPromptTurn> = emptyList(),
)
