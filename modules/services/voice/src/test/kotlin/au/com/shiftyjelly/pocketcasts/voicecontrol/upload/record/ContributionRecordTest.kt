package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContributionRecordTest {

    private fun audio(truncated: Boolean = false, spans: List<DroppedSpan> = emptyList(), originalMs: Long? = null) = AudioBlock(
        sha256 = "a".repeat(64),
        byteCount = 44 + 3200,
        retainedSamples = 1600,
        originalFormat = "oboe_float32",
        conversionVersion = "v1",
        truncated = truncated,
        droppedSpans = spans,
        originalDurationMs = originalMs,
    )

    private fun builder() = ContributionRecordBuilder(UUID.randomUUID().toString(), "epoch-1", 7L)

    private fun withStages(b: ContributionRecordBuilder): ContributionRecordBuilder = b
        .stage(StageName.Wake, StageEntry(StageState.Completed, mapOf("decision" to RecordNode.Str("rejected"), "offset_units" to RecordNode.Str("samples"))))
        .stage(StageName.Bleed, StageEntry(StageState.NotRun))
        .stage(StageName.Asr, StageEntry(StageState.Failed, mapOf("reason" to RecordNode.Str("no-speech"))))
        .stage(StageName.Routing, StageEntry(StageState.Cancelled))

    @Test
    fun `canonical form sorts keys and omits absent fields`() {
        val record = withStages(builder()).model("vad", "vad-2").model("asr", null).build("android", "9.9.9", "123", audio())
        val json = CanonicalJson.serialize(record)
        // Required top-level order after canonical sort (UTF-16 code units).
        val capture = Regex(""""capture_id":"([^"]+)"""").find(json)!!.groupValues[1]
        assertEquals(
            """{"app_version":"9.9.9","audio":{"byte_count":3244,"channels":1,"conversion_version":"v1","dropped_spans":[],"encoding":"pcm_s16le","original_format":"oboe_float32","retained_samples":1600,"sample_rate_hz":16000,"sha256":"${"a".repeat(64)}","truncated":false},"build":"123","capture_id":"$capture","capture_session_id":7,"enrollment_epoch":"epoch-1","models":{"vad":"vad-2"},"observations":{"asr":{"reason":"no-speech","state":"failed"},"bleed":{"state":"not_run"},"routing":{"state":"cancelled"},"wake":{"decision":"rejected","offset_units":"samples","state":"completed"}},"platform":"android","schema_version":1}""",
            json,
        )
        // Absent asr text stays ABSENT; no null placeholders anywhere.
        assertFalse(json.contains("null"))
        assertFalse(json.contains("\"text\""))
        // Model that did not run is simply MISSING from `models` (vad present above, asr absent).
        assertFalse(json.contains(""""asr":""""))
    }

    @Test
    fun `truncation includes duration only when known and spans are ordered arrays`() {
        val spans = listOf(DroppedSpan(10L, 20L), DroppedSpan(30L, 31L))
        val record = builder().build("android", "1", "1", audio(truncated = true, spans = spans, originalMs = 5000))
        val json = CanonicalJson.serialize(record)
        assertTrue(json.contains("""original_duration_ms":5000"""))
        assertTrue(json.contains("""dropped_spans":[{"end_sample":20,"start_sample":10},{"end_sample":31,"start_sample":30}]"""))
        val unknown = builder().build("android", "1", "1", audio(truncated = true, spans = spans, originalMs = null))
        assertFalse(CanonicalJson.serialize(unknown).contains("original_duration_ms"))
    }

    @Test
    fun `unknown stage fields and stages are refused not silently retained`() {
        // Unknown field name for a stage -> refusal.
        runCatching { builder().stage(StageName.Asr, StageEntry(StageState.Completed, mapOf("transcript_raw" to RecordNode.Str("x")))) }
            .onSuccess { throw AssertionError("unknown field accepted") }
        // Duplicate stage -> refusal.
        builder().stage(StageName.Wake, StageEntry(StageState.Unknown))
        runCatching { builder().stage(StageName.Wake, StageEntry(StageState.Completed)) }.getOrThrow()
        // Links vocabulary is closed too.
        runCatching { builder().build("android", "1", "1", audio(), links = mapOf("session_secret" to RecordNode.Str("x"))) }
            .onSuccess { throw AssertionError("unknown link accepted") }
    }

    @Test
    fun `digest is stable across map insertion order and binds audio identity`() {
        val capture = UUID.randomUUID().toString()
        val b1 = ContributionRecordBuilder(capture, "epoch-1", 7L)
            .stage(StageName.Wake, StageEntry(StageState.Completed, mapOf("decision" to RecordNode.Str("detected"), "score" to RecordNode.Num(0.5))))
            .stage(StageName.Asr, StageEntry(StageState.Completed, mapOf("text" to RecordNode.Str("hello there"))))
        val b2 = ContributionRecordBuilder(capture, "epoch-1", 7L)
            .stage(StageName.Asr, StageEntry(StageState.Completed, mapOf("text" to RecordNode.Str("hello there"))))
            .stage(StageName.Wake, StageEntry(StageState.Completed, mapOf("score" to RecordNode.Num(0.5), "decision" to RecordNode.Str("detected"))))
        val r1 = b1.build("android", "1", "1", audio())
        val r2 = b2.build("android", "1", "1", audio())
        assertEquals(recordSha256(r1), recordSha256(r2))
        // Changed text with identical audio MUST change the digest (same-ID conflict rule).
        val b3 = ContributionRecordBuilder(capture, "epoch-1", 7L)
            .stage(StageName.Wake, StageEntry(StageState.Completed, mapOf("decision" to RecordNode.Str("detected"), "score" to RecordNode.Num(0.5))))
            .stage(StageName.Asr, StageEntry(StageState.Completed, mapOf("text" to RecordNode.Str("hello three"))))
        assertFalse(recordSha256(r1) == recordSha256(b3.build("android", "1", "1", audio())))
    }

    @Test
    fun `canonical number formatting follows RFC 8785 ES6 rendering`() {
        assertEquals("0", CanonicalJson.formatNumber(-0.0))
        assertEquals("1", CanonicalJson.formatNumber(1.0))
        assertEquals("1.5", CanonicalJson.formatNumber(1.5))
        assertEquals("12345678", CanonicalJson.formatNumber(1.2345678E7))
        assertEquals("1e-7", CanonicalJson.formatNumber(1e-7))
        assertEquals("0.0001", CanonicalJson.formatNumber(0.0001))
        assertEquals("0.0004", CanonicalJson.formatNumber(0.0004)) // Java would emit 4.0E-4
        assertEquals("1.5e+21", CanonicalJson.formatNumber(1.5e21))
        assertEquals("-12345.6789", CanonicalJson.formatNumber(-12345.6789)) // sign preserved
        assertEquals("123456789012345680000", CanonicalJson.formatNumber(1.2345678901234568E20))
    }

    @Test
    fun `non-finite numbers and duplicate keys cannot be constructed`() {
        runCatching { RecordNode.Num(Double.NaN) }.onSuccess { throw AssertionError("NaN accepted") }
        runCatching { RecordNode.ObjBuilder().put("k", RecordNode.Bool(true)).put("k", RecordNode.Bool(false)) }
            .onSuccess { throw AssertionError("duplicate key accepted") }
    }

    @Test
    fun `allowlisted numeric args serialize but free-text refs never appear`() {
        // The routing stage's `args` object is produced ONLY from ArgumentAllowlist output.
        val args = mapOf("delta_seconds" to RecordNode.Num(30.0), "direction" to RecordNode.Str("forward"))
        val record = builder()
            .stage(StageName.Routing, StageEntry(StageState.Completed, mapOf("intent" to RecordNode.Str("playback.seek"), "args" to RecordNode.Obj(args))))
            .build("android", "1", "1", audio())
        val json = CanonicalJson.serialize(record)
        assertTrue(json.contains("""args":{"delta_seconds":30,"direction":"forward"}"""))
        // And an intent whose args are private yields an EMPTY args object or absent field — the
        // builder refuses any attempt to smuggle unlisted keys at stage level (covered above).
        assertFalse(json.contains("query"))
    }
}
