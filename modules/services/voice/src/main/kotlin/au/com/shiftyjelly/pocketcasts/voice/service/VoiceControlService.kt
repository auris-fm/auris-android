package au.com.shiftyjelly.pocketcasts.voice.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import au.com.shiftyjelly.pocketcasts.voice.gate.VoiceControlGate
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import timber.log.Timber

@AndroidEntryPoint
class VoiceControlService : Service() {
    @Inject lateinit var gate: VoiceControlGate

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
