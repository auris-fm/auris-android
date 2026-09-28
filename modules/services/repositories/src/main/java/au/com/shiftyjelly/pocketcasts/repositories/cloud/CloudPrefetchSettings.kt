package au.com.shiftyjelly.pocketcasts.repositories.cloud

import au.com.shiftyjelly.pocketcasts.preferences.Settings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feature flag for the optional playback-start prefetch hint. Default off:
 * the code ships dark and only a configuration change enables it.
 */
interface CloudPrefetchFlag {
    fun isEnabled(): Boolean
}

@Singleton
class CloudPrefetchSettings @Inject constructor(
    private val settings: Settings,
) : CloudPrefetchFlag {
    override fun isEnabled(): Boolean = settings.cloudContextPrefetch.value
}
