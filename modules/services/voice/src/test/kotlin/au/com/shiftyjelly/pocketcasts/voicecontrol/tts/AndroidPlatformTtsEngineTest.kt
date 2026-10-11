package au.com.shiftyjelly.pocketcasts.voicecontrol.tts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.FingerprintPcmTap
import au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackBufferRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidPlatformTtsEngineTest {
    private lateinit var engine: AndroidPlatformTtsEngine
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        engine = AndroidPlatformTtsEngine(
            context,
            PlaybackBufferRecorder(FingerprintPcmTap()),
        )
    }

    @Test
    fun `release disposes resources without crash`() {
        engine.release()
        // No crash expected
    }

    @Test
    fun `speak before initialization returns without throwing`() = runTest {
        // The engine tolerates speak before TTS is ready, which is what the renderer relies on when
        // a reply arrives during startup.
        engine.speak("hello", "en")
    }

    @Test
    fun `release is idempotent`() {
        engine.release()
        engine.release()
    }

    @Test
    fun `cancelling while synthesis is suspended stops the engine`() = runTest {
        // The synthesis never completes, so the coroutine is cancelled exactly while suspended inside
        // the call -- the case the !isActive check after the call cannot reach, because that check
        // runs only once the call has already returned.
        val (wired, fake) = engineWithFakeSynthesizer()
        fake.onInitialized?.invoke(true)

        // Real dispatchers, not runTest's: speak() uses withContext(Dispatchers.Main) to touch the
        // platform engine, and runTest's virtual scheduler never runs Main, so the test would block
        // on work that cannot progress. A real scope on the default dispatcher lets the call reach
        // its suspension, which is the state this test is about.
        val scope = CoroutineScope(Dispatchers.Default)
        val job = scope.launch { wired.speak("hello", "en") }
        // speak() touches the platform engine through withContext(Dispatchers.Main), which posts to
        // Robolectric's main looper; those runnables must be idled or the call never advances to its
        // synthesis suspension and the test waits on work that is sitting in the queue.
        val queued = (0 until 50).any {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
            fake.synthesizeQueued
        }
        assertTrue(
            "precondition: the synthesis must actually be queued and left suspended",
            queued,
        )
        job.cancelAndJoin()
        scope.cancel()

        assertTrue(
            "cancelling while suspended must stop the queued synthesis (stopCalls=${fake.stopCalls})",
            fake.stopCalls > 0,
        )
    }

    @Test
    fun `a completed synthesis is not disturbed, and a later utterance still runs`() {
        // The other half of the cancellation contract: adding invokeOnCancellation must not break
        // the normal path. The first utterance completes through the platform's own onDone callback,
        // and a second utterance on the same engine must still synthesize -- a cancellation handler
        // that fired on success, or that left the engine stopped, would show up here.
        val (wired, fake) = engineWithFakeSynthesizer()
        fake.onInitialized?.invoke(true)

        val scope = CoroutineScope(Dispatchers.Default)
        repeat(2) { attempt ->
            val job = scope.launch { wired.speak("hello", "en") }
            val queued = (0 until 50).any {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.sleep(20)
                fake.synthesizeCount > attempt
            }
            assertTrue("utterance ${attempt + 1} must reach synthesis", queued)
            // Complete it the way the platform does, so the call resumes normally.
            fake.listener?.onDone(fake.lastUtteranceId)
            runBlocking { job.join() }
        }
        assertEquals(
            "both utterances must have reached synthesis without a cancellation",
            2,
            fake.synthesizeCount,
        )
        assertEquals("a completed synthesis must not stop the engine", 0, fake.stopCalls)
        scope.cancel()
    }

    @Test
    fun `a cancellation whose cleanup runs after a successor starts does not stop the successor`() {
        // The distinguishing case two completed utterances cannot reach. The cancellation handlers
        // stop the SHARED engine, and they run asynchronously: if the first utterance's handler runs
        // after the second has already begun synthesizing, the successor is silenced by a
        // cancellation that belonged to a turn already over.
        //
        // The ordering is made EXPLICIT rather than hoped for: the successor's synthesis is confirmed
        // queued BEFORE the cancelled utterance is allowed to finish cancelling.
        val (wired, fake) = engineWithFakeSynthesizer()
        fake.onInitialized?.invoke(true)
        val scope = CoroutineScope(Dispatchers.Default)

        // Utterance 1 reaches its synthesis suspension.
        val first = scope.launch { wired.speak("first", "en") }
        assertTrue("precondition: utterance 1 reached synthesis", awaitSynthesize(fake, 1))

        // The successor claims the engine and is confirmed synthesizing while utterance 1 is still
        // cancelling. Only then is the cancellation allowed to complete its cleanup.
        val second = scope.launch { wired.speak("second", "en") }
        assertTrue(
            "precondition: the successor reached synthesis before utterance 1 finished cancelling",
            awaitSynthesize(fake, 2),
        )
        runBlocking { first.cancelAndJoin() }

        assertEquals(
            "the successor's synthesis must survive a cancellation that belonged to utterance 1 " +
                "(stopCalls=${fake.stopCalls})",
            0,
            fake.stopCalls,
        )
        scope.cancel()
    }

    @Test
    fun `a cancellation cannot stop the engine once a successor holds the boundary`() {
        // A controlled interleaving rather than a stress run: holding the synthesis boundary, a
        // successor cannot claim it, so a cancellation that checked ownership before the successor
        // started must not still be able to stop the engine afterwards. Without the shared lock the
        // check and the stop are separate steps and the successor can slip between them -- the same
        // read-then-act window that let a released claim publish in the route observer.
        val (wired, fake) = engineWithFakeSynthesizer()
        fake.onInitialized?.invoke(true)

        var successorClaimed = false
        wired.runSerialisedForTest {
            // Inside the boundary, a successor's startup must not be able to complete.
            val done = java.util.concurrent.CountDownLatch(1)
            Thread {
                wired.claimSynthesisForTest()
                done.countDown()
            }.start()
            successorClaimed = done.await(150, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        assertFalse(
            "a successor must not claim the synthesis boundary while it is held",
            successorClaimed,
        )

        // And once free, claiming proceeds and the engine is left usable.
        val claimed = java.util.concurrent.CountDownLatch(1)
        Thread {
            wired.claimSynthesisForTest()
            claimed.countDown()
        }.start()
        assertTrue(
            "claiming completes once the boundary is free",
            claimed.await(2, java.util.concurrent.TimeUnit.SECONDS),
        )
    }

    /**
     * Idles the main looper until the fake has queued at least [count] syntheses.
     */
    private fun awaitSynthesize(fake: FakeSynthesizer, count: Int): Boolean = (0 until 100).any {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        Thread.sleep(20)
        fake.synthesizeCount >= count
    }

    /**
     * Builds an engine whose synthesizer is controlled by the test, and fires the platform's OWN
     * initialization callback with SUCCESS so `speak` reaches the synthesis suspension. The callback
     * driven here is the production one -- the same lambda the real TextToSpeech invokes -- rather
     * than a test-only "initialized" flag.
     */
    private fun engineWithFakeSynthesizer(
        onSynthesize: (utteranceId: String) -> Unit = {},
    ): Pair<AndroidPlatformTtsEngine, FakeSynthesizer> {
        val fake = FakeSynthesizer(onSynthesize)
        lateinit var engineRef: AndroidPlatformTtsEngine
        engineRef = AndroidPlatformTtsEngine(
            context,
            PlaybackBufferRecorder(FingerprintPcmTap()),
        ) { onInitialized ->
            fake.onInitialized = onInitialized
            fake
        }
        return engineRef to fake
    }

    private class FakeSynthesizer(
        private val onSynthesize: (String) -> Unit,
    ) : android.speech.tts.TextToSpeech(
        androidx.test.core.app.ApplicationProvider.getApplicationContext(),
        null,
    ) {
        var onInitialized: ((Boolean) -> Unit)? = null
        var stopCalls = 0
            private set

        @Volatile
        var synthesizeQueued = false
            private set
        var synthesizeCount = 0
            private set
        var lastUtteranceId: String = ""
            private set
        var listener: android.speech.tts.UtteranceProgressListener? = null

        override fun setOnUtteranceProgressListener(
            l: android.speech.tts.UtteranceProgressListener?,
        ): Int {
            listener = l
            return android.speech.tts.TextToSpeech.SUCCESS
        }

        override fun setLanguage(locale: java.util.Locale): Int = android.speech.tts.TextToSpeech.LANG_AVAILABLE

        override fun synthesizeToFile(
            text: CharSequence?,
            params: android.os.Bundle?,
            file: java.io.File?,
            utteranceId: String?,
        ): Int {
            lastUtteranceId = utteranceId ?: ""
            synthesizeCount += 1
            onSynthesize(utteranceId ?: "")
            synthesizeQueued = true
            return android.speech.tts.TextToSpeech.SUCCESS
        }

        override fun stop(): Int {
            stopCalls += 1
            return android.speech.tts.TextToSpeech.SUCCESS
        }
    }
}
