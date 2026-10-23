package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contribution record: schema-v1 WIRE contract.
 *
 * The expected JSON below is written against the contract pinned by the spec ("Contribution
 * record") and enforced by the Cloud Worker's `record_schema.ts` — not against whatever the local
 * builder happens to emit. `WorkerWireParityTest` is the authority that keeps these honest; the
 * literal expectations here are the regression net for canonical form.
 */
class ContributionRecordTest {

    /**
     * delta736d3180 STRICT PARITY (client side; Cloud intake c569b319 controls U1-U5):
     * `observation_unavailable` is accepted ONLY paired with state `unknown` and refused with
     * every other state — a known outcome always keeps its specific reason.
     */
    @Test
    fun `observation_unavailable is accepted only with state unknown and refused with every other state`() {
        // ALL SIX STAGES (spec 52e0f608): the rule is a shared reason, so applying it to bleed alone
        // would leave five stages where an unenumerated reason could be silently admitted.
        fun record(stage: StageName, state: StageState) = runCatching {
            complete(builder()).replaceStage(
                stage,
                StageEntry(state, mapOf("reason" to RecordNode.Str("observation_unavailable"))),
            ).build(platform = "android", appVersion = "8.20", build = "1", audio = audio())
        }

        val stages = listOf(
            StageName.Wake,
            StageName.Bleed,
            StageName.Asr,
            StageName.Translation,
            StageName.Routing,
            StageName.Execution,
        )
        for (stage in stages) {
            val accepted = record(stage, StageState.Unknown)
            assertTrue(
                "unknown + observation_unavailable must be accepted on ${stage.wire}: ${accepted.exceptionOrNull()}",
                accepted.isSuccess,
            )
            for (state in listOf(StageState.Completed, StageState.NotRun, StageState.Failed, StageState.Cancelled)) {
                val refused = record(stage, state)
                assertTrue(
                    "observation_unavailable must be refused with state ${state.wire} on ${stage.wire}",
                    refused.isFailure,
                )
                // The refusal must come from the RULE, not from an incidental rejection a validator
                // lacking the reason entirely would also produce. A completed stage is refused for a
                // separate reason — a completed stage may not carry a reason at all — so that cell
                // asserts THAT rule; every known non-completed state must name the pairing rule.
                val message = refused.exceptionOrNull()?.message.orEmpty()
                val expected = if (state == StageState.Completed) {
                    "reason must be absent"
                } else {
                    "only valid with state unknown"
                }
                assertTrue(
                    "the refusal on ${stage.wire}/${state.wire} must come from the rule " +
                        "(expected \"$expected\"), was: $message",
                    message.contains(expected),
                )
            }
        }
    }

    /**
     * c6f3bdf7: EMPTY ASR RESULT IS AN OBSERVATION, NOT AN ABSENCE.
     *
     * A recognizer that ran successfully and heard no usable text produced a real observation. The
     * record must say so explicitly (`completed`/`empty_result`, both text fields absent) rather than
     * pretend the stage never ran or invent a reason for a failure that did not happen.
     */
    @Test
    fun `completed ASR with empty_result and no text fields is accepted`() {
        val record = complete(builder())
            .replaceStage(
                StageName.Asr,
                StageEntry(StageState.Completed, mapOf("reason" to RecordNode.Str("empty_result"))),
            )
            .build(platform = "android", appVersion = "8.20", build = "12345", audio = audio())
        val asr = (record.fields.getValue("observations") as RecordNode.Obj).fields.getValue("asr") as RecordNode.Obj
        assertEquals("completed", (asr.fields.getValue("state") as RecordNode.Str).value)
        assertEquals("empty_result", (asr.fields.getValue("reason") as RecordNode.Str).value)
        assertFalse("the empty result carries no text", asr.fields.containsKey("text"))
    }

    @Test
    fun `empty_result carrying text is refused - the record would contradict itself`() {
        val failure = runCatching {
            complete(builder())
                .replaceStage(
                    StageName.Asr,
                    StageEntry(
                        StageState.Completed,
                        mapOf(
                            "reason" to RecordNode.Str("empty_result"),
                            "text" to RecordNode.Str("skip this ad"),
                        ),
                    ),
                )
                .build(platform = "android", appVersion = "8.20", build = "12345", audio = audio())
        }.exceptionOrNull()
        assertTrue(
            "empty_result claims no text; carrying text contradicts the claim, got: $failure",
            failure?.message?.contains("must NOT carry text") == true,
        )
    }

    @Test
    fun `empty_result carrying router_input is refused`() {
        val failure = runCatching {
            complete(builder())
                .replaceStage(
                    StageName.Asr,
                    StageEntry(
                        StageState.Completed,
                        mapOf(
                            "reason" to RecordNode.Str("empty_result"),
                            "router_input" to RecordNode.Str("skip this ad"),
                        ),
                    ),
                )
                .build(platform = "android", appVersion = "8.20", build = "12345", audio = audio())
        }.exceptionOrNull()
        assertTrue(
            "empty_result means no downstream input existed, got: $failure",
            failure?.message?.contains("must NOT carry router_input") == true,
        )
    }

    /**
     * EMITTED-BYTES PARITY BETWEEN BUILDER AND RECOVERY (c6f3bdf7).
     *
     * The full cross-process parity (Android bytes → pinned Worker) needs an intake revision that
     * implements `empty_result`, which does not exist yet — the spec says intake catches up. What CAN
     * be proven now, and is the half that would otherwise silently diverge, is that the bytes the
     * builder emits are exactly the bytes the recovery validator accepts, and that both refuse the
     * same contradictions. Asserting on the ACTUAL emitted bytes rather than on the object graph is
     * deliberate: the builder returns a node, but the record that travels is the canonical JSON, and
     * that is what a persisted record is re-read from.
     */
    @Test
    fun `the emitted bytes for empty_result round-trip through recovery acceptance`() {
        val node = complete(builder())
            .replaceStage(
                StageName.Asr,
                StageEntry(StageState.Completed, mapOf("reason" to RecordNode.Str("empty_result"))),
            )
            .build(platform = "android", appVersion = "8.20", build = "12345", audio = audio())
        val bytes = CanonicalJson.canonicalBytes(node)
        val json = bytes.toString(Charsets.UTF_8)

        assertTrue(
            "the emitted bytes must actually contain the empty_result pair: $json",
            json.contains("\"asr\":{\"reason\":\"empty_result\",\"state\":\"completed\"}"),
        )
        val decoded = RecordValidator.validate(
            CanonicalJson.parse(json),
        )
        assertTrue(
            "the bytes the builder emits must be ACCEPTED by the recovery validator, got: $decoded",
            decoded is RecordValidator.Result.Ok,
        )
        val asr = (decoded as RecordValidator.Result.Ok).record.observations.getValue(StageName.Asr)
        assertEquals(StageState.Completed, asr.state)
        assertEquals("empty_result", (asr.fields.getValue("reason") as RecordNode.Str).value)
        assertFalse("no text survives the round trip", asr.fields.containsKey("text"))
    }

    @Test
    fun `empty_result on a non-completed ASR stage is refused by the builder too`() {
        // The builder must refuse what the recovery validator refuses. Proving the rule on only one
        // of the two authorities is exactly the gap that let a contradictory pairing through before.
        for (state in listOf(StageState.NotRun, StageState.Unknown, StageState.Failed, StageState.Cancelled)) {
            val failure = runCatching {
                complete(builder())
                    .replaceStage(
                        StageName.Asr,
                        StageEntry(state, mapOf("reason" to RecordNode.Str("empty_result"))),
                    )
                    .build(platform = "android", appVersion = "8.20", build = "12345", audio = audio())
            }.exceptionOrNull()
            assertTrue(
                "asr/${state.wire} + empty_result must be refused: an empty result means recognition ran, " +
                    "got: $failure",
                failure != null,
            )
        }
    }

    @Test
    fun `a raw wake-only transcript stays completed with text and no router_input`() {
        // The spec's positive control for the OTHER shape: the recognizer DID hear something (the
        // wake phrase), so the transcript is recorded verbatim and only router_input is omitted.
        val record = complete(builder())
            .replaceStage(
                StageName.Asr,
                StageEntry(
                    StageState.Completed,
                    mapOf("text" to RecordNode.Str("hey auris")),
                ),
            )
            .build(platform = "android", appVersion = "8.20", build = "12345", audio = audio())
        val asr = (record.fields.getValue("observations") as RecordNode.Obj).fields.getValue("asr") as RecordNode.Obj
        assertEquals("completed", (asr.fields.getValue("state") as RecordNode.Str).value)
        assertEquals("hey auris", (asr.fields.getValue("text") as RecordNode.Str).value)
        assertFalse(
            "no command input survived filtering, so router_input must be absent",
            asr.fields.containsKey("router_input"),
        )
    }

    @Test
    fun `empty_result on a non-ASR completed stage is refused`() {
        val failure = runCatching {
            complete(builder())
                .replaceStage(
                    StageName.Translation,
                    StageEntry(StageState.Completed, mapOf("reason" to RecordNode.Str("empty_result"))),
                )
                .build(platform = "android", appVersion = "8.20", build = "12345", audio = audio())
        }.exceptionOrNull()
        assertTrue(
            "empty_result belongs to ASR only, got: $failure",
            failure != null,
        )
    }

    private fun audio(
        truncated: Boolean = false,
        spans: List<DroppedSpan> = emptyList(),
        originalMs: Long? = null,
    ) = AudioBlock(
        sha256 = "a".repeat(64),
        byteCount = 3244,
        retainedSamples = 1600,
        originalFormat = "oboe_float32",
        conversionVersion = "v1",
        truncated = truncated,
        droppedSpans = spans,
        originalDurationMs = originalMs,
    )

    private fun builder(capture: String = UUID.randomUUID().toString()) = ContributionRecordBuilder(capture, "epoch-1", "7")

    /** All five model keys, declared absent rather than omitted. */
    private fun allModelsNone(b: ContributionRecordBuilder): ContributionRecordBuilder = b.model("vad", ModelRef.None)
        .model("wakeword", ModelRef.None)
        .model("asr", ModelRef.None)
        .model("translation", ModelRef.None)
        .model("router", ModelRef.None)

    /** All six stages with legal declared-absence reasons. */
    // Overwrite a stage already declared by a helper (the builder rejects duplicates by design).
    private fun ContributionRecordBuilder.override(stage: StageName, entry: StageEntry): ContributionRecordBuilder = this.replaceStage(stage, entry)

    private fun allStagesNotRun(b: ContributionRecordBuilder): ContributionRecordBuilder = b.stage(StageName.Wake, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_wake_detected"))))
        .stage(StageName.Bleed, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_playback_reference"))))
        .stage(StageName.Asr, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("local_only_action"))))
        .stage(StageName.Translation, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_text"))))
        .stage(StageName.Routing, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_candidates"))))
        .stage(StageName.Execution, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("client_refused"))))

    private fun complete(b: ContributionRecordBuilder) = allStagesNotRun(allModelsNone(b))

    //
    // IDENTITY FIELDS ARE REQUIRED, NOT BLANK-TOLERATED (real-engine finding).
    //
    // The joined-path control submitted the record the engine actually emits and the Cloud intake
    // refused it: `invalid_type app_version`. The cause is production wiring — `versionName` is null
    // in some builds, `?: ""` wrote an empty string into a REQUIRED field, and the producer then
    // returned null, so those captures were lost silently with nothing reported anywhere. A required
    // identity field must be refused at construction with a clear message, not emitted blank and
    // rejected remotely after the audio has been retained and queued.
    //

    /**
     * DETECTOR_FAILURE IS A KNOWN FAILURE (spec 15:46Z, reviewer 6d68ac4a).
     *
     * The wake observer knows its detector threw, so the record must say `failed`, never `unknown`.
     * `detector_error` is valid ONLY with state failed, and the two adjacent known reasons must not be
     * substituted for it (they describe different outcomes).
     */
    @Test
    fun `detector_error is valid only with state failed and refused with every other state`() {
        fun record(state: StageState) = runCatching {
            complete(builder()).replaceStage(
                StageName.Wake,
                StageEntry(state, mapOf("reason" to RecordNode.Str("detector_error"))),
            ).build(platform = "android", appVersion = "8.20", build = "1", audio = audio())
        }
        val accepted = record(StageState.Failed)
        assertTrue("detector_error + failed must be accepted: ${accepted.exceptionOrNull()}", accepted.isSuccess)
        for (state in listOf(StageState.Unknown, StageState.NotRun, StageState.Cancelled)) {
            val refused = record(state)
            assertTrue("detector_error must be refused with state ${state.wire}", refused.isFailure)
            assertTrue(
                "the refusal for ${state.wire} must name the pairing rule, was: ${refused.exceptionOrNull()?.message}",
                refused.exceptionOrNull()?.message.orEmpty().contains("only valid with state failed"),
            )
        }
    }

    @Test
    fun `blank app_version and build are refused at build time`() {
        for (blank in listOf("", "   ")) {
            val badVersion = runCatching {
                complete(builder()).build(platform = "android", appVersion = blank, build = "1", audio = audio())
            }
            assertTrue("app_version '$blank' must be refused", badVersion.isFailure)
            assertTrue(
                "the refusal must name app_version, was: ${badVersion.exceptionOrNull()?.message}",
                badVersion.exceptionOrNull()?.message.orEmpty().contains("app_version"),
            )
            val badBuild = runCatching {
                complete(builder()).build(platform = "android", appVersion = "8.20", build = blank, audio = audio())
            }
            assertTrue("build '$blank' must be refused", badBuild.isFailure)
            assertTrue(
                "the refusal must name build, was: ${badBuild.exceptionOrNull()?.message}",
                badBuild.exceptionOrNull()?.message.orEmpty().contains("build"),
            )
        }
    }

    @Test
    fun `canonical form sorts keys and carries the pinned wire shape`() {
        val capture = UUID.randomUUID().toString()
        val record = complete(builder(capture)).build("android", "9.9.9", "123", audio())
        val json = CanonicalJson.serialize(record)
        assertEquals(
            """{"app_version":"9.9.9","audio":{"byte_count":3244,"channels":1,"conversion_version":"v1","dropped_spans":[],"encoding":"pcm_s16le","original_format":"oboe_float32","retained_samples":1600,"sample_rate_hz":16000,"sha256":"${"a".repeat(64)}","truncated":false},"build":"123","capture_id":"$capture","capture_session_id":"7","enrollment_epoch":"epoch-1","models":{"asr":null,"router":null,"translation":null,"vad":null,"wakeword":null},"observations":{"asr":{"reason":"local_only_action","state":"not_run"},"bleed":{"reason":"no_playback_reference","state":"not_run"},"execution":{"reason":"client_refused","state":"not_run"},"routing":{"reason":"no_candidates","state":"not_run"},"translation":{"reason":"no_text","state":"not_run"},"wake":{"reason":"no_wake_detected","state":"not_run"}},"platform":"android","schema_version":1}""",
            json,
        )
    }

    @Test
    fun `capture_session_id is an opaque wire string never a JSON number`() {
        val json = CanonicalJson.serialize(complete(builder()).build("android", "1", "1", audio()))
        assertTrue(json.contains(""""capture_session_id":"7""""))
        // A bare number would be a durable server incompatibility (Worker expects a string).
        assertFalse(json.contains(""""capture_session_id":7"""))
    }

    @Test
    fun `every model key and every stage must be present`() {
        // Omitting a model key is invalid on the wire (null declares not-run, omission does not).
        val missingModel = runCatching {
            builder()
                .model("vad", ModelRef.None)
                .model("wakeword", ModelRef.None)
                .model("asr", ModelRef.None)
                .model("translation", ModelRef.None)
                .let(::allStagesNotRun)
                .build("android", "1", "1", audio())
        }
        assertTrue("missing model key accepted", missingModel.isFailure)
        // Omitting a stage is equally invalid.
        val missingStage = runCatching {
            allModelsNone(builder())
                .stage(StageName.Wake, StageEntry(StageState.NotRun, mapOf("reason" to RecordNode.Str("no_wake_detected"))))
                .build("android", "1", "1", audio())
        }
        assertTrue("missing stage accepted", missingStage.isFailure)
    }

    @Test
    fun `a non-completed stage requires an enumerated reason and a completed stage forbids one`() {
        // Missing reason on a non-completed stage.
        runCatching { builder().stage(StageName.Asr, StageEntry(StageState.Failed)) }
            .onSuccess { throw AssertionError("failed stage without reason accepted") }
        // Free-text reason is not enumerated.
        runCatching { builder().stage(StageName.Asr, StageEntry(StageState.Failed, mapOf("reason" to RecordNode.Str("provider blew up at /var/log/x")))) }
            .onSuccess { throw AssertionError("arbitrary reason accepted") }
        // A completed stage carrying a reason is refused too.
        runCatching { builder().stage(StageName.Asr, StageEntry(StageState.Completed, mapOf("text" to RecordNode.Str("hi"), "reason" to RecordNode.Str("no_text")))) }
            .onSuccess { throw AssertionError("completed stage with reason accepted") }
    }

    @Test
    fun `wire stage field names are the pinned millisecond forms, not sample aliases`() {
        // Sample-offset aliases and offset_units are explicitly not on the wire.
        runCatching { builder().stage(StageName.Wake, StageEntry(StageState.Completed, mapOf("offset_units" to RecordNode.Str("samples")))) }
            .onSuccess { throw AssertionError("offset_units accepted") }
        runCatching { builder().stage(StageName.Wake, StageEntry(StageState.Completed, mapOf("speech_offset_samples" to RecordNode.ofInt(10)))) }
            .onSuccess { throw AssertionError("sample-offset alias accepted") }
        // The millisecond forms ARE accepted.
        builder().stage(
            StageName.Wake,
            StageEntry(StageState.Completed, mapOf("speech_offset_ms" to RecordNode.ofInt(120), "wake_offset_ms" to RecordNode.ofInt(340))),
        )
    }

    @Test
    fun `truncation includes duration only when known and spans are ordered arrays`() {
        val spans = listOf(DroppedSpan(10L, 20L), DroppedSpan(30L, 31L))
        val record = complete(builder()).build("android", "1", "1", audio(truncated = true, spans = spans, originalMs = 5000))
        val json = CanonicalJson.serialize(record)
        assertTrue(json.contains("""original_duration_ms":5000"""))
        assertTrue(json.contains("""dropped_spans":[{"end_sample":20,"start_sample":10},{"end_sample":31,"start_sample":30}]"""))
        val unknown = complete(builder()).build("android", "1", "1", audio(truncated = true, spans = spans, originalMs = null))
        assertFalse(CanonicalJson.serialize(unknown).contains("original_duration_ms"))
    }

    @Test
    fun `unknown stage fields and stages are refused not silently retained`() {
        runCatching { builder().stage(StageName.Asr, StageEntry(StageState.Completed, mapOf("transcript_raw" to RecordNode.Str("x")))) }
            .onSuccess { throw AssertionError("unknown field accepted") }
        builder().stage(StageName.Wake, StageEntry(StageState.Unknown, mapOf("reason" to RecordNode.Str("outcome_wait_expired"))))
        runCatching {
            builder().stage(StageName.Wake, StageEntry(StageState.Completed))
                .stage(StageName.Wake, StageEntry(StageState.Completed))
        }.onSuccess { throw AssertionError("duplicate stage accepted") }
        // Links vocabulary is closed too (and now includes request_id).
        runCatching { complete(builder()).build("android", "1", "1", audio(), links = mapOf("session_secret" to RecordNode.Str("x"))) }
            .onSuccess { throw AssertionError("unknown link accepted") }
        complete(builder()).build("android", "1", "1", audio(), links = mapOf("request_id" to RecordNode.Str("r-1")))
    }

    @Test
    fun `digest is stable across map insertion order and binds audio identity`() {
        val capture = UUID.randomUUID().toString()
        val asrHello = StageEntry(StageState.Completed, mapOf("text" to RecordNode.Str("hello there")))
        val b1 = complete(ContributionRecordBuilder(capture, "epoch-1", "7")).override(StageName.Asr, asrHello)
        val b2 = complete(ContributionRecordBuilder(capture, "epoch-1", "7")).override(StageName.Asr, asrHello)
        val r1 = b1.build("android", "1", "1", audio())
        val r2 = b2.build("android", "1", "1", audio())
        assertEquals(recordSha256(r1), recordSha256(r2))
        // Changed text with identical audio MUST change the digest (same-ID conflict rule).
        val b3 = complete(ContributionRecordBuilder(capture, "epoch-1", "7"))
            .override(StageName.Asr, StageEntry(StageState.Completed, mapOf("text" to RecordNode.Str("hello three"))))
        assertFalse(recordSha256(r1) == recordSha256(b3.build("android", "1", "1", audio())))
    }

    @Test
    fun `platform is the closed wire vocabulary`() {
        runCatching { complete(builder()).build("windows", "1", "1", audio()) }
            .onSuccess { throw AssertionError("unknown platform accepted") }
        complete(builder()).build("ios", "1", "1", audio())
    }
}
