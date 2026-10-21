package au.com.shiftyjelly.ttsprobe

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Bounded, SILENT synthesis check (spec c8739ed6): drives the SAME platform TTS engine the app
 * uses (the system default, Google TTS) with the app's language-selection contract for the
 * Chinese refusal, synthesizes to a scratch file — no audio is played — and asserts a
 * non-empty result. Fails if the Chinese voice data is missing or synthesis errors.
 */
@RunWith(AndroidJUnit4::class)
class ZhTtsSynthesisCheckTest {

    private fun synthesizeToDone(languageTag: String, text: String): Pair<Int, Boolean> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val latch = CountDownLatch(1)
        var initStatus = TextToSpeech.ERROR
        var done = false
        lateinit var tts: TextToSpeech
        tts = TextToSpeech(context) { status ->
            initStatus = status
            latch.countDown()
        }
        assertTrue("TTS init latch timed out", latch.await(10, TimeUnit.SECONDS))
        assertTrue("TTS init failed: $initStatus", initStatus == TextToSpeech.SUCCESS)
        try {
            val setLang = tts.setLanguage(Locale.forLanguageTag(languageTag))
            val synthLatch = CountDownLatch(1)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    done = true
                    synthLatch.countDown()
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    done = false
                    synthLatch.countDown()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    done = false
                    synthLatch.countDown()
                }
            })
            val wav = File(context.cacheDir, "zh_synthesis_check_${System.currentTimeMillis()}.wav")
            val synth = tts.synthesizeToFile(text, null, wav, "zh-check")
            assertTrue("synthesizeToFile refused: $synth", synth == TextToSpeech.SUCCESS)
            assertTrue("synthesis did not complete in 30s", synthLatch.await(30, TimeUnit.SECONDS))
            val bytes = if (wav.exists()) wav.length() else 0L
            wav.delete()
            return setLang to (done && bytes > 0)
        } finally {
            tts.shutdown()
        }
    }

    @Test
    fun chineseRefusalLineSynthesizesSilently() {
        val text = "你已达到 Auris 的每日免费额度，请明天再试。"
        val (setLang, ok) = synthesizeToDone("zh-CN", text)
        assertTrue(
            "setLanguage(zh-CN)=$setLang, synthesized=$ok — Chinese voice data missing or synthesis errored",
            ok,
        )
    }
}
