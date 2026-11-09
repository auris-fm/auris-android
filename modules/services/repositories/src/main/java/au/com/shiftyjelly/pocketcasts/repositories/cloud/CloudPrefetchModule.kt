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
    abstract fun bindAurisScopedTokenProviding(
        impl: AurisTokenProvider,
    ): AurisScopedTokenProviding

    @Binds
    abstract fun bindPrefetchFlag(impl: CloudPrefetchSettings): CloudPrefetchFlag

    @Binds
    abstract fun bindAurisAccountCredentialProviding(
        impl: AurisSessionCredentialProvider,
    ): AurisAccountCredentialProviding

    companion object {
        /**
         * THE Auris token provider as a SINGLE shared instance.
         *
         * Sharing matters and is not a convenience: the refresh chain is instance state, so two
         * instances would each rotate the refresh token and revoke the other's chain
         * (`refresh_reused`). Callers that must never fall back to the legacy static identity --
         * the Voice Sharing account/allowance/upload path (@spec `5d5b0d7a`) -- inject THIS type
||||||| /tmp/mfb
         * the Voice Sharing account/allowance/upload path ( `5d5b0d7a`) -- inject THIS type
         * rather than the routing interface, so no cutover flag can silently swap the bearer they
         * present for a `user_<uuid>` the Auris-only routes cannot verify.
         */
        @Provides
        @Singleton
        fun provideAurisTokenProvider(
            cloudConfig: CloudConfig,
            credentialProvider: AurisAccountCredentialProviding,
        ): AurisTokenProvider = AurisTokenProvider(
            clientProvider = {
                cloudConfig.baseUrl().takeIf { it.isNotBlank() }?.let(::AurisAuthClient)
            },
            credentialProvider = credentialProvider,
        )

        /**
         * The credential every cloud call presents.
         *
         * Config-gated *per call*: with no configured cutover the static
         * identity token is used exactly as before, and the moment an Auris
         * environment is configured the Auris-issued token takes over — no
         * restart, and no legacy bearer reaching the edge.
         */
        @Provides
        @Singleton
        fun provideCloudTokenProviding(
            cloudConfig: CloudConfig,
            staticIdentity: CloudStaticIdentityTokenProvider,
            aurisTokenProvider: AurisTokenProvider,
        ): CloudTokenProviding = RoutingCloudTokenProviding(
            isAurisActive = { cloudConfig.baseUrl().isNotBlank() },
            auris = aurisTokenProvider,
            legacy = staticIdentity,
        )

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
