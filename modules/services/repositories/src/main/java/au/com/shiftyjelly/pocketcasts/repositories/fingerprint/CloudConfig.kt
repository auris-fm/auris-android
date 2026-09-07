package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import au.com.shiftyjelly.pocketcasts.preferences.gateway.GatewayUrlProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Configuration for the Auris cloud server the client talks to
 * (cloud assistant + fingerprint reference data).
 *
 * The effective base URL is the gateway URL when cutover is active
 * ([GatewayUrlProvider.isCutoverActive]). Resolution order:
 * SharedPreferences `base_url` override, then build-time [Settings.AURIS_GATEWAY_URL].
 *
 * An empty effective URL disables cloud alignment — the client degrades to
 * the transcript-sync mapping and `client_position_ms`. The same empty URL or
 * the `direct_upstream` kill switch restores direct Pocket Casts upstream hosts
 * for Retrofit (see [GatewayUrlProvider]).
 */
interface CloudConfig {
    fun baseUrl(): String
}

@Singleton
class SharedPreferencesCloudConfig @Inject constructor(
    private val gatewayUrlProvider: GatewayUrlProvider,
) : CloudConfig {
    override fun baseUrl(): String {
        if (!gatewayUrlProvider.isCutoverActive()) {
            return ""
        }
        return gatewayUrlProvider.configuredGatewayUrl().trimEnd('/')
    }

    companion object {
        const val PREFS_NAME = "auris_cloud"
        const val KEY_BASE_URL = "base_url"
    }
}
