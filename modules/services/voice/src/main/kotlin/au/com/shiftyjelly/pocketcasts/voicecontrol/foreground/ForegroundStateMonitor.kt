package au.com.shiftyjelly.pocketcasts.voicecontrol.foreground

import au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
import au.com.shiftyjelly.pocketcasts.repositories.playback.AppLifecycleProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@Singleton
class ForegroundStateMonitor @Inject constructor(
    private val appLifecycleProvider: AppLifecycleProvider,
    @ApplicationScope private val scope: CoroutineScope,
) {
    val isInForeground: StateFlow<Boolean> = appLifecycleProvider.isInForeground
        .stateIn(scope, SharingStarted.Eagerly, false)
}
