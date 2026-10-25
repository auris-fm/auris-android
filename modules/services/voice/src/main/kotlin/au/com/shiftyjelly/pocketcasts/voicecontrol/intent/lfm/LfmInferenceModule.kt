package au.com.shiftyjelly.pocketcasts.voicecontrol.intent.lfm

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the production inference engine. Kept as its own module so the external-native boundary has
 * exactly one replaceable unit: the JNI touch happens on first use of [LfmNativeInference], which is
 * only reachable through this binding.
 *
 * WHY THE INTERFACE IS PUBLIC, MEASURED (reviewer 6707f03a asked for the actual error, not a
 * location argument): with `internal interface LfmInference`, compilation fails with
 *   LfmInferenceModule.kt:19:9 'public' function exposes its 'internal' return type 'LfmInference'
 *   LfmIntentRouter.kt:37:9 'public' function exposes its 'internal' parameter type 'LfmInference'
 * Making the provider and the @Inject constructor internal as well still fails with
 *   LfmIntentRouter.kt:37:9 'public' function exposes its 'internal' parameter type 'LfmInference'
 * because the CLASS is public, so its public-generated surface cannot take an internal parameter.
 * The remaining options were widening the class's visibility or the interface's; the interface is
 * the smaller surface. [LfmNativeInference] stays internal either way.
 */
@Module
@InstallIn(SingletonComponent::class)
object LfmInferenceModule {
    @Provides
    @Singleton
    fun provideLfmInference(): LfmInference = LfmNativeInference
}
