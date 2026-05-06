package au.com.shiftyjelly.pocketcasts.voice.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

@AndroidEntryPoint
class VoiceControlService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.i("Voice control service started")
        return START_STICKY
    }

    override fun onDestroy() {
        Timber.i("Voice control service stopped")
        super.onDestroy()
    }
}
