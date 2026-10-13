@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package au.com.shiftyjelly.pocketcasts.voicecontrol.route

import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Route acquisition is what moves a Bluetooth candidate (`BluetoothA2dpOnly`) to an
 * `Isolated` route: it opens SCO / the BLE communication device, opens the input stream
 * without capturing, and only starts it once the stream reports an external headset
 * microphone. Availability is never selection.
 */
class RouteAcquisitionTest {

    private class FakePlatform : RouteAcquisitionPlatform {
        val candidates = mutableListOf<BluetoothCandidate>()

        var scoOn = false
        var scoStartCalls = 0
        var scoCalls = 0
        var communicationDeviceId: Int? = null
        val communicationDeviceCalls = mutableListOf<Int>()
        var clearCommunicationDeviceCalls = 0

        var openedDeviceId: Int = -1
        var openCalls = 0
        var startCalls = 0
        var streamStartResult = true
        var closeCalls = 0
        val deviceTypes = mutableMapOf<Int, Int>()
        var currentAudioMode = AudioManager.MODE_IN_COMMUNICATION
        val modeSets = mutableListOf<Int>()

        /** Ordered record of routing / mode calls, so ordering can be asserted. */
        val events = mutableListOf<String>()

        fun interactions(): Int = scoStartCalls + scoCalls + communicationDeviceCalls.size +
            clearCommunicationDeviceCalls + openCalls + startCalls + closeCalls

        override fun bluetoothCandidates(): List<BluetoothCandidate> = candidates

        override fun startBluetoothSco() {
            scoStartCalls++
            events += "startSco"
        }

        override fun stopBluetoothSco() {
            scoCalls++
            scoOn = false
        }

        override fun isBluetoothScoOn(): Boolean = scoOn

        override fun setCommunicationDevice(deviceId: Int): Boolean {
            communicationDeviceCalls += deviceId
            return true
        }

        override fun communicationDeviceId(): Int? = communicationDeviceId

        override fun clearCommunicationDevice() {
            clearCommunicationDeviceCalls++
            communicationDeviceId = null
        }

        override fun deviceType(deviceId: Int): Int? = deviceTypes[deviceId]

        override fun audioMode(): Int = currentAudioMode

        override fun setAudioMode(mode: Int) {
            currentAudioMode = mode
            modeSets += mode
            events += "mode=$mode"
        }

        override fun openInputStream(): Int {
            openCalls++
            return openedDeviceId
        }

        override fun startInputStream(): Boolean {
            startCalls++
            return streamStartResult
        }

        override fun closeInputStream() {
            closeCalls++
        }
    }

    private fun acquisition(platform: FakePlatform) = RouteAcquisition(
        platform = platform,
        timeoutMs = 1_000L,
        pollIntervalMs = 10L,
    )

    // ── Classic Bluetooth (HFP/SCO) ─────────────────────────────────────

