package au.com.shiftyjelly.pocketcasts.repositories.cloud

/**
 * Chooses where the bearer token comes from on every call.
 *
 * The decision deliberately is not made when the graph is built: cutover can be
 * turned on or off while the app is running, and a provider chosen at
 * construction would keep answering from the environment that was active at
 * start-up — handing the legacy `user_<uuid>` bearer to the edge after cutover,
 * or the Auris token to a build that has no Auris environment configured.
 *
 * Each implementation is responsible for the environment it serves: the Auris
 * provider re-resolves its origin per call and keys its cache to it, and the
 * legacy provider fails closed without an identity.
 */
class RoutingCloudTokenProviding(
    private val isAurisActive: () -> Boolean,
    private val auris: CloudTokenProviding,
    private val legacy: CloudTokenProviding,
) : CloudTokenProviding {
    override suspend fun currentToken(): String? = if (isAurisActive()) auris.currentToken() else legacy.currentToken()
}
