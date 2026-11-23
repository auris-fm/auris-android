package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.fingerprint_uniffi.MatchResult

/**
 * The acceptance rule against ranked lists the inherited library actually returned, captured from a
 * real episode on both sides of the match. Both phone apps test this same file, so a disagreement
 * between them is a difference in the rule rather than in the data.
 *
 * The file is committed unchanged; its digest is asserted so a quiet edit cannot pass as the shared
 * fixture.
 */
class ReferenceAcceptanceRealCasesTest {

    @JsonClass(generateAdapter = true)
    data class Candidate(val timestamp: Float, val score: Float)

    @JsonClass(generateAdapter = true)
    data class Case(
        val name: String,
        val kind: String,
        @Json(name = "reference_checkpoints") val referenceCheckpoints: Int,
        @Json(name = "shift_ms") val shiftMs: Int,
        @Json(name = "query_timestamp_ms") val queryTimestampMs: Int,
        @Json(name = "true_reference_s") val trueReferenceSeconds: Double?,
        val candidates: List<Candidate>,
        val accept: Boolean,
        val reason: String,
        @Json(name = "runner_up_rule_accept") val runnerUpRuleAccept: Boolean,
        val note: String? = null,
    )

    /** The rule the fixture's verdicts were computed with; asserted against our constants below. */
    @JsonClass(generateAdapter = true)
    data class Rule(
        @Json(name = "score_floor") val scoreFloor: Float,
        val lead: Float,
        @Json(name = "distant_means_more_than_s") val distantMeansMoreThanSeconds: Float,
    )

    @JsonClass(generateAdapter = true)
    data class Fixture(val description: String, val rule: Rule, val cases: List<Case>)

    private val bytes: ByteArray by lazy {
        checkNotNull(javaClass.classLoader?.getResourceAsStream(FILE)) {
            "the shared matcher fixture is missing from the test classpath: $FILE"
        }.use { it.readBytes() }
    }

    private val fixture: Fixture by lazy {
        Moshi.Builder().build().adapter(Fixture::class.java).fromJson(bytes.decodeToString())
            ?: error("could not parse $FILE")
    }

    private fun ranked(case: Case): List<MatchResult> = case.candidates.map { MatchResult(it.timestamp, it.score) }

    @Test
    fun `the shared fixture is the one both apps test`() {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

        assertEquals(SHA256, digest)
        assertEquals(40, fixture.cases.size)
    }

    @Test
    fun `the fixture's verdicts were computed with the rule we implement`() {
        // Without this the file could describe a different rule and every verdict case would still
        // pass, because both sides would be wrong together.
        assertEquals(FingerprintConstants.REFERENCE_ACCEPTANCE_SCORE, fixture.rule.scoreFloor, 0f)
        assertEquals(FingerprintConstants.REFERENCE_LEAD_GAP, fixture.rule.lead, 0f)
        assertEquals(
            FingerprintConstants.REFERENCE_LEAD_DISTANCE_SECONDS,
            fixture.rule.distantMeansMoreThanSeconds,
            0f,
        )
    }

    @Test
    fun `every real case gets the verdict the rule gives it`() {
        val wrong = fixture.cases.filter { case ->
            (ReferenceAcceptance.accept(ranked(case)) != null) != case.accept
        }

        assertTrue(
            "the rule disagrees with the captured verdicts: " +
                wrong.joinToString { "${it.name} (expected ${it.reason})" },
            wrong.isEmpty(),
        )
    }

    @Test
    fun `an accepted real case lands on a checkpoint overlapping the window's true place`() {
        val shifted = fixture.cases.filter { it.kind == "shifted" && it.accept }
        assertTrue("the fixture must contain accepted shifted windows", shifted.isNotEmpty())

        for (case in shifted) {
            val accepted = ReferenceAcceptance.accept(ranked(case))
            assertNotNull(case.name, accepted)
            // Checkpoints sit on whole seconds and each covers eight of them, so a window between
            // two checkpoints legitimately matches either neighbour. What must never happen is a
            // match somewhere else entirely: the accepted checkpoint has to overlap the window.
            val truePlace = case.trueReferenceSeconds!!
            val distance = kotlin.math.abs(accepted!!.referenceTimeSeconds - truePlace)
            assertTrue(
                "${case.name}: accepted ${accepted.referenceTimeSeconds}s, window really at ${truePlace}s",
                distance < FingerprintConstants.WINDOW_DURATION_MS / 1000.0,
            )
        }
    }

    @Test
    fun `the old runner-up rule disagrees on real data`() {
        // This is what makes the change worth making: on these lists the two rules give different
        // answers, so a test suite that passed under both would not be pinning the new rule.
        val disagreements = fixture.cases.filter { it.accept != it.runnerUpRuleAccept }

        assertEquals(15, disagreements.size)

        // Both directions matter. The new rule rescues correct matches the runner-up vetoed, and
        // it refuses short references the old rule accepted on the floor alone.
        val rescued = disagreements.filter { it.accept }
        val refused = disagreements.filter { !it.accept }
        assertEquals(8, rescued.size)
        assertEquals(7, refused.size)
        assertTrue(
            "the rescues must include the half-second shifts",
            rescued.any { it.shiftMs == 500 },
        )
        assertTrue(
            "every refusal must be a reference with no distant candidate",
            refused.all { it.reason == "no_distant_candidate" },
        )
    }

    @Test
    fun `a short reference fails closed on every captured case`() {
        val threeCheckpoint = fixture.cases.filter { it.referenceCheckpoints == 3 }
        assertTrue("the fixture must contain three-checkpoint references", threeCheckpoint.isNotEmpty())

        for (case in threeCheckpoint) {
            assertNull(case.name, ReferenceAcceptance.accept(ranked(case)))
            assertEquals(case.name, "no_distant_candidate", case.reason)
        }
    }

    @Test
    fun `a candidate exactly two seconds away is a neighbour, not competition`() {
        val exactlyTwo = fixture.cases.filter { case ->
            case.reason == "no_distant_candidate" && case.referenceCheckpoints > 3
        }
        assertTrue("the fixture must pin the boundary itself", exactlyTwo.isNotEmpty())

        for (case in exactlyTwo) {
            val best = case.candidates.first()
            assertTrue(
                "${case.name}: every rival must sit within two seconds of the best",
                case.candidates.drop(1).all { kotlin.math.abs(it.timestamp - best.timestamp) <= 2f },
            )
            assertNull(case.name, ReferenceAcceptance.accept(ranked(case)))
        }
    }

    @Test
    fun `unrelated audio is refused except where the drift filter has to decide`() {
        val unrelated = fixture.cases.filter { it.kind == "unrelated" }
        assertEquals(6, unrelated.size)

        // One unrelated window really does pass the rule: a quiet stretch of the reference sets few
        // bits, so it agrees with almost anything. The rule must still accept it — refusing it here
        // would mean tuning the rule to one clip. Several windows across time are what reject it.
        val accepted = unrelated.filter { ReferenceAcceptance.accept(ranked(it)) != null }
        assertEquals(1, accepted.size)
        assertNotNull("the accepted unrelated case must carry its explanation", accepted.single().note)
    }

    private companion object {
        const val FILE = "fingerprint/matcher-top6-cases.json"
        const val SHA256 = "83a2c999e7d6eb13bb32daa687276c7b66d33863684a4505dff2f633df1c6941"
    }
}
