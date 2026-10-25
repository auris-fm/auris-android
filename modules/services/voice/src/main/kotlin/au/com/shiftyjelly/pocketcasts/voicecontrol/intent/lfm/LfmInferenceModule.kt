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
 */
@Module
@InstallIn(SingletonComponent::class)
object LfmInferenceModule {
    @Provides
    @Singleton
    internal fun provideLfmInference(): LfmInference = LfmNativeInference
}
