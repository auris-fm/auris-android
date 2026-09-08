package au.com.shiftyjelly.pocketcasts.preferences.gateway

import au.com.shiftyjelly.pocketcasts.preferences.Settings

/**
 * Resolves Pocket Casts-compatible API base URLs for gateway cutover.
 *
 * When [isCutoverActive], [serverApiUrl] points at the gateway (plain host swap;
 * upstream-shaped paths). Other Pocket Casts hosts stay on direct upstream until
 * the gateway can multi-host proxy — collapsing static/refresh/search onto
 * api.pocketcasts.com yields 401s that never happen on the real hosts. Kill
 * switch / empty gateway URL restores direct API upstream too. Auris cloud
 * routes still use [configuredGatewayUrl] via CloudConfig.
 */
interface GatewayUrlProvider {
    /** Gateway URL from SharedPreferences override or build default (may be blank). */
    fun configuredGatewayUrl(): String

    /** Local-first kill switch: force direct upstream without reinstall. */
    fun isDirectUpstreamForced(): Boolean

    /** True when gateway URL is configured and kill switch is off. */
    fun isCutoverActive(): Boolean

    fun serverMainUrl(): String
    fun serverApiUrl(): String
    fun serverCacheUrl(): String
    fun serverStaticUrl(): String
    fun serverSharingUrl(): String
    fun serverListUrl(): String
    fun searchApiUrl(): String
    fun webFeedsApiUrl(): String
}
