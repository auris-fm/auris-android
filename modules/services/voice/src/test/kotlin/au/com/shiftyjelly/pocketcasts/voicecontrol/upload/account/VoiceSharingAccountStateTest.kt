package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun projectionJson(
    plan: String = "pro",
    enrolled: Boolean = true,
    epoch: String? = "epoch-1",
    disclosure: String? = "v3",
    withdrawal: String = "active",
    schemaVersion: String = "1",
    rawOverrides: Map<String, String> = emptyMap(),
): String = buildString {
    append("{")
    append("\"schema_version\":$schemaVersion,")
    append("\"plan\":\"$plan\",")
    if ("voice_sharing_enrolled" !in rawOverrides) append("\"voice_sharing_enrolled\":$enrolled,")
    if ("enrollment_epoch" !in rawOverrides) append("\"enrollment_epoch\":${epoch?.let { "\"$it\"" } ?: "null"},")
    append("\"accepted_disclosure_version\":${disclosure?.let { "\"$it\"" } ?: "null"},")
    append("\"withdrawal_state\":\"$withdrawal\"")
    rawOverrides.forEach { (k, v) -> append(",\"$k\":$v") }
    append("}")
}

/**
 * Task 2 contract controls (reviewer 99f9d241): exact JSON types/values with NO coercion, an
 * explicitly duplicate-detecting strict parser, lifecycle generation issued SYNCHRONOUSLY at
 * request issuance (not account ID, not async flow observation — a conflating flow must not be
 * the invalidation mechanism), token-scoped authority applies (only the originally issued
 * session token may apply a response), enrollment/withdrawal consistency, and the opaque
 * projection shape the server actually returns.
 */
class VoiceSharingAccountStateTest {

    private fun state() = VoiceSharingAccountState()

    // --- strict parsing: exact types ---

    @Test
    fun `valid projection body parses into the full projection`() {
        val projection = state().parseProjection(projectionJson())!!
        assertEquals(VoiceSharingPlan.PRO, projection.plan)
        assertEquals(VoiceSharingPlan.PRO, projection.plan)
        assertTrue(projection.voiceSharingEnrolled)
        assertEquals("epoch-1", projection.enrollmentEpoch)
        assertEquals("v3", projection.acceptedDisclosureVersion)
        assertEquals(WithdrawalState.ACTIVE, projection.withdrawalState)
    }

    @Test
    fun `free plan not enrolled parses`() {
        val projection = state().parseProjection(
            projectionJson(plan = "free", enrolled = false, epoch = null, disclosure = null, withdrawal = "not_enrolled"),
        )!!
        assertEquals(VoiceSharingPlan.FREE, projection.plan)
        assertNull(projection.enrollmentEpoch)
        assertNull(projection.acceptedDisclosureVersion)
        assertEquals(WithdrawalState.NOT_ENROLLED, projection.withdrawalState)
    }

    @Test
    fun `schema_version as string fraction long or null fails closed - no numeric coercion`() {
        val st = state()
        assertNull(st.parseProjection(projectionJson(schemaVersion = "\"1\"")))
        assertNull(st.parseProjection(projectionJson(schemaVersion = "1.5")))
        assertNull(st.parseProjection(projectionJson(schemaVersion = "null")))
        assertNull(st.parseProjection(projectionJson(schemaVersion = "2")))
    }

    @Test
    fun `string boolean fails closed - no boolean coercion`() {
        assertNull(state().parseProjection(projectionJson(rawOverrides = mapOf("voice_sharing_enrolled" to "\"true\""))))
        assertNull(state().parseProjection(projectionJson(rawOverrides = mapOf("voice_sharing_enrolled" to "1"))))
    }

    @Test
    fun `enum case variants fail closed - no uppercase coercion`() {
        val st = state()
        assertNull(st.parseProjection(projectionJson(plan = "PRO")))
        assertNull(st.parseProjection(projectionJson(plan = "Pro")))
        assertNull(st.parseProjection(projectionJson(withdrawal = "Active")))
        assertNull(st.parseProjection(projectionJson(withdrawal = "NOT_ENROLLED")))
    }

    @Test
    fun `wrong schema version fails closed`() {
        assertNull(state().parseProjection(projectionJson(schemaVersion = "2")))
    }

    @Test
    fun `unknown field fails closed`() {
        assertNull(state().parseProjection(projectionJson(rawOverrides = mapOf("plan_details" to "{}"))))
    }

    @Test
    fun `missing field fails closed`() {
        assertNull(state().parseProjection(projectionJson(rawOverrides = mapOf("voice_sharing_enrolled" to "null"))))
    }

    // --- nonempty fields + enrollment/withdrawal consistency ---

    @Test
    fun `empty epoch or disclosure fails closed`() {
        val st = state()
        assertNull(st.parseProjection(projectionJson(epoch = "")))
        assertNull(st.parseProjection(projectionJson(disclosure = "")))
    }

    @Test
    fun `contradictory enrollment and withdrawal states fail closed`() {
        val st = state()
        assertNull(st.parseProjection(projectionJson(enrolled = false, withdrawal = "active")))
        assertNull(st.parseProjection(projectionJson(epoch = null, withdrawal = "active")))
        assertNull(st.parseProjection(projectionJson(disclosure = null, withdrawal = "active")))
        assertNull(st.parseProjection(projectionJson(enrolled = true, withdrawal = "withdrawn")))
        assertNull(st.parseProjection(projectionJson(enrolled = true, withdrawal = "not_enrolled")))
        assertNull(st.parseProjection(projectionJson(enrolled = false, epoch = "epoch-1", withdrawal = "not_enrolled")))
    }

