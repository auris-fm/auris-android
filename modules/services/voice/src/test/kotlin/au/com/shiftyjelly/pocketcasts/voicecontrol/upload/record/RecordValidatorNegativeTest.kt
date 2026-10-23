package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NEGATIVE CONTROLS FOR THE DECODER, reproduced from review findings on `1e45f9cfc`.
 *
 * Each case here was ACCEPTED by the previous `RecordValidator` while the Worker REFUSED it. They are
 * kept as a standing control because the failure mode was silent divergence: the Android decoder said
 * "valid" about a record the server would reject, which breaks recovery (a record is retained and
 * trusted locally that can never be uploaded) and breaks the validator's only job — being the single
 * decode-side authority that agrees with the wire.
 *
 * The three root causes:
 *  1. `enumIfPresent`/`textIfPresent` returned `Boolean?` where an INVALID value produced `false`, but
 *     callers wrote `?: return null`. Elvis only rejects `null`, so every invalid value sailed through.
 *  2. `routing.arguments` was checked for object-ness only; authorization (intent allowlist) and the
 *     recursive content boundary were not enforced at all.
 *  3. `source_language`/`target_language` used the 64 KiB text bound where the Worker applies 512 bytes.
 */
class RecordValidatorNegativeTest {

    private fun baseRecord(): MutableMap<String, Any?> = mutableMapOf(
        "schema_version" to 1L,
        "capture_id" to "6f1e5c2a-0000-4000-8000-000000000001",
        "enrollment_epoch" to "epoch-1",
        "capture_session_id" to "42",
        "platform" to "android",
        "app_version" to "8.20",
        "build" to "12345",
        "models" to mapOf(
            "vad" to mapOf("id" to "silero-vad", "version" to "4.0"),
            "wakeword" to mapOf("id" to "auris-wake", "version" to "1.2"),
            "asr" to mapOf("id" to "whisper-small", "version" to "2024-11"),
            "translation" to null,
            "router" to mapOf("id" to "lfm-intent", "version" to "0.3"),
        ),
        // Wire shape per the pinned contract: retained_samples (not sample_count) and the
        // conversion provenance fields are REQUIRED, so the fixture must carry them or it is
        // testing a record the wire would refuse for an unrelated reason.
        "audio" to mapOf(
            "sha256" to "a".repeat(64),
            "byte_count" to 3244L,
            "retained_samples" to 1600L,
            "sample_rate_hz" to 16000L,
            "channels" to 1L,
            "encoding" to "pcm_s16le",
            "original_format" to "pcm_s16le",
            "conversion_version" to "1",
            "truncated" to false,
            "dropped_spans" to emptyList<Any>(),
        ),
        "observations" to mutableMapOf(
            "wake" to mutableMapOf<String, Any?>(
                "state" to "completed",
                "decision" to "detected",
                "score" to 0.82,
                "threshold" to 0.5,
                "speech_offset_ms" to 120L,
                "wake_offset_ms" to 340L,
            ),
            "bleed" to mutableMapOf<String, Any?>("state" to "completed", "decision" to "no_overlap"),
            "asr" to mutableMapOf<String, Any?>("state" to "completed", "text" to "skip this ad", "source_language" to "en"),
            "translation" to mutableMapOf<String, Any?>("state" to "not_run", "reason" to "no_text"),
            "routing" to mutableMapOf<String, Any?>(
                "state" to "completed",
                "predicted_intent" to "track.play",
                "validation" to "valid",
                "execution_disposition" to "executed",
                "arguments" to mapOf("track_hint" to "queen"),
            ),
            "execution" to mutableMapOf<String, Any?>("state" to "completed", "outcome" to "success"),
        ),
        "links" to mapOf("conversation_id" to "6f1e5c2a-0000-4000-8000-000000000002"),
    )

