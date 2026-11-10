package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue2

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionOutcome
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ExecutionOutcome
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

        // The server refuses any rate but 16 kHz, and a refusal means the recording is deleted.
        // The capture pipeline is 16 kHz mono; a capture that somehow is not cannot be described
        // honestly, so it is not published at all rather than sent to be discarded.
        val sampleRate = sealed.sampleRateHz
        if (sampleRate != SERVER_SAMPLE_RATE_HZ) return false
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
            "bleed" to bleedObservation(sealed),
            "wake" to wakeObservation(sealed),
            "asr" to asrObservation(sealed, transcript, sourceLanguage),
            "translation" to translationObservation(sealed),
            "routing" to routingObservation(sealed, routed),
            "execution" to executionObservation(sealed),
        )
    }

    /**
     * The signal filter.
     *
     * The only filter fact the engine records is that it refused the segment: the reason `bleed`
     * on a not-collected outcome. A published capture therefore passed the filter, but the decision
     * itself was never observed, so a segment that passed is reported as unknown rather than
     * asserted as no overlap. The one recorded decision, a refusal, is kept.
     */
    private fun bleedObservation(sealed: SealedCapture): StageObservation {
        // The engine records ONE filter fact: that it refused the segment. That is an overlap
        // decision and is kept. A segment that passed leaves no recorded decision, so its decision
        // stays unknown rather than being asserted as no overlap.
        val refusedByFilter = (sealed.stageHistory + listOfNotNull(sealed.outcome))
            .filterIsInstance<CollectionOutcome.NotCollected>()
            .any { it.reason == REASON_BLEED }
        return if (refusedByFilter) {
            StageObservation.completed("decision" to "overlap")
        } else {
            StageObservation("unknown", reason = OBSERVATION_UNAVAILABLE)
        }
    }

    private fun wakeObservation(sealed: SealedCapture): StageObservation {
        // A refusal before the wake word was even evaluated carries its own reason.
        val refusedByWake = sealed.stageHistory.filterIsInstance<CollectionOutcome.NotCollected>()
            .any { it.reason == REASON_REJECTED_BY_WAKE }

        val measurement = sealed.wakeMeasurement
        if (measurement == null) {
            return if (refusedByWake) {
                // The detector answered, and its answer was no. That is a completed decision with
                // no measured numbers, not a stage that failed to run.
                StageObservation.completed("decision" to "rejected")
            } else {
                StageObservation.notRun(REASON_NOT_MEASURED)
            }
        }
        return StageObservation.completed(
            *buildMap<String, Any> {
                if (measurement.score != null) put("score", measurement.score)
                if (measurement.threshold != null) put("threshold", measurement.threshold)
                put("decision", if (sealed.wakeState == SegmentObservation.WakeState.Detected) "detected" else "rejected")
            }.toList().toTypedArray(),
        )
    }

    /**
     * Why the capture was refused before recognition, if it was.
     *
     * Every stage downstream of the refusal is `not_run` for that reason rather than for its own,
     * so they share one lookup instead of each deciding separately which reasons count.
     */
    private fun refusalReasonBeforeAsr(sealed: SealedCapture): String? = sealed.stageHistory.filterIsInstance<CollectionOutcome.NotCollected>()
        .map { it.reason }
        .lastOrNull { it == REASON_BLEED || it == REASON_REJECTED_BY_WAKE || it == REASON_WAKE_ONLY }

    private fun asrObservation(
        sealed: SealedCapture,
        transcript: String?,
        sourceLanguage: String?,
    ): StageObservation {
        val fields = buildMap<String, Any> {
            transcript?.let { put("text", it) }
            sourceLanguage?.let { put("source_language", it) }
        }
        // A capture refused before recognition never reached ASR. Marking it completed would claim
        // a run that did not happen, so the refusal reason decides the state.
        val refusalReason = refusalReasonBeforeAsr(sealed)
        if (refusalReason != null && sealed.asrInvocationModel == null) {
            return StageObservation("not_run", reason = refusalReason)
        }

        return when (sealed.outcome) {
            is CollectionOutcome.AsrFailed -> StageObservation("failed", reason = "recognition_failed")

            is CollectionOutcome.AsrCancelled -> StageObservation("cancelled", reason = "cancelled_by_user")

            // The router failing is a fact about routing, not about recognition: it has its own
            // outcome so it never reads back as a completed ASR run.
            is CollectionOutcome.RoutingFailed -> StageObservation("completed", null, fields)

            is CollectionOutcome.Recognized, is CollectionOutcome.Routed, is CollectionOutcome.Executed,
            is CollectionOutcome.RoutingNone, is CollectionOutcome.NotCollected,
            -> StageObservation("completed", null, fields)

            null ->
                if (sealed.asrInvocationModel != null) {
                    StageObservation("unknown", reason = OBSERVATION_UNAVAILABLE, fields = fields)
                } else {
                    StageObservation("not_run", reason = REASON_NOT_INVOKED)
                }
        }
    }

    private fun translationObservation(sealed: SealedCapture): StageObservation {
        val translation = sealed.translation
            ?: return StageObservation.notRun(
                // When the capture was refused before recognition, translation was never reached
                // for that reason, not merely "not invoked": naming the refusal is what tells a
                // reader why nothing downstream ran.
                refusalReasonBeforeAsr(sealed) ?: REASON_NOT_INVOKED,
            )
        // A stage that ran and failed is a failure, not a skip. Conflating them would report a
        // broken translator as "the languages were already the same".
        translation.failedWith?.let { return StageObservation("failed", reason = it) }
        // Otherwise a stage that produced nothing keeps its own recorded skip reason.
        if (!translation.producedResult) {
            return StageObservation.notRun(translation.skipReason ?: "source_language_target_language")
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
        if (sealed.outcome is CollectionOutcome.NotCollected && sealed.outcome.reason == REASON_WAKE_ONLY) {
            return StageObservation.notRun(REASON_WAKE_ONLY)
        }
        // Refused before recognition means routing was never reached, and the refusal is why.
        refusalReasonBeforeAsr(sealed)?.let { return StageObservation.notRun(it) }
        // The router was attempted and never ran. A fault, not a non-match, so it must not be
        // reported as "never invoked": that reads as a benign skip and hides a broken capability.
        (sealed.stageHistory + listOfNotNull(sealed.outcome))
            .filterIsInstance<CollectionOutcome.RoutingFailed>().lastOrNull()
            ?.let { return StageObservation("failed", reason = it.reason) }
        if (routed != null) {
            return StageObservation.completed("predicted_intent" to routed.intentCategory)
        }
        val none = (sealed.stageHistory + listOfNotNull(sealed.outcome))
            .filterIsInstance<CollectionOutcome.RoutingNone>().lastOrNull()
        if (none != null) {
            // The recogniser's own reported reason travels; a blanket label would replace a fact
            // the pipeline recorded with a guess.
            return StageObservation("failed", reason = none.recognizerReason ?: "no_usable_intent")
        }
        return when {
            sealed.routerInvocationModel != null -> StageObservation("completed")

            // The bounded wait expired before routing reported, so the result is genuinely not
            // known rather than known to be absent. Recording a skip here would state as a fact
            // something the pipeline never established.
            sealed.cause == SealedCapture.CAUSE_OUTCOME_WAIT_EXPIRED ->
                StageObservation("unknown", reason = REASON_FINALIZED_BEFORE_RESULT)

            // No invocation and no failure: nothing reached routing at all.
            else -> StageObservation.notRun(REASON_NOT_INVOKED)
        }
    }

    private fun executionObservation(sealed: SealedCapture): StageObservation {
        val observed = sealed.executionOutcome
            ?: return StageObservation.notRun(
                // Nothing was dispatched, and if a refusal stopped the pipeline that refusal is
                // the reason: saying only "not dispatched" hides why from anyone reading it.
                refusalReasonBeforeAsr(sealed) ?: "not_dispatched",
            )
        // The spec fixes state, outcome and reason together. A failure and a refusal are different
        // facts about the action, and a user cancel is not a failure at all.
        return when (observed) {
            ExecutionOutcome.Success -> StageObservation.completed("outcome" to observed.wire)

            ExecutionOutcome.Failure ->
                StageObservation("failed", reason = "action_failed", fields = mapOf("outcome" to observed.wire))

            ExecutionOutcome.Refused ->
                StageObservation("failed", reason = "client_refused", fields = mapOf("outcome" to observed.wire))

            ExecutionOutcome.Cancelled ->
                StageObservation("cancelled", reason = "cancelled_by_user", fields = mapOf("outcome" to observed.wire))

            // No result was seen at all: say so, and omit the outcome entirely.
            ExecutionOutcome.NotObserved -> StageObservation("unknown", reason = OBSERVATION_UNAVAILABLE)
        }
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
        /** The engine's own reasons for a capture it did not collect. */
        // The only rate the server accepts; anything else is a refusal, which loses the recording.
        const val SERVER_SAMPLE_RATE_HZ = 16_000

        const val REASON_BLEED = "bleed"
        const val REASON_REJECTED_BY_WAKE = "rejected_by_wake"
        const val REASON_WAKE_ONLY = "wake_only"

        const val REASON_NOT_MEASURED = "not_measured"
        const val REASON_NOT_INVOKED = "not_invoked"

        /** The bounded wait sealed the capture before routing reported a result. */
        const val REASON_FINALIZED_BEFORE_RESULT = "finalized_before_result"
        const val OBSERVATION_UNAVAILABLE = "observation_unavailable"

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
