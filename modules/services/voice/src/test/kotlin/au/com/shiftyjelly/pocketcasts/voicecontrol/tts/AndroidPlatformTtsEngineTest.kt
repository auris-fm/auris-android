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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
        var emitRealWav = false
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
            // Write a real WAV only when a test asks for the write path to be reached: an empty file
            // makes speak() return before the write loop, so without this the truncation case would
            // exercise nothing. Off by default, because a decodable clip also engages the drain wait,
            // which the synthesis-oriented cases do not need.
            if (emitRealWav) file?.also { writeMinimalWav(it, samples = 512, sampleRateHz = 22050) }
            onSynthesize(utteranceId ?: "")
            synthesizeQueued = true
            return android.speech.tts.TextToSpeech.SUCCESS
        }

        override fun stop(): Int {
            stopCalls += 1
            return android.speech.tts.TextToSpeech.SUCCESS
        }
    }

    @Test
    fun `a refused sink write surfaces as an incomplete playback rather than a silent end`() {
        // A refused AudioTrack.write means the reply was TRUNCATED, not finished. The old code broke
        // out of the loop, leaving offset at the accepted count -- so the drain loop waited only for
        // frames that had already played, speak() returned normally, and nothing reached
        // onPlaybackFailure. The user heard a cut-off reply and saw no error: the same "silent end"
        // shape this round fixed on the cloud path.
        //
        // The refusal needs a failing AudioTrack, which a unit test cannot construct, so the write is
        // injected -- the seam CloudAudioPlayer already exposes for its own path.
        val (wired, fake) = engineWithFakeSynthesizer()
        fake.onInitialized?.invoke(true)
        fake.emitRealWav = true
        // No drain wait: the write is refused, so there is nothing to drain and waiting would only
        // exercise Robolectric's playback clock.
        wired.drainWaitMs = 0
        // Refuse after accepting nothing, which is the ERROR_DEAD_OBJECT shape.
        wired.writeToSink = { _, _, _, _ -> -1 }

        val scope = CoroutineScope(Dispatchers.Default)
        var failure: Throwable? = null
        val job = scope.launch {
            try {
                wired.speak("hello", "en")
            } catch (t: Throwable) {
                failure = t
            }
        }
        val queued = (0 until 50).any {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
            fake.synthesizeCount > 0
        }
        assertTrue("the utterance must reach synthesis", queued)
        fake.listener?.onDone(fake.lastUtteranceId)
        runBlocking { job.join() }
        scope.cancel()

        assertTrue(
            "a refused write must surface as an incomplete playback, not a silent end: " +
                "failure=${failure?.let { it::class.simpleName }}",
            failure is TtsPlaybackIncompleteException,
        )
    }

    @Test
    fun `cancelling while a write is blocked stops the owned output without waiting for the write`() {
        // @spec's correction to the supersession fix: between-write checks cover SUPERSESSION, but a
        // write already BLOCKED when cancellation lands cannot reach the next check -- the stop must
        // be initiated BY the cancellation, while the write is still blocked, and a plain completion
        // hook would wait for the very write it needs to interrupt.
        val (wired, fake) = engineWithFakeSynthesizer()
        fake.onInitialized?.invoke(true)
        fake.emitRealWav = true
        wired.drainWaitMs = 0

        val enteredWrite = java.util.concurrent.CountDownLatch(1)
        val releaseWrite = java.util.concurrent.CountDownLatch(1)
        val stoppedWhileBlocked = java.util.concurrent.atomic.AtomicBoolean(false)
        wired.writeToSink = { _, _, _, count ->
            enteredWrite.countDown()
            // Hold the write until the test has observed whether cancellation stopped the output
            // behind it. If cancellation waits for this write, the flag stays false.
            releaseWrite.await(5, java.util.concurrent.TimeUnit.SECONDS)
            count
        }

        val scope = CoroutineScope(Dispatchers.Default)
        val job = scope.launch {
            try {
                wired.speak("a long reply", "en")
            } catch (t: Throwable) {
                // EXPECTED: the interrupted playback surfaces as incomplete. Swallowed here so the
                // failure does not leak as an uncaught exception into the next runTest.
            }
        }
        val queued = (0 until 50).any {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
            fake.synthesizeCount > 0
        }
        assertTrue("the utterance must reach synthesis", queued)
        fake.listener?.onDone(fake.lastUtteranceId)
        assertTrue(
            "the reply must reach its first write",
            enteredWrite.await(5, java.util.concurrent.TimeUnit.SECONDS),
        )

        // Cancel while the write is blocked.
        job.cancel()
        // Give the watcher its 10ms poll window, then look WITHOUT releasing the write.
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(300) }
        stoppedWhileBlocked.set(fake.stopCalls > 0)

        releaseWrite.countDown()
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(5_000) { job.join() }
            kotlinx.coroutines.delay(100)
        }
        scope.cancel()

        assertTrue(
            "cancellation must stop the owned output while the write is still blocked " +
                "(stopCalls=${fake.stopCalls})",
            stoppedWhileBlocked.get(),
        )
        wired.release()
    }

    @Test
    fun `a successor claiming during the old utterance's synthesis does not let the old playback adopt its generation`() {
        // @spec's ordering: the ownership token must be carried from the SYNTHESIS claim. If playback
        // re-read the global generation, an old utterance whose successor claimed during its own
        // synthesis would ADOPT the successor's generation, believe itself the owner, and its
        // teardown would stop the successor's output.
        //
        // With the token carried, the old playback starts ALREADY superseded: it must exit without
        // writing and without touching the shared engine -- which is what the assertions below
        // measure. (A first version of this case expected a write, which was wrong: an already
        // superseded reply writes nothing at all.)
        val oldSynthesizing = java.util.concurrent.CountDownLatch(1)
        val (wired, fake) = engineWithFakeSynthesizer(onSynthesize = { oldSynthesizing.countDown() })
        fake.onInitialized?.invoke(true)
        fake.emitRealWav = true
        wired.drainWaitMs = 0

        val writes = java.util.concurrent.atomic.AtomicInteger()
        wired.writeToSink = { _, _, _, count ->
            writes.incrementAndGet()
            count
        }

        val scope = CoroutineScope(Dispatchers.Default)
        var oldFailure: Throwable? = null
        val oldJob = scope.launch {
            try {
                wired.speak("old reply", "en")
            } catch (t: Throwable) {
                oldFailure = t
            }
        }
        // speak touches the platform engine on Main, so idle Robolectric's looper while waiting.
        val oldClaimed = (0 until 100).any {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
            oldSynthesizing.await(10, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        assertTrue("the old utterance must enter synthesis (and claim) first", oldClaimed)

        // The successor claims the engine BEFORE the old playback starts.
        wired.claimSynthesisForTest()
        // Let the old synthesis finish so its playback begins with the ORIGINAL, older token.
        fake.listener?.onDone(fake.lastUtteranceId)
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(5_000) { oldJob.join() }
            kotlinx.coroutines.delay(100)
        }
        scope.cancel()

        assertEquals(
            "the old playback holds its ORIGINAL token, sees itself superseded, and writes nothing " +
                "(writes=${writes.get()})",
            0,
            writes.get(),
        )
        assertEquals(
            "and it must NOT stop the successor's engine (stopCalls=${fake.stopCalls})",
            0,
            fake.stopCalls,
        )
        assertNull("the old playback must not throw in this ordering (failure=$oldFailure)", oldFailure)
        // @spec's flag assertion: the old utterance never reached playback, so it must not have
        // claimed the shared flag -- an utterance that wrote nothing has no playing audio to report.
        assertFalse(
            "a superseded utterance that never wrote must not leave the playback flag set",
            wired.isPlayingSynthesizedAudio(),
        )
        wired.release()
    }

    @Test
    fun `a superseded reply stops at the next write instead of continuing under the successor`() {
        // @spec's focused blocked-write regression for TTS:262. The seam blocks INSIDE the first
        // write -- the state a slow or contended output actually produces. While it is blocked, a
        // successor claims the engine; when the write unblocks, the old reply must perform NO
        // further writes. The job-completion hook cannot provide this: a blocked write never
        // completes, so the hook never fires until the write returns -- which is the untraced seam.
        val (wired, fake) = engineWithFakeSynthesizer()
        fake.onInitialized?.invoke(true)
        fake.emitRealWav = true
        wired.drainWaitMs = 0

        val enteredWrite = java.util.concurrent.CountDownLatch(1)
        val releaseWrite = java.util.concurrent.CountDownLatch(1)
        val writes = java.util.concurrent.atomic.AtomicInteger()
        var supersededWhileBlocked = false
        wired.writeToSink = { _, _, _, count ->
            val n = writes.incrementAndGet()
            if (n == 1) {
                enteredWrite.countDown()
                // Bounded: an indefinite block here hangs the whole suite rather than failing.
                releaseWrite.await(5, java.util.concurrent.TimeUnit.SECONDS)
                supersededWhileBlocked =
                    wired.playbackGenerationForTest < wired.currentGenerationForTest()
                // Accept HALF: the clip cannot fit in one write, so reaching "no further writes"
                // requires the loop to have exited via supersession rather than exhaustion. Without
                // this the whole clip lands in write one and the case has no degree of freedom --
                // the earlier version of this case survived the mutation for exactly that reason.
                count / 2
            } else {
                count
            }
        }

        val scope = CoroutineScope(Dispatchers.Default)
        var failure: Throwable? = null
        val job = scope.launch {
            try {
                wired.speak("first reply", "en")
            } catch (t: Throwable) {
                failure = t
            }
        }
        val queued = (0 until 50).any {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
            fake.synthesizeCount > 0
        }
        assertTrue("the utterance must reach synthesis", queued)
        fake.listener?.onDone(fake.lastUtteranceId)

        val reached = enteredWrite.await(5, java.util.concurrent.TimeUnit.SECONDS)
        System.err.println(
            "[DIAG] reached=$reached writes=" + writes.get() + " synth=" + fake.synthesizeCount +
                " failure=" + failure,
        )
        assertTrue("the reply must reach its first write (failure=$failure)", reached)
        // The successor claims the engine while the first reply is blocked mid-write: a new speak()
        // bumps the generation under the same lock the old reply's loop checks.
        wired.claimSynthesisForTest()
        releaseWrite.countDown()
        runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(5_000) { job.join() }
            kotlinx.coroutines.delay(100)
        }
        scope.cancel()

        assertTrue(
            "the write was blocked when the successor claimed the engine",
            supersededWhileBlocked,
        )
        assertEquals(
            "the superseded reply must perform no further writes after the blocked one " +
                "(writes=${writes.get()})",
            1,
            writes.get(),
        )
        wired.release()
    }

    @Test
    fun `reviewer old render exit preserves actual successor output`() {
        val (wired, fake) = engineWithFakeSynthesizer()
        fake.onInitialized?.invoke(true)
        fake.emitRealWav = true
        wired.drainWaitMs = 0
        val oldEntered = java.util.concurrent.CountDownLatch(1)
        val newEntered = java.util.concurrent.CountDownLatch(1)
        val oldRelease = java.util.concurrent.CountDownLatch(1)
        val newRelease = java.util.concurrent.CountDownLatch(1)
        val firstTrack = java.util.concurrent.atomic.AtomicReference<android.media.AudioTrack>()
        val secondTrack = java.util.concurrent.atomic.AtomicReference<android.media.AudioTrack>()
        wired.writeToSink = { track, _, _, count ->
            if (firstTrack.compareAndSet(null, track)) {
                oldEntered.countDown()
                oldRelease.await(5, java.util.concurrent.TimeUnit.SECONDS)
                count / 2
            } else {
                secondTrack.set(track)
                newEntered.countDown()
                newRelease.await(5, java.util.concurrent.TimeUnit.SECONDS)
                count
            }
        }
        val scope = CoroutineScope(Dispatchers.Default)
        fun start(text: String, targetCount: Int): kotlinx.coroutines.Job {
            val job = scope.launch {
                try {
                    wired.speak(text, "en")
                } catch (_: Throwable) {}
            }
            assertTrue(
                "synthesis must reach $targetCount",
                (0 until 50).any {
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    Thread.sleep(20)
                    fake.synthesizeCount >= targetCount
                },
            )
            fake.listener?.onDone(fake.lastUtteranceId)
            return job
        }
        val old = start("old", 1)
        assertTrue("old write entered", oldEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val successor = start("successor", 2)
        assertTrue("successor write entered", newEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val successorTrack = secondTrack.get()
        assertTrue("outputs must be distinct tracks", successorTrack !== firstTrack.get())
        assertEquals("successor must be playing before old exits", android.media.AudioTrack.PLAYSTATE_PLAYING, successorTrack.playState)
        oldRelease.countDown()
        val finished = runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(3000) {
                old.join()
                true
            }
        } == true
        val afterOldExit = successorTrack.playState
        val flagAfterOldExit = wired.isPlayingSynthesizedAudio()
        val engineStops = fake.stopCalls
        newRelease.countDown()
        scope.cancel()
        runBlocking { kotlinx.coroutines.withTimeoutOrNull(3000) { successor.join() } }
        wired.release()
        assertTrue("old render must finish", finished)
        assertTrue("old teardown must preserve successor flag", flagAfterOldExit)
        assertEquals("old exit must preserve actual successor track", android.media.AudioTrack.PLAYSTATE_PLAYING, afterOldExit)
        assertEquals("old exit must preserve successor synthesizer", 0, engineStops)
    }

    @Test
    fun `reviewer cancellation stops old blocked track and preserves actual successor`() {
        val (wired, fake) = engineWithFakeSynthesizer()
        fake.onInitialized?.invoke(true)
        fake.emitRealWav = true
        wired.drainWaitMs = 0
        val oldEntered = java.util.concurrent.CountDownLatch(1)
        val newEntered = java.util.concurrent.CountDownLatch(1)
        val oldRelease = java.util.concurrent.CountDownLatch(1)
        val newRelease = java.util.concurrent.CountDownLatch(1)
        val firstTrack = java.util.concurrent.atomic.AtomicReference<android.media.AudioTrack>()
        val secondTrack = java.util.concurrent.atomic.AtomicReference<android.media.AudioTrack>()
        wired.writeToSink = { track, _, _, count ->
            if (firstTrack.compareAndSet(null, track)) {
                oldEntered.countDown()
                oldRelease.await(5, java.util.concurrent.TimeUnit.SECONDS)
                count / 2
            } else {
                secondTrack.set(track)
                newEntered.countDown()
                newRelease.await(5, java.util.concurrent.TimeUnit.SECONDS)
                count
            }
        }
        val scope = CoroutineScope(Dispatchers.Default)
        fun start(text: String, targetCount: Int): kotlinx.coroutines.Job {
            val job = scope.launch {
                try {
                    wired.speak(text, "en")
                } catch (_: Throwable) {}
            }
            assertTrue(
                "synthesis must reach $targetCount",
                (0 until 50).any {
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    Thread.sleep(20)
                    fake.synthesizeCount >= targetCount
                },
            )
            fake.listener?.onDone(fake.lastUtteranceId)
            return job
        }
        val old = start("old", 1)
        assertTrue("old write entered", oldEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val successor = start("successor", 2)
        assertTrue("successor write entered", newEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val successorTrack = secondTrack.get()
        assertTrue("outputs must be distinct tracks", successorTrack !== firstTrack.get())
        assertEquals("successor must be playing before old exits", android.media.AudioTrack.PLAYSTATE_PLAYING, successorTrack.playState)
        old.cancel()
        Thread.sleep(300)
        val oldStateBeforeUnblock = firstTrack.get().playState
        val successorStateBeforeUnblock = successorTrack.playState
        val stopCallsBeforeUnblock = fake.stopCalls
        oldRelease.countDown()
        val finished = runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(3000) {
                old.join()
                true
            }
        } == true
        val afterOldExit = successorTrack.playState
        val flagAfterOldExit = wired.isPlayingSynthesizedAudio()
        val engineStops = fake.stopCalls
        newRelease.countDown()
        scope.cancel()
        runBlocking { kotlinx.coroutines.withTimeoutOrNull(3000) { successor.join() } }
        wired.release()
        assertTrue("old render must finish", finished)
        assertTrue("old teardown must preserve successor flag", flagAfterOldExit)
        assertEquals("old local track must stop before blocked write returns", android.media.AudioTrack.PLAYSTATE_STOPPED, oldStateBeforeUnblock)
        assertEquals("successor must keep playing before old unblock", android.media.AudioTrack.PLAYSTATE_PLAYING, successorStateBeforeUnblock)
        assertEquals("old cancellation must preserve successor engine before unblock", 0, stopCallsBeforeUnblock)

        assertEquals("old exit must preserve actual successor track", android.media.AudioTrack.PLAYSTATE_PLAYING, afterOldExit)
        assertEquals("old exit must preserve successor synthesizer", 0, engineStops)
    }

    @Test
    fun `reviewer shared track publication respects ownership boundary`() {
        val (wired, fake) = engineWithFakeSynthesizer()
        fake.onInitialized?.invoke(true)
        fake.emitRealWav = true
        wired.drainWaitMs = 0
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val oldTrack = java.util.concurrent.atomic.AtomicReference<android.media.AudioTrack>()
        wired.writeToSink = { track, _, _, count ->
            if (oldTrack.compareAndSet(null, track)) {
                entered.countDown()
                release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                count / 2
            } else {
                count
            }
        }
        val scope = CoroutineScope(Dispatchers.Default)
        fun start(text: String, target: Int): kotlinx.coroutines.Job {
            val job = scope.launch {
                try {
                    wired.speak(text, "en")
                } catch (_: Throwable) {}
            }
            assertTrue(
                "synthesis must claim first",
                (0 until 50).any {
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    Thread.sleep(20)
                    fake.synthesizeCount >= target
                },
            )
            return job
        }
        val old = start("old", 1)
        fake.listener?.onDone(fake.lastUtteranceId)
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val successor = start("successor", 2)
        val active = wired.javaClass.getDeclaredField("activeTrack")
        active.isAccessible = true
        var observed: android.media.AudioTrack? = null
        wired.runSerialisedForTest {
            assertSame("old owns the slot before successor resumes", oldTrack.get(), active.get(wired))
            fake.listener?.onDone(fake.lastUtteranceId)
            // Successor has already claimed its token; only publication is held by this boundary.
            repeat(50) {
                if (active.get(wired) === oldTrack.get()) Thread.sleep(10)
            }
            observed = active.get(wired) as android.media.AudioTrack?
        }
        release.countDown()
        scope.cancel()
        runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(3000) {
                old.join()
                successor.join()
            }
        }
        wired.release()
        assertSame("successor must not replace the shared slot while its cleanup lock is held", oldTrack.get(), observed)
    }
}

