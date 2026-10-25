package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import android.content.Context
import android.net.NetworkCapabilities
import au.com.shiftyjelly.pocketcasts.crashlogging.ConnectionStatusProvider
import au.com.shiftyjelly.pocketcasts.crashlogging.di.CrashLoggingModule
import au.com.shiftyjelly.pocketcasts.repositories.playback.AppLifecycleProvider
import au.com.shiftyjelly.pocketcasts.repositories.playback.NetworkConnectionWatcher
import au.com.shiftyjelly.pocketcasts.repositories.playlist.DefaultPlaylistsInitializer
import au.com.shiftyjelly.pocketcasts.utils.di.FirebaseModule
import coil3.ImageLoader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Test-only bindings for the EXTERNAL INFRASTRUCTURE the generated test component aggregates.
 *
 * WHY THESE EXIST. The voice module's Hilt component is shared by every `@EntryPoint` on its
 * classpath, so building the real [VoiceControlService] also assembles playback and crash-logging
 * infrastructure the voice Service never touches. These are the bindings that assembly needs.
 *
 * WHAT THEY ARE, AND ARE NOT. Substitutions at the external-infrastructure boundary: foreground
 * reporting, connectivity, playlist seeding, image loading, Remote Config, encrypted logging. None
 * of them is a service decision. Start, stop, dispatch, settlement, quiescence, teardown ordering,
 * engine job ownership and resource release all remain production code under test.
 *
 * HOW THE SET WAS DERIVED — by measurement, not inference. The generated component's
 * `[Dagger/MissingBinding]` errors were read and supplied one layer at a time until the real service
 * constructed: five missing bindings, then Remote Config, then encrypted logging. Each replacement
 * below is here because removing it produces a concrete, observed failure.
 *
 * REPLACE vs SUPPLY. [FirebaseModule] and [CrashLoggingModule] ship production providers, so they are
 * REPLACED — and note that replacing `CrashLoggingModule` also requires supplying `CrashLogging`
 * ourselves, because uninstalling the module removes its binding as well. [NetworkConnectionWatcher],
 * [AppLifecycleProvider], [ImageLoader], [DefaultPlaylistsInitializer] and [ConnectionStatusProvider]
 * have no provider on this classpath, so they are SUPPLIED. Using `replaces` for a missing binding is
 * a compile error; supplying one that already exists is a duplicate-binding error.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [FirebaseModule::class, CrashLoggingModule::class],
)
object RealServiceReplacements {
    /** Remote Config reads a project config that cannot exist in a JVM test. */
    @Provides
    @Singleton
    fun provideFirebaseRemoteConfig(): com.google.firebase.remoteconfig.FirebaseRemoteConfig = org.mockito.Mockito.mock(com.google.firebase.remoteconfig.FirebaseRemoteConfig::class.java)

    /**
     * Encrypted logging, replaced at its production module.
     *
     * The real implementation builds a Volley queue that needs Apache HTTP, which is an UNDECLARED
     * transitive of `com.automattic:encryptedlogging` and is therefore absent from the JVM unit-test
     * runtime classpath (`NoClassDefFoundError: org/apache/http/StatusLine`). Log transport is
     * infrastructure; nothing about the service lifecycle depends on it.
     */
    @Provides
    @Singleton
    fun provideEncryptedLogging(): com.automattic.encryptedlogging.EncryptedLogging = org.mockito.Mockito.mock(com.automattic.encryptedlogging.EncryptedLogging::class.java)

    /** Uninstalling [CrashLoggingModule] also removes this binding, so it is supplied here. */
    @Provides
    @Singleton
    fun provideCrashLogging(): com.automattic.android.tracks.crashlogging.CrashLogging = org.mockito.Mockito.mock(com.automattic.android.tracks.crashlogging.CrashLogging::class.java)
}

/** Types with no existing provider on this module's classpath. */
@Module
@InstallIn(SingletonComponent::class)
object RealServiceSupplements {
    @Provides
    @Singleton
    fun provideConnectionStatusProvider(): ConnectionStatusProvider = ConnectionStatusProvider { false }

    @Provides
    @Singleton
    fun provideNetworkConnectionWatcher(): NetworkConnectionWatcher = InertNetworkConnectionWatcher()

    @Provides
    @Singleton
    fun provideAppLifecycleProvider(): AppLifecycleProvider = InertAppLifecycleProvider()

    @Provides
    @Singleton
    fun provideImageLoader(@ApplicationContext context: Context): ImageLoader = ImageLoader.Builder(context).build()

    @Provides
    @Singleton
    fun provideDefaultPlaylistsInitializer(): DefaultPlaylistsInitializer = object : DefaultPlaylistsInitializer {
        override suspend fun initialize(force: Boolean) = Unit
    }
}

private class InertNetworkConnectionWatcher : NetworkConnectionWatcher {
    override val networkCapabilities: StateFlow<NetworkCapabilities?> = MutableStateFlow(null)
}

private class InertAppLifecycleProvider : AppLifecycleProvider {
    override val isInForeground: StateFlow<Boolean> = MutableStateFlow(false)
}
