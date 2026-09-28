package au.com.shiftyjelly.pocketcasts.repositories.cloud

import android.content.Context
import androidx.startup.Initializer
import au.com.shiftyjelly.pocketcasts.repositories.di.initializerEntryPoint
import javax.inject.Inject

/** Starts the playback-start prefetch observer at app startup (flag-gated). */
class CloudPrefetchStartupInitializer : Initializer<Unit> {
    @Inject lateinit var observer: CloudPrefetchObserver

    override fun create(context: Context) {
        context.initializerEntryPoint().inject(this)
        observer.start()
    }

    override fun dependencies() = emptyList<Class<out Initializer<*>>>()
}
