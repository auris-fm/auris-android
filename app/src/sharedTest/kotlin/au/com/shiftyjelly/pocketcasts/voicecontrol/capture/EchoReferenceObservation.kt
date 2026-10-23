package au.com.shiftyjelly.pocketcasts.voicecontrol.capture

import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceSegmenterResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.engine.PlaybackReference
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Test evidence, copied before later playback retirement can withdraw the live reference. */
internal data class EchoReferenceObservation(
    val speech: FloatArray,
    val reference: PlaybackReference,
    val invalidSpeechReason: String?,
) {
    val referenceSignalSpan: Int
        get() = reference.signalSpan()

    fun unusableReason(): String? = when {
        invalidSpeechReason != null -> invalidSpeechReason

        speech.none { it != 0f } -> "captured speech has no signal"

        reference.samples.any { !it.isFinite() } -> "reference contains non-finite samples"

        referenceSignalSpan == 0 -> "reference has no signal (retired or never recorded)"

        referenceSignalSpan < speech.size -> "reference signal does not cover the reported speech interval"

        // The unpositioned correlator needs at least its 50 ms minimum-delay candidate.
        reference.samples.size < speech.size + reference.sampleRateHz / 20 -> "reference has no complete in-window correlation candidate"

        else -> null
    }

    companion object {
        fun capture(segment: VoiceSegmenterResult.SpeechEnded, reference: PlaybackReference): EchoReferenceObservation {
            val frames = segment.frames
            val samples = frames.flatMap { frame -> frame.samples.map { it / 32768f } }.toFloatArray()
            val start = segment.speechOnsetSample
            val end = segment.speechEndSample
            val invalid = when {
                frames.isEmpty() -> "native segment contains no frames"
                frames.any { it.sampleRateHz != reference.sampleRateHz } -> "capture/reference sample rates differ"
                start !in samples.indices || end <= start || end > samples.size -> "native speech interval is unavailable or invalid"
                else -> null
            }
            return EchoReferenceObservation(
                speech = if (invalid == null) samples.copyOfRange(start, end) else FloatArray(0),
                reference = reference.copy(samples = reference.samples.copyOf()),
                invalidSpeechReason = invalid,
            )
        }
    }
}

/**
 * Observes one isolated acoustic stimulus, shared by the device fixture and host control.
 *
 * Reference writes may finish after SpeechStarted, and retirement may finish before SpeechEnded.
 * Retain the greatest signal coverage observed DURING this VAD segment, independently of any
 * correlation score. Later retirement can zero the live ring without destroying this test evidence.
 * This records the VAD-event interval; it does not invent sample-level capture or render timestamps.
 */
internal suspend fun Flow<VoiceSegmenterResult>.collectEchoObservations(
    reference: () -> PlaybackReference,
    observe: (EchoReferenceObservation) -> Unit,
) = coroutineScope {
    val active = ActiveEchoReference(reference)
    val sampler = launch {
        while (isActive) {
            active.sample()
            delay(20L)
        }
    }
    try {
        this@collectEchoObservations.collect { event ->
            active.onEvent(event)?.let(observe)
        }
    } finally {
        sampler.cancelAndJoin()
    }
}

private class ActiveEchoReference(private val reference: () -> PlaybackReference) {
    private var started = false
    private var best: PlaybackReference? = null
    private var bestSpan = -1

    @Synchronized
    fun sample() {
        if (!started) return
        val current = reference()
        val span = current.signalSpan()
        if (span > bestSpan) {
            best = current.copy(samples = current.samples.copyOf())
            bestSpan = span
        }
    }

    @Synchronized
    fun onEvent(event: VoiceSegmenterResult): EchoReferenceObservation? = when (event) {
        VoiceSegmenterResult.SpeechStarted -> {
            started = true
            best = null
            bestSpan = -1
            sample()
            null
        }

        is VoiceSegmenterResult.SpeechEnded -> {
            val hadStart = started
            sample()
            val saved = best ?: PlaybackReference(FloatArray(0), 16_000, null)
            started = false
            best = null
            bestSpan = -1
            EchoReferenceObservation.capture(event, saved).let {
                if (hadStart) it else it.copy(invalidSpeechReason = "native segment has no observed SpeechStarted interval")
            }
        }

        is VoiceSegmenterResult.Rejected -> {
            started = false
            best = null
            bestSpan = -1
            null
        }

        else -> null
    }
}

private fun PlaybackReference.signalSpan(): Int {
    val first = samples.indexOfFirst { it != 0f }
    val last = samples.indexOfLast { it != 0f }
    return if (first < 0) 0 else last - first + 1
}
