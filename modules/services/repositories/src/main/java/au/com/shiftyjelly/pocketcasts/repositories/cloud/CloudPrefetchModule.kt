package au.com.shiftyjelly.pocketcasts.repositories.cloud

import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.CloudConfig
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Bindings for the playback-start prefetch path. The hinter is provided (not
 * bound) because its base URL is resolved per call — the cloud base URL can be
 * repointed between environments at runtime.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class CloudPrefetchModule {

    @Binds
    abstract fun bindPrefetchFlag(impl: CloudPrefetchSettings): CloudPrefetchFlag

    @Binds
    abstract fun bindAurisAccountCredentialProviding(
        impl: AurisSessionCredentialProvider,
    ): AurisAccountCredentialProviding

    companion object {
        /**
         * The credential every cloud call presents.
         *
         * Config-gated so merging this changes nothing today: while cloud
         * routing has no configured base URL the static identity token is used
         * exactly as before. Once the environment values are in place the
         * Auris-issued token provider takes over, resolving the base URL per
         * call so a repointed environment is picked up.
         */
        @Provides
        @Singleton
        fun provideCloudTokenProviding(
            cloudConfig: CloudConfig,
            staticIdentity: CloudStaticIdentityTokenProvider,
            credentialProvider: AurisAccountCredentialProviding,
        ): CloudTokenProviding {
            if (cloudConfig.baseUrl().isBlank()) return staticIdentity
            return AurisTokenProvider(
                clientProvider = {
                    cloudConfig.baseUrl().takeIf { it.isNotBlank() }?.let { AurisAuthClient(it) }
                },
                credentialProvider = credentialProvider,
            )
        }

        @Provides
        @Singleton
        fun providePrefetchHinter(
            cloudConfig: CloudConfig,
            tokenProvider: CloudTokenProviding,
        ): CloudPrefetchHinter = CloudPrefetchClient(
            baseUrlProvider = { cloudConfig.baseUrl() },
            tokenProvider = tokenProvider,
        )
    }
}
