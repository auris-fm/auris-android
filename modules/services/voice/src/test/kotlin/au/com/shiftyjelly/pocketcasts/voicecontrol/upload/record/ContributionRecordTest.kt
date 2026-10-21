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
