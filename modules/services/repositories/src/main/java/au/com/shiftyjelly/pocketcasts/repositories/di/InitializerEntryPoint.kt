package au.com.shiftyjelly.pocketcasts.repositories.di

import android.content.Context
import au.com.shiftyjelly.pocketcasts.repositories.cloud.CloudPrefetchStartupInitializer
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Upstream deleted this entry point when it stopped seeding default playlists —
 * that initializer was its only user. The cloud prefetch initializer still needs
 * injection, so the file stays with that inject rather than being taken away
 * with the reason it used to exist.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface InitializerEntryPoint {
    fun inject(initializer: CloudPrefetchStartupInitializer)
}

internal fun Context.initializerEntryPoint() = EntryPointAccessors.fromApplication<InitializerEntryPoint>(this)
