package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.model

/**
 * A model the engine actually loaded: either the concrete `{id, version}` that ran, or
 * [None] meaning it did not run or its identity is unknown.
 *
 * [None] is not "unknown version of a known model" - the server needs to tell "never ran" from
 * "ran and we do not know which", so a bare version string is not representable here.
 */
sealed interface ModelRef {
    data class Named(val id: String, val version: String) : ModelRef
    object None : ModelRef
}

/**
 * Whether the routing stage may report `wake_only`.
 *
 * Off by default so the wire vocabulary is unchanged until the server accepts it; the routing
 * observation itself stays in place either way.
 */
object WakeOnlyRoutingEmission {
    @Volatile
    var enabled: Boolean = false
}
