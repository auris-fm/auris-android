package au.com.shiftyjelly.pocketcasts.voicecontrol.route

import javax.inject.Qualifier

/**
 * The scope [AndroidAudioRouteMonitor] runs its debounced route read on. Production binds the
 * application-lifetime scope; a test binds a scope it cancels in teardown.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RouteMonitorScope
