package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy

import app.cash.turbine.test
import au.com.shiftyjelly.pocketcasts.utils.config.FirebaseConfig
import au.com.shiftyjelly.pocketcasts.utils.featureflag.providers.FirebaseRemoteFeatureProvider
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
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
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

/**
 * Task 1 red control (docs/plans/android/voice-recording-uploads.md, reviewer 3aae64cf's
 * boundary): the voice-sharing policy is fail-closed at EVERY step, and "enabled" requires REAL
 * SDK freshness — a genuinely successful fetch AND activation observed through the actual
 * [FirebaseRemoteFeatureProvider] composition, never a cached config value.
 *
 * The real provider runs against a scripted [FirebaseRemoteConfig] mock, the same seam the
 * existing Firebase-backed tests use, so no test can pass without the provider's own
 * fetch+activation choreography having completed successfully.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(MockitoJUnitRunner.Silent::class)
class VoiceSharingPolicyProviderTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(dispatcher)

    @Mock private lateinit var config: FirebaseRemoteConfig

    private var monotonicNowMs = 1_000L

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    /** True tasks complete immediately on this thread; failure tasks complete with the exception. */
    private fun <T : Any> task(value: T?): Task<T> = if (value == null) {
        Tasks.forException(IllegalStateException("scripted failure"))
    } else {
        Tasks.forResult(value)
    }

    private fun scriptRemote(value: String?) {
        whenever(config.getString(FirebaseConfig.VOICE_SHARING_ENABLED)).thenReturn(value ?: "")
        whenever(config.getBoolean(FirebaseConfig.VOICE_SHARING_ENABLED)).thenReturn(value?.lowercase() == "true")
        whenever(config.fetch()).thenAnswer {
            if (fetchOutcome == null) {
                Tasks.forException<Void>(IllegalStateException("scripted fetch failure"))
            } else {
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

    private var fetchOutcome: Unit? = Unit
    private var activateOutcome: Unit? = Unit

    private fun provider(): VoiceSharingPolicyProvider = VoiceSharingPolicyProvider(
        remoteConfig = config,
        featureProvider = FirebaseRemoteFeatureProvider(config, scope),
        scope = scope,
        nowMs = { monotonicNowMs },
    )

    private fun refresh(provider: VoiceSharingPolicyProvider, fetchOk: Boolean, activateOk: Boolean) {
        fetchOutcome = if (fetchOk) Unit else null
        activateOutcome = if (activateOk) Unit else null
        scriptRemote(remoteValueAtRefresh)
        provider.refreshBlocking()
    }

    private var remoteValueAtRefresh: String? = null

    @Test
    fun `disabled before any successful fetch and activation - cached true never enables`() {
        remoteValueAtRefresh = "true"
        scriptRemote("true")
        val provider = provider()
        assertFalse(provider.snapshot().enabled)
    }

    @Test
    fun `enabled only after a fresh fetch and activation turn the remote true`() {
        remoteValueAtRefresh = "true"
        scriptRemote("true")
        val provider = provider()
        refresh(provider, fetchOk = true, activateOk = true)
        assertTrue(provider.snapshot().enabled)
    }

    @Test
    fun `remote false disables even after fresh fetch and activation`() {
        remoteValueAtRefresh = "false"
        scriptRemote("false")
        val provider = provider()
        refresh(provider, fetchOk = true, activateOk = true)
        assertFalse(provider.snapshot().enabled)
    }

    @Test
    fun `missing value disables`() {
        remoteValueAtRefresh = null
        scriptRemote(null)
        val provider = provider()
        refresh(provider, fetchOk = true, activateOk = true)
        assertFalse(provider.snapshot().enabled)
    }

    @Test
    fun `fetch failure leaves the policy disabled - cached true never enables`() {
        remoteValueAtRefresh = "true"
        scriptRemote("true")
        val provider = provider()
        refresh(provider, fetchOk = false, activateOk = true)
        assertFalse(provider.snapshot().enabled)
    }

    @Test
    fun `activation failure leaves the policy disabled even when fetch succeeded`() {
        remoteValueAtRefresh = "true"
        scriptRemote("true")
        val provider = provider()
        refresh(provider, fetchOk = true, activateOk = false)
        assertFalse(provider.snapshot().enabled)
    }

    @Test
    fun `expired lease disables until a fresh successful refresh`() {
        remoteValueAtRefresh = "true"
        scriptRemote("true")
        val provider = provider()
        refresh(provider, fetchOk = true, activateOk = true)
        assertTrue(provider.snapshot().enabled)

        // The lease window passes with no refresh: whatever is cached must not keep it enabled.
        monotonicNowMs += VoiceSharingPolicyProvider.DEFAULT_LEASE_MS + 1
        assertFalse(provider.snapshot().enabled)

        // A fresh successful refresh re-enables.
        refresh(provider, fetchOk = true, activateOk = true)
        assertTrue(provider.snapshot().enabled)
    }

    @Test
    fun `a failed refresh after expiry does not re-enable`() {
        remoteValueAtRefresh = "true"
        scriptRemote("true")
        val provider = provider()
        refresh(provider, fetchOk = true, activateOk = true)
        monotonicNowMs += VoiceSharingPolicyProvider.DEFAULT_LEASE_MS + 1
        refresh(provider, fetchOk = false, activateOk = true)
        assertFalse(provider.snapshot().enabled)
    }

    @Test
    fun `policy change is observable at the collection boundary`() {
        remoteValueAtRefresh = "false"
        scriptRemote("false")
        val provider = provider()
        scope.launch {
            provider.policy.test {
                assertFalse(awaitItem().enabled)
                remoteValueAtRefresh = "true"
                scriptRemote("true")
                refresh(provider, fetchOk = true, activateOk = true)
                assertTrue(awaitItem().enabled)
                cancelAndIgnoreRemainingEvents()
            }
        }
    }
}
