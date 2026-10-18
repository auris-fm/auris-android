package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy

import android.os.SystemClock
import au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
import au.com.shiftyjelly.pocketcasts.utils.featureflag.providers.FirebaseRemoteFeatureProvider
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay

/**
 * Real composition entry for the voice-sharing policy (reviewer 40ff55d1 blocker 1): this Hilt
 * module OWNS the provider's lifecycle wiring — the application-scope coroutine context, the
 * real monotonic clock, the real delay-based sleep, and the whole-refresh deadline are bound
 * here, at the same seam the rest of the app's singletons are composed. Constructing the
 * provider in a test file is not composition; binding it here and testing THIS entry is.
 *
 * The binding does not enable collection: it only instantiates the fail-closed provider, whose
 * lifecycle init observes the real Firebase adapter sequence. Enabling collection additionally
 * requires the account projection and enrollment (later plan tasks) composed at the
 * VoiceSharingController gate.
 */
@Module
@InstallIn(SingletonComponent::class)
object VoiceSharingPolicyModule {

    @Provides
    @Singleton
    fun provideVoiceSharingPolicyProvider(
        remoteConfig: FirebaseRemoteConfig,
        featureProvider: FirebaseRemoteFeatureProvider,
        @ApplicationScope scope: CoroutineScope,
    ): VoiceSharingPolicyProvider = VoiceSharingPolicyProvider(
        remoteConfig = remoteConfig,
        featureProvider = featureProvider,
        scope = scope,
        nowMs = { SystemClock.elapsedRealtime() },
        sleepMs = { delay(it) },
        refreshTimeoutMs = VoiceSharingPolicyProvider.REFRESH_TIMEOUT_MS,
    )
}
