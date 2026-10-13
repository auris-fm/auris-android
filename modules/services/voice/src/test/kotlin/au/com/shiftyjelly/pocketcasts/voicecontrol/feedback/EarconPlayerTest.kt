package au.com.shiftyjelly.pocketcasts.voicecontrol.feedback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EarconPlayerTest {
    private lateinit var player: EarconPlayer
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        player = EarconPlayer(context)
    }

    @Test
    fun `play does not throw for any EarconId`() {
        EarconId.entries.forEach { id ->
            try {
                player.play(id)
            } catch (e: Exception) {
                fail("Unexpected exception: $e")
            }
        }
    }

    @Test
    fun `play returns false for missing assets`() {
        // When assets are not present, play should return false, not crash
        val result = player.play(EarconId.SUCCESS)
        assertFalse(result)
    }

    @Test
    fun `release disposes resources`() {
        player.release()
        // Subsequent play should be no-op, not crash
        try {
            player.play(EarconId.SUCCESS)
        } catch (e: Exception) {
            fail("Unexpected exception: $e")
        }
    }

    @Test
    fun `playback keeps the clip's rate while only the reference is normalized`() {
        // @spec's rate policy: playback keeps original fidelity, and only the reference copy is
        // normalized to the correlator's rate. The two halves live in EarconClip, and nothing pinned
        // that they stay separate — a change that resampled the clip in place would downgrade what the
        // user hears to satisfy the correlator, which is the outcome the policy forbids.
        val clipRate = 44_100
        val clip = EarconAudio.decodeWavToMono(monoWav(rate = clipRate, seconds = 0.25, hz = 440.0).inputStream())

        assertEquals("the decoded clip must keep its own rate", clipRate, clip.sampleRateHz)
        assertEquals(
            "and the samples must be the clip's own, not a rate-converted copy",
            (clipRate * 0.25).toInt(),
            clip.samples.size,
        )

        // The reference side of the same clip: normalized, and a different length by construction.
        val forReference = EarconAudio.resampleRange(
            samples = clip.samples,
            fromRate = clip.sampleRateHz,
            toRate = 16_000,
            startSample = 0,
            count = clip.samples.size,
        )
        val sourceMs = clip.samples.size * 1000 / clipRate
        val referenceMs = forReference.size * 1000 / 16_000
        assertTrue(
            "the reference must be converted to 16 kHz (size=${forReference.size})",
            forReference.size != clip.samples.size,
        )
        assertTrue(
            "and must describe the same instant of sound ($sourceMs ms vs $referenceMs ms)",
            kotlin.math.abs(sourceMs - referenceMs) <= 5,
        )
    }

    @Test
    fun `a clip already at the reference rate is not resampled away`() {
        // The boundary case: when the clip IS at 16 kHz the reference conversion must be a no-op in
        // length, so a change that always converted cannot silently drop audio at this rate.
        val clip = EarconAudio.decodeWavToMono(monoWav(rate = 16_000, seconds = 0.25, hz = 440.0).inputStream())
        val forReference = EarconAudio.resampleRange(
            samples = clip.samples,
            fromRate = clip.sampleRateHz,
            toRate = 16_000,
            startSample = 0,
            count = clip.samples.size,
        )
        assertEquals("no rate change, no length change", clip.samples.size, forReference.size)
    }

    /** A minimal 16-bit mono PCM WAV, so the decoder is driven by a real container. */
    private fun monoWav(rate: Int, seconds: Double, hz: Double): ByteArray {
        val frames = (rate * seconds).toInt()
        val data = java.nio.ByteBuffer.allocate(frames * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val v = (kotlin.math.sin(2.0 * Math.PI * hz * i / rate) * 12_000).toInt().toShort()
            data.putShort(v)
        }
        val pcm = data.array()
        val out = java.nio.ByteBuffer.allocate(44 + pcm.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray())
        out.putInt(36 + pcm.size)
        out.put("WAVE".toByteArray())
        out.put("fmt ".toByteArray())
        out.putInt(16)
        out.putShort(1)
        out.putShort(1)
        out.putInt(rate)
        out.putInt(rate * 2)
        out.putShort(2)
        out.putShort(16)
        out.put("data".toByteArray())
        out.putInt(pcm.size)
        out.put(pcm)
        return out.array()
    }

    @Test
    fun `concurrent play and release never leave an orphaned track or throw`() {
        // `track` is shared mutable state that was written from Main (the listening cue) and IO (the
        // ASR collect coroutine) with no synchronization: two overlapping cues could each build a
        // track and orphan the first -- still playing, never released -- and a release() landing
        // mid-write could leave the newly created track playing forever. Serialising play/release on
        // the player's own thread is what makes them one-at-a-time in call order.
        //
        // This is a STRESS CHECK, not a reproduced race: a plain thread test cannot control the
        // interleaving, so a pass is a guard rather than proof. What it does pin is the contract the
        // serialization must not break -- no throw and no crash under contention -- plus that the
        // player stays usable as a no-op afterwards.
        val player = EarconPlayer(org.robolectric.RuntimeEnvironment.getApplication())
        val failures = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val start = java.util.concurrent.CountDownLatch(1)
        val threads = (0 until 4).map { i ->
            Thread {
                start.await()
                try {
                    if (i % 2 == 0) player.play(EarconId.SUCCESS) else player.release()
                } catch (t: Throwable) {
                    failures.add(t)
                }
            }.apply {
                isDaemon = true
                start()
            }
        }
        start.countDown()
        threads.forEach { it.join(5_000) }

        assertTrue(
            "concurrent play/release must not throw: ${failures.map { it::class.simpleName }}",
            failures.isEmpty(),
        )
        // And the player is still usable as the documented no-op.
        try {
            player.play(EarconId.SUCCESS)
        } catch (t: Throwable) {
            fail("a play after release must be a no-op, not a throw: $t")
        }
        player.release()
    }

    @Test
    fun `a released singleton still attempts playback, and play reports the same as fresh`() {
        // The defect: `released` was one-way and the player is a Hilt @Singleton, while
        // VoiceControlService.stopVoiceControl() releases it on every stop AND AudioFeedbackRenderer
        // holds its own one-way flag too. So one user STOP disabled all feedback for the rest of the
        // process, silently.
        //
        // WHAT THIS CASE CAN AND CANNOT SEE, stated because the first version got it wrong: under
        // Robolectric there are no raw resources, so `clips` is empty and play() returns false no
        // matter what the release state is. A comparison of two players therefore passes with the
        // defect present, which I measured before keeping it. Reaching the real property needs a
        // device, where the clips decode and the track is real.
        //
        // What is checkable here is the lifecycle contract the fix establishes: release() frees
        // resources without making the object terminal, so a subsequent play is ATTEMPTED rather than
        // short-circuited by a flag, and repeated release() stays safe.
        val player = EarconPlayer(context)
        player.release()
        player.release() // idempotent: the service can stop more than once
        try {
            player.play(EarconId.SUCCESS)
        } catch (t: Throwable) {
            fail("a play after release must be safe, not a throw: $t")
        }
        // And the object is still usable as a session's player, not a spent one.
        val fresh = EarconPlayer(context)
        assertEquals(
            "release must leave the player behaving like a freshly constructed one",
            fresh.play(EarconId.SUCCESS),
            player.play(EarconId.SUCCESS),
        )
        player.release()
        fresh.release()
    }

    /**
     * Disposal releases the worker, and a later session still gets one.
     *
     * @spec's teardown correction: documenting a retained thread does not close it. The player is a
     * Hilt `@Singleton` reused by every service session, so the worker must be RELEASED on disposal
     * without making the object permanently unusable -- which is why the executor is created on demand
     * rather than held forever or shut down once.
     */
    @Test
    fun `release shuts the worker down and the next use still runs`() {
        val player = EarconPlayer(context)
        // Start the worker by using it.
        player.play(EarconId.SUCCESS)
        val firstWorker = player.workerThreadForTest()
        assertTrue("using the player must start its worker", firstWorker != null)

        player.release()

        assertTrue(
            "disposal must release the worker thread, not retain it for the process",
            player.workerIsShutdownForTest(),
        )

        // And a later session must still be served: a shut-down executor would refuse here.
        player.play(EarconId.SUCCESS)
        val secondWorker = player.workerThreadForTest()
        assertTrue("a later session must get a worker again", secondWorker != null)
        assertFalse(
            "the new worker must be a live one, not the shut-down instance",
            player.workerIsShutdownForTest(),
        )
        player.release()
    }

    @Test
    fun `a new track starts its own frontier and cannot inherit the previous one`() {
        // @reviewer's track-reset item: the frontier counts frames THIS track has accepted, so a new
        // track must start it at zero.
        //
        // THE DIRECTION MATTERS and my first description of it was WRONG: carrying the previous total
        // forward does not report the new cue Consumed early -- it reports it PLAYING FOREVER. The
        // boundary becomes old + new (200) while the new track's head can only reach its own accepted
        // count (100), so Consumed is never reached and the cue never completes. @reviewer measured
        // exactly that and corrected the rationale.
        val mockPlayer = EarconPlayer(context)
        val clipClass = Class.forName(EarconPlayer::class.java.name + "\$EarconClip")
        val clip = clipClass.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(mockPlayer, ShortArray(100), 16000)
        @Suppress("UNCHECKED_CAST")
        mockPlayer.javaClass.getDeclaredField("clips").apply { isAccessible = true }
            .set(mockPlayer, mapOf(EarconId.WAKE_WORD to clip))

        fun installTrack(): () -> Unit {
            val track = org.mockito.Mockito.mock(android.media.AudioTrack::class.java)
            org.mockito.Mockito.`when`(track.sampleRate).thenReturn(16000)
            org.mockito.Mockito.`when`(
                track.write(
                    org.mockito.ArgumentMatchers.any(ShortArray::class.java),
                    org.mockito.ArgumentMatchers.anyInt(),
                    org.mockito.ArgumentMatchers.anyInt(),
                ),
            ).thenAnswer { it.getArgument<Int>(2) }
            mockPlayer.javaClass.getDeclaredField("track").apply { isAccessible = true }.set(mockPlayer, track)
            var head = 0
            mockPlayer.readPlaybackHeadFrames = { head }
            return { head++ }
        }

        // First track: a cue is accepted and consumed.
        val advanceFirst = installTrack()
        assertTrue(mockPlayer.play(EarconId.WAKE_WORD))
        repeat(100) { advanceFirst() }
        assertEquals(
            "the first cue is consumed on its own track",
            EarconPlayer.EarconCompletion.Consumed,
            mockPlayer.completion(),
        )

        // A NEW track, as a later session would create. Its head starts at zero.
        mockPlayer.release()
        val advanceSecond = installTrack()
        assertTrue(mockPlayer.play(EarconId.WAKE_WORD))

        assertEquals(
            "a cue on a NEW track that has not begun sounding is QUEUED, not Playing: its frames " +
                "have been accepted and the head has not passed its first frame",
            EarconPlayer.EarconCompletion.Queued,
            mockPlayer.completion(),
        )
        repeat(100) { advanceSecond() }
        assertEquals(
            "and it is Consumed once its own frames have",
            EarconPlayer.EarconCompletion.Consumed,
            mockPlayer.completion(),
        )
        mockPlayer.release()
    }

    @Test
    fun `a second cue cannot inherit the first cue's consumed head`() {
        // @reviewer's measured defect: the track's playback head is CUMULATIVE across cues, while
        // completion compared it against the cue's own frame COUNT. So a second cue read Consumed
        // immediately -- the head already stood at the first cue's total, which exceeds the second
        // cue's count before any of its frames had sounded.
        val track = org.mockito.Mockito.mock(android.media.AudioTrack::class.java)
        org.mockito.Mockito.`when`(track.sampleRate).thenReturn(16000)
        org.mockito.Mockito.`when`(
            track.write(
                org.mockito.ArgumentMatchers.any(ShortArray::class.java),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
            ),
        ).thenAnswer { it.getArgument<Int>(2) }

        val clipClass = Class.forName(EarconPlayer::class.java.name + "\$EarconClip")
        val clip = clipClass.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(player, ShortArray(100), 16000)
        player.javaClass.getDeclaredField("track").apply { isAccessible = true }.set(player, track)
        @Suppress("UNCHECKED_CAST")
        player.javaClass.getDeclaredField("clips").apply { isAccessible = true }
            .set(player, mapOf(EarconId.WAKE_WORD to clip))

        var head = 0
        player.readPlaybackHeadFrames = { head }

        assertTrue("the first cue is accepted", player.play(EarconId.WAKE_WORD))
        assertEquals(
            "the first cue is QUEUED while the head has not passed its first frame: accepted, " +
                "not yet audible",
            EarconPlayer.EarconCompletion.Queued,
            player.completion(),
        )
        head = 100
        assertEquals(
            "and CONSUMED once it has",
            EarconPlayer.EarconCompletion.Consumed,
            player.completion(),
        )

        assertTrue("the second cue is accepted", player.play(EarconId.WAKE_WORD))
        assertEquals(
            "a cue whose frames have not advanced must not inherit the previous cue's head " +
                "(head=$head): it is QUEUED, which is the whole point -- inheriting the head would " +
                "have reported it CONSUMED with none of its frames sounded",
            EarconPlayer.EarconCompletion.Queued,
            player.completion(),
        )
        head = 200
        assertEquals(
            "and it is CONSUMED once its own frames have sounded",
            EarconPlayer.EarconCompletion.Consumed,
            player.completion(),
        )
    }

    @Test
    fun `a queued first cue does not move the second cue's consumed boundary backwards`() {
        // @reviewer's measured case: cue 1 is queued (head 50 of its 100 frames played) and cue 2 adds
        // 100 more, so only 50 of cue 2's frames can have sounded. Reading the head at submission
        // understated the boundary and reported cue 2 Consumed early. The boundary is the frame
        // FRONTIER the track has accepted.
        val mockPlayer = EarconPlayer(context)
        val track = org.mockito.Mockito.mock(android.media.AudioTrack::class.java)
        org.mockito.Mockito.`when`(track.sampleRate).thenReturn(16000)
        org.mockito.Mockito.`when`(
            track.write(
                org.mockito.ArgumentMatchers.any(ShortArray::class.java),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
            ),
        ).thenAnswer { it.getArgument<Int>(2) }
        val clipClass = Class.forName(EarconPlayer::class.java.name + "\$EarconClip")
        val clip = clipClass.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(mockPlayer, ShortArray(100), 16000)
        mockPlayer.javaClass.getDeclaredField("track").apply { isAccessible = true }.set(mockPlayer, track)
        @Suppress("UNCHECKED_CAST")
        mockPlayer.javaClass.getDeclaredField("clips").apply { isAccessible = true }
            .set(mockPlayer, mapOf(EarconId.WAKE_WORD to clip))

        var head = 0
        mockPlayer.readPlaybackHeadFrames = { head }

        assertTrue(mockPlayer.play(EarconId.WAKE_WORD))
        head = 50 // half of cue 1 has sounded; cue 1 is still queued
        assertTrue(mockPlayer.play(EarconId.WAKE_WORD))
        head = 150 // cue 1 finished (100), so only 50 of cue 2's frames have sounded

        assertEquals(
            "only 50 of the second cue's 100 frames have been consumed (head=$head)",
            EarconPlayer.EarconCompletion.Playing,
            mockPlayer.completion(),
        )
        head = 200
        assertEquals(
            "and it is Consumed once the frontier is reached",
            EarconPlayer.EarconCompletion.Consumed,
            mockPlayer.completion(),
        )
        mockPlayer.release()
    }

    @Test
    fun `a head that advances during submission does not move the accepted boundary`() {
        // The single-cue form of the same defect: the head moves WHILE the frames are being accepted,
        // so a boundary read from the head at submission is already stale when the cue finishes.
        val mockPlayer = EarconPlayer(context)
        val track = org.mockito.Mockito.mock(android.media.AudioTrack::class.java)
        org.mockito.Mockito.`when`(track.sampleRate).thenReturn(16000)
        var headDuringWrite = 0
        org.mockito.Mockito.`when`(
            track.write(
                org.mockito.ArgumentMatchers.any(ShortArray::class.java),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
            ),
        ).thenAnswer {
            headDuringWrite = 100
            it.getArgument<Int>(2)
        }
        val clipClass = Class.forName(EarconPlayer::class.java.name + "\$EarconClip")
        val clip = clipClass.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(mockPlayer, ShortArray(100), 16000)
        mockPlayer.javaClass.getDeclaredField("track").apply { isAccessible = true }.set(mockPlayer, track)
        @Suppress("UNCHECKED_CAST")
        mockPlayer.javaClass.getDeclaredField("clips").apply { isAccessible = true }
            .set(mockPlayer, mapOf(EarconId.WAKE_WORD to clip))
        mockPlayer.readPlaybackHeadFrames = { headDuringWrite }

        assertTrue(mockPlayer.play(EarconId.WAKE_WORD))

        assertEquals(
            "all 100 submitted frames are already consumed, so the cue is complete",
            EarconPlayer.EarconCompletion.Consumed,
            mockPlayer.completion(),
        )
        mockPlayer.release()
    }

    @Test
    fun `an installed observer returning null stays unavailable rather than using the platform head`() {
        // @reviewer's third case: a selected observation of null means "not observable". Falling back
        // to the platform head would fabricate an observation the observer explicitly did not make.
        val mockPlayer = EarconPlayer(context)
        val track = org.mockito.Mockito.mock(android.media.AudioTrack::class.java)
        org.mockito.Mockito.`when`(track.sampleRate).thenReturn(16000)
        org.mockito.Mockito.`when`(
            track.write(
                org.mockito.ArgumentMatchers.any(ShortArray::class.java),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
            ),
        ).thenAnswer { it.getArgument<Int>(2) }
        val clipClass = Class.forName(EarconPlayer::class.java.name + "\$EarconClip")
        val clip = clipClass.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(mockPlayer, ShortArray(100), 16000)
        mockPlayer.javaClass.getDeclaredField("track").apply { isAccessible = true }.set(mockPlayer, track)
        @Suppress("UNCHECKED_CAST")
        mockPlayer.javaClass.getDeclaredField("clips").apply { isAccessible = true }
            .set(mockPlayer, mapOf(EarconId.WAKE_WORD to clip))
        mockPlayer.readPlaybackHeadFrames = { null }

        assertTrue(mockPlayer.play(EarconId.WAKE_WORD))

        assertEquals(
            "a nil selected observer is UNAVAILABLE, not a platform-head reading",
            EarconPlayer.EarconCompletion.Unknown,
            mockPlayer.completion(),
        )
        mockPlayer.release()
    }

    @Test
    fun `frames a FAILED cue already accepted still count against the next cue`() {
        // @reviewer's partial-acceptance case. A cue can be accepted in part and then fail: the track
        // takes some frames and the next write returns an error. Returning on that failure WITHOUT
        // folding the accepted frames into the frontier forgets audio the track will really play, so the
        // NEXT cue's target excludes it -- the head, which does include it, passes that target early and
        // the next cue reads Consumed while half of it is still sounding.
        //
        // The two facts are separate: the cue FAILED (reported to the caller), and the track accepted
        // some of its frames (what the frontier must record).
        val mockPlayer = EarconPlayer(context)
        val clipClass = Class.forName(EarconPlayer::class.java.name + "\$EarconClip")
        val clip = clipClass.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(mockPlayer, ShortArray(100), 16000)
        @Suppress("UNCHECKED_CAST")
        mockPlayer.javaClass.getDeclaredField("clips").apply { isAccessible = true }
            .set(mockPlayer, mapOf(EarconId.WAKE_WORD to clip))

        val track = org.mockito.Mockito.mock(android.media.AudioTrack::class.java)
        org.mockito.Mockito.`when`(track.sampleRate).thenReturn(16000)
        // First cue: 50 of 100 frames accepted, then the write fails.
        org.mockito.Mockito.`when`(
            track.write(
                org.mockito.ArgumentMatchers.any(ShortArray::class.java),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
            ),
        ).thenReturn(50, -1)
        mockPlayer.javaClass.getDeclaredField("track").apply { isAccessible = true }.set(mockPlayer, track)
        var head = 0
        mockPlayer.readPlaybackHeadFrames = { head }

        assertFalse("the first cue is reported as FAILED", mockPlayer.play(EarconId.WAKE_WORD))

        // Second cue: fully accepted.
        org.mockito.Mockito.`when`(
            track.write(
                org.mockito.ArgumentMatchers.any(ShortArray::class.java),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
            ),
        ).thenAnswer { it.getArgument<Int>(2) }
        assertTrue("the second cue is accepted", mockPlayer.play(EarconId.WAKE_WORD))

        // The head reaches 100: the 50 the failed cue accepted, plus 50 of the new 100.
        head = 100
        val result = mockPlayer.completion()
        mockPlayer.release()

        assertEquals(
            "only 50 of the second cue's frames have sounded, because the failed cue's accepted " +
                "frames are ahead of them in the queue",
            EarconPlayer.EarconCompletion.Playing,
            result,
        )
    }

    @Test
    fun `releasing the worker between selection and submission cannot reject the submission`() {
        // DETERMINISTIC, and it drives the PRODUCTION path. The earlier version was two racing threads
        // whose own comment admitted it passed with the fix mutated back: it could neither fail for the
        // defect nor avoid false alarms.
        //
        // The executor records the moment it is SELECTED, and refuses if it is USED after that moment has
        // been made visible to another thread. Sequencing it explicitly makes the two paths differ:
        //
        //   fixed   selection and submit happen in one locked step, so "used after selected" is never
        //           observable to the releaser: the submission succeeds.
        //   defect  the submit happens after the lock is dropped, so the releaser's shutdown is visible
        //           by then and the submission is refused.
        val player = EarconPlayer(context)
        val selected = java.util.concurrent.CountDownLatch(1)
        val releaserRan = java.util.concurrent.CountDownLatch(1)
        val delegate = java.util.concurrent.Executors.newSingleThreadExecutor()
        val guarded = object : java.util.concurrent.AbstractExecutorService() {
            override fun shutdown() {}
            override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
            override fun isShutdown(): Boolean = false
            override fun isTerminated(): Boolean = false
            override fun awaitTermination(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean = false

            @Suppress("SUBTLE_OVERRIDE")
            override fun execute(command: Runnable) {
                // SELECTED: another thread may now release, which is the state the lock must exclude.
                selected.countDown()

                // If the release has already COMPLETED by the time this executor is used, the selection
                // and the use were separate steps -- the defect -- and the release wins.
                val releasedFirst = releaserRan.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (releasedFirst) throw java.util.concurrent.RejectedExecutionException("worker released")
                delegate.execute(command)
            }
        }
        player.installExecutorForTest(guarded)

        // Releases as soon as the executor has been selected. Under the fix the submission is already
        // inside the lock, so the release cannot complete in time.
        Thread {
            selected.await(2, java.util.concurrent.TimeUnit.SECONDS)
            player.shutdownExecutorForTest()
            releaserRan.countDown()
        }.apply { start() }

        assertEquals(
            "selection and submission must be one locked step, so a release landing between them " +
                "cannot reject the submission",
            1,
            player.submitForTest { 1 },
        )
    }

    @Test
    fun `a stop aimed at a cue that is not playing does not silence the one that is`() {
        // @reviewer: the real player's stop must not be able to harm a successor. Its block runs LATER on
        // the player thread, by which time `track` may hold a DIFFERENT cue -- and stopping the TRACK
        // would stop audio nobody asked to stop. So the stop is scoped to the cue it was issued for.
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val mockPlayer = EarconPlayer(context)
        val clipClass = Class.forName(EarconPlayer::class.java.name + "\$EarconClip")
        val clip = clipClass.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(mockPlayer, ShortArray(100), 16000)
        @Suppress("UNCHECKED_CAST")
        mockPlayer.javaClass.getDeclaredField("clips").apply { isAccessible = true }
            .set(mockPlayer, mapOf(EarconId.WAKE_WORD to clip, EarconId.LISTENING_START to clip))
        val track = org.mockito.Mockito.mock(android.media.AudioTrack::class.java)
        org.mockito.Mockito.`when`(track.sampleRate).thenReturn(16000)
        org.mockito.Mockito.`when`(
            track.write(
                org.mockito.ArgumentMatchers.any(ShortArray::class.java),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
            ),
        ).thenAnswer { it.getArgument<Int>(2) }
        mockPlayer.javaClass.getDeclaredField("track").apply { isAccessible = true }.set(mockPlayer, track)
        mockPlayer.readPlaybackHeadFrames = { 0 }

        assertTrue("the cue under test must be accepted", mockPlayer.play(EarconId.WAKE_WORD))
        // THE PRECONDITION: the track must OWE the cue, or the case would be about a failed play rather
        // than about the scoping it asks about.
        assertEquals(
            "the precondition is that the track owes the cue just played",
            EarconId.WAKE_WORD,
            mockPlayer.javaClass.getDeclaredField("lastPlayedId")
                .apply { isAccessible = true }
                .get(mockPlayer),
        )

        // A STOP FOR A DIFFERENT CUE: the track is playing WAKE_WORD, and this stop speaks for another.
        mockPlayer.stopCueNow(EarconId.LISTENING_START)

        org.mockito.Mockito.verify(track, org.mockito.Mockito.never()).stop()
    }
}
