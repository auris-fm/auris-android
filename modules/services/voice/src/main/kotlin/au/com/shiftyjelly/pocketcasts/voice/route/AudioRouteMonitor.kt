package au.com.shiftyjelly.pocketcasts.voice.route

import kotlinx.coroutines.flow.StateFlow

interface AudioRouteMonitor {
    val route: StateFlow<AudioRoute>
}
