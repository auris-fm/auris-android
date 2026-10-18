package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun projectionJson(
    revision: String = "rev-abc",
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
    append("\"membership_revision\":\"$revision\",")
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
 * membership revision exposed for equality only.
 */
class VoiceSharingAccountStateTest {

    private fun state() = VoiceSharingAccountState()

    // --- strict parsing: exact types ---

    @Test
    fun `valid projection body parses into the full projection`() {
        val projection = state().parseProjection(projectionJson())!!
        assertEquals("rev-abc", projection.membershipRevision)
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
    fun `empty revision epoch or disclosure fails closed`() {
        val st = state()
        assertNull(st.parseProjection(projectionJson(revision = "")))
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
        val json = "{\"schema_version\":1,\"membership_revision\":\"a\",\"membership_revision\":\"b\"," +
            "\"plan\":\"pro\",\"voice_sharing_enrolled\":true,\"enrollment_epoch\":\"e\"," +
            "\"accepted_disclosure_version\":\"v\",\"withdrawal_state\":\"active\"}"
        assertNull(state().parseProjection(json))
    }

    // --- synchronous lifecycle issuance + token-scoped applies ---

    @Test
    fun `session issuance is synchronous and unique per lifecycle`() {
        val st = state()
        val t1 = st.sessionStarted()
        val t2 = st.sessionStarted()
        assertTrue(t1 !== t2)
    }

    @Test
    fun `apply requires the ORIGINALLY ISSUED token - stale token never applies`() {
        val st = state()
        val issued = st.sessionStarted()
        st.sessionEnded()
        val relogin = st.sessionStarted() // same account, NEW incarnation
        assertFalse(st.applySessionResponse(issued, projectionJson()))
        assertTrue(st.applySessionResponse(relogin, projectionJson()))
        assertEquals("rev-abc", st.projection.value!!.membershipRevision)
    }

    @Test
    fun `logout invalidates synchronously - no flow conflation dependency`() {
        val st = state()
        val issued = st.sessionStarted()
        assertTrue(st.applySessionResponse(issued, projectionJson()))
        st.sessionEnded()
        assertNull(st.projection.value)
        assertFalse(st.hasCurrentSessionProjection())
        // The ended token cannot re-arm anything afterwards.
        assertFalse(st.applySessionResponse(issued, projectionJson()))
    }

    @Test
    fun `no apply path without an issued session`() {
        val st = state()
        assertNull(st.projection.value)
        assertFalse(st.hasCurrentSessionProjection())
    }

    @Test
    fun `token refresh without a lifecycle boundary keeps the projection`() {
        val st = state()
        val issued = st.sessionStarted()
        assertTrue(st.applySessionResponse(issued, projectionJson()))
        st.onTokenRefresh()
        assertTrue(st.hasCurrentSessionProjection())
        assertTrue(st.applySessionResponse(issued, projectionJson()))
    }

    @Test
    fun `opaque revision equality-only - no ordering surface`() {
        val st = state()
        val issued = st.sessionStarted()
        st.applySessionResponse(issued, projectionJson(revision = "zzz-later-looking"))
        assertTrue(st.isCurrentRevision("zzz-later-looking"))
        assertFalse(st.isCurrentRevision("aaa-earlier-looking"))
    }

    // --- reviewer 19dc4b90: lifecycle atomicity + strict-complete parsing ---

    @Test
    fun `a NEW session token atomically clears the prior projection - no sessionEnded needed`() {
        val st = state()
        val a = st.sessionStarted()
        assertTrue(st.applySessionResponse(a, projectionJson()))
        assertEquals("rev-abc", st.projection.value!!.membershipRevision)

        st.sessionStarted() // authoritative B starts; A's projection must clear ATOMICALLY
        assertNull(st.projection.value)
        assertFalse(st.hasCurrentSessionProjection())
        // A's token can never re-arm.
        assertFalse(st.applySessionResponse(a, projectionJson()))
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
        // The escape decodes to membership_revision, which is also present plainly: duplicate.
        val dup = """{"schema_version":1,"membership_revision":"a","member\u0073hip_revision":"b","plan":"pro","voice_sharing_enrolled":true,"enrollment_epoch":"e","accepted_disclosure_version":"v","withdrawal_state":"active"}"""
        assertNull(st.parseProjection(dup))
        // A single escaped key is a legal spelling and must parse.
        val alias = """{"schema_version":1,"member\u0073hip_revision":"rev-esc","plan":"pro","voice_sharing_enrolled":true,"enrollment_epoch":"e","accepted_disclosure_version":"v","withdrawal_state":"active"}"""
        assertEquals("rev-esc", st.parseProjection(alias)?.membershipRevision)
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
    fun `unparseable body never arms authority`() {
        val st = state()
        val issued = st.sessionStarted()
        assertFalse(st.applySessionResponse(issued, "garbage"))
        assertNull(st.projection.value)
    }
}
