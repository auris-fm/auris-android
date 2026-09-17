package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import au.com.shiftyjelly.pocketcasts.preferences.gateway.GatewayUrlProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class CloudConfigTest {

    @Test
    fun `baseUrl empty when cutover inactive`() {
        val gateway = mock<GatewayUrlProvider>()
        whenever(gateway.isCutoverActive()).thenReturn(false)

        val config = SharedPreferencesCloudConfig(gateway)

        assertEquals("", config.baseUrl())
    }

    @Test
    fun `baseUrl returns trimmed gateway URL when cutover active`() {
        val gateway = mock<GatewayUrlProvider>()
        whenever(gateway.isCutoverActive()).thenReturn(true)
        whenever(gateway.configuredGatewayUrl()).thenReturn("https://gateway.example.com/")

        val config = SharedPreferencesCloudConfig(gateway)

        assertEquals("https://gateway.example.com", config.baseUrl())
    }
}