    private fun toNode(value: Any?): RecordNode = when (value) {
        null -> RecordNode.Null
        is String -> RecordNode.Str(value)
        is Long -> RecordNode.Num(value.toDouble())
        is Int -> RecordNode.Num(value.toDouble())
        is Double -> RecordNode.Num(value)
        is Boolean -> RecordNode.Bool(value)
        is Map<*, *> -> RecordNode.Obj(value.entries.associate { (k, v) -> k as String to toNode(v) })
        is List<*> -> RecordNode.Arr(value.map { toNode(it) })
        else -> error("unsupported: ${value::class}")
    }

    private fun validate(record: Map<String, Any?>): RecordValidator.Result = RecordValidator.validate(toNode(record))

    private fun assertRefused(what: String, record: Map<String, Any?>) {
        val result = validate(record)
        assertTrue(
            "$what must be REFUSED by RecordValidator but was ACCEPTED: $result",
            result is RecordValidator.Result.Rejected,
        )
    }

    private fun baseValid(): Map<String, Any?> {
        val r = baseRecord()
        val result = validate(r)
        assertTrue("baseline fixture must be ACCEPTED but got: $result", result is RecordValidator.Result.Ok)
        return r
    }

    @Suppress("UNCHECKED_CAST")
    private fun stage(record: Map<String, Any?>, name: String): MutableMap<String, Any?> = (record["observations"] as MutableMap<String, MutableMap<String, Any?>>)[name]!!

    // ---- Finding 1: invalid enum values passed because elvis only rejects null ----

    @Test
    fun `wake decision outside the enumeration is refused`() {
        val r = baseRecord()
        stage(r, "wake")["decision"] = "bogus"
        assertRefused("wake.decision=\"bogus\"", r)
    }

    @Test
    fun `routing validation outside the enumeration is refused`() {
        val r = baseRecord()
        stage(r, "routing")["validation"] = "bogus"
        assertRefused("routing.validation=\"bogus\"", r)
    }

    @Test
    fun `execution outcome outside the enumeration is refused`() {
        val r = baseRecord()
        stage(r, "execution")["outcome"] = "bogus"
        assertRefused("execution.outcome=\"bogus\"", r)
    }

    @Test
    fun `over-bound and wrong-type text fields are refused`() {
        val r = baseRecord()
        stage(r, "asr")["text"] = "x".repeat(WireBounds.TEXT_FIELD_BYTES + 1)
        assertRefused("asr.text over the 64 KiB bound", r)

        val r2 = baseRecord()
        stage(r2, "asr")["text"] = 12L
        assertRefused("asr.text as a number", r2)
    }

    // ---- Finding 2: routing.arguments had no authorization or content boundary ----

    @Test
    fun `arguments under an intent with no approved list are refused`() {
        val r = baseRecord()
        stage(r, "routing")["predicted_intent"] = "unknown.intent"
        stage(r, "routing")["arguments"] = mapOf("anything" to "value")
        assertRefused("arguments on an unauthorized intent", r)
    }

    @Test
    fun `arguments with an unapproved member are refused`() {
        val r = baseRecord()
        stage(r, "routing")["arguments"] = mapOf("track_hint" to "queen", "access_token" to "abc")
        assertRefused("arguments carrying an unapproved member", r)
    }

    @Test
    fun `arguments with a structured value are refused`() {
        val r = baseRecord()
        stage(r, "routing")["arguments"] = mapOf("track_hint" to mapOf("nested" to "payload"))
        assertRefused("arguments carrying a structured value", r)
    }

    @Test
    fun `arguments with an over-bound or empty string value are refused`() {
        val r = baseRecord()
        stage(r, "routing")["arguments"] = mapOf("track_hint" to "x".repeat(1025))
        assertRefused("arguments with an over-bound value", r)

        val r2 = baseRecord()
        stage(r2, "routing")["arguments"] = mapOf("track_hint" to "")
        assertRefused("arguments with an empty value", r2)
    }

    // ---- Finding 3: language fields used the 64 KiB text bound ----

