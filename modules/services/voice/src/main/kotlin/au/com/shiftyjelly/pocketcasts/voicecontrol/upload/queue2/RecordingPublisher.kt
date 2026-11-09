package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue2

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SealedCapture
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SegmentObservation
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record2.AudioDescription
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record2.PlainRecord
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record2.StageObservation
import java.io.File

/**
 * Turns a finished capture into a queued recording.
 *
 * This is the only place that decides what a recording contains, and it keeps exactly the facts the
 * spec requires: the real `wake_required` at segment start, the measured wake score/threshold when
 * there was one, the ASR `source_language`, and a `wake_only` routing observation. Anything not
 * measured is reported as not run with a reason - never invented.
 *
 * The record is plain JSON. Nothing here computes digests or canonical bytes, because the server
 * parses and validates the fields itself.
 */
class RecordingPublisher(
    private val queue: FolderQueue,
    private val appVersion: () -> String,
    private val appBuild: () -> String,
    private val platform: String = "android",
    private val conversionVersion: Int = 1,
) {
    /**
     * Publish [sealed] to the queue. Returns false when it was dropped: full, failed to write, over
     * the bounds, or missing a field the record requires.
     *
     * A drop is never an exception. Collection has already finished by the time this runs, so
     * throwing here would fail a capture that cost the user a spoken sentence for no benefit.
     */
    fun publish(sealed: SealedCapture): Boolean {
        val pcm = sealed.pcm
        if (pcm.isEmpty()) return false
        if (pcm.size > MAX_SAMPLES) return false
        // enrollment_epoch is a required record field: a capture with no epoch has no account to
        // attribute it to, so it cannot be sent at all.
        if (sealed.context.issuedAuthority?.enrollmentEpoch.isNullOrBlank()) return false

        val sampleRate = sealed.sampleRateHz.coerceAtLeast(1)
        val byteCount = WAV_HEADER_BYTES + pcm.size * 2L
        val audio = AudioDescription(
            byteCount = byteCount,
            sampleRateHz = sampleRate,
            channels = 1,
            retainedSamples = pcm.size.toLong(),
            originalFormat = "pcm_s16le",
            conversionVersion = conversionVersion,
            truncated = false,
        )

        val record = PlainRecord(
            captureId = sealed.captureId.toString(),
            enrollmentEpoch = sealed.context.issuedAuthority.enrollmentEpoch,
            sessionId = sealed.context.captureSession.toString(),
            platform = platform,
            appVersion = appVersion(),
            build = appBuild(),
            // The real grace state at segment start. Not inferred from what the clip contains.
            wakeRequired = sealed.context.wakeRequired,
            audio = audio,
            models = modelsOf(sealed),
            observations = observationsOf(sealed),
            links = emptyMap(),
        )

        return runCatching {
            val metadata = record.encodedBytes()
            if (metadata.size > PlainRecord.MAX_METADATA_BYTES) return@runCatching false
            queue.publish(
                captureId = sealed.captureId.toString(),
                audioWriter = { file -> writeWav(file, pcm, sampleRate) },
                metadataBytes = metadata,
            ) != null
        }.getOrDefault(false)
    }

    private fun modelsOf(sealed: SealedCapture): List<Pair<String, au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record2.ModelRef>> = listOfNotNull(
        sealed.asrInvocationModel?.let { "asr" to it.asRecordRef() },
        sealed.routerInvocationModel?.let { "router" to it.asRecordRef() },
    )

    private fun observationsOf(sealed: SealedCapture): List<StageObservation> {
        val stages = mutableListOf<StageObservation>()

        // Wake: completed with its decision when the detector ran, otherwise honestly not run.
        val measurement = sealed.wakeMeasurement
        stages += if (measurement != null) {
            StageObservation(
                stage = "wake",
                state = "completed",
                fields = buildMap {
                    if (measurement.score != null) put("score", measurement.score)
                    if (measurement.threshold != null) put("threshold", measurement.threshold)
                    put("decision", if (sealed.wakeState == SegmentObservation.WakeState.Detected) "detected" else "rejected")
                },
            )
        } else {
            StageObservation(stage = "wake", state = "not_run", reason = "not_measured")
        }

        // ASR: the observed language and whether recognition produced text.
        stages += when (val outcome = sealed.outcome) {
            is CollectionOutcome.Recognized -> StageObservation(
                stage = "asr",
                state = "completed",
                fields = buildMap {
                    put("text", outcome.transcript)
                    outcome.detectedLanguage?.let { put("source_language", it) }
                },
            )

            is CollectionOutcome.Routed, is CollectionOutcome.Executed -> StageObservation(stage = "asr", state = "completed")

            is CollectionOutcome.NotCollected -> StageObservation(
                stage = "asr",
                state = if (sealed.asrInvocationModel != null) "completed" else "not_run",
                reason = if (sealed.asrInvocationModel != null) null else "not_invoked",
                fields = buildMap {
                    outcome.measuredLanguage?.let { put("source_language", it) }
                    outcome.observedText?.let { put("text", it) }
                },
            )

            is CollectionOutcome.RoutingNone -> StageObservation(stage = "asr", state = "completed")

            is CollectionOutcome.AsrFailed -> StageObservation(stage = "asr", state = "failed", reason = "recognition_failed")

            is CollectionOutcome.AsrCancelled -> StageObservation(stage = "asr", state = "cancelled")

            null -> StageObservation(stage = "asr", state = "unknown")
        }

        // Routing: a wake-positive trim that left no text is the one case the spec names.
        stages += when {
            sealed.routerInvocationModel != null ->
                StageObservation(stage = "routing", state = "completed")

            // A wake-positive trim that left no text is the one routing case the spec names.
            sealed.outcome is CollectionOutcome.NotCollected && sealed.outcome.reason == "wake_only" ->
                StageObservation(stage = "routing", state = "not_run", reason = "wake_only")

            else -> StageObservation(stage = "routing", state = "not_run", reason = "not_invoked")
        }

        return stages
    }

    /** Writes a 16 kHz mono PCM16 WAV: a 44-byte RIFF header then the samples. */
    private fun writeWav(file: File, pcm: ShortArray, sampleRate: Int) {
        val dataBytes = pcm.size * 2
        val out = java.io.BufferedOutputStream(file.outputStream())
        out.use { o ->
            o.write("RIFF".toByteArray(Charsets.US_ASCII))
            o.write(le32(36 + dataBytes))
            o.write("WAVE".toByteArray(Charsets.US_ASCII))
            o.write("fmt ".toByteArray(Charsets.US_ASCII))
            o.write(le32(16))
            o.write(le16(1)) // PCM
            o.write(le16(1)) // mono
            o.write(le32(sampleRate))
            o.write(le32(sampleRate * 2)) // byte rate
            o.write(le16(2)) // block align
            o.write(le16(16)) // bits per sample
            o.write("data".toByteArray(Charsets.US_ASCII))
            o.write(le32(dataBytes))
            val buffer = ByteArray(pcm.size * 2)
            for (i in pcm.indices) {
                val v = pcm[i].toInt()
                buffer[i * 2] = (v and 0xFF).toByte()
                buffer[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
            o.write(buffer)
        }
    }

    private fun le16(value: Int) = byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

    private fun le32(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    companion object {
        const val WAV_HEADER_BYTES = 44L

        /** 60 seconds at 16 kHz, the spec's bound on a retained segment. */
        const val MAX_SAMPLES = 60 * 16_000
    }
}

/** The record layer's own model reference: the same identity, minus the wire-only distinctions. */
private fun au.com.shiftyjelly.pocketcasts.voicecontrol.upload.model.ModelRef.asRecordRef(): au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record2.ModelRef = when (this) {
    is au.com.shiftyjelly.pocketcasts.voicecontrol.upload.model.ModelRef.Named ->
        au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record2.ModelRef(id, version)

    au.com.shiftyjelly.pocketcasts.voicecontrol.upload.model.ModelRef.None ->
        au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record2.ModelRef("unknown", "unknown")
}