    @Test
    fun `classic candidate starts SCO and captures only after the link is up`() = runTest {
        val platform = FakePlatform().apply {
            scoOn = false
            openedDeviceId = 41
            deviceTypes[41] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
        var result: AcquisitionResult? = null
        val job = launch {
            result = acquisition(platform).acquire(
                BluetoothCandidate(deviceId = 41, transport = BluetoothTransport.ClassicSco),
                gateAllows = { true },
            )
        }

        runCurrent()
        assertEquals(1, platform.scoStartCalls)
        // No stream may be opened, let alone started, before the link is established.
        assertEquals(0, platform.openCalls)
        assertEquals(0, platform.startCalls)

        platform.scoOn = true
        advanceUntilIdle()
        job.join()

        assertEquals(AcquisitionResult.Acquired(41), result)
        assertEquals(1, platform.openCalls)
        assertEquals(1, platform.startCalls)
    }

    @Test
    fun `classic acquisition sets the normal audio mode before SCO and restores it on release`() = runTest {
        // HFP establishment needs the normal audio mode on some devices; this is the mode
        // handling that used to live in awaitBluetoothSco, scoped here to acquisition.
        val platform = FakePlatform().apply {
            currentAudioMode = AudioManager.MODE_IN_COMMUNICATION
            scoOn = true
            openedDeviceId = 32
            deviceTypes[32] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
        val acquisition = acquisition(platform)

        val result = acquisition.acquire(
            BluetoothCandidate(deviceId = 32, transport = BluetoothTransport.ClassicSco),
            gateAllows = { true },
        )

        assertEquals(AcquisitionResult.Acquired(32), result)
        // The normal mode must be set before startBluetoothSco(), not after it.
        assertEquals(
            listOf("mode=" + AudioManager.MODE_NORMAL, "startSco"),
            platform.events.take(2),
        )

        acquisition.release()

        assertEquals(
            "the previous mode must be restored on release",
            AudioManager.MODE_IN_COMMUNICATION,
            platform.currentAudioMode,
        )
    }

    // ── BLE (LE Audio) ──────────────────────────────────────────────────

    @Test
    fun `ble candidate selects the communication device and captures only when it is reported`() = runTest {
        val platform = FakePlatform().apply {
            openedDeviceId = 42
            deviceTypes[42] = AudioDeviceInfo.TYPE_BLE_HEADSET
        }
        var result: AcquisitionResult? = null
        val job = launch {
            result = acquisition(platform).acquire(
                BluetoothCandidate(deviceId = 42, transport = BluetoothTransport.LeAudio),
                gateAllows = { true },
            )
        }

        runCurrent()
        // Availability is not selection: the device was selected, but nothing is reported yet.
        assertEquals(listOf(42), platform.communicationDeviceCalls)
        assertEquals(0, platform.openCalls)

        platform.communicationDeviceId = 42
        advanceUntilIdle()
        job.join()

        assertEquals(AcquisitionResult.Acquired(42), result)
        assertEquals(1, platform.startCalls)
    }

    // ── Stream confirmation is the final authority ──────────────────────

    @Test
    fun `a built-in microphone stream is closed and the acquisition fails`() = runTest {
        val platform = FakePlatform().apply {
            scoOn = true
            openedDeviceId = 7
            deviceTypes[7] = AudioDeviceInfo.TYPE_BUILTIN_MIC
        }

        val result = acquisition(platform).acquire(
            BluetoothCandidate(deviceId = 7, transport = BluetoothTransport.ClassicSco),
            gateAllows = { true },
        )

        assertEquals(AcquisitionResult.Failed(RouteAcquisition.REASON_FAILED), result)
        assertEquals(0, platform.startCalls)
        assertEquals(1, platform.closeCalls)
        assertTrue("routing must be released", platform.scoCalls >= 1)
    }

    @Test
    fun `an unspecified stream device fails and is closed`() = runTest {
        val platform = FakePlatform().apply {
            scoOn = true
            openedDeviceId = 0 // kUnspecified — OpenSL ES backend confirms nothing
        }

        val result = acquisition(platform).acquire(
            BluetoothCandidate(deviceId = 9, transport = BluetoothTransport.ClassicSco),
            gateAllows = { true },
        )

        assertEquals(AcquisitionResult.Failed(RouteAcquisition.REASON_FAILED), result)
        assertEquals(0, platform.startCalls)
        assertEquals(1, platform.closeCalls)
    }

    @Test
    fun `an unresolvable stream device fails and is closed`() = runTest {
        val platform = FakePlatform().apply {
            scoOn = true
            openedDeviceId = 99 // no device with this id is connected
        }

        val result = acquisition(platform).acquire(
            BluetoothCandidate(deviceId = 99, transport = BluetoothTransport.ClassicSco),
            gateAllows = { true },
        )

        assertEquals(AcquisitionResult.Failed(RouteAcquisition.REASON_FAILED), result)
        assertEquals(0, platform.startCalls)
        assertEquals(1, platform.closeCalls)
    }

    @Test
    fun `a gate that blocks after the stream opens cancels without starting capture`() = runTest {
        // gateAllows() is checked twice: while polling for selection and again after the stream
        // is opened and confirmed. A gate that was passable through selection but blocks at the
        // post-open check must still cancel, close the stream, and release routing.
        val platform = FakePlatform().apply {
            scoOn = true
            openedDeviceId = 31
            deviceTypes[31] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
        var gateChecks = 0

        val result = acquisition(platform).acquire(
            BluetoothCandidate(deviceId = 31, transport = BluetoothTransport.ClassicSco),
            // true for the selection check, false at the post-open re-check
            gateAllows = { gateChecks++ == 0 },
        )

        assertTrue("the gate must have been checked again after the stream opened", gateChecks >= 2)
        assertEquals(AcquisitionResult.Cancelled, result)
        assertEquals("the stream must not be started", 0, platform.startCalls)
        assertEquals("the opened stream must be closed", 1, platform.closeCalls)
        assertTrue("routing must be released", platform.scoCalls >= 1)
    }

    // ── Cancellation and gate loss ──────────────────────────────────────

    @Test
    fun `a gate question that blocks cancels a pending acquisition without capture`() = runTest {
        val platform = FakePlatform().apply { scoOn = false }
        var gateAllows = true
        var result: AcquisitionResult? = null
        val job = launch {
            result = acquisition(platform).acquire(
                BluetoothCandidate(deviceId = 1, transport = BluetoothTransport.ClassicSco),
                gateAllows = { gateAllows },
            )
        }

        runCurrent()
        assertTrue(platform.openCalls == 0)

        gateAllows = false
        advanceUntilIdle()
        job.join()

        assertEquals(AcquisitionResult.Cancelled, result)
        assertEquals(0, platform.startCalls)
        assertEquals(0, platform.openCalls)
        assertTrue("routing must be released", platform.scoCalls >= 1)
    }

    @Test
    fun `a late completion after cancellation is discarded and never captures`() = runTest {
        val platform = FakePlatform().apply {
            scoOn = false
            openedDeviceId = 5
            deviceTypes[5] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
        val job = launch {
            acquisition(platform).acquire(
                BluetoothCandidate(deviceId = 5, transport = BluetoothTransport.ClassicSco),
                gateAllows = { true },
            )
        }

        runCurrent()
        job.cancel()
        // The candidate link comes up after the attempt was cancelled: it must not start capture.
        platform.scoOn = true
        advanceUntilIdle()

        assertEquals(0, platform.startCalls)
        assertEquals(0, platform.openCalls)
        assertTrue("routing must be released", platform.scoCalls >= 1)
    }

    @Test
    fun `a stream that fails to start is closed and routing is released`() = runTest {
        val platform = FakePlatform().apply {
            scoOn = true
            openedDeviceId = 11
            deviceTypes[11] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            streamStartResult = false
        }

        val result = acquisition(platform).acquire(
            BluetoothCandidate(deviceId = 11, transport = BluetoothTransport.ClassicSco),
            gateAllows = { true },
        )

        assertEquals(AcquisitionResult.Failed(RouteAcquisition.REASON_FAILED), result)
        assertEquals(1, platform.startCalls)
        assertEquals(1, platform.closeCalls)
        assertTrue(platform.scoCalls >= 1)
    }

    // ── Routes that need no acquisition ─────────────────────────────────

    @Test
    fun `a built-in speaker route attempts no acquisition`() = runTest {
        val platform = FakePlatform()

        val result = acquisition(platform).acquire(AudioRoute.Speaker, gateAllows = { true })

        assertEquals(AcquisitionResult.NotNeeded, result)
        assertEquals(0, platform.interactions())
    }

    @Test
    fun `an already isolated wired headset needs no acquisition`() = runTest {
        val platform = FakePlatform()

        val result = acquisition(platform).acquire(
            AudioRoute.Headset(hasMicrophone = true),
            gateAllows = { true },
        )

        assertEquals(AcquisitionResult.NotNeeded, result)
        assertEquals(0, platform.interactions())
    }

    @Test
    fun `a bluetooth candidate route is discovered from the platform`() = runTest {
        val platform = FakePlatform().apply {
            candidates += BluetoothCandidate(deviceId = 3, transport = BluetoothTransport.ClassicSco)
            scoOn = true
            openedDeviceId = 3
            deviceTypes[3] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }

        val result = acquisition(platform).acquire(
            AudioRoute.BluetoothA2dpOnly,
            gateAllows = { true },
        )

        assertEquals(AcquisitionResult.Acquired(3), result)
    }

    @Test
    fun `a candidate route with no candidate device fails without capture`() = runTest {
        val platform = FakePlatform()

        val result = acquisition(platform).acquire(
            AudioRoute.BluetoothA2dpOnly,
            gateAllows = { true },
        )

        assertEquals(AcquisitionResult.Failed(RouteAcquisition.REASON_FAILED), result)
        assertEquals(0, platform.startCalls)
    }

    @Test
    fun `a timeout releases routing and never captures`() = runTest {
        val platform = FakePlatform().apply { scoOn = false }

        val result = acquisition(platform).acquire(
            BluetoothCandidate(deviceId = 2, transport = BluetoothTransport.ClassicSco),
            gateAllows = { true },
        )

        assertEquals(AcquisitionResult.Failed(RouteAcquisition.REASON_TIMEOUT), result)
        assertEquals(0, platform.startCalls)
        assertTrue("routing must be released", platform.scoCalls >= 1)
    }

    // ── Stream ownership and release ────────────────────────────────────

    @Test
    fun `release without an acquisition attempt never closes the engine-owned stream`() = runTest {
        // A wired or speaker route never opens a stream for acquisition, and the engine owns
        // its own capture stream. release() must not delete a stream this component never opened.
        val platform = FakePlatform()

        RouteAcquisition(platform, timeoutMs = 1_000L, pollIntervalMs = 10L).release()

        assertEquals(0, platform.closeCalls)
        assertEquals(0, platform.interactions())
    }

    @Test
    fun `release after the engine takes over the stream does not close it`() = runTest {
        val platform = FakePlatform().apply {
            scoOn = true
            openedDeviceId = 12
            deviceTypes[12] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
        val acquisition = acquisition(platform)

        val result = acquisition.acquire(
            BluetoothCandidate(deviceId = 12, transport = BluetoothTransport.ClassicSco),
            gateAllows = { true },
        )
        assertEquals(AcquisitionResult.Acquired(12), result)

        // The service hands the stream to the engine once capture starts.
        acquisition.handOffStream()
        acquisition.release()

        // The engine owns the stream post-handoff; release only undoes routing.
        assertEquals(0, platform.closeCalls)
        assertTrue("routing must still be released", platform.scoCalls >= 1)
    }

    @Test
    fun `release before the engine takes over closes the started stream`() = runTest {
        // Acquisition succeeded (the stream is live) but the engine never started — for
        // example the gate blocked after the stream opened. The stream is still this
        // component's to close.
        val platform = FakePlatform().apply {
            scoOn = true
            openedDeviceId = 14
            deviceTypes[14] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
        val acquisition = acquisition(platform)

        val result = acquisition.acquire(
            BluetoothCandidate(deviceId = 14, transport = BluetoothTransport.ClassicSco),
            gateAllows = { true },
        )
        assertEquals(AcquisitionResult.Acquired(14), result)

        acquisition.release()

        assertEquals(1, platform.closeCalls)
        assertTrue("routing must still be released", platform.scoCalls >= 1)
    }

    @Test
    fun `release after a failed acquisition still closes the opened stream`() = runTest {
        val platform = FakePlatform().apply {
            scoOn = true
            openedDeviceId = 13
            deviceTypes[13] = AudioDeviceInfo.TYPE_BUILTIN_MIC
        }
        val acquisition = acquisition(platform)

        val result = acquisition.acquire(
            BluetoothCandidate(deviceId = 13, transport = BluetoothTransport.ClassicSco),
            gateAllows = { true },
        )
        assertEquals(AcquisitionResult.Failed(RouteAcquisition.REASON_FAILED), result)

        // The failed attempt already closed it once ...
        assertEquals(1, platform.closeCalls)
        // ... and a second release must not touch it again.
        acquisition.release()
        assertEquals(1, platform.closeCalls)
    }
}
