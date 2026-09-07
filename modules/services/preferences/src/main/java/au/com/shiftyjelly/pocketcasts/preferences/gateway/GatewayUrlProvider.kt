package au.com.shiftyjelly.pocketcasts.preferences.gateway

import au.com.shiftyjelly.pocketcasts.preferences.Settings

/**
 * Resolves Pocket Casts-compatible API base URLs for gateway cutover.
 *
 * When [isCutoverActive], all proxied Pocket Casts hosts collapse to the
 * configured gateway base URL. When the gateway URL is empty or the local
 * kill switch is enabled, upstream [Settings] BuildConfig URLs are used
 * unchanged (safe default for production).
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
