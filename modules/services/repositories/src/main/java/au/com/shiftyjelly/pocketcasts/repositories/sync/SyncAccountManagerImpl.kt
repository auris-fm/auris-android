package au.com.shiftyjelly.pocketcasts.repositories.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.AccountManagerFuture
import android.accounts.NetworkErrorException
import android.accounts.OnAccountsUpdateListener
import android.content.Intent
import android.os.Bundle
import au.com.shiftyjelly.pocketcasts.preferences.AccessToken
import au.com.shiftyjelly.pocketcasts.preferences.AccountConstants
import au.com.shiftyjelly.pocketcasts.preferences.RefreshToken
import au.com.shiftyjelly.pocketcasts.servers.sync.LoginIdentity
import au.com.shiftyjelly.pocketcasts.servers.sync.TokenHandler
import au.com.shiftyjelly.pocketcasts.servers.sync.exception.RefreshTokenExpiredException
import au.com.shiftyjelly.pocketcasts.utils.Optional
import au.com.shiftyjelly.pocketcasts.utils.extensions.findParcelable
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import com.automattic.eventhorizon.SignInSourceType
import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

@Singleton
open class SyncAccountManagerImpl @Inject constructor(
    private val tokenErrorNotification: TokenErrorNotification,
    private val accountManager: AccountManager,
) : TokenHandler,
    SyncAccountManager,
    CredentialOwnershipOwner {

    /**
     * Credential OWNERSHIP (reviewer a75df7ef): one lock and one non-secret generation guard
     * EVERY credential write (set/invalidate/login/signOut) and the atomic compare-and-
     * invalidate other boundaries run their scoped checks inside. External rotations are
     * therefore never missed by a cached read and never cleared by a stale rejection.
     */
    private val credentialWriteLock = Any()
    private val credentialGenerationCounter = java.util.concurrent.atomic.AtomicLong(0)
    private val sessionGenerationCounter = java.util.concurrent.atomic.AtomicLong(0)

    override fun credentialGeneration(): Long = credentialGenerationCounter.get()

    /**
     * DISTINCT synchronous SESSION incarnation: increments ONLY on login/logout/account-switch
     * writes (addAccount, signOut), never on token refresh/rotation. Async session events are
     * fenced against it: an event observed at generation G is applied only if the generation is
     * still G after its suspend work, so a late async completion can neither re-arm a logged-out
     * session nor double-apply churn.
     */
    override fun sessionGeneration(): Long = sessionGenerationCounter.get()

    override fun accessTokenSnapshot(): AccessTokenSnapshot? = synchronized(credentialWriteLock) {
        val account = getAccount() ?: return null
        peekAccessToken(account)?.let { AccessTokenSnapshot(it, credentialGenerationCounter.get()) }
    }

    /**
     * ATOMIC compare-and-invalidate against BOTH the observed generation AND the observed
     * token VALUE: async AccountManager/authenticator refresh writes that bypass
     * setAccessToken change the platform token without our counter, so the value compare
     * closes that gap — a rotation by ANY writer makes a stale rejection a no-op.
     */
    override fun invalidateIfUnchanged(observed: AccessTokenSnapshot, scopedCheck: () -> Boolean): Boolean = synchronized(credentialWriteLock) {
        if (credentialGenerationCounter.get() != observed.generation) return false
        val account = getAccount() ?: return false
        val currentToken = peekAccessToken(account)
        if (currentToken == null || currentToken.value != observed.token.value) return false
        if (!scopedCheck()) return false
        accountManager.invalidateAuthToken(AccountConstants.ACCOUNT_TYPE, observed.token.value)
        credentialGenerationCounter.incrementAndGet()
        true
    }

    override fun getAccount(): Account? {
        return accountManager.getAccountsByType(AccountConstants.ACCOUNT_TYPE).firstOrNull()
    }

    override fun isLoggedIn(): Boolean {
        return getAccount() != null
    }

    override fun getEmail(): String? {
        return getAccount()?.name
    }

    override fun emailFlow() = callbackFlow<String?> {
        val listener = object : OnAccountsUpdateListener {
            override fun onAccountsUpdated(accounts: Array<out Account>?) {
                val email = accounts?.find { it.type == AccountConstants.ACCOUNT_TYPE }?.name
                trySend(email)
            }
        }
        trySend(getEmail())
        accountManager.addOnAccountsUpdatedListener(listener, null, true)
        awaitClose {
            accountManager.removeOnAccountsUpdatedListener(listener)
        }
    }

    override fun emailFlowable(): Flowable<Optional<String>> {
        return Flowable.create({ emitter ->
            try {
                val listener = OnAccountsUpdateListener { accounts ->
                    val email = accounts?.find { it.type == AccountConstants.ACCOUNT_TYPE }?.name
                    emitter.onNext(Optional.of(email))
                }
                emitter.onNext(Optional.of(getEmail()))
                accountManager.addOnAccountsUpdatedListener(listener, null, true)
                emitter.setCancellable {
                    accountManager.removeOnAccountsUpdatedListener(listener)
                }
            } catch (e: Exception) {
                emitter.tryOnError(e)
            }
        }, BackpressureStrategy.BUFFER)
    }

    override fun getUuid(): String? = getAccount()?.let { account ->
        accountManager.getUserData(account, AccountConstants.UUID)
    }

    override fun getLoginIdentity(): LoginIdentity? {
        val account = getAccount() ?: return null
        val loginIdentity = accountManager.getUserData(account, AccountConstants.LOGIN_IDENTITY)
        return LoginIdentity.valueOf(loginIdentity) ?: LoginIdentity.PocketCasts
    }

    override fun peekAccessToken(account: Account): AccessToken? = accountManager.peekAuthToken(account, AccountConstants.TOKEN_TYPE)?.let {
        if (it.isNotEmpty()) {
            AccessToken(it)
        } else {
            null
        }
    }

    override suspend fun getAccessToken(): AccessToken? {
        val account = getAccount() ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val resultFuture: AccountManagerFuture<Bundle> = accountManager.getAuthToken(
                    account,
                    AccountConstants.TOKEN_TYPE,
                    Bundle(),
                    false,
                    null,
                    null,
                )
                val bundle: Bundle = resultFuture.result // This call will block until the result is available.
                val token = bundle.getString(AccountManager.KEY_AUTHTOKEN)
                // Token failed to refresh
                if (token == null) {
                    val intent = bundle.findParcelable<Intent>(AccountManager.KEY_INTENT)
                    if (intent == null) {
                        throw NetworkErrorException()
                    } else {
                        tokenErrorNotification.show(intent)
                        throw RefreshTokenExpiredException()
                    }
                } else {
                    AccessToken(token)
                }
            } catch (e: RefreshTokenExpiredException) {
                LogBuffer.i(LogBuffer.TAG_BACKGROUND_TASKS, "Could not get token because the refresh token has expired")
                throw e
            } catch (e: Exception) {
                LogBuffer.e(LogBuffer.TAG_BACKGROUND_TASKS, e, "Could not get token")
                throw e // Rethrow the exception so it carries on
            }
        }
    }

    override fun invalidateAccessToken() {
        synchronized(credentialWriteLock) {
            val account = getAccount() ?: return
            val accessToken = peekAccessToken(account) ?: return
            accountManager.invalidateAuthToken(AccountConstants.ACCOUNT_TYPE, accessToken.value)
            credentialGenerationCounter.incrementAndGet()
        }
    }

    override fun getSignInType(account: Account): AccountConstants.SignInType {
        return AccountConstants.SignInType.fromString(accountManager.getUserData(account, AccountConstants.SIGN_IN_TYPE_KEY))
    }

    override fun addAccount(email: String, uuid: String, refreshToken: RefreshToken, accessToken: AccessToken, loginIdentity: LoginIdentity) {
        val account = Account(email, AccountConstants.ACCOUNT_TYPE)
        val userData = Bundle().apply {
            putString(AccountConstants.UUID, uuid)
            putString(
                AccountConstants.SIGN_IN_TYPE_KEY,
                AccountConstants.SignInType.Tokens.value,
            )
            putString(AccountConstants.LOGIN_IDENTITY, loginIdentity.key)
        }
        val accountAdded = synchronized(credentialWriteLock) {
            val added = accountManager.addAccountExplicitly(account, refreshToken.value, userData)
            accountManager.setAuthToken(account, AccountConstants.TOKEN_TYPE, accessToken.value)
            credentialGenerationCounter.incrementAndGet()
            sessionGenerationCounter.incrementAndGet()
            added
        }

        // When the account was already added, set the sign in type to Tokens because the account
        // does not seem to get updated with this from the userData in the addAccountExplicitly call
        if (!accountAdded) {
            LogBuffer.i(LogBuffer.TAG_BACKGROUND_TASKS, "Account already added, setting sign in type to Tokens")
            accountManager.setUserData(account, AccountConstants.SIGN_IN_TYPE_KEY, AccountConstants.SignInType.Tokens.value)
        }
    }

    override fun signOut() {
        synchronized(credentialWriteLock) {
            accountManager.getAccountsByType(AccountConstants.ACCOUNT_TYPE).forEach { account ->
                accountManager.removeAccountExplicitly(account)
            }
            credentialGenerationCounter.incrementAndGet()
            sessionGenerationCounter.incrementAndGet()
        }
    }

    override fun setRefreshToken(refreshToken: RefreshToken) {
        val account = getAccount() ?: return
        accountManager.setPassword(account, refreshToken.value)
        accountManager.setUserData(account, AccountConstants.SIGN_IN_TYPE_KEY, AccountConstants.SignInType.Tokens.value)
    }

    override fun setAccessToken(accessToken: AccessToken) {
        synchronized(credentialWriteLock) {
            val account = getAccount() ?: return
            accountManager.setAuthToken(account, AccountConstants.TOKEN_TYPE, accessToken.value)
            credentialGenerationCounter.incrementAndGet()
        }
    }

    override fun getRefreshToken(account: Account?): RefreshToken? = (account ?: getAccount())?.let {
        val refreshToken = accountManager.getPassword(it)
        if (refreshToken != null && refreshToken.isNotEmpty()) {
            RefreshToken(refreshToken)
        } else {
            null
        }
    }

    override fun setEmail(email: String) {
        val account = getAccount() ?: return
        accountManager.renameAccount(account, email, null, null)
    }
}

sealed class SignInSource {
    sealed class UserInitiated(val analyticsValue: SignInSourceType) : SignInSource() {
        object SignInViewModel : UserInitiated(SignInSourceType.SignInViewModel)
        object Onboarding : UserInitiated(SignInSourceType.Onboarding)
        object Watch : UserInitiated(SignInSourceType.Watch)
    }
    object WatchPhoneSync : SignInSource()
}
