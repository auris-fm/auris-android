package au.com.shiftyjelly.pocketcasts.preferences.gateway

import android.content.Context
import android.content.SharedPreferences
import au.com.shiftyjelly.pocketcasts.preferences.BuildConfig
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SharedPreferencesGatewayUrlProvider @Inject constructor(
    @ApplicationContext context: Context,
) : GatewayUrlProvider by GatewayUrlResolver(
    prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    buildDefaultGatewayUrl = BuildConfig.AURIS_GATEWAY_URL,
)

/**
 * Shared resolution logic; construct directly in unit tests with an in-memory
 * [SharedPreferences] and explicit build default.
 *
 * The live Railway gateway still proxies unknown routes to a **single** upstream
 * (`POCKET_CASTS_BASE_URL` → api.pocketcasts.com). Remapping static/refresh/search
 * onto that host turns public CDN/refresh 200s into 401s (verified: discover on
 * static.pocketcasts.com is 200; same path on api.pocketcasts.com / api.auris.fm
 * is 401 with or without Bearer). Until the gateway routes by Pocket Casts host,
 * only [serverApiUrl] cutover goes through the gateway; other hosts stay direct.
 * Auris-owned `/api/v1/...` routes still use [configuredGatewayUrl] via CloudConfig.
 */
internal class GatewayUrlResolver(
    private val prefs: SharedPreferences,
    private val buildDefaultGatewayUrl: String,
) : GatewayUrlProvider {

    override fun configuredGatewayUrl(): String {
        if (prefs.contains(KEY_BASE_URL)) {
            return prefs.getString(KEY_BASE_URL, null).orEmpty()
        }
        return buildDefaultGatewayUrl
    }

    override fun isDirectUpstreamForced(): Boolean = prefs.getBoolean(KEY_DIRECT_UPSTREAM, false)

    override fun isCutoverActive(): Boolean = configuredGatewayUrl().isNotBlank() && !isDirectUpstreamForced()

    override fun serverMainUrl(): String = Settings.SERVER_MAIN_URL

    override fun serverApiUrl(): String = resolveApi(Settings.SERVER_API_URL)

    override fun serverCacheUrl(): String = Settings.SERVER_CACHE_URL

    override fun serverStaticUrl(): String = Settings.SERVER_STATIC_URL

    override fun serverSharingUrl(): String = Settings.SERVER_SHARING_URL

    override fun serverListUrl(): String = Settings.SERVER_LIST_URL

    override fun searchApiUrl(): String = Settings.SEARCH_API_URL

    override fun webFeedsApiUrl(): String = Settings.WEB_FEEDS_API_URL

    private fun resolveApi(upstreamUrl: String): String = if (isCutoverActive()) configuredGatewayUrl().trimEnd('/') else upstreamUrl
}

internal const val PREFS_NAME = "auris_cloud"
internal const val KEY_BASE_URL = "base_url"
internal const val KEY_DIRECT_UPSTREAM = "direct_upstream"
