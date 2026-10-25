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
    fun provideFirebaseRemoteConfig(): com.google.firebase.remoteconfig.FirebaseRemoteConfig = // EVERY method gets a type-appropriate default: Task -> completed, primitives -> zero,
        // interfaces -> mock. A bare mock returns null from any unstubbed async method, and
        // production calls `.addOnCompleteListener` on the result -- an NPE that escapes on a
        // Dispatchers.IO worker and is then attributed by `runTest` to whichever test class runs
        // next (the suite-level "uncaught exceptions before the test started" failure). Measured,
        // not hypothetical: bare-mock nulls reproduced as VoiceSharingAccountAuthBindingTest
        // failing only in full-suite runs, one method at a time (fetch, activate,
        // addOnConfigUpdateListener).
        org.mockito.Mockito.mock(
            com.google.firebase.remoteconfig.FirebaseRemoteConfig::class.java,
            org.mockito.Mockito.withSettings().defaultAnswer { invocation ->
                val type = invocation.method.returnType
                when {
                    com.google.android.gms.tasks.Task::class.java.isAssignableFrom(type) ->
                        @Suppress("UNCHECKED_CAST")
                        com.google.android.gms.tasks.Tasks.forResult(null as Any?) as com.google.android.gms.tasks.Task<Any>

                    type == java.lang.Boolean.TYPE -> false

                    type == java.lang.Long.TYPE -> 0L

                    type == java.lang.Integer.TYPE -> 0

                    type.isInterface -> org.mockito.Mockito.mock(type)

                    !java.lang.reflect.Modifier.isFinal(type.modifiers) -> org.mockito.Mockito.mock(type)

                    else -> null
                }
            },
        )

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

/**
 * The EXTERNAL-NATIVE seam replacement (spec 3b9d5053). Replaces ONLY
 * [au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordNativeModule], whose binding is the
 * single JNI-touching adapter in the graph. The inert implementation fails init the way an absent
 * runtime does — `nativeInit` returns false, no throw — so the detector is honestly UNREADY, which
 * is its own production behaviour for a failed model load. Detection policy is untouched; every
 * service decision that follows (settlement, release ownership, teardown ordering) is production
 * code, and the ASR backend seam is NOT replaced: this does not make the engine able to transcribe.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordNativeModule::class],
)
object InertWakeWordNativeReplacements {
    @Provides
    @Singleton
    fun provideWakeWordNative(): au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordNative = object : au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordNative {
        override fun nativeInit(melModel: ByteArray, embedModel: ByteArray, classifierModel: ByteArray, threshold: Float): Boolean = false

        override fun nativeDetect(samples: FloatArray, sampleRateHz: Int, outOffset: FloatArray): Float = -1f

        override fun nativeRelease() = Unit
    }
}

/**
 * The recognizer's EXTERNAL-NATIVE seam replacement (spec 3b9d5053). Replaces ONLY
 * [au.com.shiftyjelly.pocketcasts.voicecontrol.intent.lfm.LfmInferenceModule], whose sole purpose is
 * binding the JNI engine. The inert implementation behaves like a runtime that loads nothing:
 * `load` returns false, `classify`/`generate`/`tokenize` report failure the way the native methods do
 * on an unloaded engine, and `release` is a no-op that CANNOT throw — which is what lets the real
 * service's settled release-count control observe the full release sequence on the JVM. Router policy
 * (mutex, model gating, format checks) is untouched production code.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [au.com.shiftyjelly.pocketcasts.voicecontrol.intent.lfm.LfmInferenceModule::class],
)
object InertLfmInferenceReplacements {
    @Provides
    @Singleton
    fun provideLfmInference(): au.com.shiftyjelly.pocketcasts.voicecontrol.intent.lfm.LfmInference = object : au.com.shiftyjelly.pocketcasts.voicecontrol.intent.lfm.LfmInference {
        override fun lastError(): String = "inert test inference: no native runtime"

        override fun load(modelPath: String, classifierPath: String, labelMapPath: String, nCtx: Int): Boolean = false

        override fun classify(promptTokenIds: IntArray, poolStart: Int, poolEnd: Int): String? = null

        override fun generate(prefill: String, nPredict: Int): String? = null

        override fun tokenize(text: String, addBos: Boolean): IntArray? = null

        override fun reset() = Unit

        override fun release() = Unit
    }
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
