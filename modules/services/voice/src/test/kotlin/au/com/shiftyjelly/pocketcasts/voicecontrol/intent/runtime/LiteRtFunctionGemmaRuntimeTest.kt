package au.com.shiftyjelly.pocketcasts.voicecontrol.intent.runtime

import au.com.shiftyjelly.pocketcasts.voicecontrol.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class LiteRtFunctionGemmaRuntimeTest {
    @Test
    fun `build exposes the pinned LiteRT-LM version`() {
        assertEquals("0.13.1", BuildConfig.LITERTLM_VERSION)
    }
}