private fun java.io.DataOutputStream.writeIntLE(value: Int) {
    writeByte(value and 0xFF)
    writeByte((value shr 8) and 0xFF)
    writeByte((value shr 16) and 0xFF)
    writeByte((value shr 24) and 0xFF)
}

private fun java.io.DataOutputStream.writeShortLE(value: Int) {
    writeByte(value and 0xFF)
    writeByte((value shr 8) and 0xFF)
}

private fun writeMinimalWav(file: java.io.File, samples: Int, sampleRateHz: Int) {
    val dataBytes = samples * 2
    java.io.DataOutputStream(java.io.BufferedOutputStream(file.outputStream())).use { out ->
        out.writeBytes("RIFF")
        out.writeIntLE(36 + dataBytes)
        out.writeBytes("WAVE")
        out.writeBytes("fmt ")
        out.writeIntLE(16)
        out.writeShortLE(1) // PCM
        out.writeShortLE(1) // mono
        out.writeIntLE(sampleRateHz)
        out.writeIntLE(sampleRateHz * 2) // byte rate
        out.writeShortLE(2) // block align
        out.writeShortLE(16) // bits per sample
        out.writeBytes("data")
        out.writeIntLE(dataBytes)
        repeat(samples) { out.writeShortLE(1_000) }
    }
}