    @Test
    fun `source and target language use the short-field bound`() {
        val overShort = "x".repeat(WireBounds.SHORT_FIELD_BYTES + 1)
        assertTrue("fixture must exceed the short bound", WireBounds.utf8Length(overShort) <= WireBounds.TEXT_FIELD_BYTES)

        val r = baseRecord()
        stage(r, "asr")["source_language"] = overShort
        assertRefused("asr.source_language above 512 bytes", r)

        val r2 = baseRecord()
        // Translation is not_run in the base fixture; make it completed so target_language is exercised.
        stage(r2, "translation")["state"] = "completed"
        stage(r2, "translation").remove("reason")
        stage(r2, "translation")["translated_text"] = "skip this ad"
        stage(r2, "translation")["target_language"] = overShort
        assertRefused("translation.target_language above 512 bytes", r2)
    }

    // ---- The ENCODE side must refuse the same payloads, not just the decoder ----

    private fun builderWithArguments(intent: String, arguments: RecordNode): () -> Unit = {
        val audio = AudioBlock(
            sha256 = "a".repeat(64),
            byteCount = 3244,
            retainedSamples = 1600,
            originalFormat = "pcm_s16le",
            conversionVersion = "v1",
            truncated = false,
            droppedSpans = emptyList(),
        )
        ContributionRecordBuilder("6f1e5c2a-0000-4000-8000-000000000001", "epoch-1", "42")
            .model("vad", ModelRef.Named("silero-vad", "4.0"))
            .model("wakeword", ModelRef.Named("auris-wake", "1.2"))
            .model("asr", ModelRef.Named("whisper-small", "2024-11"))
            .model("translation", ModelRef.None)
            .model("router", ModelRef.Named("lfm-intent", "0.3"))
            .stage(StageName.Wake, StageEntry(StageState.Completed, mapOf("decision" to RecordNode.Str("detected"))))
            .stage(StageName.Bleed, StageEntry(StageState.Completed, mapOf("decision" to RecordNode.Str("no_overlap"))))
            .stage(StageName.Asr, StageEntry(StageState.Completed, mapOf("text" to RecordNode.Str("play queen"))))
            .stage(StageName.Translation, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_text"))))
            .stage(
                StageName.Routing,
                StageEntry(
                    StageState.Completed,
                    mapOf("predicted_intent" to RecordNode.Str(intent), "arguments" to arguments),
                ),
            )
            .stage(StageName.Execution, StageEntry(StageState.Completed, mapOf("outcome" to RecordNode.Str("success"))))
            .build(platform = "android", appVersion = "8.20", build = "12345", audio = audio)
        Unit
    }

    private fun assertBuilderRefuses(what: String, build: () -> Unit) {
        val failure = runCatching(build).exceptionOrNull()
        assertTrue("$what must be REFUSED by the builder but was accepted", failure != null)
    }

    @Test
    fun `builder refuses an unauthorized intent carrying arguments`() {
        assertBuilderRefuses("unknown intent + arguments") {
            builderWithArguments("unknown.intent", RecordNode.Obj(mapOf("anything" to RecordNode.Str("value"))))()
        }
    }

    @Test
    fun `builder refuses an unapproved argument member`() {
        assertBuilderRefuses("unapproved argument member") {
            builderWithArguments(
                "track.play",
                RecordNode.Obj(mapOf("track_hint" to RecordNode.Str("queen"), "access_token" to RecordNode.Str("abc"))),
            )()
        }
    }

    @Test
    fun `builder refuses a structured argument value`() {
        assertBuilderRefuses("structured argument value") {
            builderWithArguments("track.play", RecordNode.Obj(mapOf("track_hint" to RecordNode.Obj(mapOf("nested" to RecordNode.Str("x"))))))()
        }
    }

    @Test
    fun `builder still accepts an authorized intent with scalar arguments`() {
        // Guard against over-refusal: the legitimate shape must remain buildable.
        val build = builderWithArguments("track.play", RecordNode.Obj(mapOf("track_hint" to RecordNode.Str("queen"))))
        build()
    }

    /**
     * THE PAIRING RULE MUST HOLD FOR A RECORD COMING BACK IN (reviewer 3180d15d).
     *
     * `RecordValidator` is the authority for a PERSISTED record, not the builder: a record written by
     * an older build, another client, or a corrupted file arrives here as raw JSON. Since
     * `observation_unavailable` was added to every stage's general reason set, a validator that only
     * consulted `StageReasons.isAllowed` would ACCEPT `failed`/`not_run`/`cancelled` paired with it —
     * exactly the pair both the builder and the Cloud intake refuse. So every stage x known state is
     * exercised through the raw decoder, never through the builder.
     */
    @Test
    fun `raw records pairing observation_unavailable with a known state are refused on every stage`() {
        val stages = listOf("wake", "bleed", "asr", "translation", "routing", "execution")
        val knownStates = listOf("not_run", "failed", "cancelled")
        for (stage in stages) {
            for (state in knownStates) {
                val record = baseRecord()

                @Suppress("UNCHECKED_CAST")
                val observations = record["observations"] as MutableMap<String, Any?>
                observations[stage] = mutableMapOf<String, Any?>(
                    "state" to state,
                    "reason" to "observation_unavailable",
                )
                val result = RecordValidator.validate(toNode(record))
                assertTrue(
                    "$stage/$state + observation_unavailable must be REFUSED by RecordValidator, got: $result",
                    result is RecordValidator.Result.Rejected,
                )
            }
        }
        // And the legal pair must still be accepted on the same path, so this is not "refuse more".
        for (stage in stages) {
            val record = baseRecord()

            @Suppress("UNCHECKED_CAST")
            val observations = record["observations"] as MutableMap<String, Any?>
            observations[stage] = mutableMapOf<String, Any?>("state" to "unknown", "reason" to "observation_unavailable")
            val result = RecordValidator.validate(toNode(record))
            assertTrue("$stage/unknown + observation_unavailable must stay ACCEPTED, got: $result", result is RecordValidator.Result.Ok)
        }
    }

    /**
     * `detector_error` ON THE RAW RECOVERY PATH (reviewer 0ee062be).
     *
     * Same reasoning as the observation_unavailable matrix: the reason is in the wake stage's general
     * set, so membership alone would accept `detector_error` with any state. It is valid ONLY for
     * wake/failed, and it must be refused on every OTHER stage as well — a reason that belongs to one
     * stage must not become a general-purpose escape hatch elsewhere.
     */
    @Test
    fun `raw records carry detector_error only as wake plus failed`() {
        val allStages = listOf("wake", "bleed", "asr", "translation", "routing", "execution")
        val allStates = listOf("completed", "not_run", "unknown", "failed", "cancelled")

        // The ONLY legal cell.
        val accepted = baseRecord()
        @Suppress("UNCHECKED_CAST")
        (accepted["observations"] as MutableMap<String, Any?>)["wake"] = mutableMapOf<String, Any?>(
            "state" to "failed",
            "reason" to "detector_error",
        )
        val acceptedResult = RecordValidator.validate(toNode(accepted))
        assertTrue("wake/failed + detector_error must be ACCEPTED, got: $acceptedResult", acceptedResult is RecordValidator.Result.Ok)

        // Every other state on wake, and EVERY state on every other stage, must be refused.
        for (stage in allStages) {
            for (state in allStates) {
                if (stage == "wake" && state == "failed") continue
                val record = baseRecord()

                @Suppress("UNCHECKED_CAST")
                val observations = record["observations"] as MutableMap<String, Any?>
                observations[stage] = mutableMapOf<String, Any?>("state" to state, "reason" to "detector_error")
                val result = RecordValidator.validate(toNode(record))
                assertTrue(
                    "$stage/$state + detector_error must be REFUSED by RecordValidator, got: $result",
                    result is RecordValidator.Result.Rejected,
                )
            }
        }
    }

    /**
     * c6f3bdf7 ON THE RECOVERY PATH (raw maps, never the builder).
     *
     * Same reason as the previous matrix: the builder and the recovery validator are DIFFERENT
     * authorities, and a rule implemented only in the builder leaves every persisted record trusted in
     * a shape the Cloud intake would refuse. `empty_result` is additionally subtle because it is the
     * one reason that is legal on a COMPLETED stage — so the check has to be narrow enough to accept
     * exactly ASR/completed and nothing else.
     */
    @Test
    fun `raw records accept completed ASR empty_result and refuse every contradiction`() {
        // POSITIVE: completed ASR with empty_result and neither text field.
        val accepted = baseRecord()
        @Suppress("UNCHECKED_CAST")
        (accepted["observations"] as MutableMap<String, Any?>)["asr"] = mutableMapOf<String, Any?>(
            "state" to "completed",
            "reason" to "empty_result",
        )
        val acceptedResult = RecordValidator.validate(toNode(accepted))
        assertTrue("completed ASR + empty_result must be ACCEPTED, got: $acceptedResult", acceptedResult is RecordValidator.Result.Ok)

        // POSITIVE: a raw wake-only transcript keeps its text with no router_input.
        val withText = baseRecord()
        @Suppress("UNCHECKED_CAST")
        (withText["observations"] as MutableMap<String, Any?>)["asr"] = mutableMapOf<String, Any?>(
            "state" to "completed",
            "text" to "hey auris",
        )
        assertTrue(
            "a completed ASR carrying the actual transcript must be ACCEPTED",
            RecordValidator.validate(toNode(withText)) is RecordValidator.Result.Ok,
        )

        // NEGATIVE: empty_result carrying text contradicts itself.
        val withTextContradiction = baseRecord()
        @Suppress("UNCHECKED_CAST")
        (withTextContradiction["observations"] as MutableMap<String, Any?>)["asr"] = mutableMapOf<String, Any?>(
            "state" to "completed",
            "reason" to "empty_result",
            "text" to "skip this ad",
        )
        assertTrue(
            "empty_result + text must be REFUSED on recovery",
            RecordValidator.validate(toNode(withTextContradiction)) is RecordValidator.Result.Rejected,
        )

        // NEGATIVE: empty_result carrying router_input.
        val withRouterInput = baseRecord()
        @Suppress("UNCHECKED_CAST")
        (withRouterInput["observations"] as MutableMap<String, Any?>)["asr"] = mutableMapOf<String, Any?>(
            "state" to "completed",
            "reason" to "empty_result",
            "router_input" to "skip this ad",
        )
        assertTrue(
            "empty_result + router_input must be REFUSED on recovery",
            RecordValidator.validate(toNode(withRouterInput)) is RecordValidator.Result.Rejected,
        )

        // NEGATIVE: every non-completed ASR state carrying empty_result.
        for (state in listOf("not_run", "unknown", "failed", "cancelled")) {
            val record = baseRecord()
            @Suppress("UNCHECKED_CAST")
            (record["observations"] as MutableMap<String, Any?>)["asr"] = mutableMapOf<String, Any?>(
                "state" to state,
                "reason" to "empty_result",
            )
            assertTrue(
                "asr/$state + empty_result must be REFUSED: an empty result means recognition ran",
                RecordValidator.validate(toNode(record)) is RecordValidator.Result.Rejected,
            )
        }

        // NEGATIVE: empty_result on a completed stage other than ASR.
        for (stage in listOf("wake", "bleed", "translation", "routing", "execution")) {
            val record = baseRecord()
            @Suppress("UNCHECKED_CAST")
            (record["observations"] as MutableMap<String, Any?>)[stage] = mutableMapOf<String, Any?>(
                "state" to "completed",
                "reason" to "empty_result",
            )
            assertTrue(
                "$stage/completed + empty_result must be REFUSED: the reason belongs to ASR",
                RecordValidator.validate(toNode(record)) is RecordValidator.Result.Rejected,
            )
        }
    }

    @Test
    fun `the baseline fixture stays accepted after the fixes`() {
        // Guards against "fix by refusing everything": the negative controls above must not have made
        // the valid shape unacceptable.
        assertTrue("baseline must stay ACCEPTED", validate(baseValid()) is RecordValidator.Result.Ok)
    }
}
