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
import org.junit.Assert.assertNotEquals
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
        val (wired, fake, _) = engineWithFakeSynthesizer()
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
        val (wired, fake, _) = engineWithFakeSynthesizer()
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
        val (wired, fake, _) = engineWithFakeSynthesizer()
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
        val (wired, fake, _) = engineWithFakeSynthesizer()
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

    @Test
    fun `a late onDone from a cancelled utterance must not complete its successor`() {
        // Reviewer 3aaab695 (original finding 4236364898): the utterance listener is SHARED across
        // speak() calls -- B's setOnUtteranceProgressListener REPLACES A's, so any LATE callback
        // from the cancelled A is delivered into B's listener, which resumes B's captured
        // continuation WITHOUT checking the utterance id. A cancel / B start / late A onDone thus
        // completes B while B's own synthesis is still outstanding.
        val (wired, fake, _) = engineWithFakeSynthesizer()
        fake.onInitialized?.invoke(true)
        val scope = CoroutineScope(Dispatchers.Default)

        val first = scope.launch { wired.speak("first", "en") }
        assertTrue("precondition: A reached synthesis", awaitSynthesize(fake, 1))
        val aId = fake.lastUtteranceId

        // A is cancelled while suspended in synthesis.
        runBlocking { first.cancelAndJoin() }

        // B starts on the shared engine: its listener is now the one installed.
        val second = scope.launch { wired.speak("second", "en") }
        assertTrue("precondition: B reached synthesis", awaitSynthesize(fake, 2))
        val bId = fake.lastUtteranceId
        assertNotEquals("each synthesis needs its own utterance id", aId, bId)

        // A's LATE completion arrives at B's listener.
        fake.listener?.onDone(aId)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        Thread.sleep(200)
        assertFalse(
            "B must stay pending: a late onDone from A must not complete B",
            second.isCompleted,
        )

        // Only B's OWN completion finishes it.
        fake.listener?.onDone(bId)
        var done = false
        for (i in 0 until 100) {
            Thread.sleep(20)
            if (second.isCompleted) {
                done = true
                break
            }
        }
        assertTrue("B completes on its own utterance id", done)
        scope.cancel()
    }

    @Test
    fun `a late onError from a cancelled utterance must not fail its successor`() {
        // Same shared-listener window as the onDone case, ERROR flavour: A's late onError must not
        // be read as B's synthesis failure.
        val (wired, fake, _) = engineWithFakeSynthesizer()
        fake.onInitialized?.invoke(true)
        val scope = CoroutineScope(Dispatchers.Default)

        val first = scope.launch { wired.speak("first", "en") }
        assertTrue(awaitSynthesize(fake, 1))
        val aId = fake.lastUtteranceId
        runBlocking { first.cancelAndJoin() }

        val second = scope.launch { wired.speak("second", "en") }
        assertTrue(awaitSynthesize(fake, 2))
        val bId = fake.lastUtteranceId

        @Suppress("DEPRECATION")
        fake.listener?.onError(aId)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        Thread.sleep(200)
        assertFalse("B must stay pending: A's late onError must not fail B", second.isCompleted)

        fake.listener?.onDone(bId)
        var done = false
        for (i in 0 until 100) {
            Thread.sleep(20)
            if (second.isCompleted) {
                done = true
                break
            }
        }
        assertTrue(done)
        scope.cancel()
    }

    @Test
    fun `a refused synthesis is one-shot and a late completion cannot double-resume it`() {
        // An immediate synthesis refusal resumes the continuation with false; a LATE platform
        // callback for the same (or a stale) utterance id must not resume it a second time. A
        // double resume throws IllegalStateException out of the listener -- on a binder thread in
        // production.
        val (wired, fake, _) = engineWithFakeSynthesizer()
        fake.onInitialized?.invoke(true)
        fake.refuseNext = true
        val scope = CoroutineScope(Dispatchers.Default)

        val job = scope.launch { wired.speak("refused", "en") }
        var refused = false
        for (i in 0 until 100) {
            // speak() touches the platform engine on Main first: idle the looper so the coroutine
            // can reach the synthesis call.
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
            if (job.isCompleted) {
                refused = true
                break
            }
        }
        assertTrue("precondition: the refusal surfaced as false", refused)
        val utteranceId = fake.lastUtteranceId

        // The platform's LATE callback for the refused utterance must be a no-op, not a crash.
        var lateThrew: Throwable? = null
        try {
            fake.listener?.onDone(utteranceId)
            @Suppress("DEPRECATION")
            fake.listener?.onError(utteranceId)
            Thread.sleep(200)
        } catch (t: Throwable) {
            lateThrew = t
        }
        assertNull("a late completion after a refusal must not double-resume", lateThrew)
        scope.cancel()
    }

    @Test
    fun `utterance ids are unique across consecutive syntheses on the shared engine`() {
        // Millisecond-clock ids collide; every synthesis must carry a distinct id so a callback can
        // always be attributed to its own utterance. Control for the id scheme itself.
        val (wired, fake, _) = engineWithFakeSynthesizer()
        fake.onInitialized?.invoke(true)
        val scope = CoroutineScope(Dispatchers.Default)
        val ids = mutableListOf<String>()
        repeat(3) {
            var job: kotlinx.coroutines.Job? = null
            job = scope.launch { wired.speak("u$it", "en") }
            assertTrue(awaitSynthesize(fake, it + 1))
            ids.add(fake.lastUtteranceId)
            runBlocking { job.cancelAndJoin() }
        }
        assertEquals("all ids distinct", ids.size, ids.distinct().size)
        scope.cancel()
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
    ): Triple<AndroidPlatformTtsEngine, FakeSynthesizer, PlaybackBufferRecorder> {
        val fake = FakeSynthesizer(onSynthesize)
        val recorder = PlaybackBufferRecorder(FingerprintPcmTap())
        lateinit var engineRef: AndroidPlatformTtsEngine
        engineRef = AndroidPlatformTtsEngine(
            context,
            recorder,
        ) { onInitialized ->
            fake.onInitialized = onInitialized
            fake
        }
        return Triple(engineRef, fake, recorder)
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

        /** When set, the next synthesizeToFile REFUSES (non-SUCCESS), as the platform can. */
        @Volatile
        var refuseNext = false
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
            if (refuseNext) {
                return android.speech.tts.TextToSpeech.ERROR
            }
            // Write a real WAV only when a test asks for the write path to be reached: an empty file
            // makes speak() return before the write loop, so without this the truncation case would
            // exercise nothing. Off by default, because a decodable clip also engages the drain wait,
            // which the synthesis-oriented cases do not need.
            if (emitRealWav) {
                file?.also {
                    // Amplitude encodes WHICH utterance this is (first: 1_000, later: 24_000), so a test
                    // can tell whose audio reached the reference -- same shape, different identity.
                    // synthesizeCount ALREADY includes this utterance (incremented above).
                    val level = if (synthesizeCount == 1) 1_000 else 24_000
                    writeMinimalWav(it, samples = 512, sampleRateHz = 22050, sampleValue = level)
                }
            }
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
        val (wired, fake, _) = engineWithFakeSynthesizer()
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
        val (wired, fake, _) = engineWithFakeSynthesizer()
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
        val (wired, fake, _) = engineWithFakeSynthesizer(onSynthesize = { oldSynthesizing.countDown() })
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
        val (wired, fake, _) = engineWithFakeSynthesizer()
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
        val (wired, fake, _) = engineWithFakeSynthesizer()
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
        val (wired, fake, _) = engineWithFakeSynthesizer()
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
        val (wired, fake, _) = engineWithFakeSynthesizer()
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

    // CONTROL @reviewer/@spec: a HELD OLD TTS WRITE released after a successor has started and the old
    // utterance's cleanup ran must not append its audio under the SUCCESSOR'S reference identity. The
    // defect shape was recordAccepted reading the shared currentUtteranceAdmission at RECORDING time:
    // an old stream blocked inside AudioTrack.write resumes AFTER a successor published its own
    // admission, and the old samples get written with the NEWER valid ticket -- contamination no
    // recorder-level control can see. The fix threads the immutable admission captured with the
    // utterance's LOCAL track through the write path, and cleanup retires THAT local admission even
    // when activeTrack already belongs to the successor.
    @Test
    fun `a tts write held across a successor start cannot append old audio under the new ticket`() {
        val (wired, fake, recorder) = engineWithFakeSynthesizer()
        fake.onInitialized?.invoke(true)
        fake.emitRealWav = true
        wired.drainWaitMs = 0

        val enteredOldWrite = java.util.concurrent.CountDownLatch(1)
        val enteredSuccessorWrite = java.util.concurrent.CountDownLatch(1)
        val releaseOldWrite = java.util.concurrent.CountDownLatch(1)
        val releaseSuccessorWrite = java.util.concurrent.CountDownLatch(1)
        var writes = 0
        wired.writeToSink = { _: android.media.AudioTrack, _, _, count ->
            when (++writes) {
                1 -> {
                    enteredOldWrite.countDown()
                    releaseOldWrite.await(5, java.util.concurrent.TimeUnit.SECONDS)
                }

                2 -> {
                    enteredSuccessorWrite.countDown()
                    // Hold the SUCCESSOR inside its own accepted write as well: its admission is
                    // published and LIVE while the old write resumes -- exactly the window where the
                    // shared-field defect would stamp the successor's VALID ticket onto old audio.
                    releaseSuccessorWrite.await(5, java.util.concurrent.TimeUnit.SECONDS)
                }
            }
            count
        }

        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        try {
            val oldJob = scope.launch { runCatching { wired.speak("old reply", "en") } }
            val oldQueued = (0 until 50).any {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.sleep(20)
                fake.synthesizeCount >= 1
            }
            check(oldQueued) { "precondition: the old utterance must reach synthesis" }
            // Synthesis COMPLETION is what releases the file for playback (the fake writes the wav in
            // onRequestSynthesis; onDone drives the listener path the engine waits on).
            fake.listener?.onDone(fake.lastUtteranceId)
            val reached = (0 until 100).any {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.sleep(20)
                enteredOldWrite.await(10, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            check(reached) { "precondition: the old utterance must be blocked inside its accepted write" }

            // A SUCCESSOR starts and reaches ITS OWN accepted write while the old write is still
            // blocked. Its wav carries amplitude 24_000 (~0.73f); the old clip is 1_000 (~0.03f).
            val successorJob = scope.launch { runCatching { wired.speak("new reply", "en") } }
            val secondQueued = (0 until 100).any {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.sleep(20)
                fake.synthesizeCount >= 2
            }
            check(secondQueued) { "precondition: the successor must reach synthesis (count=${'$'}{fake.synthesizeCount})" }
            fake.listener?.onDone(fake.lastUtteranceId)
            val successorInWrite = (0 until 100).any {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.sleep(20)
                enteredSuccessorWrite.await(10, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            check(successorInWrite) { "precondition: the successor must be inside its accepted write" }

            // OLD retirement runs WHILE the successor's admission is live: supersession makes the old
            // utterance exit (its cleanup retires ITS local admission even though activeTrack now
            // belongs to the successor), then its held write RESUMES against the successor's still-
            // published ticket. Under the fix the resumed write uses the old CLOSED admission ->
            // refused. Under the shared-field defect it reads the successor's LIVE ticket -> stored.
            releaseOldWrite.countDown()
            val oldCompleted = kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(5_000) { oldJob.join() } != null
            }
            check(oldCompleted) { "precondition: the old utterance must COMPLETE after its write resumes" }

            // Let the successor finish normally afterwards.
            releaseSuccessorWrite.countDown()
            val successorCompleted = kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(5_000) { successorJob.join() } != null
            }
            check(successorCompleted) { "precondition: the successor must complete after its write resumes" }

            // ATTRIBUTION, not presence: both utterances legitimately leave tagged tails until they
            // expire, so the question is WHICH admission's ticket each sample carries. Old-utterance
            // audio (amplitude ~0.03) written under the SUCCESSOR's live ticket is exactly the defect
            // shape; under the fix those samples carry the OLD utterance's own ticket and its cleanup
            // retired that incarnation, zeroing them beyond the delay tail.
            val snapshot = recorder.snapshot()
            val tags = recorder.ownerTagIdsForTest()
            assertEquals("tags align with samples", snapshot.size, tags.size)
            assertTrue("the successor's audio is in the reference (ring non-empty)", snapshot.isNotEmpty())
            val oldTickets = tags.filterIndexed { i, _ -> kotlin.math.abs(snapshot[i]) in 0.025f..0.04f }.toSet()
            val successorTickets = tags.filterIndexed { i, _ -> snapshot[i] > 0.6f }.toSet()
            // @reviewer: without this, an empty successor set would make the disjointness below
            // vacuously true even if the successor contributed nothing to the ring at all.
            assertTrue(
                "the successor must have recorded audio under ITS OWN ticket (else disjointness is vacuous)",
                successorTickets.isNotEmpty(),
            )
            assertTrue(
                "old-utterance samples must NOT be attributed to any ticket that also owns successor " +
                    "audio (shared-field read would do exactly that): oldTickets=$oldTickets " +
                    "successorTickets=$successorTickets",
                oldTickets.intersect(successorTickets).isEmpty(),
            )
            wired.release()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `engine release during a blocked write never queries a released track`() {
        // @reviewer 4236364948: AudioFeedbackRenderer.release() calls engine release() while this
        // utterance is BLOCKED in track.write on IO -- and the old release() stopped AND RELEASED the
        // same AudioTrack. The loop then resumed, evaluated playbackHeadPosition on the released
        // instance, and threw IllegalStateException: stopping voice control mid-answer turned speak()
        // into a crash-shaped failure instead of a clean shutdown.
        //
        // Contract now: release() only STOPS the local track (silencing immediately); releasing the
        // native object belongs to the utterance's own finally, which runs after its loops exit. A
        // stopped-or-broken position read ends the drain as an INCOMPLETE outcome -- never evidence
        // that audio played, never an uncaught IllegalStateException.
        val (wired, fake, _) = engineWithFakeSynthesizer()
        fake.onInitialized?.invoke(true)
        fake.emitRealWav = true
        wired.drainWaitMs = 50

        val enteredWrite = java.util.concurrent.CountDownLatch(1)
        val releaseWrite = java.util.concurrent.CountDownLatch(1)
        wired.writeToSink = { _, _, _, count ->
            enteredWrite.countDown()
            releaseWrite.await(5, java.util.concurrent.TimeUnit.SECONDS)
            count
        }

        val scope = CoroutineScope(Dispatchers.Default)
        var thrown: Throwable? = null
        val job = scope.launch {
            try {
                wired.speak("a long reply", "en")
            } catch (t: Throwable) {
                thrown = t
            }
        }
        val queued = (0 until 50).any {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
            fake.synthesizeCount > 0
        }
        assertTrue("the utterance must reach synthesis", queued)
        fake.listener?.onDone(fake.lastUtteranceId)
        assertTrue("the reply must reach its first write", enteredWrite.await(5, java.util.concurrent.TimeUnit.SECONDS))

        // Release the ENGINE while the write is still blocked -- the exact interleaving from the
        // finding (renderer teardown on another thread).
        wired.release()
        // Sample the LOCAL track's state BEFORE unblocking the write. Ownership contract: engine
        // release() silences (stop) but does NOT free the native object -- release belongs to the
        // utterance's own finally, which has not run yet. A released instance reports an
        // uninitialized state; a merely stopped one keeps reporting STOPPED. This is the observable
        // JVM proxy for "the drain cannot be handed a released track".
        val stateWhileBlocked = wired.ownedTrackPlaybackStateForTest()
        assertTrue(
            "engine release() must stop the owned track immediately (state=$stateWhileBlocked)",
            stateWhileBlocked == android.media.AudioTrack.PLAYSTATE_STOPPED ||
                stateWhileBlocked == android.media.AudioTrack.PLAYSTATE_PAUSED,
        )
        assertNotEquals(
            "engine release() must NOT release the native track while the utterance still owns it " +
                "(a released AudioTrack reports state 0 / uninitialized)",
            0,
            stateWhileBlocked,
        )
        releaseWrite.countDown()
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(5_000) { job.join() } ?: fail("speak() hung after engine release")
        }
        scope.cancel()

        val ise = generateSequence(thrown) { it.cause }
            .firstOrNull { it is IllegalStateException }
        assertNull(
            "release during a blocked write/drain must not surface IllegalStateException from a " +
                "released track (thrown=$thrown)",
            ise,
        )
        // Clean shutdown means EITHER a normal return or the explicit incomplete-playback outcome --
        // the caller can act on a decision; it must not be a crash.
        if (thrown != null) {
            assertTrue(
                "a non-null failure after release must be the declared incomplete-playback outcome, " +
                    "got ${thrown::class.simpleName}",
                thrown is au.com.shiftyjelly.pocketcasts.voicecontrol.tts.TtsPlaybackIncompleteException ||
                    thrown is kotlinx.coroutines.CancellationException,
            )
        }
        // Ownership closes in the owner: exactly one cleanup release by the utterance itself, AFTER
        // its loops exited -- never while another thread could still read the instance.
        assertEquals(
            "the utterance's own finally must release its local track once",
            1,
            wired.ownedTrackReleasesForTest,
        )
    }

    @Test
    @Suppress("DEPRECATION")
    fun `engine release silences the owned track without freeing it while the owner still reads`() {
        // @reviewer ca0a39554 review: the ownership contract must be observable at the instance
        // boundary, not inferred from counters that stay green when the forbidden release returns.
        // The injected track records release() and refuses position reads afterwards -- exactly the
        // native hazard shape (IllegalStateException on a freed AudioTrack) -- so premature
        // engine-level release is falsifiable on the JVM.
        class RecordingTrack(sampleRateHz: Int) :
            android.media.AudioTrack(
                android.media.AudioManager.STREAM_MUSIC,
                sampleRateHz,
                android.media.AudioFormat.CHANNEL_OUT_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                4096,
                android.media.AudioTrack.MODE_STREAM,
            ) {
            var releases = 0
                private set
            var released = false
                private set
            override fun release() {
                releases += 1
                released = true
                super.release()
            }

            override fun getPlaybackHeadPosition(): Int {
                if (released) throw IllegalStateException("playback head position on a released AudioTrack")
                return super.getPlaybackHeadPosition()
            }
        }

        val (wired, fake, _) = engineWithFakeSynthesizer()
        fake.onInitialized?.invoke(true)
        fake.emitRealWav = true
        wired.drainWaitMs = 200
        var created: RecordingTrack? = null
        wired.trackFactoryForTest = { rate -> RecordingTrack(rate).also { created = it } }

        val enteredWrite = java.util.concurrent.CountDownLatch(1)
        val releaseWrite = java.util.concurrent.CountDownLatch(1)
        wired.writeToSink = { _, _, _, count ->
            enteredWrite.countDown()
            releaseWrite.await(5, java.util.concurrent.TimeUnit.SECONDS)
            count
        }

        val scope = CoroutineScope(Dispatchers.Default)
        var thrown: Throwable? = null
        val job = scope.launch {
            try {
                wired.speak("a long reply", "en")
            } catch (t: Throwable) {
                thrown = t
            }
        }
        val queued = (0 until 50).any {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
            fake.synthesizeCount > 0
        }
        assertTrue("the utterance must reach synthesis", queued)
        fake.listener?.onDone(fake.lastUtteranceId)
        assertTrue("the reply must reach its first write", enteredWrite.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val track = created ?: error("the utterance must have built its local track")

        // Engine-level release during the blocked write: silence NOW, free NEVER while the owner reads.
        wired.release()
        assertEquals(
            "engine release() must not call AudioTrack.release() while the utterance owns the track",
            0,
            track.releases,
        )

        releaseWrite.countDown()
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(5_000) { job.join() } ?: fail("speak() hung after engine release")
        }
        scope.cancel()

        // Any failure escaping speak() must be the declared incomplete outcome or cancellation -- and
        // specifically NOT the released-track IllegalStateException the injected track would raise if
        // the drain read a position after a premature free.
        val ise = generateSequence(thrown) { it.cause }
            .firstOrNull { it is IllegalStateException }
        assertNull("drain must never read position from a prematurely released track (thrown=$thrown)", ise)
        if (thrown != null) {
            assertTrue(
                "a non-null failure after release must be the declared incomplete-playback outcome, got ${thrown::class.simpleName}",
                thrown is au.com.shiftyjelly.pocketcasts.voicecontrol.tts.TtsPlaybackIncompleteException ||
                    thrown is kotlinx.coroutines.CancellationException,
            )
        }
        assertEquals(
            "the owner's finally must release its own track exactly once, after its loops exited",
            1,
            track.releases,
        )
        wired.trackFactoryForTest = null
    }

    @Test
    @Suppress("DEPRECATION")
    fun `a throwing final position read cannot become successful completion`() {
        // @reviewer ca0a39554 review: `if (positionBroken) 0 else headPosition()` consulted the flag
        // BEFORE the final read, so a final read that threw AFTER earlier successful reads returned
        // the fallback == framesWritten and could pass the shortfall check as success. The fix reads
        // once, then checks the flag. The injected track throws only from the second position read,
        // pinning that exact sequence.
        val (wired, fake, _) = engineWithFakeSynthesizer()
        fake.onInitialized?.invoke(true)
        fake.emitRealWav = true
        wired.drainWaitMs = 200

        // Sequence pinned to the defect: in-loop reads SUCCEED but never reach `offset`, so the
        // while-condition exits on the timeout -- positionBroken is still false at that point. Only
        // the FINAL read (the one the buggy ordering evaluated inside `if (positionBroken) 0 else
        // headPosition()`) throws. Pre-flag checking then stores headPosition()'s fallback == offset
        // into framesPlayedAtReturn, the shortfall check passes, and speak() returns "success" for an
        // utterance whose real playback state is unknown.
        // drainWaitMs=200 with a 10 ms step allows at most ~20 condition reads plus the entry read;
        // succeeding through all of them keeps positionBroken false when the timeout exits the loop,
        // so ONLY the post-loop final read can fail -- the exact ordering the fix pins.
        // Measured boundary (probe run): with drainWaitMs=200 and a 10 ms step the utterance makes
        // 21 in-loop condition reads before the timeout exits the loop; the 22nd read is the FINAL
        // post-loop read. Succeeding through 21 keeps positionBroken false at the ordering site, so
        // ONLY the final read can fail -- exactly what the fix pins. Assert the boundary holds below.
        val maxInLoopReads = 21
        var positionReads = 0
        class FlakyTrack(sampleRateHz: Int) :
            android.media.AudioTrack(
                android.media.AudioManager.STREAM_MUSIC,
                sampleRateHz,
                android.media.AudioFormat.CHANNEL_OUT_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                4096,
                android.media.AudioTrack.MODE_STREAM,
            ) {
            override fun getPlaybackHeadPosition(): Int {
                positionReads += 1
                // Throw from every read at or after the drain-timeout boundary: the loop's own
                // condition read may still succeed (returning a stalled 0 < offset); the read made
                // after the loop has exited must fail and force the incomplete outcome.
                if (positionReads > maxInLoopReads) throw IllegalStateException("simulated late position failure")
                return 0
            }
        }
        wired.trackFactoryForTest = { rate -> FlakyTrack(rate) }

        // Writes succeed fully; only the FINAL drain read fails.
        wired.writeToSink = { _, _, _, count -> count }
        val scope = CoroutineScope(Dispatchers.Default)
        var thrown: Throwable? = null
        val job = scope.launch {
            try {
                wired.speak("a reply", "en")
            } catch (t: Throwable) {
                thrown = t
            }
        }
        val queued = (0 until 50).any {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
            fake.synthesizeCount > 0
        }
        assertTrue("the utterance must reach synthesis", queued)
        fake.listener?.onDone(fake.lastUtteranceId)
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(8_000) { job.join() } ?: fail("speak() hung with a failing final read")
        }
        scope.cancel()
        wired.trackFactoryForTest = null

        assertTrue(
            "a failed final position read must produce the explicit incomplete outcome, got thrown=$thrown " +
                "framesPlayed=${wired.framesPlayedAtReturn()} framesWritten=${wired.framesWritten()}",
            thrown is au.com.shiftyjelly.pocketcasts.voicecontrol.tts.TtsPlaybackIncompleteException,
        )
        assertTrue(
            "the control must actually have written frames before the failing final read",
            wired.framesWritten() > 0,
        )
        assertEquals(
            "the fixture must make exactly the measured in-loop reads plus one final failing read",
            22,
            positionReads,
        )
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

private fun writeMinimalWav(file: java.io.File, samples: Int, sampleRateHz: Int, sampleValue: Int = 1_000) {
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
        repeat(samples) { out.writeShortLE(sampleValue) }
    }
}
