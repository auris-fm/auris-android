package au.com.shiftyjelly.pocketcasts.repositories.cloud

/**
 * The two app-operation identity field names, and nothing else.
 *
 * Named in this module because RECOGNITION happens at decode, before the voice module sees a frame.
 * Nothing here advertises the capability; no advertisement of `app_operations_v1` exists yet (core
 * `cloud-assistant.md`, "App-operation protocol v1", committed `bf5fb219`).
 */
internal object CloudRouteAppOps {
    const val FIELD_OPERATION_ID = "operation_id"
    const val FIELD_CAPABILITY_VERSION = "capability_version"

    /** The result frame's discriminator, sent back on the same authenticated socket. */
    const val RESULT_TYPE = "app_operation_result"
}
