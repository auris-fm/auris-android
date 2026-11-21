package au.com.shiftyjelly.pocketcasts.voicecontrol.gate

import android.media.AudioManager
import android.os.Build
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.mockito.kotlin.doAnswer

/**
 * The `NotOnCall` gate used to read call state from `TelephonyManager`, which needs
 * `READ_PHONE_STATE` — a permission the app does not hold — so the listener failed with
 * "permission not granted" and the gate defaulted to Allowed even during a call.
 * Detection now comes from `AudioManager`'s mode (`MODE_IN_CALL` / `MODE_IN_COMMUNICATION`)
 * and its mode-change listener, which needs no permission.
 */
class AudioModeCallWatcherTest {
    private val audioManager = mock<AudioManager>()
    private val executor = Executor { it.run() }
    private val calls = mutableListOf<Boolean>()

    private fun watcher(initialMode: Int = AudioManager.MODE_NORMAL): AudioModeCallWatcher {
        whenever(audioManager.mode).thenReturn(initialMode)
        return AudioModeCallWatcher(audioManager, executor) { calls.add(it) }
    }

    private fun registeredListener(): AudioManager.OnModeChangedListener {
        val captor = ArgumentCaptor.forClass(AudioManager.OnModeChangedListener::class.java)
        verify(audioManager).addOnModeChangedListener(any<Executor>(), captor.capture())
        return captor.value
    }

    @Test
    fun `a call already in progress is reported at start`() {
        watcher(initialMode = AudioManager.MODE_IN_CALL).start()

        assertEquals(listOf(true), calls)
    }

    @Test
    fun `every call-owning audio mode reports a call and ringing does not`() {
        assertTrue(AudioModeCallWatcher.isCall(AudioManager.MODE_IN_CALL))
        assertTrue(AudioModeCallWatcher.isCall(AudioManager.MODE_IN_COMMUNICATION))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            assertTrue(AudioModeCallWatcher.isCall(AudioManager.MODE_CALL_SCREENING))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            assertTrue(AudioModeCallWatcher.isCall(AudioManager.MODE_CALL_REDIRECT))
            assertTrue(AudioModeCallWatcher.isCall(AudioManager.MODE_COMMUNICATION_REDIRECT))
        }
        assertFalse(AudioModeCallWatcher.isCall(AudioManager.MODE_RINGTONE))
        assertFalse(AudioModeCallWatcher.isCall(AudioManager.MODE_NORMAL))
        assertFalse(AudioModeCallWatcher.isCall(AudioManager.MODE_INVALID))
    }

    @Test
    fun `a call starting blocks and a call ending allows`() {
        val w = watcher()
        w.start()
        assertEquals(listOf(false), calls)
        val listener = registeredListener()

        listener.onModeChanged(AudioManager.MODE_IN_CALL)
        listener.onModeChanged(AudioManager.MODE_IN_COMMUNICATION)
        listener.onModeChanged(AudioManager.MODE_NORMAL)

        assertEquals(listOf(false, true, true, false), calls)
    }

    @Test
    fun `mode changes that are not calls never flip the gate to Blocked`() {
        val w = watcher()
        w.start()
        val listener = registeredListener()

        listener.onModeChanged(AudioManager.MODE_NORMAL)
        listener.onModeChanged(AudioManager.MODE_RINGTONE)
        listener.onModeChanged(AudioManager.MODE_NORMAL)

        assertEquals(listOf(false, false, false, false), calls)
        assertFalse(calls.any { it })
    }

    @Test
    fun `the listener is registered before the mode is sampled`() {
        watcher().start()

        val inOrder = org.mockito.Mockito.inOrder(audioManager)
        inOrder.verify(audioManager).addOnModeChangedListener(any<Executor>(), any())
        inOrder.verify(audioManager).mode
    }

    @Test
    fun `a call starting before the initial sample runs is not lost`() {
        val queued = ArrayDeque<Runnable>()
        val queuingExecutor = Executor { queued.add(it) }
        whenever(audioManager.mode).thenReturn(AudioManager.MODE_NORMAL)
        val w = AudioModeCallWatcher(audioManager, queuingExecutor) { calls.add(it) }

        w.start()
        // A call starts before the executor gets to the initial sample.
        val captor = ArgumentCaptor.forClass(AudioManager.OnModeChangedListener::class.java)
        verify(audioManager).addOnModeChangedListener(any<Executor>(), captor.capture())
        captor.value.onModeChanged(AudioManager.MODE_IN_CALL)
        whenever(audioManager.mode).thenReturn(AudioManager.MODE_IN_CALL)
        queued.removeFirst().run() // the initial sample runs after the call event

        assertEquals(listOf(true, true), calls)
        assertFalse(calls.contains(false))
    }

    @Test
    fun `a stale callback arriving after start re-reads nothing the watcher owns`() {
        // The listener is the sole input after start; the watcher must not query TelephonyManager
        // (it would throw SecurityException without READ_PHONE_STATE).
        val w = watcher()
        w.start()
        val listener = registeredListener()

        listener.onModeChanged(AudioManager.MODE_IN_CALL)

        assertTrue(calls.last())
        verify(audioManager, org.mockito.Mockito.times(1)).mode // initial read only
    }
}
