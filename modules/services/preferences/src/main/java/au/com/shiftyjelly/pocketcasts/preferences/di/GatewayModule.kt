package au.com.shiftyjelly.pocketcasts.preferences.di

import au.com.shiftyjelly.pocketcasts.preferences.gateway.GatewayUrlProvider
import au.com.shiftyjelly.pocketcasts.preferences.gateway.SharedPreferencesGatewayUrlProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class GatewayModule {

    @Binds
    @Singleton
    abstract fun bindGatewayUrlProvider(impl: SharedPreferencesGatewayUrlProvider): GatewayUrlProvider
}
