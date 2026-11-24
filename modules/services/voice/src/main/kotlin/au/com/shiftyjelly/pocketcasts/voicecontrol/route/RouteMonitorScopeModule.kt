package au.com.shiftyjelly.pocketcasts.voicecontrol.route

import au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

/**
 * Binds the route monitor's debounced-read scope to the APPLICATION-lifetime scope: the monitor is
 * a process-lifetime singleton (see its lifetime comment). Its own module so a test can replace
 * exactly this binding with a test-lifetime scope, without touching the rest of the voice graph.
 */
@Module
@InstallIn(SingletonComponent::class)
object RouteMonitorScopeModule {
    @Provides
    @Singleton
    @RouteMonitorScope
    fun provideRouteMonitorScope(@ApplicationScope scope: CoroutineScope): CoroutineScope = scope
}
