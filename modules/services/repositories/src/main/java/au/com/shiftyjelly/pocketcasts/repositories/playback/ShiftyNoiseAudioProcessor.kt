package au.com.shiftyjelly.pocketcasts.repositories.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import au.com.shiftyjelly.pocketcasts.models.to.NoiseEnvironmentMode
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

@OptIn(UnstableApi::class)
internal class ShiftyNoiseAudioProcessor(
    private val sampleSource: (NoiseEnvironmentMode) -> PracticeNoiseSamples.ModeSamples? = { null },
) : BaseAudioProcessor() {
    var enabled: Boolean = false
    var environmentMode: NoiseEnvironmentMode = NoiseEnvironmentMode.COFFEE_SHOP
    var intensity: Float = DEFAULT_INTENSITY
    var eventfulness: Float = DEFAULT_EVENTFULNESS
    var spatialMotion: Float = DEFAULT_SPATIAL_MOTION

    private var randomState: Int = 0x2F6E2B1
    private var sampleRate: Int = 0
    private var channelCount: Int = 1

    private var framesIntoSegment: Long = 0
    private var segmentFrames: Long = 1
    private var pulseActive: Boolean = true
    private var pulseFrames: Long = 1
    private var pulseNoiseAmplitude: Float = 0f
    private var pulseEdgeFraction: Float = 0.2f

    private var lowBed: Float = 0f
    private var midBed: Float = 0f
    private var highBed: Float = 0f
    private var speechLow: Float = 0f
    private var speechHigh: Float = 0f
    private var speechEnvelope: Float = 0.35f
    private var speechTarget: Float = 0.5f
    private var speechTargetFramesRemaining: Long = 1

    private var textureEnvelope: Float = 0f
    private var textureDecay: Float = 0.996f
    private var textureType: Int = 0
    private var texturePhase: Float = 0f
    private var texturePhaseIncrement: Float = 0f
    private var textureNoiseState: Float = 0f

    private var movementPhase: Float = 0f
    private var movementRateHz: Float = 0.16f
    private var movementTargetRateHz: Float = 0.16f
    private var movementTransitionFrames: Long = 1
    private var movementBurstEnvelope: Float = 0f
    private var movementBurstDecay: Float = 0.9985f

    private var humPhase: Float = 0f
    private var humBaseFrequencyHz: Float = 82f
    private var humDriftPhase: Float = 0f
    private var modeSamples: PracticeNoiseSamples.ModeSamples? = null
    private var loadedSampleMode: NoiseEnvironmentMode? = null
    private var currentBedClip: PracticeNoiseSamples.Clip? = null
    private var currentBedPosition: Float = 0f
    private var currentBedStep: Float = 1f
    private var overlayCooldownFrames: Long = 0
    private var movementGainPhase: Float = 0f
    private val activeOverlays = mutableListOf<OverlayPlayer>()
    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)
        resetPulseState(profileForMode(environmentMode))
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: java.nio.ByteBuffer) {
        val size = inputBuffer.remaining()
        val usePassthrough = !enabled || sampleRate <= 0
        val passthroughInput = if (usePassthrough) inputBuffer.duplicate() else null
        val output = replaceOutputBuffer(size)

        if (usePassthrough) {
            val isAliasedBuffer = output === inputBuffer
            output.put(passthroughInput!!).flip()
            if (!isAliasedBuffer) {
                inputBuffer.position(inputBuffer.limit())
            }
            return
        }

        val inBuffer = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val outBuffer = output.order(ByteOrder.LITTLE_ENDIAN)
        val profile = profileForMode(environmentMode)

        var channelIndex = 0
        var frameNoise = 0

        while (inBuffer.remaining() >= Short.SIZE_BYTES) {
            val sample = inBuffer.short.toInt()

            if (channelIndex == 0) {
                val intensityValue = intensity.coerceIn(0f, 1f)
                if (intensityValue <= 0f) {
                    frameNoise = 0
                } else {
                    val outputIntensity = outputGainForIntensity(intensityValue)
                    val sampleScene = renderSampleScene()
                    frameNoise = if (sampleScene != null) {
                        (sampleScene * outputIntensity * SAMPLE_SCENE_OUTPUT_GAIN)
                            .coerceIn(-4_200f, 4_200f)
                            .toInt()
                    } else {
                        val eventfulnessValue = eventfulness.coerceIn(0f, 1f)
                        val ambientFloor = (0.12f + (0.2f * eventfulnessValue))
                            .coerceIn(0.12f, 0.5f)
                        val envelope = if (pulseActive) {
                            val accent = pulseEnvelope(framesIntoSegment, pulseFrames)
                            ambientFloor + ((1f - ambientFloor) * accent)
                        } else {
                            ambientFloor
                        }
                        val shapedScene = generateEnvironmentNoise(profile)
                        val intensityGain = outputIntensity * SYNTH_SCENE_OUTPUT_GAIN
                        (shapedScene * pulseNoiseAmplitude * envelope * intensityGain)
                            .coerceIn(-4_200f, 4_200f)
                            .toInt()
                    }
                }
            }

            outBuffer.putShort((sample + frameNoise).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())

            channelIndex++
            if (channelIndex == channelCount) {
                channelIndex = 0
                advanceSegment(profile)
            }
        }

        outBuffer.flip()
        inputBuffer.position(inputBuffer.limit())
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        super.onFlush(streamMetadata)
        randomState = 0x2F6E2B1
        resetPulseState(profileForMode(environmentMode))
    }

    override fun onReset() {
        enabled = false
        randomState = 0x2F6E2B1
        environmentMode = NoiseEnvironmentMode.COFFEE_SHOP
        intensity = DEFAULT_INTENSITY
        eventfulness = DEFAULT_EVENTFULNESS
        spatialMotion = DEFAULT_SPATIAL_MOTION
        sampleRate = 0
        channelCount = 1
        framesIntoSegment = 0
        segmentFrames = 1
        pulseActive = true
        pulseFrames = 1
        pulseNoiseAmplitude = 0f
        pulseEdgeFraction = 0.2f

        lowBed = 0f
        midBed = 0f
        highBed = 0f
        speechLow = 0f
        speechHigh = 0f
        speechEnvelope = 0.35f
        speechTarget = 0.5f
        speechTargetFramesRemaining = 1

        textureEnvelope = 0f
        textureDecay = 0.996f
        textureType = 0
        texturePhase = 0f
        texturePhaseIncrement = 0f
        textureNoiseState = 0f

        movementPhase = 0f
        movementRateHz = 0.16f
        movementTargetRateHz = 0.16f
        movementTransitionFrames = 1
        movementBurstEnvelope = 0f
        movementBurstDecay = 0.9985f

        humPhase = 0f
        humBaseFrequencyHz = 82f
        humDriftPhase = 0f
        modeSamples = null
        loadedSampleMode = null
        currentBedClip = null
        currentBedPosition = 0f
        currentBedStep = 1f
        overlayCooldownFrames = 0
        movementGainPhase = 0f
        activeOverlays.clear()
    }

    private fun advanceSegment(profile: EnvironmentProfile) {
        framesIntoSegment++
        if (framesIntoSegment < segmentFrames) {
            return
        }

        framesIntoSegment = 0
        if (pulseActive) {
            pulseActive = false
            segmentFrames = nextQuietFrames(profile)
        } else {
            pulseActive = true
            pulseFrames = nextActiveFrames(profile)
            segmentFrames = pulseFrames
            preparePulseNoise(profile)
        }
    }

    private fun resetPulseState(profile: EnvironmentProfile) {
        framesIntoSegment = 0
        pulseActive = true
        pulseFrames = nextActiveFrames(profile)
        segmentFrames = pulseFrames
        preparePulseNoise(profile)

        lowBed = 0f
        midBed = 0f
        highBed = 0f
        speechLow = 0f
        speechHigh = 0f
        speechEnvelope = 0.35f
        speechTarget = 0.5f
        speechTargetFramesRemaining = 1

        textureEnvelope = 0f
        textureNoiseState = 0f

        movementPhase = 0f
        movementBurstEnvelope = 0f
        movementTransitionFrames = 1

        humPhase = 0f
        humDriftPhase = 0f
        currentBedClip = null
        currentBedPosition = 0f
        currentBedStep = 1f
        overlayCooldownFrames = 0
        movementGainPhase = 0f
        activeOverlays.clear()
    }

    private fun preparePulseNoise(profile: EnvironmentProfile) {
        pulseNoiseAmplitude = nextRandomFloat(profile.baseAmplitudeMin, profile.baseAmplitudeMax)
        pulseEdgeFraction = nextRandomFloat(profile.edgeFractionMin, profile.edgeFractionMax)
        humBaseFrequencyHz = nextRandomFloat(profile.humFreqMinHz, profile.humFreqMaxHz)

        movementTargetRateHz = nextRandomFloat(profile.movementRateMinHz, profile.movementRateMaxHz)
        movementRateHz = movementTargetRateHz
        movementTransitionFrames = nextSegmentFrames(minMs = 240f, maxMs = 1200f)
    }

    private fun nextActiveFrames(profile: EnvironmentProfile): Long {
        val activity = eventfulness.coerceIn(0f, 1f)
        val scale = 0.9f + (activity * 0.45f)
        return nextSegmentFrames(profile.activeMinMs * scale, profile.activeMaxMs * scale)
    }

    private fun nextQuietFrames(profile: EnvironmentProfile): Long {
        val activity = eventfulness.coerceIn(0f, 1f)
        val scale = 1.2f - (activity * 0.5f)
        return nextSegmentFrames(profile.quietMinMs * scale, profile.quietMaxMs * scale)
    }

    private fun generateEnvironmentNoise(profile: EnvironmentProfile): Float {
        val white = nextNoiseSampleNormalized()
        val clampedEventfulness = eventfulness.coerceIn(0f, 1f)
        val clampedMotion = spatialMotion.coerceIn(0f, 1f)

        lowBed = (lowBed * 0.995f) + (white * 0.005f)
        val lowRemoved = white - lowBed
        midBed = (midBed * 0.9f) + (lowRemoved * 0.1f)
        highBed = (highBed * 0.42f) + ((white - midBed) * 0.58f)

        speechLow = (speechLow * 0.94f) + (white * 0.06f)
        speechHigh = (speechHigh * 0.986f) + (white * 0.014f)
        val speechBand = speechLow - speechHigh
        updateSpeechEnvelope(clampedEventfulness)

        maybeTriggerTextureEvent(profile, clampedEventfulness)
        val texture = renderTextureEvent(profile, white)

        updateMovement(profile, clampedMotion)
        val movementLfo = (0.5f + (0.5f * sin((2f * PI.toFloat()) * movementPhase))).coerceIn(0f, 1f)
        val movement = ((lowBed * 0.72f) + (midBed * 0.28f)) *
            profile.movementMix *
            (0.25f + (0.85f * movementLfo)) *
            clampedMotion +
            (highBed * 0.12f * movementBurstEnvelope * clampedMotion)

        val hum = renderHum(profile, clampedMotion)

        val babbleMix = profile.speechMix * (0.45f + (clampedEventfulness * 1.25f))
        val bed = (lowBed * profile.lowMix) + (midBed * profile.midMix) + (highBed * profile.highMix)
        val babble = speechBand * babbleMix * (0.4f + (speechEnvelope * 0.95f))

        return (bed + babble + texture + movement + hum).coerceIn(-2.3f, 2.3f)
    }

    private fun renderSampleScene(): Float? {
        val samples = ensureModeSamples() ?: return null
        if (samples.beds.isEmpty()) return null

        val clampedEventfulness = eventfulness.coerceIn(0f, 1f)
        val clampedMotion = spatialMotion.coerceIn(0f, 1f)

        val bed = nextBedSample(samples)
        maybeSpawnOverlay(samples, clampedEventfulness)
        val speech = renderOverlaySamples()

        // Mono-compatible "spatial" movement as slow gain drift + flutter.
        if (sampleRate > 0) {
            val movementRate = 0.015f + (0.11f * clampedMotion)
            movementGainPhase += movementRate / sampleRate.toFloat()
            if (movementGainPhase >= 1f) movementGainPhase -= 1f
        }
        val motionGain = 0.82f + (0.18f * sin((2f * PI.toFloat()) * movementGainPhase))
        val bedMix = bed * (0.55f + (0.35f * (1f - clampedEventfulness)))
        val speechMix = speech * (0.38f + (0.7f * clampedEventfulness))
        return ((bedMix + speechMix) * motionGain * SAMPLE_SCENE_GAIN).coerceIn(-8f, 8f)
    }

    private fun updateSpeechEnvelope(clampedEventfulness: Float) {
        speechTargetFramesRemaining--
        if (speechTargetFramesRemaining <= 0L) {
            speechTarget = nextRandomFloat(0.24f, 1f)
            val minMs = 140f - (clampedEventfulness * 50f)
            val maxMs = 520f - (clampedEventfulness * 170f)
            speechTargetFramesRemaining = nextSegmentFrames(minMs.coerceAtLeast(60f), maxMs.coerceAtLeast(120f))
        }

        val chase = 0.0015f + (0.002f * clampedEventfulness)
        speechEnvelope += (speechTarget - speechEnvelope) * chase
    }

    private fun maybeTriggerTextureEvent(profile: EnvironmentProfile, clampedEventfulness: Float) {
        if (textureEnvelope > 0.01f) {
            return
        }
        val probability = profile.textureProbabilityPerFrame * (0.35f + (clampedEventfulness * 1.9f))
        if (nextRandomFloat(0f, 1f) >= probability) {
            return
        }

        textureEnvelope = nextRandomFloat(0.25f, 1f) * (0.45f + (clampedEventfulness * 0.8f))
        textureDecay = nextRandomFloat(profile.textureDecayMin, profile.textureDecayMax)
        textureType = nextRandomInt(0, 2)
        texturePhase = 0f
        val frequency = nextRandomFloat(profile.textureFreqMinHz, profile.textureFreqMaxHz)
        texturePhaseIncrement = if (sampleRate > 0) frequency / sampleRate.toFloat() else 0f
        textureNoiseState = 0f
    }

    private fun renderTextureEvent(profile: EnvironmentProfile, white: Float): Float {
        if (textureEnvelope < 0.001f) {
            return 0f
        }

        textureEnvelope *= textureDecay
        texturePhase += texturePhaseIncrement
        if (texturePhase >= 1f) {
            texturePhase -= 1f
        }
        textureNoiseState = (textureNoiseState * 0.76f) + (white * 0.24f)

        val resonant = sin((2f * PI.toFloat()) * texturePhase)
        val brightNoise = white - (lowBed * 0.6f)
        val body = when (textureType) {
            0 -> (resonant * 0.95f) + (brightNoise * 0.18f)
            1 -> (brightNoise * 0.92f) + (resonant * 0.22f)
            else -> (textureNoiseState * 0.78f) + (resonant * 0.35f)
        }

        return body * textureEnvelope * profile.textureMix
    }

    private fun updateMovement(profile: EnvironmentProfile, clampedMotion: Float) {
        movementTransitionFrames--
        if (movementTransitionFrames <= 0L) {
            val rangeScale = 0.7f + (clampedMotion * 0.8f)
            movementTargetRateHz = nextRandomFloat(
                profile.movementRateMinHz * rangeScale,
                profile.movementRateMaxHz * rangeScale,
            )
            movementTransitionFrames = nextSegmentFrames(minMs = 260f, maxMs = 1300f)
        }

        movementRateHz += (movementTargetRateHz - movementRateHz) * 0.0018f
        if (sampleRate > 0) {
            movementPhase += movementRateHz / sampleRate.toFloat()
            if (movementPhase >= 1f) {
                movementPhase -= 1f
            }
        }

        maybeTriggerMovementBurst(profile, clampedMotion)
        movementBurstEnvelope *= movementBurstDecay
    }

    private fun maybeTriggerMovementBurst(profile: EnvironmentProfile, clampedMotion: Float) {
        if (movementBurstEnvelope > 0.01f) {
            return
        }

        val probability = profile.movementBurstProbabilityPerFrame * (0.35f + (clampedMotion * 1.8f))
        if (nextRandomFloat(0f, 1f) < probability) {
            movementBurstEnvelope = nextRandomFloat(0.2f, 1f)
            movementBurstDecay = nextRandomFloat(
                profile.movementBurstDecayMin,
                profile.movementBurstDecayMax,
            )
        }
    }

    private fun renderHum(profile: EnvironmentProfile, clampedMotion: Float): Float {
        if (profile.humMix <= 0f || sampleRate <= 0) {
            return 0f
        }

        humDriftPhase += 0.12f / sampleRate.toFloat()
        if (humDriftPhase >= 1f) {
            humDriftPhase -= 1f
        }

        val drift = sin((2f * PI.toFloat()) * humDriftPhase) * (2f + clampedMotion * 1.8f)
        val humFrequencyHz = humBaseFrequencyHz + drift
        humPhase += humFrequencyHz / sampleRate.toFloat()
        if (humPhase >= 1f) {
            humPhase -= 1f
        }

        val fundamental = sin((2f * PI.toFloat()) * humPhase)
        val harmonic = sin((4f * PI.toFloat()) * humPhase + 0.37f)
        return ((fundamental * 0.72f) + (harmonic * 0.28f)) * profile.humMix
    }

    private fun ensureModeSamples(): PracticeNoiseSamples.ModeSamples? {
        if (loadedSampleMode != environmentMode) {
            modeSamples = sampleSource(environmentMode)
            loadedSampleMode = environmentMode
            currentBedClip = null
            activeOverlays.clear()
        }
        return modeSamples
    }

    private fun nextBedSample(samples: PracticeNoiseSamples.ModeSamples): Float {
        val selectedBed = currentBedClip ?: samples.beds.randomOrNull()?.also { clip ->
            currentBedClip = clip
            currentBedPosition = nextRandomFloat(0f, clip.samples.size.toFloat().coerceAtLeast(1f))
            currentBedStep = if (sampleRate > 0) {
                clip.sampleRate.toFloat() / sampleRate.toFloat()
            } else {
                1f
            }
        } ?: return 0f

        val sample = loopedBedSample(selectedBed, currentBedPosition)
        val crossfadeFrames = bedLoopCrossfadeFrames(selectedBed)
        currentBedPosition += currentBedStep
        while (currentBedPosition >= selectedBed.samples.size) {
            currentBedPosition = currentBedPosition - selectedBed.samples.size + crossfadeFrames
        }
        return sample
    }

    private fun maybeSpawnOverlay(samples: PracticeNoiseSamples.ModeSamples, clampedEventfulness: Float) {
        if (samples.speech.isEmpty()) return

        overlayCooldownFrames--
        if (overlayCooldownFrames > 0L) return

        val triggerProbability = 0.00035f + (0.0025f * clampedEventfulness)
        if (nextRandomFloat(0f, 1f) >= triggerProbability) return

        val clip = samples.speech.randomOrNull() ?: return
        val step = if (sampleRate > 0) clip.sampleRate.toFloat() / sampleRate.toFloat() else 1f
        val minFrames = ((sampleRate * 0.8f).toLong()).coerceAtLeast(1L)
        val maxFrames = ((sampleRate * (1.6f + (2.8f * clampedEventfulness))).toLong()).coerceAtLeast(minFrames + 1)
        val clipFrames = ((clip.samples.size - 2) / step).toLong().coerceAtLeast(minFrames)
        val targetFrames = nextRandomLong(minFrames, maxFrames).coerceAtMost(clipFrames)
        val maxStartFrame = (clipFrames - targetFrames).coerceAtLeast(0L)
        val startFrame = if (maxStartFrame > 0L) nextRandomLong(0, maxStartFrame) else 0L
        val position = startFrame * step

        val gain = nextRandomFloat(0.22f, 0.62f) * (0.55f + (clampedEventfulness * 0.75f))
        val fadeFrames = (targetFrames * 0.2f).toLong().coerceAtLeast(8L)
        activeOverlays += OverlayPlayer(
            clip = clip,
            position = position,
            step = step,
            gain = gain,
            totalFrames = targetFrames,
            fadeFrames = fadeFrames,
        )
        overlayCooldownFrames = nextSegmentFrames(
            minMs = (220f - (120f * clampedEventfulness)).coerceAtLeast(45f),
            maxMs = (1300f - (760f * clampedEventfulness)).coerceAtLeast(220f),
        )
    }

    private fun renderOverlaySamples(): Float {
        if (activeOverlays.isEmpty()) return 0f
        var mix = 0f
        val iterator = activeOverlays.iterator()
        while (iterator.hasNext()) {
            val overlay = iterator.next()
            if (overlay.playedFrames >= overlay.totalFrames) {
                iterator.remove()
                continue
            }
            if (overlay.position >= overlay.clip.samples.size) {
                iterator.remove()
                continue
            }

            val envelope = overlayEnvelope(overlay.playedFrames, overlay.totalFrames, overlay.fadeFrames)
            val sample = sampleAt(overlay.clip, overlay.position)
            mix += sample * overlay.gain * envelope

            overlay.position += overlay.step
            overlay.playedFrames++
            if (overlay.playedFrames >= overlay.totalFrames) {
                iterator.remove()
            }
        }
        return mix.coerceIn(-1.8f, 1.8f)
    }

    private fun sampleAt(clip: PracticeNoiseSamples.Clip, position: Float): Float {
        if (clip.samples.isEmpty()) return 0f
        val base = position.toInt().coerceIn(0, clip.samples.lastIndex)
        val next = (base + 1).coerceAtMost(clip.samples.lastIndex)
        val fraction = (position - base).coerceIn(0f, 1f)
        return clip.samples[base] + ((clip.samples[next] - clip.samples[base]) * fraction)
    }

    private fun loopedBedSample(clip: PracticeNoiseSamples.Clip, position: Float): Float {
        if (clip.samples.isEmpty()) return 0f

        val clipLength = clip.samples.size.toFloat()
        val wrappedPosition = wrapPosition(position, clipLength)
        val crossfadeFrames = bedLoopCrossfadeFrames(clip)
        val tailCrossfadeStart = clipLength - crossfadeFrames

        if (crossfadeFrames > 1f && wrappedPosition >= tailCrossfadeStart) {
            val progress = ((wrappedPosition - tailCrossfadeStart) / crossfadeFrames).coerceIn(0f, 1f)
            val tail = sampleAtLooped(clip, wrappedPosition)
            val head = sampleAtLooped(clip, wrappedPosition - tailCrossfadeStart)
            return tail + ((head - tail) * progress)
        }

        return sampleAtLooped(clip, wrappedPosition)
    }

    private fun sampleAtLooped(clip: PracticeNoiseSamples.Clip, position: Float): Float {
        if (clip.samples.isEmpty()) return 0f

        val clipLength = clip.samples.size.toFloat()
        val wrappedPosition = wrapPosition(position, clipLength)
        val base = wrappedPosition.toInt().coerceIn(0, clip.samples.lastIndex)
        val next = (base + 1) % clip.samples.size
        val fraction = (wrappedPosition - base).coerceIn(0f, 1f)
        return clip.samples[base] + ((clip.samples[next] - clip.samples[base]) * fraction)
    }

    private fun wrapPosition(position: Float, clipLength: Float): Float {
        if (clipLength <= 0f) return 0f
        val wrapped = position % clipLength
        return if (wrapped < 0f) wrapped + clipLength else wrapped
    }

    private fun bedLoopCrossfadeFrames(clip: PracticeNoiseSamples.Clip): Float {
        if (clip.samples.size <= 2) return 0f
        val maxAllowed = ((clip.samples.size - 1) / 2).coerceAtLeast(1)
        val suggested = (clip.samples.size / 8).coerceAtLeast(8)
        return suggested.coerceAtMost(BED_LOOP_MAX_CROSSFADE_SAMPLES).coerceAtMost(maxAllowed).toFloat()
    }

    private fun overlayEnvelope(frame: Long, total: Long, fade: Long): Float {
        if (total <= 1L || fade <= 1L) return 1f
        return when {
            frame < fade -> easedRamp(frame, fade)
            frame >= total - fade -> {
                val fromEnd = total - frame - 1
                easedRamp(fromEnd, fade)
            }
            else -> 1f
        }
    }

    private fun pulseEnvelope(frameIndex: Long, totalFrames: Long): Float {
        val halfFrames = (totalFrames / 2).coerceAtLeast(1L)
        val edgeFrames = (totalFrames * pulseEdgeFraction)
            .toLong()
            .coerceAtLeast(1L)
            .coerceAtMost(halfFrames)

        if (edgeFrames <= 1L || totalFrames <= 2L * edgeFrames) {
            return 1f
        }

        return when {
            frameIndex < edgeFrames -> easedRamp(frameIndex, edgeFrames)
            frameIndex >= totalFrames - edgeFrames -> {
                val framesFromEnd = totalFrames - frameIndex - 1
                easedRamp(framesFromEnd, edgeFrames)
            }
            else -> 1f
        }
    }

    private fun easedRamp(frameIndex: Long, edgeFrames: Long): Float {
        val progress = ((frameIndex.toDouble() + 1.0) / edgeFrames.toDouble()).coerceIn(0.0, 1.0)
        return (0.5 - 0.5 * cos(PI * progress)).toFloat()
    }

    private fun nextSegmentFrames(minMs: Float, maxMs: Float): Long {
        if (sampleRate <= 0) {
            return 1L
        }
        val durationMs = nextRandomFloat(minMs, maxMs)
        return ((durationMs / 1000f) * sampleRate).toLong().coerceAtLeast(1L)
    }

    private fun nextNoiseSampleNormalized(): Float {
        randomState = randomState * 1103515245 + 12345
        val normalized = (randomState ushr 16) and 0x7FFF
        return (normalized / 16383.5f) - 1f
    }

    private fun nextRandomFloat(min: Float, max: Float): Float {
        randomState = randomState * 1664525 + 1013904223
        val normalized = ((randomState ushr 1).toLong() and 0x7FFFFFFF).toFloat() / 0x7FFFFFFF.toFloat()
        return min + ((max - min) * normalized)
    }

    private fun nextRandomInt(min: Int, max: Int): Int {
        if (max <= min) return min
        return nextRandomFloat(min.toFloat(), (max + 1).toFloat()).toInt().coerceIn(min, max)
    }

    private fun nextRandomLong(min: Long, max: Long): Long {
        if (max <= min) return min
        val unit = nextRandomFloat(0f, 1f).toDouble()
        val span = (max - min).toDouble()
        return min + (unit * span).toLong()
    }

    private fun outputGainForIntensity(intensity: Float): Float {
        return intensity.coerceIn(0f, 1f).pow(INTENSITY_RESPONSE_EXPONENT) * INTENSITY_OUTPUT_BOOST
    }

    private fun profileForMode(mode: NoiseEnvironmentMode): EnvironmentProfile {
        return when (mode) {
            NoiseEnvironmentMode.COFFEE_SHOP -> EnvironmentProfile(
                baseAmplitudeMin = 700f,
                baseAmplitudeMax = 1_650f,
                activeMinMs = 650f,
                activeMaxMs = 2_200f,
                quietMinMs = 190f,
                quietMaxMs = 760f,
                edgeFractionMin = 0.2f,
                edgeFractionMax = 0.36f,
                lowMix = 0.34f,
                midMix = 0.56f,
                highMix = 0.24f,
                speechMix = 0.38f,
                textureProbabilityPerFrame = 0.00075f,
                textureDecayMin = 0.988f,
                textureDecayMax = 0.997f,
                textureFreqMinHz = 420f,
                textureFreqMaxHz = 2_000f,
                textureMix = 0.46f,
                humMix = 0.04f,
                humFreqMinHz = 74f,
                humFreqMaxHz = 90f,
                movementMix = 0.2f,
                movementRateMinHz = 0.05f,
                movementRateMaxHz = 0.32f,
                movementBurstProbabilityPerFrame = 0.00035f,
                movementBurstDecayMin = 0.994f,
                movementBurstDecayMax = 0.9985f,
            )

            NoiseEnvironmentMode.BUSY_STREET -> EnvironmentProfile(
                baseAmplitudeMin = 960f,
                baseAmplitudeMax = 2_200f,
                activeMinMs = 900f,
                activeMaxMs = 2_800f,
                quietMinMs = 140f,
                quietMaxMs = 600f,
                edgeFractionMin = 0.22f,
                edgeFractionMax = 0.4f,
                lowMix = 0.62f,
                midMix = 0.33f,
                highMix = 0.18f,
                speechMix = 0.14f,
                textureProbabilityPerFrame = 0.0006f,
                textureDecayMin = 0.992f,
                textureDecayMax = 0.9988f,
                textureFreqMinHz = 180f,
                textureFreqMaxHz = 1_400f,
                textureMix = 0.34f,
                humMix = 0.03f,
                humFreqMinHz = 68f,
                humFreqMaxHz = 86f,
                movementMix = 0.62f,
                movementRateMinHz = 0.08f,
                movementRateMaxHz = 0.55f,
                movementBurstProbabilityPerFrame = 0.0009f,
                movementBurstDecayMin = 0.9945f,
                movementBurstDecayMax = 0.9992f,
            )

            NoiseEnvironmentMode.MEETING_ROOM -> EnvironmentProfile(
                baseAmplitudeMin = 500f,
                baseAmplitudeMax = 1_150f,
                activeMinMs = 720f,
                activeMaxMs = 2_000f,
                quietMinMs = 260f,
                quietMaxMs = 980f,
                edgeFractionMin = 0.18f,
                edgeFractionMax = 0.32f,
                lowMix = 0.38f,
                midMix = 0.42f,
                highMix = 0.14f,
                speechMix = 0.24f,
                textureProbabilityPerFrame = 0.00042f,
                textureDecayMin = 0.989f,
                textureDecayMax = 0.9978f,
                textureFreqMinHz = 240f,
                textureFreqMaxHz = 1_500f,
                textureMix = 0.26f,
                humMix = 0.2f,
                humFreqMinHz = 78f,
                humFreqMaxHz = 102f,
                movementMix = 0.08f,
                movementRateMinHz = 0.03f,
                movementRateMaxHz = 0.16f,
                movementBurstProbabilityPerFrame = 0.00018f,
                movementBurstDecayMin = 0.994f,
                movementBurstDecayMax = 0.998f,
            )
        }
    }

    private data class EnvironmentProfile(
        val baseAmplitudeMin: Float,
        val baseAmplitudeMax: Float,
        val activeMinMs: Float,
        val activeMaxMs: Float,
        val quietMinMs: Float,
        val quietMaxMs: Float,
        val edgeFractionMin: Float,
        val edgeFractionMax: Float,
        val lowMix: Float,
        val midMix: Float,
        val highMix: Float,
        val speechMix: Float,
        val textureProbabilityPerFrame: Float,
        val textureDecayMin: Float,
        val textureDecayMax: Float,
        val textureFreqMinHz: Float,
        val textureFreqMaxHz: Float,
        val textureMix: Float,
        val humMix: Float,
        val humFreqMinHz: Float,
        val humFreqMaxHz: Float,
        val movementMix: Float,
        val movementRateMinHz: Float,
        val movementRateMaxHz: Float,
        val movementBurstProbabilityPerFrame: Float,
        val movementBurstDecayMin: Float,
        val movementBurstDecayMax: Float,
    )

    private data class OverlayPlayer(
        val clip: PracticeNoiseSamples.Clip,
        var position: Float,
        val step: Float,
        val gain: Float,
        val totalFrames: Long,
        val fadeFrames: Long,
        var playedFrames: Long = 0,
    )

    companion object {
        const val DEFAULT_INTENSITY: Float = 0.55f
        const val DEFAULT_EVENTFULNESS: Float = 0.5f
        const val DEFAULT_SPATIAL_MOTION: Float = 0.5f
        private const val INTENSITY_RESPONSE_EXPONENT: Float = 0.8f
        private const val INTENSITY_OUTPUT_BOOST: Float = 1.35f
        private const val SYNTH_SCENE_OUTPUT_GAIN: Float = 1.8f
        private const val SAMPLE_SCENE_GAIN: Float = 10f
        private const val SAMPLE_SCENE_OUTPUT_GAIN: Float = 2_100f
        private const val BED_LOOP_MAX_CROSSFADE_SAMPLES: Int = 2_048
    }
}
