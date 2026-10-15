package au.com.shiftyjelly.pocketcasts.voicecontrol.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackBufferRecorder
import au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PositionAxis
import java.nio.ByteBuffer
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Plays binary audio frames from the cloud route.
 *
 * The player buffers frames as they arrive, decodes them if the codec requires it (Opus), and
 * writes decoded PCM to an [AudioTrack] for playback. It supports pause/resume so the host
 * player can be paused while cloud audio plays and restored after.
 *
 * **Supported codecs:**
 * - `pcm_s16le` — 16-bit signed little-endian PCM, played directly.
 * - `opus` — decoded via [MediaCodec] to PCM, then played.
 *
 * Unknown or empty codecs default to PCM with the provided sample rate and channel config.
 */
// Base of the cloud answer's producer identities through the shared reference recorder. Each track
// incarnation gets its OWN identity (base + generation): a stale stop or late write callback can
// retire or tag only the incarnation it names, never a successor already on a newer track. The space
// is negative so it cannot collide with the episode tap's non-negative stream epochs.
const val CLOUD_ANSWER_IDENTITY_BASE: Long = Long.MIN_VALUE / 2

class CloudAudioPlayer(
    private val context: Context,
    private val sampleRateHz: Int = 16000,
    private val channelConfig: Int = AudioFormat.CHANNEL_OUT_MONO,
    private val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT,
    // Fed with the PCM this player actually sends to the output, so the shared echo reference holds
    // the cloud answer as it is played. Null in contexts that do not run the echo filter.
    private val playbackBufferRecorder: PlaybackBufferRecorder? = null,
    // Publishes this player's observed output routing, so route-dependent decisions read where the audio
    // actually goes rather than what is merely available. Null in contexts with no route consumer.
    private val routedOutputObserver: au.com.shiftyjelly.pocketcasts.voicecontrol.route.RoutedOutputObserver? = null,
) {
    /**
     * This stream's claim on the routed observation, taken when playback starts.
     *
     * Not taken in the constructor: the claim is released when playback stops, and one player serves
     * several turns, so a claim taken once at construction would be spent after the first stop and the
     * second playback would publish into a claim the observer no longer counts — the route would be
     * observably absent while audio was sounding.
     */
    private var observedRoute: au.com.shiftyjelly.pocketcasts.voicecontrol.route.RoutedOutputObserver.ObservedRoute? = null

    /**
     * Converts decoded PCM to the recorder's timeline, holding phase across chunks.
     *
     * Per-player state on purpose: the reference is written chunk by chunk, so continuity belongs to
     * this stream's frame sequence rather than to a single conversion call.
     */
    private val resampler = ChunkResampler()

    /**
     * Audio attributes that route through the shared STREAM_MUSIC output path.
     *
     * USAGE_MEDIA + CONTENT_TYPE_SPEECH maps to AudioManager.STREAM_MUSIC, the same
     * stream the host player uses. This means cloud audio is mixed into the shared
     * output path by the Android audio system — not isolated on a separate path.
     *
     * Ducking is handled by AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, which causes the
     * host player's FocusManager to lower its volume while cloud audio plays.
     */
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var audioTrack: AudioTrack? = null

    /**
     * Reports whether this player is currently making sound, for the voice gate.
     *
     * The gate treats `AudioManager.isMusicActive` as "another app is playing" unless we are the
     * ones emitting. Its self-attribution timestamp is written by the earcon/TTS renderer, which
     * never runs for a cloud answer — so a multi-second answer was heard as a stranger and the
     * microphone was cut while we were still speaking. This is the cloud path joining that same
     * signal rather than the gate special-casing a cloud turn.
     */
    var onPlaybackAudibleChanged: ((Boolean) -> Unit)? = null

    /** Ticks the audible stamp while we are playing, so it cannot go stale mid-answer. */
    private var audibleHeartbeat: Job? = null
    private val frameBuffer = mutableListOf<ByteArray>()
    private var playing = false
    private var paused = false

    /** Cumulative samples this player has submitted, used as the answer's playback position. */
    private var submittedSamples = 0L

    /**
     * Frames handed to the CURRENT track since it was created: the track's OWN frame index, in the
     * track's frames.
     *
     * THIS IS NOT [submittedSamples] WHEN THE CODEC IS NOT 16 kHz. [submittedSamples] counts the
     * RESAMPLED recorder-side samples, while `AudioTrack.getTimestamp().framePosition` counts the
     * track's frames at the negotiated rate. At 48 kHz a one-second chunk is 48_000 track frames and
     * 16_000 submitted samples, so subtracting one from the other is arithmetic across two scales and
     * the result is not a duration. This counter is the track-side one, so the comparison is meaningful
     * at any rate.
     */
    private var trackFrames = 0L

    /**
     * Incremented every time a NEW track is created.
     *
     * Both the track's frame index and this answer's frame counters restart at zero on a rebuild, so
     * numbers from before and after agree while describing different audio. An observation and a block
     * record carry the generation they were made under, and the recorder matches only within one
     * generation -- which is what stops an old-track observation from selecting a new-track block once
     * the new write head passes the old frame number.
     */
    private var trackGeneration = 0L

    private var released = false
    private var decodeCodec: MediaCodec? = null
    private var decodeBuffering = false

    /** Negotiated codec name; empty means PCM. */
    var codec: String = ""
        internal set

    /** Current sample rate derived from the negotiated codec. */
    var currentSampleRate: Int = sampleRateHz
        private set

    /**
     * Update the codec after the server's auth response, before any audio frames arrive.
     * Re-creates the [AudioTrack] and decoder so the sample rate and channel config
     * match the server's negotiated settings.
     */
    fun setCodec(name: String) {
        if (name != codec) {
            codec = name
            currentSampleRate = parseSampleRate(name)
            Timber.i("[CloudAudio] negotiated codec: %s (sample rate: %d Hz)", name, currentSampleRate)
            rebuildTrackAndDecoder()
        }
    }

    /** Parse the sample rate from a codec string like `opus@48k` or `pcm@24k`. */
    private fun parseSampleRate(name: String): Int {
        val lower = name.lowercase()
        return when {
            lower.contains("48k") -> 48000
            lower.contains("24k") -> 24000
            lower.contains("16k") -> 16000
            else -> sampleRateHz // default
        }
    }

    /** Re-create the [AudioTrack] and [MediaCodec] decoder to match the negotiated codec. */
    private fun rebuildTrackAndDecoder() {
        decodeCodec?.stop()
        decodeCodec?.release()
        decodeCodec = null
        audioTrack?.release()
        audioTrack = null
        ensureAudioTrack()
    }

    /** True while audio is actively being played (not paused, not idle). */
    val isPlaying: Boolean get() = playing && !paused

    /** True when at least one frame was successfully written to the AudioTrack. */
    var audioWritten = false

    /**
     * Set when the sink refuses audio mid-answer, so the turn is stopped rather than silently gapped.
     *
     * Reset at the start of every playback run, not only at construction: one player serves several
     * turns, and a flag that survives the answer it stopped ends the NEXT answer after its first
     * frame -- attributing one turn's failure to a turn that never failed.
     */
    @Volatile
    private var audioWriteFailed = false
        private set

    /**
     * Reads the platform's `AudioTimestamp`, or null when it cannot report one. Defaults to the real
     * `getTimestamp` call; a test substitutes a recorded observation so the frame-to-chunk translation
     * can be exercised against a KNOWN sounding frame, which the device cannot be asked to produce on
     * demand.
     */
    var readAudioTimestamp: ((track: AudioTrack) -> android.media.AudioTimestamp?)? = null

    /**
     * Submits one frame to the output and returns how many bytes the sink accepted (<= 0 for a
     * refusal). Defaults to the real AudioTrack; a test can substitute a refusing writer so the
     * refusal path is exercised deterministically rather than inferred from a code reading.
     */
    var writeToSink: ((track: AudioTrack, bytes: ByteArray, offset: Int, count: Int) -> Int)? = null

    /**
     * Called when the sink refuses answer audio mid-turn.
     *
     * A refusal stops the answer, and without a path to the turn's result the caller could not tell
     * it from a clean end — the same gap the TTS path had. This reports the failure so the turn is
     * not recorded as completed; it is deliberately separate from any server terminal outcome, which
     * is a different fact and is not overwritten by a local playback failure.
     */
    var onWriteFailure: ((framesSubmitted: Long) -> Unit)? = null

    init {
        ensureAudioTrack()
    }

    /**
     * Submit a binary audio frame for buffering and eventual playback.
     *
     * Frames are queued until [play] is called; they are then consumed in submission order.
     */
    fun submitFrame(bytes: ByteArray) {
        if (released) return
        synchronized(frameBuffer) {
            frameBuffer.add(bytes)
        }
        // If we're already playing, try to drain immediately.
        if (playing && !paused) {
            drainFrames()
        }
    }

    // This playback's recorder ADMISSION, minted when the track incarnation is created (below, where
    // trackGeneration advances) and carried by every write and observation this player submits -- never
    // obtained at execution time inside the recorder. A stop closes it; a held callback carrying the
    // dead ticket cannot re-enter even before its first write ever landed, and a wholesale route
    // retirement invalidates it by generation.
    private var answerAdmission: au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackBufferRecorder.Admission? = null

    /**
     * Start (or resume) playback.
     *
     * If the track is not yet playing, this transitions it to playing.
     * If it was paused, this resumes from where it left off.
     */
    fun play() {
        if (released) return
        ensureAudioTrack()
        // Reset the failure flag for this run. The drain loop reads it at the end of every frame, so
        // leaving it set after the answer it stopped would end the next answer after its first frame
        // and report nothing -- a turn that played almost none of its audio, recorded as delivered.
        audioWriteFailed = false
        synchronized(frameBuffer) {
            if (paused) {
                audioTrack?.play()
                paused = false
                Timber.i("[CloudAudio] resumed")
                startAudibleHeartbeat()
            } else if (!playing) {
                audioTrack?.play()
                playing = true
                drainFrames()
                Timber.i("[CloudAudio] started")
                startAudibleHeartbeat()
            }
        }
    }

    /** Pause playback without discarding the buffer. */
    fun pause() {
        if (released) return
        synchronized(frameBuffer) {
            if (playing && !paused) {
                audioTrack?.pause()
                paused = true
                Timber.i("[CloudAudio] paused")
            }
        }
    }

    /**
     * Drain remaining buffered frames to the audio track, then stop and discard.
     *
     * Use this on turn completion so the last audio frames land before the player
     * resets. Call [stop] when a turn is superseded and stale frames must not play.
     */
    fun drainAndStop() {
        if (released) return
        synchronized(frameBuffer) {
            drainFrames()
        }
        stop()
    }

    /** Whether the output is observed to have consumed everything submitted to it. */
    enum class Completion { Consumed, Unconsumed, Unknown }

    /**
     * Reads the track's playback head in TRACK frames, or null when it cannot be observed. Test seam
     * mirrors [readAudioTimestamp]; the default is the platform's own head position.
     */
    var readPlaybackHeadFrames: ((track: AudioTrack) -> Long?)? = null

    /** The frames handed to the current track so far. Exposed for tests that drive the head seam. */
    fun trackFramesForTest(): Long = trackFrames

    /**
     * Drains the queued frames and then WAITS until the output is observed to have played them, per
     * @spec's completion ruling: frames handed to the track are SUBMITTED, not played, and a turn is
     * complete only when the playback head reaches what was written.
     *
     * Returns [Completion.Consumed] when the head reaches the submitted count before [timeoutMs];
     * [Completion.Unconsumed] when the timeout expires first -- the audio was cut short, which the
     * caller must not report as a normal completion; [Completion.Unknown] when the platform reports
     * no head at all, so nothing was measured either way. In every case the queued frames are
     * drained and playback is stopped before returning.
     */
    suspend fun drainAndAwaitCompletion(timeoutMs: Long = 10_000L): Completion {
        if (released) return Completion.Unknown
        val submittedAtSubmitTime = synchronized(frameBuffer) {
            drainFrames()
            trackFrames
        }
        // Whether ANY observation was made while waiting: this is the distinction between Unknown
        // (the platform offered no head, so nothing was measured) and Unconsumed (the head reported
        // but never arrived). It must be taken DURING the wait -- after stop() the track reports a
        // zero head, which would misread as an observation.
        var headWasObservable = false
        val head: Long? = try {
            kotlinx.coroutines.withTimeout(timeoutMs) {
                while (true) {
                    val t = audioTrack
                    // An installed seam is AUTHORITATIVE: its null means "not observable" and must
                    // not fall through to the real head, which would fabricate an observation the
                    // test explicitly said does not exist.
                    val observed: Long? = when {
                        readPlaybackHeadFrames != null -> t?.let { readPlaybackHeadFrames!!.invoke(it) }
                        t != null -> t.playbackHeadPosition.toLong()
                        else -> null
                    }
                    if (observed != null) {
                        headWasObservable = true
                        if (observed >= submittedAtSubmitTime) break
                    }
                    kotlinx.coroutines.delay(20L)
                }
                submittedAtSubmitTime
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            null
        }
        stop()
        return when {
            head != null -> Completion.Consumed
            headWasObservable -> Completion.Unconsumed
            else -> Completion.Unknown
        }
    }

    /**
     * Stop playback and discard the frame buffer.
     *
     * Call this when a turn is finished or cancelled so stale frames from a superseded turn
     * do not bleed into the next one.
     */
    fun stop() {
        if (released) return
        val stoppedIncarnation: au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackBufferRecorder.Admission? = answerAdmission
        synchronized(frameBuffer) {
            frameBuffer.clear()
            // (admission captured above; stop path retires it only if this instance was the player)
            if (playing) {
                audioTrack?.stop()
                playing = false
                paused = false
                Timber.i("[CloudAudio] stopped")
                stopAudibleHeartbeat()
            }
        }
        // Retire THIS incarnation's stream on any stop -- including a stop before anything played, which
        // closes admission for callbacks the player may still hold without removing audible audio (there
        // was none). Previously only a playing stop retired; the stricter form matches the token contract.
        // The answer is no longer playing, so retire ITS stream completely: conversion state AND the
        // acoustic reference beyond the audible delay tail, so speech after the stop cannot match
        // audio that is already gone and be dropped as bleed. Scoped to the incarnation this stop
        // actually ended: a late or duplicate stop must not touch a successor on a newer track, and a
        // whole-recorder retire would reset an episode still feeding the recorder.
        if (stoppedIncarnation != null) {
            playbackBufferRecorder?.retireProducer(stoppedIncarnation)
        }
        // And withdraw the routed observation. The contract is that the published route describes a
        // live reporting stream: this stream has stopped, so leaving the route published would keep
        // answering for a stream that is gone. Nulling it as well lets a later playback on this same
        // player take a fresh claim; clear() itself is idempotent per claim, so a following release()
        // withdrawing an already-cleared claim is a no-op.
        observedRoute?.clear()
        observedRoute = null
    }

    /**
     * Report "we are making sound" every [AUDIBLE_HEARTBEAT_MS] while playing.
     *
     * A single stamp at play start goes stale in an answer longer than the gate's attribution
     * window (5 s), and the microphone is then cut while we are still speaking — the reported bug,
     * just past the window. The refresh cannot ride the write path, because that path returns when
     * the buffer empties and the client is designed to ride out an underrun; a stamp tied to it
     * would look like coverage and be a no-op in exactly the case the property exists for.
     *
     * The tick samples the same predicate the write path and [submitFrame] already sample
     * (`playing && !paused`) — it is that predicate sampled by time rather than by frame arrival,
     * not a second way of answering the question.
     *
     * Coupled to [pause] deliberately: pausing stops the tick, so the gate's copy keeps the last
     * stamp and the 5 s window expires on its own. That expiry is what carries the paused case,
     * which is why [pause] does not signal `false` itself — if this predicate ever stops matching
     * "consuming audio", the paused case breaks with it.
     */
    private fun startAudibleHeartbeat() {
        // Sample the routed output alongside the audible stamp: the heartbeat already runs while this
        // player is producing sound, which is exactly when its routing is the answer for route decisions.
        // Claim the observation for this playback run if it is not already held, so a reused player
        // reports a live route on every turn rather than only the first.
        if (observedRoute == null) {
            observedRoute = routedOutputObserver?.observe()
        }
        observedRoute?.publish(audioTrack?.routedDevice)
        audibleHeartbeat?.cancel()
        onPlaybackAudibleChanged?.invoke(true)
        audibleHeartbeat = heartbeatScope.launch {
            while (isActive) {
                delay(AUDIBLE_HEARTBEAT_MS)
                if (!isPlaying) break
                onPlaybackAudibleChanged?.invoke(true)
            }
        }
    }

    private fun stopAudibleHeartbeat() {
        audibleHeartbeat?.cancel()
        audibleHeartbeat = null
        onPlaybackAudibleChanged?.invoke(false)
    }

    private val heartbeatScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Release all resources. Must be called before the player is discarded. */
    fun release() {
        if (released) return
        released = true
        // stop() withdraws the claim when one is held and clears the reference, so a released player
        // cannot be re-used to take another. Nothing else to clear here.
        stop()
        decodeCodec?.stop()
        decodeCodec?.release()
        decodeCodec = null
        audioTrack?.release()
        audioTrack = null
        Timber.i("[CloudAudio] released")
    }

    // -- internal --

    private fun ensureAudioTrack() {
        if (audioTrack != null) return

        val minBufSize = AudioTrack.getMinBufferSize(
            currentSampleRate,
            channelConfig,
            audioFormat,
        )
        if (minBufSize <= 0) {
            Timber.w("[CloudAudio] could not determine min buffer size")
            return
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(currentSampleRate)
                    .setChannelMask(channelConfig)
                    .setEncoding(audioFormat)
                    .build(),
            )
            .setBufferSizeInBytes(max(minBufSize * 4, 32 * 1024))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack = track
        // A new track's frame index starts at zero, so the track-side counter restarts with it, and
        // everything recorded against the previous track is now from another incarnation. The admission
        // for the NEW incarnation opens HERE, at owner creation -- an epoch change is a new producer,
        // not a migration of the old one's audio.
        trackFrames = 0L
        trackGeneration += 1L
        answerAdmission = playbackBufferRecorder?.openAdmission(CLOUD_ANSWER_IDENTITY_BASE + trackGeneration)
    }

    private fun drainFrames() {
        val track = audioTrack ?: return
        if (!playing || paused) return

        while (true) {
            val frame = synchronized(frameBuffer) {
                if (frameBuffer.isEmpty()) return
                frameBuffer.removeAt(0)
            }

            // Decode if needed.
            val pcmBytes = if (codec.equals("opus", ignoreCase = true)) {
                decodeOpus(frame)
            } else {
                frame
            }

            if (pcmBytes.isNotEmpty()) {
                // Write the whole frame, tracking the accepted prefix: a short write must not drop
                // the remainder, or answer audio is silently lost while the turn is still active.
                var offset = 0
                while (offset < pcmBytes.size) {
                    val writer = writeToSink
                    val written = if (writer != null) {
                        writer(track, pcmBytes, offset, pcmBytes.size - offset)
                    } else {
                        track.write(
                            pcmBytes,
                            offset,
                            pcmBytes.size - offset,
                            AudioTrack.WRITE_BLOCKING,
                        )
                    }
                    if (written <= 0) {
                        // An explicit failure rather than a silent drop: stop this answer and report
                        // it, so the turn is not treated as a clean end.
                        Timber.w("[CloudAudio] AudioTrack.write returned $written; stopping answer")
                        audioWriteFailed = true
                        onWriteFailure?.invoke(submittedSamples)
                        break
                    }
                    audioWritten = true
                    // Record only what the sink accepted, so the reference never holds audio the
                    // output rejected, and position it on the submitted-sample timeline.
                    val floats = s16leBytesToFloats(pcmBytes, written, offset)
                    // The decoded PCM is at the codec's rate; the recorder's timeline is 16 kHz.
                    // Recording it unchanged would store rate/16000 times too many samples for the
                    // span of audio they represent, so the reference would claim a duration that
                    // never played and correlation could never align against it. Resample through the
                    // stateful resampler so successive chunks stay phase-continuous: a frame whose
                    // length does not divide into a whole output count would otherwise drop its
                    // remainder every time and drift off the producer clock.
                    resampler.beginChunk(currentSampleRate)
                    val referenceFloats = resampler.resample(floats)
                    // The chunk's position on the RENDER clock, when the platform reports one. That
                    // is a time on the same monotonic axis the capture side uses, so it is the only
                    // value here the microphone instant can be compared against.
                    //
                    // THE SUBMITTED COUNT IS NOT A SUBSTITUTE AND MUST NOT BE LABELLED AS ONE. It is
                    // a different quantity -- what was SENT, not when it SOUNDED -- and the difference
                    // is exactly the player's buffer latency, which is what an echo-bleed filter is
                    // trying to measure. Recording it under `Monotonic` would make the recorder offer
                    // an anchor (its `anchored` gate reads that label) and the filter would compare a
                    // submission count against a capture instant: a confident misalignment, which is
                    // worse than no position at all. So an unavailable clock records NO position, and
                    // the recorder's own null path leaves the window unanchored.
                    //
                    // The PCM is still recorded either way -- an absent position changes WHEN the
                    // window can be placed, never WHAT it holds.
                    // The observation names WHICH frame is sounding and WHEN, and this chunk begins at
                    // a later frame on the same index. `submittedSamples` is exactly that index: it
                    // counts the frames already handed to the track before this chunk, so it is this
                    // chunk's first frame. The translation between the two is the buffer depth, which
                    // is the quantity the observation alone cannot express.
                    // 2 bytes per s16le sample, times the channel count, is the bytes-per-frame.
                    val bytesPerFrame = 2L * (if (channelConfig == AudioFormat.CHANNEL_OUT_STEREO) 2 else 1)
                    val observation = renderedFrame(currentSampleRate)
                    val renderMs = observation?.let {
                        expectedInstantOfChunk(
                            observation = it,
                            // The chunk's first frame on the TRACK's index, which is what `framePosition`
                            // is expressed in. Not `submittedSamples`, which is the recorder-side count.
                            firstFrameOfChunk = trackFrames,
                            sampleRateHz = currentSampleRate,
                        )
                    }
                    playbackBufferRecorder?.write(
                        pcm = referenceFloats,
                        admission = answerAdmission,
                        // The chunk's frame range on the track's index, recorded so a render
                        // observation can later select THIS block -- or the block it truly names --
                        // by frame identity rather than by extrapolated time. The rendered-block
                        // mapping is built from these records; they retire with their samples.
                        trackStartFrame = trackFrames,
                        trackEndFrame = trackFrames + written.toLong() / bytesPerFrame,
                        trackGeneration = trackGeneration,
                        // A rendered-frame observation when the platform reports one, else the
                        // producer's own placement -- the cumulative submitted count, from the chunks
                        // before this one (write() adds this chunk's own duration). The count is
                        // retained because it is what makes the window's DURATION checkable and it is
                        // what a drifting resample would break.
                        positionMs = renderMs ?: (submittedSamples * 1_000L) / currentSampleRate,
                        // NO AXIS, even when renderMs came from a real observation: the extrapolated
                        // chunk instant is a PREDICTION about unrendered audio, and prediction
                        // metadata stays separate from observed alignment. Observed alignment is
                        // claimed only by the rendered-block mapping, which selects blocks the
                        // observation's frame actually names. (The estimate still sets the recorded
                        // position, which duration checks rely on; it just cannot anchor.)
                        axis = null,
                    )
                    submittedSamples += floats.size
                    // The track's own frame count, advanced by the frames just written at the track's
                    // rate: one frame is one sample for the mono/stereo layouts used here, and the byte
                    // count is what the track received.
                    trackFrames += written.toLong() / bytesPerFrame
                    // The observation is offered to the recorder only now, after the block it might
                    // name is recorded. A matchable observation (frame at or before the write head)
                    // drives the rendered-block mapping; an unmatched one is refused there.
                    if (observation != null) {
                        playbackBufferRecorder?.onRenderObservation(
                            observation.framePosition,
                            observation.nanoTime,
                            currentSampleRate,
                            trackGeneration,
                            answerAdmission,
                        )
                    }
                    offset += written
                }
                if (audioWriteFailed) break
            }
        }
    }

    /**
     * An observation of the output, in the two quantities the platform reports TOGETHER, or null when
     * the platform cannot report one.
     *
     * `AudioTrack.getTimestamp` answers with a PAIR: a frame position and the monotonic instant that
     * frame was rendered at. Keeping them together is the whole point, because `framePosition` is what
     * identifies WHICH frame sounded, and the caller needs that identity to say something about a
     * different frame -- the one it is submitting.
     *
     * @property framePosition the track's own frame index (frames since the track started) of the frame
     * that was sounding at [nanoTime].
     * @property nanoTime the instant that frame sounded, in MILLISECONDS on `TIMEBASE_MONOTONIC` --
     * the same clock domain as the capture observation, which is what makes the two sides comparable
     * at all. The platform reports nanoseconds; the conversion happens where the value is read.
     */
    private data class RenderedFrame(val framePosition: Long, val nanoTime: Long)

    /**
     * Reads the output's rendered-frame observation, or null when the platform reports none.
     *
     * A platform that cannot report one stays unknown. Substituting the submitted count would be a
     * value this side never measured, presented as a rendered position -- the substitution the whole
     * alignment exists to avoid, and worse than an absent value because it aligns confidently against
     * the wrong instant.
     */
    private fun renderedFrame(sampleRateHz: Int): RenderedFrame? {
        val track = audioTrack ?: return null
        if (sampleRateHz <= 0) return null
        val timestamp = readAudioTimestamp?.invoke(track) ?: android.media.AudioTimestamp().also {
            if (!track.getTimestamp(it)) return null
        }
        // A zero frame position means the track has not rendered anything yet, so there is no frame to
        // name and the observation says nothing.
        if (timestamp.framePosition <= 0) return null
        // `nanoTime` is NANOSECONDS and the recorder's positions are MILLISECONDS. The division
        // happens HERE, at the single point the platform value enters the class, so no caller has to
        // remember which unit it is holding -- and so a caller cannot pair the two domains by accident.
        return RenderedFrame(timestamp.framePosition, timestamp.nanoTime / 1_000_000L)
    }

    /**
     * The monotonic instant THIS chunk's first sample will sound at, derived from an observation of a
     * DIFFERENT frame, or null when nothing can be said.
     *
     * The distinction this exists to keep: an observation names the frame that is SOUNDING NOW, which
     * at the moment of submission is audio from earlier chunks -- the new chunk is behind the buffer's
     * depth. Treating the observation's time as this chunk's position is not a rounding difference; it
     * is the whole buffer depth applied as though it were zero, and it lands on the one quantity the
     * echo filter compares the capture instant against.
     *
     * So the translation is explicit. The observation names frame [RenderedFrame.framePosition] at
     * [RenderedFrame.nanoTime]; the chunk being submitted starts at frame [firstFrameOfChunk] on the
     * same index, because the track's frame index counts what has been handed to it. Frames between the
     * two are still queued ahead of this chunk, so the chunk sounds that many frames later.
     *
     * This is an EXTRAPOLATION and is named as one: it says when the chunk is expected to sound given
     * that the queue drains at the answer's rate, not that the chunk has sounded. Nothing here claims
     * the chunk has rendered.
     */
    private fun expectedInstantOfChunk(
        observation: RenderedFrame,
        firstFrameOfChunk: Long,
        sampleRateHz: Int,
    ): Long? {
        if (sampleRateHz <= 0) return observation.nanoTime
        // Both sides are TRACK-relative, which is the reason the subtraction is meaningful:
        // framePosition is the track's own index, and firstFrameOfChunk is [trackFrames] -- the
        // frames this track has been handed since it was created, which restarts at zero with the
        // track exactly as framePosition does. A codec change resets both together, so no re-basing
        // offset is needed. Both count frames at the track's rate, so the difference is frames and
        // the sample-rate division below converts that difference to time.
        val framesAhead = firstFrameOfChunk - observation.framePosition
        // Zero or negative means the observation names this chunk's own frame or a later one. That is
        // a track reset, a stale read, or an observation from a rebuilt track. CLAMPING to the
        // observation's own instant would place the chunk at a time derived from a reading that does
        // not describe the current queue and present it as observed alignment, so the mapping reports
        // UNAVAILABLE instead and the caller records no anchored position for the chunk.
        if (framesAhead <= 0) return null
        return observation.nanoTime + framesAhead * 1_000L / sampleRateHz
    }

    private fun decodeOpus(input: ByteArray): ByteArray {
        if (decodeCodec == null) {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_OPUS,
                currentSampleRate,
                if (channelConfig == AudioFormat.CHANNEL_OUT_STEREO) 2 else 1,
            )
            format.setString(
                MediaFormat.KEY_CHANNEL_COUNT,
                if (channelConfig == AudioFormat.CHANNEL_OUT_STEREO) "2" else "1",
            )
            format.setInteger(MediaFormat.KEY_SAMPLE_RATE, currentSampleRate)

            try {
                decodeCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
                decodeCodec?.configure(format, null, null, 0)
                decodeCodec?.start()
                decodeBuffering = true
            } catch (e: Exception) {
                Timber.w(e, "[CloudAudio] failed to create Opus decoder")
                decodeCodec = null
                return ByteArray(0) // reject: raw Opus bytes are not valid audio
            }
        }

        val codec = decodeCodec ?: return ByteArray(0)

        val inputBufferIndex = codec.dequeueInputBuffer(10_000)
        if (inputBufferIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: return input
            inputBuffer.clear()
            inputBuffer.put(input)
            codec.queueInputBuffer(
                inputBufferIndex,
                0,
                input.size,
                0,
                0,
            )
        }

        val info = MediaCodec.BufferInfo()
        val outputBufferIndex = codec.dequeueOutputBuffer(info, 10_000)

        return if (outputBufferIndex >= 0) {
            val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
            var out = ByteArray(info.size)
            outputBuffer?.get(out)
            codec.releaseOutputBuffer(outputBufferIndex, false)
            // Drain any remaining output.
            while (true) {
                val next = codec.dequeueOutputBuffer(info, 0)
                if (next < 0) break
                if (next >= 0) {
                    val buf = codec.getOutputBuffer(next)
                    if (buf != null && info.size > 0) {
                        val chunk = ByteArray(info.size)
                        buf.get(chunk)
                        out += chunk
                    }
                    codec.releaseOutputBuffer(next, false)
                }
            }
            out
        } else {
            ByteArray(0)
        }
    }

    companion object {
        private const val TAG = "CloudAudio"

        /**
         * Resamples [samples] from [sourceRateHz] to the recorder's 16 kHz timeline.
         *
         * Stateless single-shot conversion, kept for callers that need one buffer. Prefer
         * [ChunkResampler] for a stream: with a fractional ratio this discards each chunk's
         * remainder independently, so successive chunks drift off the producer clock.
         */
        internal fun resampleToRecorderRate(
            samples: FloatArray,
            sourceRateHz: Int,
            targetRateHz: Int = PlaybackBufferRecorder.SAMPLE_RATE,
        ): FloatArray {
            val resampler = ChunkResampler(targetRateHz)
            resampler.beginChunk(sourceRateHz)
            return resampler.resample(samples)
        }

        /**
         * Resamples a stream of chunks while preserving phase and audio continuity across them.
         *
         * A per-chunk conversion cannot be correct in general: a chunk whose length does not divide
         * into a whole output count drops its remainder, so a long answer loses samples at every
         * boundary and the reference drifts behind the audio that actually played. This holds back
         * the tail it cannot yet interpolate against and prepends it to the next chunk, so the
         * emitted count over N chunks equals the emitted count for their concatenation.
         */
        internal class ChunkResampler(
            private val targetRateHz: Int = PlaybackBufferRecorder.SAMPLE_RATE,
        ) {
            private var sourceRateHz = 0
            private var step = 1.0

            /** Position in the current virtual chunk at which the next output sample is due. */
            private var cursor = 0.0

            /** The unconsumed tail of the previous chunk, carried into the next one. */
            private var carry = FloatArray(0)

            /** Starts a chunk at [rateHz], carrying position and tail from the previous one. */
            fun beginChunk(rateHz: Int) {
                if (rateHz != sourceRateHz) {
                    sourceRateHz = rateHz
                    step = rateHz.toDouble() / targetRateHz
                    cursor = 0.0
                    carry = FloatArray(0)
                }
            }

            /**
             * Converts one chunk, continuing the previous chunk's phase. The unconsumed tail is kept
             * for the next call, so no audio is dropped at a boundary and none is repeated.
             */
            fun resample(samples: FloatArray): FloatArray {
                if (sourceRateHz == targetRateHz || samples.isEmpty()) {
                    // At the target rate the samples pass through, but a carried tail from a previous
                    // rate must not be stranded.
                    if (carry.isEmpty()) return samples
                    val joined = carry + samples
                    carry = FloatArray(0)
                    return joined
                }
                // Work on the carried tail plus this chunk, so the interpolation that could not be
                // completed last time is completed now rather than dropped.
                val source = if (carry.isEmpty()) samples else carry + samples
                val out = ArrayList<Float>((source.size / step).toInt() + 2)
                var idx = cursor
                while (true) {
                    val lo = idx.toInt()
                    // Need lo and lo+1 present to interpolate between.
                    if (lo + 1 >= source.size) break
                    val frac = (idx - lo).toFloat()
                    out.add(source[lo] + (source[lo + 1] - source[lo]) * frac)
                    idx += step
                }
                // Hold back everything from the last emitted sample's floor onward: the next chunk
                // continues from there, so the boundary sample is interpolated, not lost.
                val consumed = idx.toInt()
                if (consumed >= source.size) {
                    carry = FloatArray(0)
                    cursor = idx - source.size
                } else {
                    carry = source.copyOfRange(consumed, source.size)
                    cursor = idx - consumed
                }
                val result = FloatArray(out.size)
                for (i in out.indices) result[i] = out[i]
                return result
            }
        }

        /**
         * Converts 16-bit little-endian PCM bytes to the float samples the echo reference stores.
         * [lengthBytes] bounds what is read, so a partial AudioTrack write records only what the
         * sink actually accepted rather than the whole decoded buffer.
         */
        internal fun s16leBytesToFloats(bytes: ByteArray, lengthBytes: Int, offset: Int = 0): FloatArray {
            val samples = lengthBytes / 2
            val out = FloatArray(samples)
            for (i in 0 until samples) {
                // Read from the chunk's own offset. Converting the remainder of a partially accepted
                // frame from index 0 re-reads the frame's opening bytes for every chunk, so the
                // reference holds duplicated, misaligned audio of a length that never matches what
                // the sink took.
                val lo = bytes[offset + i * 2].toInt() and 0xFF
                val hi = bytes[offset + i * 2 + 1].toInt()
                val v = (hi shl 8) or lo
                out[i] = v.toShort() / 32768f
            }
            return out
        }

        /**
         * How often to refresh the audible stamp while playing.
         *
         * The gate attributes sound by asking whether this app emitted within the last 5 s
         * (`OtherAppPlayingCondition.transitionWindowMs`), so the refresh has to be comfortably
         * inside that: an answer of 5 s or more would otherwise go stale mid-playback.
         */
        private const val AUDIBLE_HEARTBEAT_MS = 1_000L
    }
}
