package au.com.shiftyjelly.pocketcasts.voice.service

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceControlServiceController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun start() {
        context.startService(Intent(context, VoiceControlService::class.java))
    }

    fun stop() {
        context.stopService(Intent(context, VoiceControlService::class.java))
    }
}
