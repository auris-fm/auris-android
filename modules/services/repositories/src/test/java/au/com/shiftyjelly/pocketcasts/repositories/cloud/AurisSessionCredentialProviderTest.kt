package au.com.shiftyjelly.pocketcasts.repositories.cloud

import au.com.shiftyjelly.pocketcasts.preferences.RefreshToken
import au.com.shiftyjelly.pocketcasts.repositories.sync.SyncAccountManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** The credential presented to the Auris token endpoint is the account session. */
class AurisSessionCredentialProviderTest {

    @Test
    fun `presents the stored account refresh token`() = runBlocking {
        val accountManager = mock<SyncAccountManager>()
        whenever(accountManager.getRefreshToken()).thenReturn(RefreshToken("session-refresh-token"))

        assertEquals("session-refresh-token", AurisSessionCredentialProvider(accountManager).credential())
    }

    @Test
    fun `no stored credential fails closed`() = runBlocking {
        val accountManager = mock<SyncAccountManager>()
        whenever(accountManager.getRefreshToken()).thenReturn(null)

        assertNull(AurisSessionCredentialProvider(accountManager).credential())
    }

    @Test
    fun `a blank stored credential is not presented`() = runBlocking {
        val accountManager = mock<SyncAccountManager>()
        whenever(accountManager.getRefreshToken()).thenReturn(RefreshToken("   "))

        assertNull(AurisSessionCredentialProvider(accountManager).credential())
    }
}
