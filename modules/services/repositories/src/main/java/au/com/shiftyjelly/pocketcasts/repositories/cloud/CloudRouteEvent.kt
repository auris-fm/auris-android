package au.com.shiftyjelly.pocketcasts.repositories.cloud

sealed class CloudRouteEvent {
    data class Action(
        val tool: String,
        val action: String,
        val params: Map<String, Any?>,
    ) : CloudRouteEvent()

    data class Token(val text: String) : CloudRouteEvent()

    data class Done(
        val inputTokens: Int,
        val outputTokens: Int,
    ) : CloudRouteEvent()

    data class Error(
        val code: String,
        val message: String,
    ) : CloudRouteEvent()
}
