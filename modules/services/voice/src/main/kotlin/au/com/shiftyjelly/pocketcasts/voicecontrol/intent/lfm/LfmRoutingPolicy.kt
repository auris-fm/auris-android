package au.com.shiftyjelly.pocketcasts.voicecontrol.intent.lfm

/**
 * The client-side expression of the contract's routing marks
 * (`docs/specs/voice-intents.md`, "LFM training and output include only `lfm + cloud`
 * actions, `cloud_route.route` and `no_match`").
 *
 * This set is the boundary between a ROUTER-selected label and a locally-EXECUTED
 * action. It is deliberately static and independent of the deployed artifact's label
 * map: a stale or mis-trained artifact can emit any label it was ever taught, and this
 * policy is what keeps a cloud-only action from executing locally just because the
 * installed model said so. Widening it therefore requires a routing-mark decision, not
 * a model update.
 *
 * `no_match` is handled before this policy (it is a decision, not an escalation), and
 * `dialog_control` is excluded per the contract ("internal and excluded from LFM
 * output") — a label outside this set defers to the cloud instead of executing.
 */
internal object LfmRoutingPolicy {
    /** Exactly the 12 `lfm + cloud` actions plus `cloud_route.route`. */
    val locallySelectable: Set<Pair<String, String>> = setOf(
        "playback" to "pause",
        "playback" to "resume",
        "playback" to "seek_relative",
        "playback" to "seek_to",
        "playback" to "next_episode",
        "effects" to "set_speed",
        "effects" to "adjust_speed",
        "volume" to "set_volume",
        "volume" to "adjust_volume",
        "sleep" to "set",
        "sleep" to "cancel",
        "bookmark" to "add",
        "cloud_route" to "route",
    )

    fun isLocallySelectable(tool: String, action: String): Boolean = (tool to action) in locallySelectable
}
