package au.com.shiftyjelly.pocketcasts.preferences.gateway

import android.content.Context
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@Config(manifest = Config.NONE)
@RunWith(RobolectricTestRunner::class)
class GatewayUrlProviderTest {

    private lateinit var prefs: android.content.SharedPreferences

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    private fun provider(buildDefault: String = "") = GatewayUrlResolver(
        prefs = prefs,
        buildDefaultGatewayUrl = buildDefault,
    )

    @Test
    fun `defaults to direct upstream BuildConfig URLs when gateway URL empty`() {
        val gateway = provider(buildDefault = "")

        assertFalse(gateway.isCutoverActive())
        assertEquals(Settings.SERVER_API_URL, gateway.serverApiUrl())
        assertEquals(Settings.SERVER_MAIN_URL, gateway.serverMainUrl())
        assertEquals(Settings.SERVER_CACHE_URL, gateway.serverCacheUrl())
    }

    @Test
    fun `uses build-time gateway default when cutover active`() {
        val gateway = provider(buildDefault = "https://gateway.staging.example.com")

        assertTrue(gateway.isCutoverActive())
        assertEquals("https://gateway.staging.example.com", gateway.serverApiUrl())
        // Single-upstream gateway cannot serve refresh/static; keep them direct.
        assertEquals(Settings.SERVER_MAIN_URL, gateway.serverMainUrl())
        assertEquals(Settings.SERVER_STATIC_URL, gateway.serverStaticUrl())
        assertEquals(Settings.SEARCH_API_URL, gateway.searchApiUrl())
        assertEquals(Settings.SERVER_CACHE_URL, gateway.serverCacheUrl())
    }

    @Test
    fun `SharedPreferences override wins over build default`() {
        val gateway = provider(buildDefault = "https://gateway.staging.example.com")
        prefs.edit().putString(KEY_BASE_URL, "https://override.example.com").apply()

        assertEquals("https://override.example.com", gateway.configuredGatewayUrl())
        assertEquals("https://override.example.com", gateway.serverApiUrl())
        assertEquals(Settings.SERVER_MAIN_URL, gateway.serverMainUrl())
    }

    @Test
    fun `explicit empty SharedPreferences override disables cutover`() {
        val gateway = provider(buildDefault = "https://gateway.staging.example.com")
        prefs.edit().putString(KEY_BASE_URL, "").apply()

        assertFalse(gateway.isCutoverActive())
        assertEquals(Settings.SERVER_API_URL, gateway.serverApiUrl())
    }

    @Test
    fun `kill switch restores direct upstream even when gateway URL configured`() {
        val gateway = provider(buildDefault = "https://gateway.staging.example.com")
        prefs.edit()
            .putBoolean(KEY_DIRECT_UPSTREAM, true)
            .apply()

        assertFalse(gateway.isCutoverActive())
        assertTrue(gateway.isDirectUpstreamForced())
        assertEquals(Settings.SERVER_API_URL, gateway.serverApiUrl())
        assertEquals(Settings.SERVER_MAIN_URL, gateway.serverMainUrl())
    }

    @Test
    fun `kill switch with empty gateway URL stays on direct upstream`() {
        val gateway = provider(buildDefault = "")
        prefs.edit()
            .putBoolean(KEY_DIRECT_UPSTREAM, true)
            .apply()

        assertFalse(gateway.isCutoverActive())
        assertEquals(Settings.SERVER_CACHE_URL, gateway.serverCacheUrl())
    }
}
