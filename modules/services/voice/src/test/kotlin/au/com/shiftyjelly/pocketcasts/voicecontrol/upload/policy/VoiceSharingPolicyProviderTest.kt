package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy

import app.cash.turbine.test
import au.com.shiftyjelly.pocketcasts.sharedtest.JvmTask
import au.com.shiftyjelly.pocketcasts.sharedtest.JvmTaskSource
import au.com.shiftyjelly.pocketcasts.utils.config.FirebaseConfig
import au.com.shiftyjelly.pocketcasts.utils.featureflag.providers.FirebaseRemoteFeatureProvider
import com.google.android.gms.tasks.Task
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigInfo
import com.google.firebase.remoteconfig.FirebaseRemoteConfigValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

/**
 * Task 1 controls (docs/plans/android/voice-recording-uploads.md, reviewer boundaries
 * 3aae64cf/efd5e29f/3651dd10): "enabled" requires REAL SDK freshness — a genuine new fetch
 * whose bookkeeping ADVANCES BEYOND THE SDK'S PRE-EXISTING STATE captured before the request
 * (a first-refresh cached success with an unchanged timestamp is not fresh), successful
 * activation through the actual [FirebaseRemoteFeatureProvider] composition, a REMOTE value
 * source with strict Boolean decoding, and a monotonic lease enforced on the live StateFlow
 * (a plain subscriber observes expiry with no refresh and no read). Overlapping refreshes are
 * fenced by generations captured before the first await. Only the FirebaseRemoteConfig seam is
 * mocked; there is no injected "valid" input.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(MockitoJUnitRunner.Silent::class)
class VoiceSharingPolicyProviderTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(dispatcher)

    // Sacrificial scope for the real FirebaseRemoteFeatureProvider composition: on the JVM its
    // task listeners touch the Android main looper and its init dies — a test artifact that
    // must not cancel the provider's own lifecycle scope. In production both share the
    // application scope.
    private val featureScope = CoroutineScope(dispatcher)

    @Mock private lateinit var config: FirebaseRemoteConfig

    private var monotonicNowMs = 1_000L
    private var fetchTimeMs = 5_000L
    private var fetchStatus = FirebaseRemoteConfig.LAST_FETCH_STATUS_SUCCESS
    private var valueSource = FirebaseRemoteConfig.VALUE_SOURCE_REMOTE
    private var rawValue: String? = "true"
    private var fetchOutcome: Unit? = Unit
    private var fetchAdvancesBookkeeping = true

    /** When set with a failing fetch, the failure is a typed server throttle with this deadline. */
    private var fetchThrottleDeadlineMs: Long? = null

    /** Wall clock behind the provider; moved explicitly to prove deadline-relative scheduling. */
    private var wallNowMs = 1_000_000L

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        restub()
    }

    @After
    fun tearDown() {
        scope.cancel()
        featureScope.cancel()
        Dispatchers.resetMain()
    }

    private var fetchGateOverride: JvmTask<Unit>? = null
    private var throwFirstInfoRead = false
    private var infoReads = 0

    /** Bounded count of real fetches requested, used to prove an extra refresh happened (or not). */
    private var fetchCount = 0

    private fun restubWithFetchGate(gate: JvmTask<Unit>?) {
        fetchGateOverride = gate
        restub()
    }

    private fun resetInfoReads() {
        infoReads = 0
    }

    private fun restub() {
        val info = mock(FirebaseRemoteConfigInfo::class.java)
        whenever(info.fetchTimeMillis).thenAnswer { fetchTimeMs }
        whenever(info.lastFetchStatus).thenAnswer { fetchStatus }
        val shouldThrowOnFirstRead = throwFirstInfoRead
        whenever(config.info).thenAnswer {
            if (shouldThrowOnFirstRead && infoReads++ == 0) {
                throw IllegalStateException("scripted unreadable baseline")
            }
            info
        }

        val value = mock(FirebaseRemoteConfigValue::class.java)
        whenever(value.asString()).thenAnswer { rawValue ?: "" }
        whenever(value.source).thenAnswer { valueSource }
        whenever(config.getValue(FirebaseConfig.VOICE_SHARING_ENABLED)).thenReturn(value)

        // FirebaseRemoteFeatureProvider's init ALSO collects configUpdates (the firebase ktx
        // extension registers a ConfigUpdateListener via addOnConfigUpdateListener). An unstubbed
        // mock returns NULL for the registration and the NPE escapes the unguarded init launch as
        // an uncaught exception that the NEXT runTest in this JVM reports - the order-dependent
        // suite flake VoiceSharingSettingsViewModelTest kept absorbing. Mockito cannot stub the
        // ktx EXTENSION property, so stub the underlying registration call instead.
        whenever(config.addOnConfigUpdateListener(org.mockito.kotlin.any())).thenAnswer {
            org.mockito.Mockito.mock(com.google.firebase.remoteconfig.ConfigUpdateListenerRegistration::class.java)
        }

        // fetchSuspending() dials the NO-ARG fetch(): an unstubbed overload returns null and its
        // addOnCompleteListener NPE escapes as an uncaught coroutine exception that the NEXT
        // runTest in this JVM reports (the VoiceSharingSettingsViewModelTest flake). Stub BOTH
        // overloads; per-test fetch(0L) scripts below override the long form only.
        whenever(config.fetch()).thenAnswer { JvmTask(Result.success(Unit)) }

        whenever(config.fetch(0L)).thenAnswer {
            fetchCount++
            fetchGateOverride?.let { return@thenAnswer it }
            if (fetchOutcome == null) {
                val deadline = fetchThrottleDeadlineMs
                // The REAL throttled exception's constructor path touches android.text.TextUtils
                // (not mocked on the JVM), so the typed failure is a Mockito mock carrying the
                // same authoritative boundary the real one exposes.
                JvmTask(
                    if (deadline == null) {
                        Result.failure(IllegalStateException("scripted fetch failure"))
                    } else {
                        val throttled =
                            mock(com.google.firebase.remoteconfig.FirebaseRemoteConfigFetchThrottledException::class.java)
                        whenever(throttled.throttleEndTimeMillis).thenReturn(deadline)
                        Result.failure(throttled)
                    },
                )
            } else {
                // Advancing bookkeeping is a SIDE EFFECT of the fetch itself: the provider must
                // capture the pre-request timestamp before this runs and observe it move after.
                if (fetchAdvancesBookkeeping) fetchTimeMs += 1_000
                JvmTask(Result.success(Unit))
            }
        }
        whenever(config.activate()).thenAnswer {
            if (activateOutcome == null) {
                JvmTask<Boolean>(Result.failure(IllegalStateException("scripted activation failure")))
            } else {
                JvmTask<Boolean>(Result.success(true))
            }
        }
    }

    private var activateOutcome: Unit? = Unit

    /** Explicit shared primitive for tests — mirrors production composition's owner lock. */
    private val testSharedLock = Any()

    private var throttleEndTimeMs: Long? = null

    private fun provider(
        sleepMs: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
        refreshTimeoutMs: Long = VoiceSharingPolicyProvider.REFRESH_TIMEOUT_MS,
        retryDelayMs: Long = VoiceSharingPolicyProvider.RETRY_DELAY_MS,
        wallClockMs: () -> Long = { wallNowMs },
    ): VoiceSharingPolicyProvider {
        val provider = VoiceSharingPolicyProvider(
            remoteConfig = config,
            featureProvider = FirebaseRemoteFeatureProvider(config, featureScope),
            scope = scope,
            nowMs = { monotonicNowMs },
            sleepMs = sleepMs,
            refreshTimeoutMs = refreshTimeoutMs,
            retryDelayMs = retryDelayMs,
            wallClockMs = wallClockMs,
            sharedCollectionLock = testSharedLock,
        )
        // Run the lifecycle init's bounded join to completion on the virtual scheduler.
        dispatcher.scheduler.advanceTimeBy(VoiceSharingPolicyProvider.INIT_AWAIT_MS + 1)
        dispatcher.scheduler.runCurrent()
        return provider
    }

    /** Drives one explicit refresh beyond the lifecycle's own init refresh. */
    private fun refresh(provider: VoiceSharingPolicyProvider, advances: Boolean = true) {
        fetchAdvancesBookkeeping = advances
        runBlocking { provider.refresh() }
        fetchAdvancesBookkeeping = true
    }

    @Test
    fun `lease expiry disables then renews itself - consent surface survives without a capture driver`() {
        // On-device evidence (2026-11-03 16:02:46 → 16:17:46): the policy enabled at init decayed
        // EXACTLY at LEASE_MS and silently disabled, because before the first consented capture
        // nothing calls refresh() — the consent surface could never survive long enough to be
        // used. The expiry wake must disable AND drive a renewal; a healthy fetch re-enables.
        val provider = provider()
        assertTrue(provider.snapshot().enabled)

        val seen = mutableListOf<String>()
        runBlocking {
            provider.policy.test {
                seen += if (awaitItem().enabled) "enabled" else "disabled"
                monotonicNowMs += VoiceSharingPolicyProvider.DEFAULT_LEASE_MS + 1
                dispatcher.scheduler.advanceTimeBy(VoiceSharingPolicyProvider.DEFAULT_LEASE_MS + 500)
                dispatcher.scheduler.runCurrent()
                seen += if (awaitItem().enabled) "enabled" else "disabled" // expiry disable
                seen += if (awaitItem().enabled) "enabled" else "disabled" // renewal re-enables
                cancelAndIgnoreRemainingEvents()
            }
        }
        org.junit.Assert.assertEquals(listOf("enabled", "disabled", "enabled"), seen)
        assertTrue(provider.snapshot().enabled)
    }

    @Test
    fun `account first becoming readable drives ONE extra refresh - cold start after login`() {
        // @spec 69630b93: on Android the launch exchange runs from onCreate, so a cold start that
        // follows a login can run it BEFORE the account is readable. Nothing then re-ran it and the
        // lease stayed disabled with no capture driver. First readability must trigger exactly one
        // extra refresh; repeated signals must not spin, and a null snapshot is "not readable yet".
        val provider = provider()
        assertTrue(provider.snapshot().enabled)
        val baselineFetches = fetchCount
        runBlocking {
            assertFalse("not readable yet must not refresh", provider.refreshOnAccountReadable(null, 0L))
            assertEquals(baselineFetches, fetchCount)

            assertTrue("first readability refreshes", provider.refreshOnAccountReadable("merlinran", 1L))
            assertEquals(baselineFetches + 1, fetchCount)

            assertFalse("a repeat signal does NOT refresh again", provider.refreshOnAccountReadable("merlinran", 1L))
            assertFalse("and neither does a third", provider.refreshOnAccountReadable("merlinran", 1L))
            assertEquals(baselineFetches + 1, fetchCount)
        }
    }

    @Test
    fun `readability refresh is identity-scoped - new session for the SAME account refreshes too`() {
        // @spec f5575767 + reviewer 4ce3ba23: dedupe repeats for the SAME owner identity, but never
        // suppress a refresh after a real account OR session change. A name-only key is wrong:
        // signing out and back into the same account, and a same-account session rotation, are both
        // NEW authority for which policy must be reacquired.
        val provider = provider()
        val baselineFetches = fetchCount
        runBlocking {
            assertTrue(provider.refreshOnAccountReadable("account-a", 1L))
            assertEquals(baselineFetches + 1, fetchCount)

            assertFalse("same owner identity again: no extra refresh", provider.refreshOnAccountReadable("account-a", 1L))
            assertEquals(baselineFetches + 1, fetchCount)

            assertTrue("a DIFFERENT account is a new owner", provider.refreshOnAccountReadable("account-b", 1L))
            assertEquals(baselineFetches + 2, fetchCount)

            assertTrue("SAME account, NEW session: still a new owner", provider.refreshOnAccountReadable("account-b", 2L))
            assertEquals(baselineFetches + 3, fetchCount)

            assertTrue("sign-out then back into the same account and session: refreshes again", provider.refreshOnAccountReadable("account-a", 1L))
            assertEquals(baselineFetches + 4, fetchCount)

            assertFalse("dedupe resumes for that identity", provider.refreshOnAccountReadable("account-a", 1L))
            assertEquals(baselineFetches + 4, fetchCount)
        }
    }

    @Test
    fun `every refresh requests an unthrottled fetch (interval 0) - freshness gate is satisfiable`() {
        // On-device evidence (2026-11-03): FirebaseRemoteConfig.fetch() (default interval)
        // THROTTLED on repeated refreshes - lastFetchStatus != SUCCESS and fetchTimeMillis never
        // advanced - so the provider's freshness gate disabled every time and the consent
        // surface never appeared. The provider must therefore always request interval 0 (a real
        // network fetch), never the throttled default.
        val config = config
        val provider = provider()
        refresh(provider)
        refresh(provider)
        org.mockito.Mockito.verify(config, org.mockito.Mockito.atLeast(2)).fetch(0L)
        assertTrue(provider.snapshot().enabled)
    }

    @Test
    fun `enabled when the lifecycle init performs a genuine advancing fetch with strict remote value`() {
        val provider = provider() // init observes the real provider's completed fetch+activation
        assertTrue(provider.snapshot().enabled)
    }

    @Test
    fun `disabled when the lifecycle's first genuine refresh fails - cached value nonauthoritative`() {
        fetchOutcome = null
        val provider = provider()
        assertFalse(provider.snapshot().enabled)
    }

    @Test
    fun `remote false disables even when the refresh is genuinely fresh`() {
        rawValue = "false"
        val provider = provider()
        assertFalse(provider.snapshot().enabled)
    }

    @Test
    fun `non-remote value source disables`() {
        valueSource = FirebaseRemoteConfig.VALUE_SOURCE_DEFAULT
        val provider = provider()
        assertFalse(provider.snapshot().enabled)
    }

    @Test
    fun `activation failure disables even when fetch succeeded`() {
        activateOutcome = null
        val provider = provider()
        assertFalse(provider.snapshot().enabled)
    }

    @Test
    fun `first genuine attempt over preexisting cached success with unchanged bookkeeping disables`() {
        // The SDK already fetched (SUCCESS, timestamp T) BEFORE this process's first attempt, and
        // that first attempt's fetch does not move the timestamp. No provider() refresh ran with
        // advancing bookkeeping beforehand: the lifecycle init IS the first genuine attempt.
        fetchAdvancesBookkeeping = false
        val provider = provider()
        assertFalse(provider.snapshot().enabled)
    }

    @Test
    fun `unreadable pre-fetch baseline fails closed even when the fetch itself later succeeds`() {
        // ONLY the FIRST SDK bookkeeping read throws; afterwards the SDK reports valid UNCHANGED
        // cached bookkeeping with a REMOTE true. Install before the lifecycle runs so the FIRST
        // reader is the lifecycle init's own first genuine refresh.
        throwFirstInfoRead = true
        restubWithFetchGate(null)
        val provider = provider()
        // Baseline unreadable at the first refresh: freshness unprovable — fail closed.
        assertFalse(provider.snapshot().enabled)
        // A later refresh over the now-readable but UNCHANGED cached bookkeeping also disables:
        // cached success never enables.
        refresh(provider, advances = false)
        assertFalse(provider.snapshot().enabled)
    }

    @Test
    fun `real expiry reached before an in-flight renewal succeeds disables until the renewal publishes`() {
        val provider = provider(refreshTimeoutMs = 10 * VoiceSharingPolicyProvider.DEFAULT_LEASE_MS)
        assertTrue(provider.snapshot().enabled) // init: enabled, timer A due at the init lease

        val fetchGate = JvmTaskSource<Boolean>()
        whenever(config.fetch(0L)).thenAnswer { fetchGate.task } // park renewal B inside its fetch

        val seen = mutableListOf<String>()
        runBlocking {
            provider.policy.test {
                seen += if (awaitItem().enabled) "enabled" else "disabled"
                // Renewal B allocates generation 2, then parks inside its fetch await.
                scope.launch { provider.refresh() }
                // A's REAL expiry passes while B is still in flight: an in-flight renewal is not
                // a fresh policy, so the subscriber MUST see disabled until B publishes.
                monotonicNowMs += VoiceSharingPolicyProvider.DEFAULT_LEASE_MS + 1
                dispatcher.scheduler.advanceTimeBy(VoiceSharingPolicyProvider.DEFAULT_LEASE_MS + 1)
                dispatcher.scheduler.runCurrent()
                seen += if (awaitItem().enabled) "enabled" else "disabled"
                // B then succeeds: bookkeeping advances, activation succeeds → fresh lease.
                fetchTimeMs += 1_000
                fetchGate.trySetResult(true)
                seen += if (awaitItem().enabled) "enabled" else "disabled"
                cancelAndIgnoreRemainingEvents()
            }
        }
        org.junit.Assert.assertEquals(listOf("enabled", "disabled", "enabled"), seen)
    }

    @Test
    fun `hung renewal never fabricates a fresh lease - disabled persists past the whole-refresh deadline`() {
        // The lifecycle init must SUCCEED first (enabled); only the explicit renewal hangs.
        val provider = provider(refreshTimeoutMs = 500)
        assertTrue(provider.snapshot().enabled)
        val fetchGate = JvmTaskSource<Boolean>()
        whenever(config.fetch(0L)).thenAnswer { fetchGate.task } // renewal B never completes

        val seen = mutableListOf<String>()
        runBlocking {
            provider.policy.test {
                seen += if (awaitItem().enabled) "enabled" else "disabled"
                scope.launch { provider.refresh() } // hangs, then hits the whole-refresh deadline
                monotonicNowMs += VoiceSharingPolicyProvider.DEFAULT_LEASE_MS + 1
                dispatcher.scheduler.advanceTimeBy(VoiceSharingPolicyProvider.DEFAULT_LEASE_MS + 500)
                dispatcher.scheduler.runCurrent()
                seen += if (awaitItem().enabled) "enabled" else "disabled"
                // No refresh ever succeeded: the policy must STAY disabled — no further emissions.
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }
        org.junit.Assert.assertEquals(listOf("enabled", "disabled"), seen)
    }

    @Test
    fun `no disable between a renewal's install and its own deadline`() {
        val provider = provider()
        assertTrue(provider.snapshot().enabled) // init enabled; old timer A due at the init lease

        val seen = mutableListOf<String>()
        runBlocking {
            provider.policy.test {
                seen += if (awaitItem().enabled) "enabled" else "disabled"
                // Renewal B installs a fresh lease that starts LATER than A's (gen2 → emission).
                monotonicNowMs += 1_000
                refresh(provider)
                seen += if (awaitItem().enabled) "enabled" else "disabled"
                assertTrue(provider.snapshot().enabled)
                // Advance into the window where A's ORIGINAL deadline has passed but B's fresh
                // lease is still valid: a stale wake here must not disable B — no emission.
                monotonicNowMs += VoiceSharingPolicyProvider.DEFAULT_LEASE_MS - 500
                dispatcher.scheduler.advanceTimeBy(VoiceSharingPolicyProvider.DEFAULT_LEASE_MS)
                dispatcher.scheduler.runCurrent()
                expectNoEvents()
                assertTrue(provider.snapshot().enabled) // B still fresh
                // B's own deadline: the only disable the subscriber may see.
                monotonicNowMs += 1_000
                dispatcher.scheduler.advanceTimeBy(1_000)
                dispatcher.scheduler.runCurrent()
                seen += if (awaitItem().enabled) "enabled" else "disabled"
                cancelAndIgnoreRemainingEvents()
            }
        }
        org.junit.Assert.assertEquals(listOf("enabled", "enabled", "disabled"), seen)
    }

    @Test
    fun `pending fetch task hits the whole-refresh deadline and fails closed`() {
        val fetchGate = JvmTaskSource<Boolean>()
        whenever(config.fetch(0L)).thenAnswer { fetchGate.task }
        val provider = provider(refreshTimeoutMs = 50)
        val result = runBlocking { provider.refresh() }
        assertFalse(result.enabled)
        assertFalse(provider.snapshot().enabled)
    }

    @Test
    fun `pending activation task hits the whole-refresh deadline and fails closed`() {
        val activationGate = JvmTaskSource<Boolean>()
        whenever(config.activate()).thenAnswer { activationGate.task }
        val provider = provider(refreshTimeoutMs = 50)
        val result = runBlocking { provider.refresh() }
        assertFalse(result.enabled)
        assertFalse(provider.snapshot().enabled)
    }

    @Test
    fun `cross-thread refresh race on ONE instance - older completion cannot overwrite newer disabled`() {
        val provider = provider(refreshTimeoutMs = 5_000)

        // Refresh A runs on a REAL second thread against the SAME instance and parks inside its
        // fetch on an unresolved task. The lifecycle init refresh already consumed fetch call #1.
        val fetchGate = JvmTaskSource<Boolean>()
        val firstExplicitFetch = java.util.concurrent.atomic.AtomicBoolean(true)
        val aReachedFetch = java.util.concurrent.CountDownLatch(1)
        whenever(config.fetch(0L)).thenAnswer {
            if (firstExplicitFetch.compareAndSet(true, false)) {
                aReachedFetch.countDown()
                fetchGate.task
            } else {
                JvmTask(Result.failure(IllegalStateException("scripted fetch failure")))
            }
        }
        // Releasing A's gate must present A as OTHERWISE VALID AND ENABLING: bookkeeping
        // advances as a side effect of the release, activation succeeds, value is REMOTE true —
        // so ONLY the generation fence stands between A and overwriting B's disabled.
        val aResult = java.util.concurrent.atomic.AtomicReference<PolicySnapshot?>()
        val aError = java.util.concurrent.atomic.AtomicReference<Exception?>()
        val threadA = Thread {
            try {
                aResult.set(runBlocking { provider.refresh() })
            } catch (e: Exception) {
                aError.set(e)
            }
        }
        threadA.start()
        org.junit.Assert.assertTrue("A never reached its fetch", aReachedFetch.await(2, java.util.concurrent.TimeUnit.SECONDS))

        // Refresh B on THIS thread, same instance: fetch fails → B is the newer disabled.
        whenever(config.fetch(0L)).thenAnswer { JvmTask(Result.failure(IllegalStateException("scripted fetch failure"))) }
        val resultB = runBlocking { provider.refresh() }
        assertFalse(resultB.enabled)

        // A's fetch now completes, WITH advancing bookkeeping so A would enable but for the fence.
        fetchTimeMs += 1_000
        rawValue = "true"
        valueSource = FirebaseRemoteConfig.VALUE_SOURCE_REMOTE
        restub()
        fetchGate.trySetResult(true)
        threadA.join(2_000)
        org.junit.Assert.assertFalse("A thread is still alive", threadA.isAlive)
        org.junit.Assert.assertNull("refresh A threw: " + aError.get(), aError.get())
        org.junit.Assert.assertNotNull(aResult.get())
        assertFalse(provider.snapshot().enabled)
    }

    @Test
    fun `DI identity control - composed provider shares the EXACT owner primitive`() {
        // Reviewer 23cae70e b1: the REAL composition path must serialize policy on the SAME
        // object as the account tier — an independent default lock is a silent no-op fence.
        val snap = au.com.shiftyjelly.pocketcasts.repositories.sync.AuthoritySnapshot(null, 0L, 0L, null)
        val ownerLock = Any()
        val ownership = object : au.com.shiftyjelly.pocketcasts.repositories.sync.CredentialOwnershipOwner {
            override val collectionSharedLock = ownerLock
            override fun authoritySnapshot() = snap
            override fun sessionGeneration(): Long = 0L

            // The mirror-clear exchange class (reviewer 82a9958d): credential lock only.
            override fun <T> credentialTransaction(block: () -> T): T = synchronized(collectionSharedLock) { block() }
            override fun <T> transaction(block: () -> T): T = synchronized(ownerLock) { block() }
            override fun installOwnershipBinding(binding: au.com.shiftyjelly.pocketcasts.repositories.sync.CredentialOwnershipBinding?) = snap
            override fun beginRefresh(observed: au.com.shiftyjelly.pocketcasts.repositories.sync.AuthoritySnapshot): au.com.shiftyjelly.pocketcasts.repositories.sync.RefreshLease? = null
            override fun publishRefresh(lease: au.com.shiftyjelly.pocketcasts.repositories.sync.RefreshLease, refreshToken: au.com.shiftyjelly.pocketcasts.preferences.RefreshToken, accessToken: au.com.shiftyjelly.pocketcasts.preferences.AccessToken) = false
            override fun invalidateIfCurrent(observed: au.com.shiftyjelly.pocketcasts.repositories.sync.AuthoritySnapshot, transaction: () -> Boolean) = false
            override fun reconcileExternalState() = snap
            override fun publishCredentials(observed: au.com.shiftyjelly.pocketcasts.repositories.sync.AuthoritySnapshot, refreshToken: au.com.shiftyjelly.pocketcasts.preferences.RefreshToken, accessToken: au.com.shiftyjelly.pocketcasts.preferences.AccessToken) = false
        }

        // The composed provider's init LAUNCHES a refresh, and the module hardcodes
        // nowMs = SystemClock.elapsedRealtime() - not mocked on the JVM. Scope the composition to
        // a dispatcher this test never advances so the refresh body deterministically never runs;
        // these controls prove DI IDENTITY wiring, not refresh behavior. A shared/advanced
        // scheduler made the SystemClock throw escape as an uncaught exception attributed to
        // whichever runTest ran next (order-dependent suite flake, probe-captured).
        val composedScope = CoroutineScope(kotlinx.coroutines.test.StandardTestDispatcher())
        val composed = VoiceSharingPolicyModule.provideVoiceSharingPolicyProvider(
            remoteConfig = config,
            featureProvider = FirebaseRemoteFeatureProvider(config, CoroutineScope(kotlinx.coroutines.test.StandardTestDispatcher())),
            ownership = ownership,
            scope = composedScope,
        )
        // Identity chain: owner primitive === module argument === provider field (reflection on
        // the ACTUAL composed object — proves the module wired the EXACT lock, not a default).
        org.junit.Assert.assertSame(ownerLock, ownership.collectionSharedLock)
        val field = VoiceSharingPolicyProvider::class.java.getDeclaredField("sharedCollectionLock")
        field.isAccessible = true
        org.junit.Assert.assertSame(ownerLock, field.get(composed))
        // Contention on the provider's OWN fenced primitives (reviewer 23cae70e b1): production
        // publication takes sharedCollectionLock THEN the internal generation fence. Drive that
        // exact nested acquisition under the held owner lock; then prove the reverse order
        // (fence-first) inverts against it — the single-lock composition cannot deadlock because
        // both lanes enter sharedCollectionLock first.
        val gate = java.util.concurrent.CountDownLatch(1)
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val f = VoiceSharingPolicyProvider::class.java.getDeclaredField("fence")
        f.isAccessible = true
        val innerFence = f.get(composed) ?: error("provider fence missing")
        val holderThread = Thread {
            synchronized(ownerLock) {
                synchronized(innerFence) {
                    gate.countDown()
                    Thread.sleep(500)
                }
            }
        }.apply { start() }
        gate.await(2, java.util.concurrent.TimeUnit.SECONDS)
        val publisher = Thread {
            synchronized(innerFence) {
                synchronized(ownerLock) {
                    finished.set(true) // INVERSION: only reachable if the two are NOT one order
                }
            }
        }.apply { start() }
        publisher.join(200)
        org.junit.Assert.assertFalse("fence-first acquisition must block behind shared-first holder", finished.get())
        holderThread.join(3_000)
        publisher.join(3_000)
        org.junit.Assert.assertTrue("publisher proceeds after the shared lock frees", finished.get())
    }

    @Test
    fun `hilt module composes the real provider entry`() {
        val snap = au.com.shiftyjelly.pocketcasts.repositories.sync.AuthoritySnapshot(null, 0L, 0L, null)
        val ownership = object : au.com.shiftyjelly.pocketcasts.repositories.sync.CredentialOwnershipOwner {
            override val collectionSharedLock = testSharedLock
            override fun authoritySnapshot() = snap
            override fun sessionGeneration(): Long = 0L
            override fun <T> transaction(block: () -> T): T = synchronized(testSharedLock) { block() }
            override fun <T> credentialTransaction(block: () -> T): T = synchronized(testSharedLock) { block() }
            override fun installOwnershipBinding(binding: au.com.shiftyjelly.pocketcasts.repositories.sync.CredentialOwnershipBinding?) = snap
            override fun beginRefresh(observed: au.com.shiftyjelly.pocketcasts.repositories.sync.AuthoritySnapshot): au.com.shiftyjelly.pocketcasts.repositories.sync.RefreshLease? = null
            override fun publishRefresh(lease: au.com.shiftyjelly.pocketcasts.repositories.sync.RefreshLease, refreshToken: au.com.shiftyjelly.pocketcasts.preferences.RefreshToken, accessToken: au.com.shiftyjelly.pocketcasts.preferences.AccessToken) = false
            override fun invalidateIfCurrent(observed: au.com.shiftyjelly.pocketcasts.repositories.sync.AuthoritySnapshot, transaction: () -> Boolean) = false
            override fun reconcileExternalState() = snap
            override fun publishCredentials(observed: au.com.shiftyjelly.pocketcasts.repositories.sync.AuthoritySnapshot, refreshToken: au.com.shiftyjelly.pocketcasts.preferences.RefreshToken, accessToken: au.com.shiftyjelly.pocketcasts.preferences.AccessToken) = false
        }
        // The composed provider's init LAUNCHES a refresh, and the module hardcodes
        // nowMs = SystemClock.elapsedRealtime() - not mocked on the JVM. Scope the composition to
        // a dispatcher this test never advances so the refresh body deterministically never runs;
        // these controls prove DI IDENTITY wiring, not refresh behavior. A shared/advanced
        // scheduler made the SystemClock throw escape as an uncaught exception attributed to
        // whichever runTest ran next (order-dependent suite flake, probe-captured).
        val composedScope = CoroutineScope(kotlinx.coroutines.test.StandardTestDispatcher())
        val composed = VoiceSharingPolicyModule.provideVoiceSharingPolicyProvider(
            remoteConfig = config,
            featureProvider = FirebaseRemoteFeatureProvider(config, CoroutineScope(kotlinx.coroutines.test.StandardTestDispatcher())),
            ownership = ownership,
            scope = composedScope,
        )
        // The actual composition entry instantiates and owns the fail-closed provider. (The
        // seed state is disabled before any refresh; snapshot() would call SystemClock, which is
        // not mocked on the JVM — the provider's behavior is covered by the other controls.)
        org.junit.Assert.assertNotNull(composed)
        assertFalse(composed.policy.value.enabled)
    }

    @Test
    fun `permissive boolean encodings disable`() {
        for (encoding in listOf("TRUE", "True", "1", "yes", "true ")) {
            rawValue = encoding
            val provider = provider() // init refresh decodes strictly
            assertFalse("encoding <$encoding> must disable", provider.snapshot().enabled)
        }
    }

    @Test
    fun `expired lease disables a plain subscriber without any refresh or read`() {
        val provider = provider() // lifecycle init publishes enabled; schedule parks on real delay
        assertTrue(provider.snapshot().enabled)

        val seen = mutableListOf<Boolean>()
        runBlocking {
            provider.policy.test {
                seen.add(awaitItem().enabled)
                // Advance the monotonic clock AND the virtual scheduler so the parked schedule
                // wakes — no refresh, no value read.
                monotonicNowMs += VoiceSharingPolicyProvider.DEFAULT_LEASE_MS + 1
                dispatcher.scheduler.advanceTimeBy(VoiceSharingPolicyProvider.DEFAULT_LEASE_MS + 1)
                dispatcher.scheduler.runCurrent()
                seen.add(awaitItem().enabled)
                cancelAndIgnoreRemainingEvents()
            }
        }
        assertTrue(seen[0])
        assertFalse(seen[1])
    }

    @Test
    fun `clock-stalled schedule fails closed for a plain subscriber`() {
        // An early-returning sleep that never advances the clock: the schedule must not spin and
        // must not leave a stale enabled snapshot published. (The stall publishes synchronously
        // on this dispatcher, so the subscriber observes the FAIL-CLOSED end state.)
        val provider = provider(sleepMs = {})
        assertFalse(provider.snapshot().enabled)
        runBlocking {
            provider.policy.test {
                assertFalse(awaitItem().enabled)
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun `clock behind the activation stamp is rejected`() {
        val provider = provider()
        assertTrue(provider.snapshot().enabled)
        monotonicNowMs -= 2_000 // behind the activation stamp
        assertFalse(provider.snapshot().enabled)
    }

    @Test
    fun `older refresh cannot overwrite a newer disabled publication`() {
        val provider = provider()

        // Refresh A: fetch succeeds but activation hangs on an unresolved task.
        val activationGate = JvmTaskSource<Boolean>()
        fetchTimeMs += 1_000
        restub()
        whenever(config.activate()).thenAnswer { activationGate.task }
        scope.launch { provider.refresh() }

        // Refresh B starts AFTER A captured its generation; B's fetch fails → newer disabled.
        fetchOutcome = null
        restub()
        runBlocking { provider.refresh() }
        assertFalse(provider.snapshot().enabled)

        // A's activation now completes successfully — the fence must suppress its stale true.
        activationGate.trySetResult(true)
        assertFalse(provider.snapshot().enabled)
    }

    @Test
    fun `a failed refresh after expiry does not re-enable`() {
        val provider = provider()
        assertTrue(provider.snapshot().enabled)

        monotonicNowMs += VoiceSharingPolicyProvider.DEFAULT_LEASE_MS + 1
        fetchOutcome = null
        refresh(provider, advances = false)
        assertFalse(provider.snapshot().enabled)
    }

    @Test
    fun `a failed initial fetch retries with backoff and recovers without an app restart`() {
        // Field evidence (2026-11-04 10:09-14:32 local): a process whose FIRST refresh fails had
        // no retry driver — the lease renewal only arms when enabled — so the lane stayed dark for
        // hours until an app restart. Transient acquisition failures must self-recover: bounded
        // retries with growing backoff, still governed by the ordinary freshness contract.
        fetchOutcome = null
        val provider = provider() // init refresh fails -> transient retry scheduled
        assertFalse(provider.snapshot().enabled)
        val baseline = fetchCount

        dispatcher.scheduler.advanceTimeBy(VoiceSharingPolicyProvider.RETRY_DELAY_MS)
        dispatcher.scheduler.runCurrent()
        assertEquals("the first retry fired after the base delay", baseline + 1, fetchCount)
        assertFalse("still disabled while the fetch keeps failing", provider.snapshot().enabled)

        // Backoff doubled: the next retry must NOT fire at the base interval again.
        dispatcher.scheduler.advanceTimeBy(VoiceSharingPolicyProvider.RETRY_DELAY_MS - 1)
        dispatcher.scheduler.runCurrent()
        assertEquals("no second retry before the doubled delay", baseline + 1, fetchCount)

        // The outage ends mid-backoff: the scheduled retry recovers the policy.
        fetchOutcome = Unit
        dispatcher.scheduler.advanceTimeBy(VoiceSharingPolicyProvider.RETRY_DELAY_MS)
        dispatcher.scheduler.runCurrent()
        assertEquals(baseline + 2, fetchCount)
        assertTrue("recovered WITHOUT an app restart", provider.snapshot().enabled)
    }

    @Test
    fun `an authoritative false answer cancels the retry loop and stays disabled`() {
        // The remote key decoded false / not-enabled is the POLICY's own verdict for this round:
        // retrying it would fight the deployed configuration. No retries may be scheduled.
        rawValue = "false"
        val provider = provider()
        assertFalse(provider.snapshot().enabled)
        val baseline = fetchCount
        dispatcher.scheduler.advanceTimeBy(10 * 60_000)
        dispatcher.scheduler.runCurrent()
        assertEquals("an authoritative answer never retries", baseline, fetchCount)
        assertFalse(provider.snapshot().enabled)
    }

    @Test
    fun `a throttled fetch still recovers through the growing backoff`() {
        // firebase-config 23.1.0 exposes NO machine-readable throttle deadline, so a server-side
        // throttle is respected through the growing backoff: a THROTTLED status keeps the snapshot
        // disabled and the retries keep widening, never spinning.
        fetchStatus = FirebaseRemoteConfig.LAST_FETCH_STATUS_THROTTLED
        fetchAdvancesBookkeeping = false
        val provider = provider(retryDelayMs = 10_000)
        assertFalse(provider.snapshot().enabled)
        val baseline = fetchCount

        dispatcher.scheduler.advanceTimeBy(10_000)
        dispatcher.scheduler.runCurrent()
        assertEquals("the throttled retry fired once at the base delay", baseline + 1, fetchCount)
        assertFalse(provider.snapshot().enabled)

        // Doubled backoff: no retry at the base interval again.
        dispatcher.scheduler.advanceTimeBy(10_000 - 1)
        dispatcher.scheduler.runCurrent()
        assertEquals(baseline + 1, fetchCount)

        // The throttle clears: the next scheduled retry recovers.
        fetchStatus = FirebaseRemoteConfig.LAST_FETCH_STATUS_SUCCESS
        fetchAdvancesBookkeeping = true
        dispatcher.scheduler.advanceTimeBy(10_000)
        dispatcher.scheduler.runCurrent()
        assertTrue("recovered after the throttle cleared", provider.snapshot().enabled)
    }

    @Test
    fun `a typed server throttle defers the retry to the server boundary - even beyond the backoff cap`() {
        // reviewer 49c23211 b1: firebase-config 23.1.0 DOES carry the throttle deadline on the
        // typed FirebaseRemoteConfigFetchThrottledException. A deadline beyond the 15-minute
        // backoff cap must still be honored: the cap bounds LOCAL backoff growth, not the
        // server's own boundary.
        fetchThrottleDeadlineMs = wallNowMs + 20 * 60_000 // 20 minutes > MAX_RETRY_BACKOFF_MS
        fetchOutcome = null
        val provider = provider(retryDelayMs = 10_000)
        assertFalse(provider.snapshot().enabled)
        val baseline = fetchCount

        // 15 minutes pass (the whole backoff cap): still inside the server's boundary — no retry.
        dispatcher.scheduler.advanceTimeBy(15 * 60_000)
        dispatcher.scheduler.runCurrent()
        assertEquals("the backoff cap does not override the server boundary", baseline, fetchCount)

        // The wall clock passes the server boundary: the retry fires.
        wallNowMs += 5 * 60_000
        dispatcher.scheduler.advanceTimeBy(5 * 60_000 + 1)
        dispatcher.scheduler.runCurrent()
        assertTrue("the retry fires once the server boundary passes", fetchCount > baseline)
    }

    @Test
    fun `a stale failure cannot schedule retries over a newer authoritative answer`() {
        // reviewer 49c23211 b2: retry ownership is fenced WITH publication. An older failed
        // refresh (its retry already scheduled) must not re-schedule or move the backoff once a
        // newer AUTHORITATIVE answer has published.
        fetchOutcome = null
        val provider = provider()
        assertFalse(provider.snapshot().enabled)
        val afterStaleFailure = fetchCount

        fetchOutcome = Unit
        rawValue = "false"
        refresh(provider) // newer authoritative false cancels the pending retry, resets backoff
        val afterNewerAnswer = fetchCount

        dispatcher.scheduler.advanceTimeBy(10 * 60_000)
        dispatcher.scheduler.runCurrent()
        assertEquals("no retry fires after the newer answer governed the timer", afterNewerAnswer, fetchCount)
        assertFalse(provider.snapshot().enabled)
    }

    @Test
    fun `a pending retry does not fire over a newer in-flight refresh`() {
        // reviewer 49c23211 b2: a newer refresh does not (and must not) silently leave the old
        // retry free to run; the retry validates its CAPTURED generation before it refreshes, so
        // it skips while a newer request is still in flight.
        fetchOutcome = null
        // A generous refresh timeout: gen2 must still be IN FLIGHT (not deadline-disabled) when
        // the stale retry fires.
        val provider = provider(retryDelayMs = 10_000, refreshTimeoutMs = 10 * 60_000)
        assertFalse(provider.snapshot().enabled)
        val afterStaleFailure = fetchCount

        fetchOutcome = Unit
        fetchTimeMs += 1_000
        restub()
        val activationGate = JvmTaskSource<Boolean>()
        whenever(config.activate()).thenAnswer { activationGate.task }
        scope.launch { provider.refresh() } // newer request (gen2) hangs in activation
        dispatcher.scheduler.runCurrent() // gen2 captures its generation and fetches (activation pending)
        val afterGen2Fetch = fetchCount

        dispatcher.scheduler.advanceTimeBy(10_000)
        dispatcher.scheduler.runCurrent()

        assertEquals(
            "the stale retry must not refresh over the in-flight newer request",
            afterGen2Fetch,
            fetchCount,
        )
        activationGate.trySetResult(true)
        dispatcher.scheduler.runCurrent() // gen2's activation resumption publishes enabled
        assertTrue(provider.snapshot().enabled)

        // After the newer request PUBLISHED enabled, the stale retry must never fire either.
        dispatcher.scheduler.advanceTimeBy(10_000)
        dispatcher.scheduler.runCurrent()
        assertTrue(provider.snapshot().enabled)
    }

    @Test
    fun `a retry that claims first cannot supersede a newer manual refresh that starts after it`() {
        // reviewer 7bdc0d3b: the OLD code checked the captured generation and claimed the next
        // one in two separate fence acquisitions — a manual refresh starting in that gap made the
        // stale retry newest, and it superseded the manual request. The claim is now atomic with
        // the check; this control gates the ordering where the retry WINS the claim: the later
        // manual refresh becomes newest, supersedes the retry, and its enabled answer is final.
        fetchOutcome = null
        val provider = provider(retryDelayMs = 10_000) // stale failure schedules retry (gen1)
        assertFalse(provider.snapshot().enabled)
        val afterStaleFailure = fetchCount

        // The retry fires and claims the next generation while failing transiently.
        dispatcher.scheduler.advanceTimeBy(10_000)
        dispatcher.scheduler.runCurrent()
        assertEquals("the retry claimed and executed", afterStaleFailure + 1, fetchCount)
        assertFalse(provider.snapshot().enabled)

        // A manual refresh starts AFTER the retry's claim: it becomes newest and wins.
        fetchOutcome = Unit
        refresh(provider)
        assertTrue(provider.snapshot().enabled)
        val afterManual = fetchCount

        // The retry's own next schedule (from its transient failure) is now stale: it must not
        // fire over the manual request's publication. The lease expiry in this window disables
        // and renews (monotonic clock advanced, so the no-progress guard permits the renewal);
        // the final state is the renewed ENABLED policy — no stale retry ever superseded it.
        monotonicNowMs += 15 * 60_000
        dispatcher.scheduler.advanceTimeBy(15 * 60_000)
        dispatcher.scheduler.runCurrent()
        assertTrue(provider.snapshot().enabled)
    }

    @Test
    fun `an old in-flight failure publishing after a newer authoritative answer schedules no retry`() {
        // reviewer 7bdc0d3b: the named stale-failure control completed the failure BEFORE the
        // newer answer; this one holds an older refresh IN FLIGHT across the newer authoritative
        // false, then lets its failure publish afterward — the fenced-out failure must not
        // schedule any retry.
        val fetchGate = JvmTaskSource<Unit>()
        restubWithFetchGate(fetchGate.task)
        val provider = provider()
        scope.launch { provider.refresh() } // gen1 hangs in fetch
        dispatcher.scheduler.runCurrent()
        val afterStaleStart = fetchCount

        // A newer authoritative false publishes while gen1 is still in flight. The fetch gate
        // is cleared so ONLY gen1 stays in flight — gen2 completes as a real authoritative
        // answer rather than its own deadline failure.
        fetchOutcome = Unit
        rawValue = "false"
        restubWithFetchGate(null)
        refresh(provider)
        assertFalse(provider.snapshot().enabled)
        val afterNewerAnswer = fetchCount

        // The stale failure now completes: fenced out, and no retry may be scheduled — the
        // fetch count must stay EXACTLY where the newer answer left it (reviewer 4fa5210c: a
        // re-fetch of another authoritative false would otherwise masquerade as "no retry").
        fetchGate.trySetException(IllegalStateException("stale fetch failure"))
        dispatcher.scheduler.runCurrent()
        assertEquals("the stale failure must not fetch", afterNewerAnswer, fetchCount)
        dispatcher.scheduler.advanceTimeBy(15 * 60_000)
        dispatcher.scheduler.runCurrent()
        assertEquals("and no retry fires across the backoff horizon", afterNewerAnswer, fetchCount)
        assertFalse(provider.snapshot().enabled)
    }
}