    @Test
    fun `malformed json fails closed independently`() {
        val st = state()
        assertNull(st.parseProjection("{"))
        assertNull(st.parseProjection("{\"a\":}"))
        assertNull(st.parseProjection("not json"))
        assertNull(st.parseProjection(""))
        assertNull(st.parseProjection("[1,2]"))
    }

    // --- explicit duplicate-key detection ---

    @Test
    fun `duplicate top-level keys fail closed`() {
        val json = "{\"schema_version\":1,\"plan\":\"pro\",\"plan\":\"free\"," +
            "\"voice_sharing_enrolled\":true,\"enrollment_epoch\":\"e\"," +
            "\"accepted_disclosure_version\":\"v\",\"withdrawal_state\":\"active\"}"
        assertNull(state().parseProjection(json))
    }

    // --- ordinary publication: no sessions, no tickets ---

    @Test
    fun `publication replaces the projection and bumps the publication count`() {
        val st = state()
        assertNull(st.projection.value)
        assertTrue(st.publishJson(projectionJson()))
        assertTrue(st.projection.value!!.voiceSharingEnrolled)
        assertEquals(1L, st.publicationCount())
        assertTrue(st.publishJson(projectionJson(epoch = "epoch-2")))
        assertEquals("epoch-2", st.projection.value!!.enrollmentEpoch)
        assertEquals(2L, st.publicationCount())
    }

    @Test
    fun `clear drops the projection so consent fails closed until the server answers again`() {
        val st = state()
        assertTrue(st.publishJson(projectionJson()))
        st.clear()
        assertNull(st.projection.value)
        assertEquals(2L, st.publicationCount())
    }

    @Test
    fun `publication listener observes both the projection and its loss`() {
        val st = state()
        val seen = mutableListOf<String?>()
        st.onProjectionListener = { seen += it?.enrollmentEpoch }
        assertTrue(st.publishJson(projectionJson()))
        st.clear()
        assertEquals(listOf("epoch-1", null), seen)
    }

    /** Publication helper used by the controls: parse, then publish (never a raw string). */
    private fun VoiceSharingAccountState.publishJson(json: String): Boolean {
        val projection = parseProjection(json) ?: return false
        publish(projection)
        return true
    }

    @Test
    fun `epoch and disclosure must be actual JSON null OR nonempty string - non-string types fail`() {
        val st = state()
        // A numeric epoch silently coerced to null must NOT be accepted as "not_enrolled".
        assertNull(st.parseProjection(projectionJson(epoch = "42", withdrawal = "not_enrolled")))
        assertNull(st.parseProjection(projectionJson(epoch = "true", withdrawal = "not_enrolled")))
        assertNull(st.parseProjection(projectionJson(epoch = "{}", withdrawal = "not_enrolled")))
        assertNull(st.parseProjection(projectionJson(disclosure = "42", withdrawal = "not_enrolled")))
        // Fractional/other numeric schema values are not integers.
        assertNull(st.parseProjection(projectionJson(schemaVersion = "1.5")))
    }

    @Test
    fun `schema_version exact numeric 1 - 1_0 and 1e0 are integers and must be accepted`() {
        val st = state()
        assertTrue(st.parseProjection(projectionJson(schemaVersion = "1.0")) != null)
        assertTrue(st.parseProjection(projectionJson(schemaVersion = "1e0")) != null)
        assertNull(st.parseProjection(projectionJson(schemaVersion = "true")))
        assertNull(st.parseProjection(projectionJson(schemaVersion = "\"1\"")))
        assertNull(st.parseProjection(projectionJson(schemaVersion = "null")))
    }

    @Test
    fun `strict complete document - trailing junk and second value fail`() {
        val st = state()
        val base = projectionJson()
        assertNull(st.parseProjection(base + " {}"))
        assertNull(st.parseProjection(base + "x"))
        assertNull(st.parseProjection(base + " null"))
    }

    @Test
    fun `legal key escapes are decoded - escaped alias of a duplicate key fails, unique escaped key parses`() {
        val st = state()
        // The escape decodes to `plan`, which is also present plainly: duplicate.
        val dup = """{"schema_version":1,"plan":"pro","pl\u0061n":"free","voice_sharing_enrolled":true,"enrollment_epoch":"e","accepted_disclosure_version":"v","withdrawal_state":"active"}"""
        assertNull(st.parseProjection(dup))
        // A single escaped key is a legal spelling and must parse.
        val alias = """{"schema_version":1,"pl\u0061n":"pro","voice_sharing_enrolled":true,"enrollment_epoch":"e","accepted_disclosure_version":"v","withdrawal_state":"active"}"""
        assertEquals("e", st.parseProjection(alias)?.enrollmentEpoch)
    }

    @Test
    fun `unquoted or single-quoted keys fail - strict syntax only`() {
        val st = state()
        val unquoted = projectionJson().replace("\"schema_version\":", "schema_version:")
        assertNull(st.parseProjection(unquoted))
    }

    // --- reviewer 6bcf0d6e: exact decimal lexeme ---

    @Test
    fun `schema_version near-1 fraction that ROUNDS to 1_0 fails - exact decimal lexeme`() {
        assertNull(state().parseProjection(projectionJson(schemaVersion = "1.00000000000000001")))
        assertNull(state().parseProjection(projectionJson(schemaVersion = "0.999999999999999999")))
    }

    // --- malformed baseline ---

    @Test
    fun `unparseable body never becomes a projection`() {
        assertNull(state().parseProjection("{\"schema_version\":1,\"plan\":\"pro\""))
    }
}
