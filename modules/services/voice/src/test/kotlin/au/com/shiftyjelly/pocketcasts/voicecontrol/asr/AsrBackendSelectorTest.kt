package au.com.shiftyjelly.pocketcasts.voicecontrol.asr

import dagger.Lazy
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class AsrBackendSelectorTest {

    private lateinit var deviceProbe: DeviceProbe
    private lateinit var whisperCppBackend: WhisperCppBackend
    private lateinit var selector: AsrBackendSelector

    @Before
    fun setUp() {
        deviceProbe = DeviceProbe(hardware = "", socManufacturer = "", sdkInt = 30)
        whisperCppBackend = WhisperCppBackend()
        selector = AsrBackendSelector(
            deviceProbe = deviceProbe,
            whisperCppBackend = object : Lazy<WhisperCppBackend> {
                override fun get(): WhisperCppBackend = whisperCppBackend
            },
        )
    }

    @Test
    fun `select returns whisperCppBackend by default`() {
        val backend = selector.select()
        assertSame(whisperCppBackend, backend)
    }

    @Test
    fun `manual override to whisper-cpp selects whisperCppBackend`() {
        selector.manualOverride = "whisper-cpp"

        val backend = selector.select()
        assertSame(whisperCppBackend, backend)
    }

    @Test
    fun `manual override to whisper-cpp is case-insensitive`() {
        selector.manualOverride = "WHISPER-CPP"

        val backend = selector.select()
        assertSame(whisperCppBackend, backend)
    }

    @Test
    fun `manual override takes priority over matrix selection`() {
        selector.manualOverride = "whisper-cpp"

        val backend = selector.select()
        assertSame(whisperCppBackend, backend)
    }

    @Test
    fun `manual override to sensevoice throws not yet implemented error`() {
        selector.manualOverride = "sensevoice"

        assertThrows(IllegalStateException::class.java) {
            selector.select()
        }
    }

    @Test
    fun `manual override to npu throws not yet implemented error`() {
        selector.manualOverride = "npu"

        assertThrows(IllegalStateException::class.java) {
            selector.select()
        }
    }

    @Test
    fun `manual override to unknown backend throws error`() {
        selector.manualOverride = "unknown"

        assertThrows(IllegalStateException::class.java) {
            selector.select()
        }
    }
}
