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
        // The account and epoch are required record fields, and they are what makes a recording
        // attributable: a capture without them cannot be sent, and certainly cannot be owned.
        val authority = sealed.context.issuedAuthority ?: return false
        val epoch = authority.enrollmentEpoch ?: return false
        val account = authority.accountName ?: return false
        if (epoch.isBlank() || account.isBlank()) return false

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
            enrollmentEpoch = epoch,
            sessionId = sealed.context.captureSession.toString(),
            platform = platform,
            appVersion = appVersion(),
            build = appBuild(),
            // Stamped so a queue that outlives a sign-in can tell whose recording this is.
            account = account,
            // The real grace state at segment start. Not inferred from what the clip contains.
            wakeRequired = sealed.context.wakeRequired,
            audio = audio,
            models = modelsOf(sealed).toMap(),
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

    private fun observationsOf(sealed: SealedCapture): Map<String, StageObservation> {
        // Derived facts used by more than one stage. Read once so the stages cannot disagree about
        // what happened in this capture.
        // The stage history is the full record of what happened; the terminal outcome is the last
        // word. Both are consulted so a capture is described by its observed facts even when the
        // terminal outcome is not the one that carried them.
        val history = sealed.stageHistory + listOfNotNull(sealed.outcome)
        val recognized = history.filterIsInstance<CollectionOutcome.Recognized>().lastOrNull()
        val notCollected = history.filterIsInstance<CollectionOutcome.NotCollected>().lastOrNull()
        val routed = history.filterIsInstance<CollectionOutcome.Routed>().lastOrNull()

        // The ASR result that actually exists, from whichever outcome carried it. A routed or
        // executed capture still has a transcript and a measured language; dropping them here was
        // losing the most valuable part of the record.
        val transcript = recognized?.transcript ?: notCollected?.observedText
        val sourceLanguage = recognized?.detectedLanguage ?: notCollected?.measuredLanguage

        return linkedMapOf(
            "bleed" to bleedObservation(),
            "wake" to wakeObservation(sealed),
            "asr" to asrObservation(sealed, transcript, sourceLanguage),
            "translation" to translationObservation(sealed),
            "routing" to routingObservation(sealed, routed),
            "execution" to executionObservation(sealed),
        )
    }

    /**
     * The signal filter. This record is only ever published after collection finished, so the
     * filter let the segment through; the decision it made is not retained anywhere else.
     */
    private fun bleedObservation() = StageObservation.completed("decision" to "no_overlap")

    private fun wakeObservation(sealed: SealedCapture): StageObservation {
        val measurement = sealed.wakeMeasurement
            ?: return StageObservation.notRun("not_measured")
        return StageObservation.completed(
            *buildMap<String, Any> {
                if (measurement.score != null) put("score", measurement.score)
                if (measurement.threshold != null) put("threshold", measurement.threshold)
                put("decision", if (sealed.wakeState == SegmentObservation.WakeState.Detected) "detected" else "rejected")
            }.toList().toTypedArray(),
        )
    }

    private fun asrObservation(
        sealed: SealedCapture,
        transcript: String?,
        sourceLanguage: String?,
    ): StageObservation {
        val fields = buildMap<String, Any> {
            transcript?.let { put("text", it) }
            sourceLanguage?.let { put("source_language", it) }
        }
        return when (sealed.outcome) {
            is CollectionOutcome.AsrFailed -> StageObservation("failed", reason = "recognition_failed")

            is CollectionOutcome.AsrCancelled -> StageObservation("cancelled")

            is CollectionOutcome.Recognized, is CollectionOutcome.Routed, is CollectionOutcome.Executed,
            is CollectionOutcome.RoutingNone, is CollectionOutcome.NotCollected,
            -> StageObservation("completed", null, fields)

            null ->
                if (sealed.asrInvocationModel != null) {
                    StageObservation("unknown", reason = "observation_unavailable", fields = fields)
                } else {
                    StageObservation("not_run", reason = "not_invoked")
                }
        }
    }

    private fun translationObservation(sealed: SealedCapture): StageObservation {
        val translation = sealed.translation
            ?: return StageObservation.notRun("not_invoked")
        if (!translation.producedResult) {
            return StageObservation.notRun("source_language_target_language")
        }
        return StageObservation.completed(
            *buildMap<String, Any> {
                translation.translatedText?.let { put("translated_text", it) }
                translation.targetLanguage?.let { put("target_language", it) }
            }.toList().toTypedArray(),
        )
    }

    private fun routingObservation(sealed: SealedCapture, routed: CollectionOutcome.Routed?): StageObservation {
        // A wake-positive trim that left no text is the one routing case the spec names.
        if (sealed.outcome is CollectionOutcome.NotCollected && sealed.outcome.reason == "wake_only") {
            return StageObservation.notRun("wake_only")
        }
        if (routed != null) {
            return StageObservation.completed("predicted_intent" to routed.intentCategory)
        }
        val none = (sealed.stageHistory + listOfNotNull(sealed.outcome))
            .filterIsInstance<CollectionOutcome.RoutingNone>().lastOrNull()
        if (none != null) {
            return StageObservation(
                "failed",
                reason = "no_usable_intent",
                fields = buildMap<String, Any> { none.recognizerReason?.let { put("reason_detail", it) } },
            )
        }
        return if (sealed.routerInvocationModel != null) {
            StageObservation("completed")
        } else {
            StageObservation.notRun("not_invoked")
        }
    }

    private fun executionObservation(sealed: SealedCapture): StageObservation {
        val observed = sealed.executionOutcome?.wire
            ?: return StageObservation.notRun("not_dispatched")
        return StageObservation.completed("outcome" to observed)
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
