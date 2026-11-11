package au.com.shiftyjelly.pocketcasts.voicecontrol.feedback
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun `submit is nonblocking, serialised, and hands each cue its OWN accepted token`() {
        player.installClipForTest(EarconId.SUCCESS, ShortArray(1600) { 1 }, 16_000)
        player.installClipForTest(EarconId.WAKE_WORD, ShortArray(1600) { 2 }, 16_000)
        val track = org.mockito.Mockito.mock(android.media.AudioTrack::class.java)
        org.mockito.Mockito.`when`(
            track.write(
                org.mockito.kotlin.any<ShortArray>(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
            ),
        ).thenAnswer { inv -> inv.getArgument<Int>(2) }
        player.trackFactoryForTest = { track }
        val blockerEntered = java.util.concurrent.CountDownLatch(1)
        val releaseBlocker = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        executor.submit {
            blockerEntered.countDown()
            releaseBlocker.await(5, java.util.concurrent.TimeUnit.SECONDS)
        }
        assertTrue("precondition: the blocker must hold the player thread", blockerEntered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        player.installExecutorForTest(executor)
        val workerFailures = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val tokens = java.util.concurrent.ConcurrentHashMap<String, EarconPlayer.AdmissionToken>()
        val bDone = java.util.concurrent.CountDownLatch(2)
        val t0 = System.nanoTime()
        player.submit(EarconId.SUCCESS) { token ->
            try {
                order += "A"
                if (token == null) {
                    workerFailures += IllegalStateException("A must be accepted, not refused")
                } else {
                    tokens["A"] = token
                }
            } catch (t: Throwable) {
                workerFailures += t
            } finally {
                releaseBlocker.countDown()
                bDone.countDown()
            }
        }
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue(
            "submit must return while the predecessor still holds the player thread (took ${elapsedMs}ms)",
            elapsedMs < 1_000 && releaseBlocker.count > 0,
        )
        player.submit(EarconId.WAKE_WORD) { token ->
            try {
                order += "B"
                if (token == null) {
                    workerFailures += IllegalStateException("B must be accepted, not refused")
                } else {
                    tokens["B"] = token
                    assertTrue(
                        "tokens must be DISTINCT per cue",
                        tokens["A"] != null && tokens["B"] != null && tokens["A"] != tokens["B"],
                    )
                }
            } catch (t: Throwable) {
                workerFailures += t
            } finally {
                bDone.countDown()
            }
        }
        releaseBlocker.countDown()
        assertTrue("both callbacks must complete", bDone.await(5, java.util.concurrent.TimeUnit.SECONDS))
        executor.shutdownNow()
        assertTrue("worker failures: $workerFailures", workerFailures.isEmpty())
        assertEquals("callbacks must run exactly A then B", listOf("A", "B"), order)
        assertTrue("A's token must be its own acceptance", tokens["A"] != null)
        assertTrue("B's token must be its own distinct acceptance", tokens["B"] != null)
    }
    @Test
    fun `a HELD accepted write does not block the submitter, and coverage follows that cue only`() {
        val holdWrite = java.util.concurrent.CountDownLatch(1)
        val enteredWrite = java.util.concurrent.CountDownLatch(1)
        val track = org.mockito.Mockito.mock(android.media.AudioTrack::class.java)
        org.mockito.Mockito.`when`(track.sampleRate).thenReturn(16_000)
        org.mockito.Mockito.`when`(
            track.write(org.mockito.ArgumentMatchers.any<ShortArray>(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()),
        ).thenAnswer { inv ->
            enteredWrite.countDown()
            assertTrue("the held write must be released by the test", holdWrite.await(5, java.util.concurrent.TimeUnit.SECONDS))
            inv.getArgument<Int>(2) // accept everything, once released
        }
        player.trackFactoryForTest = { track }
        player.installClipForTest(EarconId.SUCCESS, ShortArray(1600) { 1 }, 16_000)
        val admitted = java.util.concurrent.CountDownLatch(1)
        var token: EarconPlayer.AdmissionToken? = null
        val t0 = System.nanoTime()
        player.submit(EarconId.SUCCESS) { t ->
            token = t
            admitted.countDown()
        }
        val returnedMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue("precondition: the worker must be INSIDE the blocking write", enteredWrite.await(2, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(
            "submit must RETURN while an actual accepted write is held (took ${returnedMs}ms)",
            returnedMs < 500,
        )
        assertTrue(
            "coverage/admission for THIS cue must NOT have arrived while its write is still held",
            !admitted.await(200, java.util.concurrent.TimeUnit.MILLISECONDS),
        )
        holdWrite.countDown()
        assertTrue("after release, the cue's own admission must arrive", admitted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue("the delivered token must be a real acceptance", token != null)
        assertEquals(
            "the frontier must carry this cue's accepted count through the production path",
            1600,
            player.acceptedFrontierForTest(),
        )
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
    fun `play returns false for a raw resource that is genuinely absent`() {
        val acceptingTrack = org.mockito.Mockito.mock(android.media.AudioTrack::class.java)
        org.mockito.Mockito.`when`(acceptingTrack.sampleRate).thenReturn(16_000)
        org.mockito.Mockito.`when`(
            acceptingTrack.write(org.mockito.ArgumentMatchers.any<ShortArray>(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()),
        ).thenAnswer { it.getArgument<Int>(2) }
        player.trackFactoryForTest = { acceptingTrack }
        val result = player.play(EarconId.SUCCESS)
        assertNotNull(
            "a present earcon resource must actually play",
            result,
        )
        val unresolvable = EarconPlayer(MissingResourceContext(context))
        assertNull(
            "an unresolvable earcon resource must return false, not throw",
            unresolvable.play(EarconId.SUCCESS),
        )
    }
    @Test
    fun `release disposes resources`() {
        player.release()
        try {
            player.play(EarconId.SUCCESS)
        } catch (e: Exception) {
            fail("Unexpected exception: $e")
        }
    }
    @Test
    fun `playback keeps the clip's rate while only the reference is normalized`() {
        val clipRate = 44_100
        val clip = EarconAudio.decodeWavToMono(monoWav(rate = clipRate, seconds = 0.25, hz = 440.0).inputStream())
        assertEquals("the decoded clip must keep its own rate", clipRate, clip.sampleRateHz)
        assertEquals(
            "and the samples must be the clip's own, not a rate-converted copy",
            (clipRate * 0.25).toInt(),
            clip.samples.size,
        )
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
        try {
            player.play(EarconId.SUCCESS)
        } catch (t: Throwable) {
            fail("a play after release must be a no-op, not a throw: $t")
        }
        player.release()
    }
    @Test
    fun `a released singleton still attempts playback, and play reports the same as fresh`() {
        val player = EarconPlayer(context)
        player.release()
        player.release() // idempotent: the service can stop more than once
        try {
            player.play(EarconId.SUCCESS)
        } catch (t: Throwable) {
            fail("a play after release must be safe, not a throw: $t")
        }
        val fresh = EarconPlayer(context)
        assertEquals(
            "release must leave the player behaving like a freshly constructed one",
            fresh.play(EarconId.SUCCESS),
            player.play(EarconId.SUCCESS),
        )
        player.release()
        fresh.release()
    }
    @Test
    fun `release shuts the worker down and the next use still runs`() {
        val player = EarconPlayer(context)
        player.play(EarconId.SUCCESS)
        val firstWorker = player.workerThreadForTest()
        assertTrue("using the player must start its worker", firstWorker != null)
        player.release()
        assertTrue(
            "disposal must release the worker thread, not retain it for the process",
            player.workerIsShutdownForTest(),
        )
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
        val advanceFirst = installTrack()
        assertTrue(mockPlayer.play(EarconId.WAKE_WORD) != null)
        repeat(100) { advanceFirst() }
        assertEquals(
            "the first cue is consumed on its own track",
            EarconPlayer.EarconCompletion.Consumed,
            mockPlayer.completion(),
        )
        mockPlayer.release()
        val advanceSecond = installTrack()
        assertTrue(mockPlayer.play(EarconId.WAKE_WORD) != null)
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
        assertTrue("the first cue is accepted", player.play(EarconId.WAKE_WORD) != null)
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
        assertTrue("the second cue is accepted", player.play(EarconId.WAKE_WORD) != null)
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
        assertTrue(mockPlayer.play(EarconId.WAKE_WORD) != null)
        head = 50 // half of cue 1 has sounded; cue 1 is still queued
        assertTrue(mockPlayer.play(EarconId.WAKE_WORD) != null)
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
        assertTrue(mockPlayer.play(EarconId.WAKE_WORD) != null)
        assertEquals(
            "all 100 submitted frames are already consumed, so the cue is complete",
            EarconPlayer.EarconCompletion.Consumed,
            mockPlayer.completion(),
        )
        mockPlayer.release()
    }
    @Test
    fun `an installed observer returning null stays unavailable rather than using the platform head`() {
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
        assertTrue(mockPlayer.play(EarconId.WAKE_WORD) != null)
        assertEquals(
            "a nil selected observer is UNAVAILABLE, not a platform-head reading",
            EarconPlayer.EarconCompletion.Unknown,
            mockPlayer.completion(),
        )
        mockPlayer.release()
    }
    @Test
    fun `frames a FAILED cue already accepted still count against the next cue`() {
        val mockPlayer = EarconPlayer(context)
        val clipClass = Class.forName(EarconPlayer::class.java.name + "\$EarconClip")
        val clip = clipClass.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(mockPlayer, ShortArray(100), 16000)
        @Suppress("UNCHECKED_CAST")
        mockPlayer.javaClass.getDeclaredField("clips").apply { isAccessible = true }
            .set(mockPlayer, mapOf(EarconId.WAKE_WORD to clip))
        val track = org.mockito.Mockito.mock(android.media.AudioTrack::class.java)
        org.mockito.Mockito.`when`(track.sampleRate).thenReturn(16000)
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
        assertNull("the first cue is reported as FAILED", mockPlayer.play(EarconId.WAKE_WORD))
        org.mockito.Mockito.`when`(
            track.write(
                org.mockito.ArgumentMatchers.any(ShortArray::class.java),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
            ),
        ).thenAnswer { it.getArgument<Int>(2) }
        assertTrue("the second cue is accepted", mockPlayer.play(EarconId.WAKE_WORD) != null)
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
                selected.countDown()
                val releasedFirst = releaserRan.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (releasedFirst) throw java.util.concurrent.RejectedExecutionException("worker released")
                delegate.execute(command)
            }
        }
        player.installExecutorForTest(guarded)
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
    fun `a discarded track is not released AGAIN by the later session release`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val mockPlayer = EarconPlayer(context)
        val clipClass = Class.forName(EarconPlayer::class.java.name + "\$EarconClip")
        val clip = clipClass.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(mockPlayer, ShortArray(100), 16000)
        @Suppress("UNCHECKED_CAST")
        mockPlayer.javaClass.getDeclaredField("clips").apply { isAccessible = true }
            .set(mockPlayer, mapOf(EarconId.WAKE_WORD to clip))
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
        assertTrue(mockPlayer.play(EarconId.WAKE_WORD) != null)
        mockPlayer.stopAdmissionNow(null)
        mockPlayer.release()
        org.mockito.Mockito.verify(track, org.mockito.Mockito.times(1)).release()
    }
    @Test
    fun `a THROWING stop still releases the discarded track`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val mockPlayer = EarconPlayer(context)
        val clipClass = Class.forName(EarconPlayer::class.java.name + "\$EarconClip")
        val clip = clipClass.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(mockPlayer, ShortArray(100), 16000)
        @Suppress("UNCHECKED_CAST")
        mockPlayer.javaClass.getDeclaredField("clips").apply { isAccessible = true }
            .set(mockPlayer, mapOf(EarconId.WAKE_WORD to clip))
        val track = org.mockito.Mockito.mock(android.media.AudioTrack::class.java)
        org.mockito.Mockito.`when`(track.sampleRate).thenReturn(16000)
        org.mockito.Mockito.`when`(
            track.write(
                org.mockito.ArgumentMatchers.any(ShortArray::class.java),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
            ),
        ).thenAnswer { it.getArgument<Int>(2) }
        org.mockito.Mockito.doThrow(RuntimeException("stop failed")).`when`(track).stop()
        mockPlayer.javaClass.getDeclaredField("track").apply { isAccessible = true }.set(mockPlayer, track)
        mockPlayer.readPlaybackHeadFrames = { 0 }
        assertTrue(mockPlayer.play(EarconId.WAKE_WORD) != null)
        mockPlayer.stopAdmissionNow(null)
        org.mockito.Mockito.verify(track, org.mockito.Mockito.times(1)).release()
    }
    @Test
    fun `an OLD admission's stop leaves a same-sound successor sounding`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val mockPlayer = EarconPlayer(context)
        val clipClass = Class.forName(EarconPlayer::class.java.name + "\$EarconClip")
        val clip = clipClass.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(mockPlayer, ShortArray(100), 16000)
        @Suppress("UNCHECKED_CAST")
        mockPlayer.javaClass.getDeclaredField("clips").apply { isAccessible = true }
            .set(mockPlayer, mapOf(EarconId.WAKE_WORD to clip))
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
        val tokenA = mockPlayer.play(EarconId.WAKE_WORD)
        assertNotNull("A must be admitted", tokenA)
        val tokenB = mockPlayer.play(EarconId.WAKE_WORD)
        assertNotNull("B must be admitted", tokenB)
        assertNotEquals("B is a different admission of the same sound", tokenA, tokenB)
        fun field(name: String): Any? = mockPlayer.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }
            .get(mockPlayer)
        val bFrontier = field("acceptedFramesTotal")
        val bTarget = field("cueHeadTarget")
        val bAdmission = field("currentAdmission")
        mockPlayer.stopAdmissionNow(tokenA)
        assertEquals("B still owns the track", bAdmission, field("currentAdmission"))
        assertEquals("B's frontier is unchanged", bFrontier, field("acceptedFramesTotal"))
        assertEquals("B's completion target is unchanged", bTarget, field("cueHeadTarget"))
        org.mockito.Mockito.verify(track, org.mockito.Mockito.never()).stop()
        mockPlayer.stopAdmissionNow(tokenB)
        org.mockito.Mockito.verify(track, org.mockito.Mockito.atLeastOnce()).stop()
    }
    @Test
    fun `stopping a track discards its frontier AND releases it`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val mockPlayer = EarconPlayer(context)
        val clipClass = Class.forName(EarconPlayer::class.java.name + "\$EarconClip")
        val clip = clipClass.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(mockPlayer, ShortArray(100), 16000)
        @Suppress("UNCHECKED_CAST")
        mockPlayer.javaClass.getDeclaredField("clips").apply { isAccessible = true }
            .set(mockPlayer, mapOf(EarconId.WAKE_WORD to clip))
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
        assertTrue(mockPlayer.play(EarconId.WAKE_WORD) != null)
        mockPlayer.stopAdmissionNow(null)
        org.mockito.Mockito.verify(track, org.mockito.Mockito.times(1)).release()
        assertEquals(
            "the retired track's accepted-frame frontier must not outlive it",
            0,
            mockPlayer.javaClass.getDeclaredField("acceptedFramesTotal")
                .apply { isAccessible = true }
                .get(mockPlayer),
        )
        assertEquals(
            "nor may its completion target survive it",
            0,
            mockPlayer.javaClass.getDeclaredField("cueHeadTarget")
                .apply { isAccessible = true }
                .get(mockPlayer),
        )
    }
    @Test
    fun `a same-sound successor completes from ITS OWN frames as the head advances`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val mockPlayer = EarconPlayer(context)
        val clipClass = Class.forName(EarconPlayer::class.java.name + "\$EarconClip")
        val clip = clipClass.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(mockPlayer, ShortArray(100), 16000)
        @Suppress("UNCHECKED_CAST")
        mockPlayer.javaClass.getDeclaredField("clips").apply { isAccessible = true }
            .set(mockPlayer, mapOf(EarconId.WAKE_WORD to clip))
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
        val first = mockPlayer.play(EarconId.WAKE_WORD)
        assertNotNull("the first cue must be admitted", first)
        head = 100
        assertEquals(
            "the first cue must be observed consumed before it is stopped",
            EarconPlayer.EarconCompletion.Consumed,
            mockPlayer.completion(),
        )
        mockPlayer.stopAdmissionNow(first)
        head = 0
        val newTrack = org.mockito.Mockito.mock(android.media.AudioTrack::class.java)
        org.mockito.Mockito.`when`(newTrack.sampleRate).thenReturn(16000)
        org.mockito.Mockito.`when`(
            newTrack.write(
                org.mockito.ArgumentMatchers.any(ShortArray::class.java),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
            ),
        ).thenAnswer { it.getArgument<Int>(2) }
        mockPlayer.javaClass.getDeclaredField("track").apply { isAccessible = true }.set(mockPlayer, newTrack)
        val successor = mockPlayer.play(EarconId.WAKE_WORD)
        assertNotNull("the successor must be admitted", successor)
        assertNotEquals(
            "the successor must not read Consumed before its own frames sound",
            EarconPlayer.EarconCompletion.Consumed,
            mockPlayer.completion(),
        )
        head = 100
        assertEquals(
            "the successor completes from ITS OWN accepted frames",
            EarconPlayer.EarconCompletion.Consumed,
            mockPlayer.completion(),
        )
    }
    @Test
    fun `a same-sound successor's accepted WRITES are observed on a real player before teardown`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val player = EarconPlayer(context)
        val clipClass = Class.forName(EarconPlayer::class.java.name + "\$EarconClip")
        val clip = clipClass.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(player, ShortArray(100), 16000)
        @Suppress("UNCHECKED_CAST")
        player.javaClass.getDeclaredField("clips").apply { isAccessible = true }
            .set(player, mapOf(EarconId.WAKE_WORD to clip))
        val track = org.mockito.Mockito.mock(android.media.AudioTrack::class.java)
        org.mockito.Mockito.`when`(track.sampleRate).thenReturn(16000)
        org.mockito.Mockito.`when`(
            track.write(
                org.mockito.ArgumentMatchers.any(ShortArray::class.java),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
            ),
        ).thenAnswer { it.getArgument<Int>(2) }
        player.javaClass.getDeclaredField("track").apply { isAccessible = true }.set(player, track)
        player.readPlaybackHeadFrames = { 0 }
        assertTrue("A must be accepted", player.play(EarconId.WAKE_WORD) != null)
        val aFrames = player.emittedFrames
        assertEquals("A's writes were accepted by the track", 100, aFrames)
        assertEquals("A is the last admission", EarconId.WAKE_WORD, player.lastPlayedId)
        assertTrue("B must be accepted", player.play(EarconId.WAKE_WORD) != null)
        assertEquals(
            "B is the last admission, superseding A",
            EarconId.WAKE_WORD,
            player.lastPlayedId,
        )
        assertEquals("B wrote its own 100 frames", 100, player.emittedFrames)
        assertEquals(
            "the cumulative frontier includes A's writes plus B's",
            200,
            player.javaClass.getDeclaredField("acceptedFramesTotal")
                .apply { isAccessible = true }
                .get(player),
        )
        assertEquals(
            EarconPlayer.EarconCompletion.Queued,
            player.completion(),
        )
        player.release()
        org.mockito.Mockito.verify(track, org.mockito.Mockito.atLeastOnce()).release()
    }
    @Test
    fun `cue ownership opens and closes the recorder admission`() {
        val recorder = au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackBufferRecorder(
            au.com.shiftyjelly.pocketcasts.repositories.fingerprint.FingerprintPcmTap(),
        )
        val owned = EarconPlayer(context, playbackBufferRecorder = recorder)
        org.junit.Assert.assertNull(owned.play(EarconId.SUCCESS))
        org.junit.Assert.assertNull("a failed cue leaves no recorder ownership behind", owned.currentRecorderAdmission)
        val cueAdmission = recorder.openAdmission(EarconPlayer.EARCON_PRODUCER_BASE + 1L)
        assertTrue("earcon producer ids occupy their own negative namespace", EarconPlayer.EARCON_PRODUCER_BASE < 0L)
        recorder.write(floatArrayOf(0.5f, -0.5f), admission = cueAdmission)
        recorder.retireProducer(cueAdmission)
        assertNotNull(recorder.snapshot().firstOrNull())
        owned.release()
    }
}
private class MissingResourceContext(private val wrapped: Context) : android.content.ContextWrapper(wrapped) {
    override fun getResources(): android.content.res.Resources = MissingResources(wrapped.resources)
}
@Suppress("DEPRECATION")
private open class MissingResources(real: android.content.res.Resources) : android.content.res.Resources(real.assets, real.displayMetrics, real.configuration) {
    override fun getIdentifier(name: String?, defType: String?, defPackage: String?): Int = 0
}
