package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy

import app.cash.turbine.test
import au.com.shiftyjelly.pocketcasts.utils.config.FirebaseConfig
import au.com.shiftyjelly.pocketcasts.utils.featureflag.providers.FirebaseRemoteFeatureProvider
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
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

    private fun restub() {
        val info = mock(FirebaseRemoteConfigInfo::class.java)
        whenever(info.fetchTimeMillis).thenAnswer { fetchTimeMs }
        whenever(info.lastFetchStatus).thenAnswer { fetchStatus }
        whenever(config.info).thenReturn(info)

        val value = mock(FirebaseRemoteConfigValue::class.java)
        whenever(value.asString()).thenAnswer { rawValue ?: "" }
        whenever(value.source).thenAnswer { valueSource }
        whenever(config.getValue(FirebaseConfig.VOICE_SHARING_ENABLED)).thenReturn(value)

        whenever(config.fetch()).thenAnswer {
            if (fetchOutcome == null) {
                Tasks.forException<Void>(IllegalStateException("scripted fetch failure"))
            } else {
                // Advancing bookkeeping is a SIDE EFFECT of the fetch itself: the provider must
                // capture the pre-request timestamp before this runs and observe it move after.
                if (fetchAdvancesBookkeeping) fetchTimeMs += 1_000
                Tasks.forResult<Void>(null)
            }
        }
        whenever(config.activate()).thenAnswer {
            if (activateOutcome == null) {
                Tasks.forException<Boolean>(IllegalStateException("scripted activation failure"))
            } else {
                Tasks.forResult(true)
            }
        }
    }

    private var activateOutcome: Unit? = Unit

    private fun provider(sleepMs: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) }): VoiceSharingPolicyProvider {
        val provider = VoiceSharingPolicyProvider(
            remoteConfig = config,
            featureProvider = FirebaseRemoteFeatureProvider(config, featureScope),
            scope = scope,
            nowMs = { monotonicNowMs },
            sleepMs = sleepMs,
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
    fun `first refresh over preexisting cached success with unchanged bookkeeping disables`() {
        // The SDK already fetched (SUCCESS, timestamp T) and the refresh's fetch does not move it.
        val provider = provider()
        refresh(provider, advances = false)
        assertFalse(provider.snapshot().enabled)
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
        val activationGate = TaskCompletionSource<Boolean>()
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
}
