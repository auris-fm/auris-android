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
     * Credential + session OWNERSHIP (reviewers a75df7ef→f1d41730): ONE lock; ONE immutable
     * AuthoritySnapshot (account, session, credential, owner-published token); lifecycle
     * transactions fire the narrow synchronous binding INSIDE the lock (owner→voice is THE
     * single lock order); refresh publication is captured BEFORE the network and conditionally
     * committed; authority is NEVER reconstructed from a bare platform-cache token.
     */
    private val credentialWriteLock = Any()

    /**
     * Nested collection-authority fence (reviewer a38421f6): when the COLLECTION lane requests
     * `collectionFence -> owner` ordering, every owner transition below enters this inner lock
     * FIRST while the caller already holds the outer collection fence — so an in-flight
     * handoff copy that holds the collection fence serializes account changes against policy
     * changes deterministically. The order is ALWAYS collection-fence(outer) → owner(this),
     * never reversed; nothing here acquires any observer/sink monitor.
     */
    private val collectionNestedLock = Any()

    /** Runs [block] with owner state serialized by the nested collection fence (owner→inner). */
    private fun <T> underCollectionNesting(block: () -> T): T = synchronized(collectionNestedLock) {
        synchronized(credentialWriteLock) { block() }
    }

    private val credentialGenerationCounter = java.util.concurrent.atomic.AtomicLong(0)
    private val sessionGenerationCounter = java.util.concurrent.atomic.AtomicLong(0)
    private var publishedToken: AccessToken? = null // owner-attributed; never a bare cache read
    private var ownershipBinding: CredentialOwnershipBinding? = null
    private var retainedAccountName: String? = null // the owner account, retained EXPLICITLY
    private var sessionOpen = false // whether the binding's session is currently armed
    private var ownershipInstalled = false // the ONCE bootstrap flag

    fun credentialGeneration(): Long = credentialGenerationCounter.get()

    /** DISTINCT session incarnation: changes ONLY on login/logout/account-switch, never on refresh. */
    override fun publishCredentials(observed: AuthoritySnapshot, refreshToken: RefreshToken, accessToken: AccessToken): Boolean = underCollectionNesting {
        reconcileLocked()
        val account = getAccount()
        // CONDITIONAL commit: the observed snapshot (captured BEFORE the request) must still be
        // the current authority — a late A password change after a B login writes NOTHING.
        if (account == null ||
            retainedAccountName != observed.accountName ||
            sessionGenerationCounter.get() != observed.session ||
            credentialGenerationCounter.get() != observed.credential
        ) {
            return@underCollectionNesting false
        }
        accountManager.setPassword(account, refreshToken.value)
        accountManager.setUserData(account, AccountConstants.SIGN_IN_TYPE_KEY, AccountConstants.SignInType.Tokens.value)
        accountManager.setAuthToken(account, AccountConstants.TOKEN_TYPE, accessToken.value)
        publishedToken = accessToken
        credentialGenerationCounter.incrementAndGet()
        true
    }

    override fun sessionGeneration(): Long = sessionGenerationCounter.get()

    override fun authoritySnapshot(): AuthoritySnapshot = underCollectionNesting {
        reconcileLocked()
        AuthoritySnapshot(retainedAccountName, sessionGenerationCounter.get(), credentialGenerationCounter.get(), publishedToken)
    }

    /**
     * External-identity reconciliation, run FIRST inside EVERY authority transaction
     * (reviewer b7ad5a9a): a removal OR replacement observed between transactions ends the
     * session and voids the published credential INDEPENDENT of token presence (even after a
     * 401 already invalidated the credential, the STALE SESSION is still revoked here). The
     * retained account is updated so the replacement is observed exactly once; a replacement
     * NEVER auto-adopts the new account's token (fail-closed re-login required).
     */
    private fun reconcileLocked() {
        val live = getAccount()?.name
        if (live != retainedAccountName) {
            if (sessionOpen) {
                sessionGenerationCounter.incrementAndGet()
                sessionOpen = false
                ownershipBinding?.onSessionEnded(sessionGenerationCounter.get())
            }
            // The retained credential is cleared INDEPENDENT of voice arming (b680934d):
            // an identity replacement while the session is closed STILL voids the token.
            publishedToken = null
            credentialGenerationCounter.incrementAndGet()
            retainedAccountName = live
        }
    }

    override fun installOwnershipBinding(binding: CredentialOwnershipBinding?): AuthoritySnapshot? = underCollectionNesting {
        reconcileLocked()
        if (binding == null) {
            // An explicit null seed REVOKES the previous binding's session.
            val previous = ownershipBinding
            ownershipBinding = null
            if (sessionOpen) {
                sessionGenerationCounter.incrementAndGet()
                credentialGenerationCounter.incrementAndGet()
                publishedToken = null
                sessionOpen = false
                previous?.onSessionEnded(sessionGenerationCounter.get())
            }
            return@underCollectionNesting null // revoked: no authority from a null seed
        }
        if (ownershipInstalled) {
            // REBINDING: an ATOMIC authority transfer under the owner lock — the OLD binding's
            // session is retired and the NEW binding is seeded with the RETAINED ledger (same
            // token, same credential incarnation, NO platform cache re-adoption).
            val previous = ownershipBinding
            ownershipBinding = binding
            if (sessionOpen) {
                previous?.onSessionEnded(sessionGenerationCounter.get())
                binding.onSessionStarted(sessionGenerationCounter.get())
                return@underCollectionNesting authoritySnapshot()
            }
            return@underCollectionNesting null
        }
        ownershipInstalled = true
        ownershipBinding = binding // FIRST install: the binding is registered for all future tails
        val account = getAccount()
        val platformToken = account?.let { peekAccessToken(it) }
        if (account == null || platformToken == null) {
            publishedToken = null // fail-closed: nothing to adopt, no authority
            retainedAccountName = account?.name
            return@underCollectionNesting null
        }
        // Explicit ONE-TIME adoption of the pre-existing platform token at install: attributed
        // to the CURRENT credential incarnation.
        retainedAccountName = account.name
        publishedToken = platformToken
        sessionOpen = true
        binding.onSessionStarted(sessionGenerationCounter.get())
        authoritySnapshot()
    }

    override fun beginRefresh(observed: AuthoritySnapshot): RefreshLease? = underCollectionNesting {
        reconcileLocked()
        val current = getAccount()
        // The caller's OBSERVED snapshot (name + session + credential, captured BEFORE the
        // request) must still be current: an A->B switch AND a same-account relogin BEFORE
        // admission refuse here — with the refused inputs never read and ZERO network work.
        if (current == null ||
            retainedAccountName != observed.accountName ||
            sessionGenerationCounter.get() != observed.session ||
            credentialGenerationCounter.get() != observed.credential
        ) {
            return@underCollectionNesting null
        }
        val refreshToken = getRefreshToken(current) ?: return@underCollectionNesting null
        val signInType = getSignInType(current)
        RefreshLease(current.name, sessionGenerationCounter.get(), credentialGenerationCounter.get(), refreshToken, signInType)
    }

    override fun publishRefresh(lease: RefreshLease, refreshToken: RefreshToken, accessToken: AccessToken): Boolean = underCollectionNesting {
        reconcileLocked()
        val account = getAccount()
        if (account == null ||
            account.name != lease.accountName || // retained-vs-live identity: never combine across accounts
            retainedAccountName != lease.accountName ||
            sessionGenerationCounter.get() != lease.session || // session moved: NEVER write into it
            credentialGenerationCounter.get() != lease.credential
        ) { // superseded account/session OR a newer credential already published (out-of-order completion)
            return@underCollectionNesting false
        }
        accountManager.setPassword(account, refreshToken.value)
        accountManager.setUserData(account, AccountConstants.SIGN_IN_TYPE_KEY, AccountConstants.SignInType.Tokens.value)
        accountManager.setAuthToken(account, AccountConstants.TOKEN_TYPE, accessToken.value)
        publishedToken = accessToken
        credentialGenerationCounter.incrementAndGet()
        true
    }

    override fun <T> transaction(block: () -> T): T = underCollectionNesting {
        reconcileLocked()
        block()
    }

    override fun invalidateIfCurrent(observed: AuthoritySnapshot, transaction: () -> Boolean): Boolean = underCollectionNesting {
        reconcileLocked() // external identity FIRST (b7ad5a9a): removal/replacement revokes
        val rejected = observed.token ?: return@underCollectionNesting false
        // Retained-vs-observed comparisons: NEVER combine a live account B with retained token A.
        if (retainedAccountName != observed.accountName ||
            sessionGenerationCounter.get() != observed.session ||
            credentialGenerationCounter.get() != observed.credential ||
            publishedToken?.value != rejected.value
        ) {
            return@underCollectionNesting false // stale rejection: invalidates NOTHING
        }
        if (!transaction()) return@underCollectionNesting false // scoped voice refusal INSIDE the owner transaction
        accountManager.invalidateAuthToken(AccountConstants.ACCOUNT_TYPE, rejected.value)
        publishedToken = null
        credentialGenerationCounter.incrementAndGet()
        true
    }

    override fun reconcileExternalState(): AuthoritySnapshot = underCollectionNesting {
        reconcileLocked()
        authoritySnapshot()
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
            publishedToken = null
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
            retainedAccountName = email // the owner account, retained EXPLICITLY
            publishedToken = accessToken // owner-published at the login transaction
            sessionOpen = true
            ownershipBinding?.onSessionStarted(sessionGenerationCounter.get()) // INSIDE the owner transaction
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
            publishedToken = null
            retainedAccountName = null
            sessionOpen = false
            ownershipBinding?.onSessionEnded(sessionGenerationCounter.get()) // INSIDE the owner transaction
        }
    }

    override fun setRefreshToken(refreshToken: RefreshToken) {
        synchronized(credentialWriteLock) {
            // audit b7ad5a9a: the refresh write was UNLOCKED
            reconcileLocked()
            val account = getAccount() ?: return
            if (account.name != retainedAccountName) return // never write into a foreign account
            accountManager.setPassword(account, refreshToken.value)
            accountManager.setUserData(account, AccountConstants.SIGN_IN_TYPE_KEY, AccountConstants.SignInType.Tokens.value)
        }
    }

    override fun setAccessToken(accessToken: AccessToken) {
        synchronized(credentialWriteLock) {
            val account = getAccount() ?: return
            accountManager.setAuthToken(account, AccountConstants.TOKEN_TYPE, accessToken.value)
            credentialGenerationCounter.incrementAndGet()
            publishedToken = accessToken
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
