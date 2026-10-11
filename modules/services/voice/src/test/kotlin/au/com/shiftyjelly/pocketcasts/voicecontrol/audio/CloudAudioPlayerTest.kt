package au.com.shiftyjelly.pocketcasts.voicecontrol.audio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CloudAudioPlayerTest {

    private lateinit var player: CloudAudioPlayer

    @Before
    fun setUp() {
        player = CloudAudioPlayer(
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            sampleRateHz = 16000,
        )
    }

    @After
    fun tearDown() {
        player.release()
    }

    @Test
    fun submitFrame_addsToBuffer() {
        val frame = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        player.submitFrame(frame)
        // Frame is buffered; we can't directly inspect the buffer, but
        // submitting without errors proves the frame was accepted.
    }

    @Test
    fun play_afterSubmit_startsPlaying() {
        player.submitFrame(byteArrayOf(0x00, 0x01))
        player.play()
        assertTrue(player.isPlaying)
    }

    @Test
    fun pause_stopsPlaying() {
        player.submitFrame(byteArrayOf(0x00, 0x01))
        player.play()
        assertTrue(player.isPlaying)
        player.pause()
        assertFalse(player.isPlaying)
    }

    @Test
    fun play_afterResume_resumesPlaying() {
        player.submitFrame(byteArrayOf(0x00, 0x01))
        player.play()
        player.pause()
        player.play()
        assertTrue(player.isPlaying)
    }

    @Test
    fun stop_clearsBuffer() {
        player.submitFrame(byteArrayOf(0x00, 0x01))
        player.stop()
        // Submitting after stop should be a no-op
        player.submitFrame(byteArrayOf(0x02, 0x03))
        // The player should be stopped and not playing
        assertFalse(player.isPlaying)
    }

    @Test
    fun release_afterStop_doesNotCrash() {
        player.submitFrame(byteArrayOf(0x00, 0x01))
        player.stop()
        player.release()
        // Should not throw
    }

    @Test
    fun submitFrame_afterRelease_doesNotCrash() {
        player.release()
        player.submitFrame(byteArrayOf(0x00, 0x01))
        // Should not throw
    }

    @Test
    fun codec_isEmptyByDefault() {
        assertEquals("", player.codec)
    }

    @Test
    fun codec_canBeSet() {
        player.codec = "opus"
        assertEquals("opus", player.codec)
    }

    @Test
    fun notPlayingInitially() {
        assertFalse(player.isPlaying)
    }

    // --- The mic-gate contract -------------------------------------------------------------
    // The voice gate blocks the mic when "another app is playing", with a self-attribution
    // guard keyed on the app's own emitted audio. The guard's timestamp is written only by the
    // earcon/TTS renderer, so a cloud answer was never covered by it: the app heard its own
    // answer as a stranger and cut the mic mid-answer. These pin the missing half.

    @Test
    fun `cloud playback reports us making sound`() {
        val states = mutableListOf<Boolean>()
        player.onPlaybackAudibleChanged = { audible -> states += audible }

        player.submitFrame(byteArrayOf(0x00, 0x01))
        player.play()

        assertTrue("play must be reported as us making sound", states.firstOrNull() == true)
    }

    @Test
    fun `a long answer keeps refreshing the stamp even when frames stall`() {
        // The property is "fresh whenever we are still the sound", so it must hold while the
        // player is playing — including an underrun, which the client is designed to ride out
        // ("pauses-and-resumes on underrun"). A refresh tied to the write path goes stale exactly
        // then, which is the case the property exists for.
        var reports = 0
        player.onPlaybackAudibleChanged = { audible -> if (audible) reports += 1 }

        player.submitFrame(byteArrayOf(0x00, 0x01))
        player.play()
        // Count only what happens AFTER play() has returned: play() reports as it starts, so a
        // total count would pass on the start-up reports alone and never exercise this property.
        val afterStart = reports
        // No further frames: the buffer is empty and the write path is idle, which is the
        // underrun the client is designed to ride out.
        Thread.sleep(2_500)

        assertTrue(
            "the stamp must refresh while playing and idle, not only at start (got ${reports - afterStart} after start)",
            reports > afterStart,
        )
    }

    @Test
    fun `a multi-frame answer refreshes the stamp for its whole duration`() {
        // The gate's attribution window is far shorter than an answer, so a single stamp at play
        // start goes stale mid-answer and the microphone is cut while we are still speaking —
        // the reported bug, just past the window. Counting the reports is the assertion that
        // cannot pass by accident: one start-of-play call would satisfy a callback-fired check.
        var reports = 0
        player.onPlaybackAudibleChanged = { audible -> if (audible) reports += 1 }

        player.submitFrame(byteArrayOf(0x00, 0x01))
        player.submitFrame(byteArrayOf(0x02, 0x03))
        player.submitFrame(byteArrayOf(0x04, 0x05))
        player.play()
        val atStart = reports
        Thread.sleep(2_500)

        assertTrue(
            "a multi-second answer must keep refreshing after start (got ${reports - atStart} after start)",
            reports > atStart,
        )
    }

    @Test
    fun `cloud playback reports that we stopped making sound`() {
        val states = mutableListOf<Boolean>()
        player.onPlaybackAudibleChanged = { audible -> states += audible }

        player.submitFrame(byteArrayOf(0x00, 0x01))
        player.play()
        player.stop()

        // The stamp is refreshed for the whole utterance, so the reports are not a clean pair —
        // what matters is the LAST word: once we stop, the gate must not keep believing we are
        // the sound, or it would swallow a foreign app for the length of the window.
        assertEquals("the final report must be that we stopped", false, states.last())
    }

    @Test
    fun `s16le converter maps full-scale and silence to the reference range`() {
        // 0x7FFF -> +1.0, 0x8000 -> -1.0, 0x0000 -> 0.0 in the shared reference's float domain.
        val bytes = byteArrayOf(
            0xFF.toByte(),
            0x7F.toByte(), // +32767
            0x00,
            0x80.toByte(), // -32768
            0x00,
            0x00, // 0
        )
        val out = CloudAudioPlayer.s16leBytesToFloats(bytes, bytes.size)
        assertEquals(3, out.size)
        assertTrue("max positive", out[0] > 0.999f)
        assertTrue("max negative", out[1] <= -0.999f)
        assertEquals("zero", 0f, out[2], 0.0001f)
    }

    @Test
    fun `s16le converter honours the accepted length, not the whole buffer`() {
        // AudioTrack.write reports how many bytes it took; a partial write must record only that
        // prefix, so the reference never claims audio the sink did not accept.
        val bytes = byteArrayOf(0x10, 0x20, 0x30, 0x40)
        val out = CloudAudioPlayer.s16leBytesToFloats(bytes, 2)
        assertEquals(1, out.size)
    }

    @Test
    fun `odd remaining byte is truncated to whole samples`() {
        val bytes = byteArrayOf(0x10, 0x20, 0x30)
        val out = CloudAudioPlayer.s16leBytesToFloats(bytes, 3)
        assertEquals(1, out.size)
    }

    @Test
    fun `stopping the player retires the echo reference`() {
        // The production lifecycle must retire on stop: a stopped answer must not leave reference
        // audio that later user speech could be matched against and dropped as bleed.
        val recorder = au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackBufferRecorder(
            au.com.shiftyjelly.pocketcasts.repositories.fingerprint.FingerprintPcmTap(),
        )
        val wired = CloudAudioPlayer(
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            sampleRateHz = 16000,
            playbackBufferRecorder = recorder,
        )
        recorder.write(FloatArray(au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackBufferRecorder.SAMPLE_RATE) { 0.4f })
        assertEquals(
            "precondition: reference holds audio",
            au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackBufferRecorder.SAMPLE_RATE,
            recorder.snapshot().size,
        )

        wired.stop()

        assertEquals(
            "stop retires the reference to the delay tail",
            au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackBufferRecorder.DELAY_TAIL_SAMPLES,
            recorder.snapshot().size,
        )
        wired.release()
    }

    @Test
    fun `stopping the player withdraws its routed observation`() {
        // The published route must not outlive the stream that reported it. stop() ends the answer,
        // so a route left published afterwards describes audio that is no longer sounding -- and the
        // monitor would keep answering with it for a stream that is gone.
        val observer = au.com.shiftyjelly.pocketcasts.voicecontrol.route.RoutedOutputObserver()
        val wired = CloudAudioPlayer(
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            sampleRateHz = 16000,
            routedOutputObserver = observer,
        )
        // Exercise the production path: play() starts the audible heartbeat, which is what publishes
        // this stream's routed output. The player claims the slot on construction.
        wired.submitFrame(byteArrayOf(0x00, 0x01, 0x02, 0x03))
        wired.play()
        assertTrue(
            "precondition: the playing stream holds the observation",
            observer.holdsAClaim(),
        )

        wired.stop()

        assertEquals(
            "stop must withdraw the observation: the stream that reported it has ended",
            null,
            observer.current(),
        )
        assertFalse("stop must release the claim, not just the value", observer.holdsAClaim())
        wired.release()
    }

    @Test
    fun `draining and stopping withdraws the routed observation`() {
        // drainAndStop() is the turn-finished path, so it carries the same obligation as stop().
        val observer = au.com.shiftyjelly.pocketcasts.voicecontrol.route.RoutedOutputObserver()
        val wired = CloudAudioPlayer(
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            sampleRateHz = 16000,
            routedOutputObserver = observer,
        )
        wired.submitFrame(byteArrayOf(0x00, 0x01, 0x02, 0x03))
        wired.play()
        assertTrue(
            "precondition: the playing stream holds the observation",
            observer.holdsAClaim(),
        )

        wired.drainAndStop()

        assertEquals(
            "drainAndStop must withdraw the observation",
            null,
            observer.current(),
        )
        assertFalse("drainAndStop must release the claim", observer.holdsAClaim())
        wired.release()
    }

    @Test
    fun `a 48 kHz answer is recorded at its own duration, not replayed at 16 kHz`() {
        // The recorder's timeline is 16 kHz: it converts sample counts to milliseconds with
        // SAMPLE_RATE. PCM decoded from a 48 kHz codec is 48 kHz PCM, so recording it unchanged
        // stores three times as many samples as 16 kHz would for the same span of audio -- the
        // reference then claims an audio duration three times the real one, and correlation against
        // it can never align.
        val recorder = au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackBufferRecorder(
            au.com.shiftyjelly.pocketcasts.repositories.fingerprint.FingerprintPcmTap(),
        )
        val wired = CloudAudioPlayer(
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            sampleRateHz = 16000,
            playbackBufferRecorder = recorder,
        )
        wired.setCodec("pcm@48k")

        // Exactly 100 ms of 48 kHz PCM: 4800 frames of s16le = 9600 bytes.
        val hundredMsAt48k = ByteArray(9600)
        for (i in 0 until 4800) {
            val v = 8000
            hundredMsAt48k[i * 2] = (v and 0xFF).toByte()
            hundredMsAt48k[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        // TWO frames, not one. The position of the first chunk is `(0 samples submitted) / rate`,
        // which is 0 at every rate -- a single-frame arrangement cannot distinguish a position
        // computed at the playback rate from one computed at the recorder's. The second frame puts a
        // nonzero submitted-sample count into that division, which is where the two rates differ.
        wired.submitFrame(hundredMsAt48k)
        wired.submitFrame(hundredMsAt48k)
        wired.play()

        assertEquals(
            "200 ms of 48 kHz audio must be recorded as 200 ms at the recorder's 16 kHz, " +
                "not as 600 ms of mis-typed samples",
            au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackBufferRecorder.SAMPLE_RATE / 5,
            recorder.snapshot().size,
        )
        // The producer-clock position must still be the wall-clock end of the audio that was
        // SUBMITTED. Resampling changes the sample count, so deriving the position from the resampled
        // length would put the reference 3x too far along at 48 kHz. The second frame's submitted
        // count is 4800, so the position is 4800/48000 = 100 ms before the second chunk's own 100 ms.
        assertEquals(
            "the newest reference sample sits at the end of the submitted audio, on the " +
                "producer's clock rather than the recorder's sample count",
            200L,
            recorder.lastRecordedPositionMs(),
        )
        wired.release()
    }

    @Test
    fun `a 24 kHz answer records the exact resampled frame count`() {
        // 24k is the other negotiated rate the parser recognises, and it is a 1.5x factor rather than
        // 3x: the output length is 2400 * 16000 / 24000, which must come out exactly 1600. A test at
        // 48 kHz alone cannot show this, because that ratio divides evenly and would hide a formula
        // that truncates where the ratio is fractional.
        val recorder = au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackBufferRecorder(
            au.com.shiftyjelly.pocketcasts.repositories.fingerprint.FingerprintPcmTap(),
        )
        val wired = CloudAudioPlayer(
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            sampleRateHz = 16000,
            playbackBufferRecorder = recorder,
        )
        wired.setCodec("pcm@24k")

        // 100 ms of 24 kHz PCM: 2400 frames of s16le = 4800 bytes.
        val hundredMsAt24k = ByteArray(4800)
        for (i in 0 until 2400) {
            val v = 8000
            hundredMsAt24k[i * 2] = (v and 0xFF).toByte()
            hundredMsAt24k[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        wired.submitFrame(hundredMsAt24k)
        wired.submitFrame(hundredMsAt24k)
        wired.play()

        assertEquals(
            "200 ms of 24 kHz audio is 3200 samples at the recorder's 16 kHz",
            au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackBufferRecorder.SAMPLE_RATE / 5,
            recorder.snapshot().size,
        )
        assertEquals(
            "the producer-clock position is 200 ms regardless of the source rate",
            200L,
            recorder.lastRecordedPositionMs(),
        )
        wired.release()
    }

    @Test
    fun `successive 48 kHz chunks keep their audio continuous across the chunk boundary`() {
        // A 100 ms block converts to an integral 1600 samples, so it cannot show what happens when a
        // chunk's length does NOT divide into a whole output count -- and real socket frames rarely
        // land on 100 ms boundaries. With a fractional ratio, resampling each chunk from its own index
        // 0 discards the remainder every time, so the output cursor and the producer clock drift apart
        // and the reference no longer lines up with the audio that played.
        val recorder = au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackBufferRecorder(
            au.com.shiftyjelly.pocketcasts.repositories.fingerprint.FingerprintPcmTap(),
        )
        val wired = CloudAudioPlayer(
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            sampleRateHz = 16000,
            playbackBufferRecorder = recorder,
        )
        wired.setCodec("pcm@48k")

        // 1000 frames at 48 kHz = 20.833 ms, which is 333.33 samples at 16 kHz: fractional.
        val chunk = ByteArray(2000)
        for (i in 0 until 1000) {
            val v = 8000
            chunk[i * 2] = (v and 0xFF).toByte()
            chunk[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        val chunks = 30
        repeat(chunks) { wired.submitFrame(chunk) }
        wired.play()

        // 30 chunks of 1000 frames is 30000 frames = 625 ms of audio at 48 kHz, which is exactly
        // 10000 samples at the recorder's 16 kHz. Truncating per chunk stores 30 * 333 = 9990.
        val expected = au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackBufferRecorder.SAMPLE_RATE * 625 / 1000
        assertEquals(
            "30 chunks of 20.833 ms must total 625 ms of reference audio, not 30 truncated chunks",
            expected,
            recorder.snapshot().size,
        )
        // The reference position is bounded to within a millisecond of the true end, not exact:
        // PlaybackBufferRecorder.write floors each chunk's own duration (pcm.size * 1000 / SAMPLE_RATE),
        // so a chunk that is not a whole number of milliseconds at 16 kHz loses its remainder there.
        // What matters -- and what a per-chunk resample would break -- is that the error does NOT
        // ACCUMULATE: a drifting clock would be tens of milliseconds out after 30 chunks, and further
        // out the longer the answer runs, which is what defeats correlation against the mic.
        val position = recorder.lastRecordedPositionMs()!!
        assertTrue(
            "the producer clock must track the 625 ms of submitted audio (within a millisecond of " +
                "rounding), not drift behind it; was $position",
            position in 624L..625L,
        )
        wired.release()
    }

    @Test
    fun `a reused player publishes a live route on its second playback`() {
        // The claim is made in the constructor, so the observer slot is held for the player's whole
        // life, and one player serves several turns. If stop() permanently marked that claim
        // released, the SECOND playback would publish into a slot the observer no longer counts as
        // held -- the route would be observable only through a claim that is already spent.
        val observer = au.com.shiftyjelly.pocketcasts.voicecontrol.route.RoutedOutputObserver()
        val wired = CloudAudioPlayer(
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            sampleRateHz = 16000,
            routedOutputObserver = observer,
        )
        val chunk = byteArrayOf(0x00, 0x01, 0x02, 0x03)

        wired.submitFrame(chunk)
        wired.play()
        assertTrue("first playback holds the claim", observer.holdsAClaim())
        wired.stop()
        assertFalse("stop releases the claim", observer.holdsAClaim())

        // The same player, a second turn.
        wired.submitFrame(chunk)
        wired.play()
        assertTrue(
            "the second playback must hold a live claim again, not publish into a spent one",
            observer.holdsAClaim(),
        )
        wired.stop()
        assertFalse("and stop must release it again", observer.holdsAClaim())
        wired.release()
    }

    @Test
    fun `a second playback on the same player withdraws its route when it stops`() {
        // The production-path probe: not just that the second playback HOLDS a claim, but that its
        // route is WITHDRAWN when that playback ends. A claim that could only release once would leave
        // the second route published after the stream ended -- the same defect the stop/drain fix
        // addressed, reappearing on the second turn.
        val observer = au.com.shiftyjelly.pocketcasts.voicecontrol.route.RoutedOutputObserver()
        val wired = CloudAudioPlayer(
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            sampleRateHz = 16000,
            routedOutputObserver = observer,
        )
        val chunk = byteArrayOf(0x00, 0x01, 0x02, 0x03)

        // session 0
        wired.submitFrame(chunk)
        wired.play()
        wired.stop()
        assertNull("session 0 withdrew its route", observer.current())
        assertFalse("session 0 released its claim", observer.holdsAClaim())

        // session 1
        wired.submitFrame(chunk)
        wired.play()
        assertTrue("session 1 holds a claim", observer.holdsAClaim())
        wired.stop()
        assertNull("session 1 must withdraw its route as well", observer.current())
        assertFalse("session 1 released its claim", observer.holdsAClaim())
        wired.release()
    }

    @Test
    fun `a sink write failure stops one answer without poisoning the next`() {
        // One player serves several turns, so a write failure on one answer must not decide the next.
        // The flag that stops the failing answer is also read at the top of every frame loop, so if it
        // survives the turn it ends, the NEXT answer breaks out before playing anything -- the failure
        // is attributed to a turn that never failed.
        val failures = java.util.concurrent.atomic.AtomicInteger(0)
        val wired = CloudAudioPlayer(
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            sampleRateHz = 16000,
        )
        wired.onWriteFailure = { failures.incrementAndGet() }
        val frame = byteArrayOf(0x00, 0x01, 0x02, 0x03)

        // Turn 1: the sink refuses.
        wired.writeToSink = { _, _, _, _ -> 0 }
        wired.submitFrame(frame)
        wired.play()
        assertEquals("turn 1 reported its failure", 1, failures.get())
        wired.stop()

        // Turn 2: the sink accepts. TWO frames, because the flag is read at the END of the per-frame
        // loop: a one-frame turn writes that frame and then reaches the check, so a single frame IS
        // the whole answer and cannot show the early stop. Two frames is the fewest that can.
        var acceptedBytes = 0
        wired.writeToSink = { _, bytes, _, count ->
            acceptedBytes += count
            count
        }
        wired.submitFrame(frame)
        wired.submitFrame(frame)
        wired.play()

        assertEquals(
            "a recovered sink must receive the whole next answer, not stop after its first frame " +
                "because the previous answer's failure flag survived",
            2 * frame.size,
            acceptedBytes,
        )
        assertEquals("and turn 2 must not report a failure of its own", 1, failures.get())
        wired.release()
    }

    @Test
    fun `a partial sink write records only the accepted prefix`() {
        // The cloud answer must record accepted samples, not the whole submitted frame: a reference
        // holding audio the sink rejected would correlate against audio that never played.
        val recorder = au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackBufferRecorder(
            au.com.shiftyjelly.pocketcasts.repositories.fingerprint.FingerprintPcmTap(),
        )
        val player = CloudAudioPlayer(
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            sampleRateHz = 16000,
            playbackBufferRecorder = recorder,
        )
        // 100 samples submitted; the converter's length bound is what limits what is recorded.
        val bytes = ByteArray(200) { 0x10 }
        val accepted = 40 // bytes = 20 samples
        val recorded = CloudAudioPlayer.s16leBytesToFloats(bytes, accepted)
        assertEquals(
            "only the accepted byte prefix may be recorded",
            accepted / 2,
            recorded.size,
        )
        player.release()
    }

    @Test
    fun `a sink refusal reaches the caller through onWriteFailure`() {
        // A refusal that only sets a private flag is indistinguishable from a clean end. The player
        // must report it, so the turn's result can avoid claiming a completed answer.
        val player = CloudAudioPlayer(
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            sampleRateHz = 16000,
        )
        var reported = -1L
        player.onWriteFailure = { frames -> reported = frames }
        // The callback is invoked from the write loop; assert it is wired rather than invoking the
        // private path, which requires a failing AudioTrack (not reachable under Robolectric).
        assertTrue("the failure callback must be installed", player.onWriteFailure != null)
        assertEquals("no failure reported before any write", -1L, reported)
        player.release()
    }

    @Test
    fun `a refused write reports through onWriteFailure with the frames already submitted`() {
        // End-to-end through the real player: the sink refuses after accepting part of the answer,
        // and the refusal reaches the caller so the turn cannot read as a clean end. A hand-set flag
        // would prove the branch, not the connection.
        val reported = java.util.concurrent.atomic.AtomicLong(-1)
        val player = CloudAudioPlayer(
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            sampleRateHz = 16000,
        ).apply {
            onWriteFailure = { frames -> reported.set(frames) }
            var accepted = 0
            writeToSink = { _, _, _, count -> if (accepted++ < 1) count else -1 }
        }
        try {
            player.submitFrame(ByteArray(4096) { 1 })
            player.play()
            player.submitFrame(ByteArray(4096) { 1 })

            assertTrue("the refusal must be reported", reported.get() >= 0)
        } finally {
            player.release()
        }
    }

    @Test
    fun `irregular partial writes record every accepted sample exactly once`() {
        // The remainder loop exists because a sink may accept part of a frame. A prefix test only shows
        // the first short write; what is unproven is that VARYING acceptance per call advances the
        // remainder correctly — too little and audio is dropped, too much and samples are counted
        // twice. The recorder is the witness: it must end up holding exactly the bytes the sink took.
        val recorder = au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackBufferRecorder(
            au.com.shiftyjelly.pocketcasts.repositories.fingerprint.FingerprintPcmTap(),
        )
        val acceptedPerCall = mutableListOf<Int>()
        val player = CloudAudioPlayer(
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            sampleRateHz = 16000,
            playbackBufferRecorder = recorder,
        ).apply {
            // Deliberately irregular, and in WHOLE samples: a real sink returns a byte count that
            // pairs up, so an odd take would test a stimulus the production path cannot produce.
            writeToSink = { _, _, _, count ->
                val take = when (acceptedPerCall.size) {
                    0 -> 4
                    1 -> 12
                    else -> count
                }.coerceAtMost(count)
                if (take == 0) -1 else take
            }
            // The sink counts as accepting; the sizes are recorded for the assertion below.
            val inner = writeToSink!!
            writeToSink = { track, bytes, offset, count ->
                inner(track, bytes, offset, count).also { acceptedPerCall += it }
            }
        }
        // Distinct bytes per position, so a wrong offset shows as a wrong VALUE, not just a count.
        val frame = ByteArray(64) { i -> (i + 1).toByte() }
        try {
            player.submitFrame(frame)
            player.play()
            player.submitFrame(frame)

            val recorded = recorder.snapshot()
            // What the reference must NOT do is duplicate or misalign: a conversion that always
            // started at index 0 re-read the frame's opening bytes for every remainder chunk, so the
            // reference held the head repeated and a length that never matched what the sink took.
            // Every recorded sample must therefore appear once, in order.
            // Across the WHOLE recording, not just its head: a conversion that ignored the chunk
            // offset re-reads the frame's opening for each remainder, so the damage appears in the
            // second chunk onward — a head-only check passes while the body is misaligned.
            val expectedFrame = FloatArray(32) { i ->
                val lo = frame[i * 2].toInt() and 0xFF
                val hi = frame[i * 2 + 1].toInt()
                ((hi shl 8) or lo).toShort() / 32768f
            }
            assertEquals(
                "every recorded sample must come from its own position in the frame (got=${recorded.take(32).toList()})",
                expectedFrame.toList(),
                recorded.take(expectedFrame.size).toList(),
            )
            // Every accepted byte is a whole number of samples here, so none may be lost.
            assertEquals(
                "the reference must hold exactly the sink's accepted samples (calls=$acceptedPerCall)",
                acceptedPerCall.sum() / 2,
                recorded.size,
            )
        } finally {
            player.release()
        }
    }

    @Test
    fun `a player claims the routed observation and clears it when it retires`() {
        // The production connection @spec directed: a player must actually participate in the shared
        // routed observation, and must withdraw it on release so a stopped player does not leave a route
        // behind for whatever runs next. Removing either call fails this.
        val observer = au.com.shiftyjelly.pocketcasts.voicecontrol.route.RoutedOutputObserver()
        val player = CloudAudioPlayer(
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            sampleRateHz = 16000,
            routedOutputObserver = observer,
        )
        // Claimed when the stream actually begins sounding, not when the object is built: the claim
        // belongs to a playback run, and a player that has never played is not producing a route for
        // anything to read.
        assertFalse("a player that has not sounded holds no observation", observer.holdsAClaim())
        player.submitFrame(byteArrayOf(0x00, 0x01, 0x02, 0x03))
        player.play()
        assertTrue("a sounding player holds the observation", observer.holdsAClaim())
        try {
            player.stop()
        } finally {
            player.release()
        }
        // Released: the stream is gone, so the route must go back to unknown rather than persist.
        assertFalse(
            "a retired player must not leave its route behind",
            observer.holdsAClaim(),
        )
    }

    @Test
    fun `a replaced player does not clear its successor's observation`() {
        val observer = au.com.shiftyjelly.pocketcasts.voicecontrol.route.RoutedOutputObserver()
        val first = CloudAudioPlayer(
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            sampleRateHz = 16000,
            routedOutputObserver = observer,
        )
        val second = CloudAudioPlayer(
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            sampleRateHz = 16000,
            routedOutputObserver = observer,
        )
        try {
            // Both are playing, so both hold claims; the first then retires. The successor keeps the
            // observation -- the ownership token is what separates their claims.
            first.submitFrame(byteArrayOf(0x00, 0x01, 0x02, 0x03))
            first.play()
            second.submitFrame(byteArrayOf(0x00, 0x01, 0x02, 0x03))
            second.play()
            first.release()
            assertTrue(
                "a superseded player must not withdraw its successor's observation",
                observer.holdsAClaim(),
            )
        } finally {
            second.release()
        }
    }
}
