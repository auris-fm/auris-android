package au.com.shiftyjelly.pocketcasts.repositories.cloud

import au.com.shiftyjelly.pocketcasts.repositories.sync.SyncAccountManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The credential this app already holds at login, presented to
 * `POST /api/v1/auth/token` (task #33): the account session's refresh token,
 * which is what the account authority issues for both Android sign-in paths.
 *
 * Read-only and never logged; the exchange is a separate, non-rotating call for
 * the *Auris* token, so the account session the app itself uses is untouched.
 */
@Singleton
class AurisSessionCredentialProvider @Inject constructor(
    private val syncAccountManager: SyncAccountManager,
) : AurisAccountCredentialProviding {
    override suspend fun credential(): String? = syncAccountManager.getRefreshToken()?.value?.takeIf { it.isNotBlank() }
}
