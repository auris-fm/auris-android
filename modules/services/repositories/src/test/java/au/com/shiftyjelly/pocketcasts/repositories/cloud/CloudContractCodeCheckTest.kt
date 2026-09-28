package au.com.shiftyjelly.pocketcasts.repositories.cloud

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The client's own claim about the error-code contract, checked against the code rather than
 * restated in prose.
 *
 * A review found tonight that the previous arrangement — the vocabulary living only in code, the
 * spec living only in another repo — meant nothing failed when the two drifted. This is the
 * client's half of the fix: [CloudRouteErrorCodes.KNOWN] is re-derived here and compared with the
 * declaration in `cloud-contract-codes.json`, which names the spec revision it was last checked
 * against. A code added, removed or reclassified without touching that file fails this test and
 * names the code.
 *
 * What this does *not* do, deliberately: reach across repos into the spec. A per-side check plus a
 * revision pin catches drift within this repo and makes a stale pin visible; failing automatically
 * when the spec's table changes needs a cross-repo fetch and a token, which is separate work. The
 * revision field is a declaration, not a verification.
 */
class CloudContractCodeCheckTest {

    @JsonClass(generateAdapter = true)
    data class ContractCodes(
        val specDocument: String,
        val specRevision: String,
        val note: String,
        val recognisedFromServer: List<String>,
        val mintedByClient: List<String>,
        val boundedShapes: List<String>,
        val specCodesNotHandled: List<String>,
    )

    private val contract: ContractCodes by lazy {
        val text = checkNotNull(javaClass.classLoader?.getResourceAsStream(CONTRACT_FILE)) {
            "contract declaration missing from the test classpath: $CONTRACT_FILE"
        }.bufferedReader().use { it.readText() }
        Moshi.Builder().build().adapter(ContractCodes::class.java).fromJson(text)
            ?: error("could not parse $CONTRACT_FILE")
    }

    @Test
    fun `the recognised vocabulary matches the declaration exactly`() {
        val declared = (contract.recognisedFromServer + contract.mintedByClient).toSet()

        // Named individually: a failure that prints two full lists makes the reader diff them by
        // eye, which is the work this test exists to do.
        val inCodeNotDeclared = CloudRouteErrorCodes.KNOWN - declared
        val declaredNotInCode = declared - CloudRouteErrorCodes.KNOWN

        assertEquals(
            "Contract drift between the code and $CONTRACT_FILE. " +
                "In code but not declared: ${inCodeNotDeclared.sorted()}. " +
                "Declared but not in code: ${declaredNotInCode.sorted()}. " +
                "Update the declaration (and bump specRevision if the spec table changed) or the " +
                "code — the point is that the two cannot drift silently.",
            emptySet<String>(),
            inCodeNotDeclared + declaredNotInCode,
        )
    }

    @Test
    fun `codes the client does not handle are declared and genuinely unrecognised`() {
        // So a code can be added to KNOWN without silently leaving this list stale.
        val unhandled = contract.specCodesNotHandled.toSet()
        val overlap = unhandled intersect CloudRouteErrorCodes.KNOWN
        assertTrue("declared as unhandled but present in KNOWN: $overlap", overlap.isEmpty())

        // And the two halves must account for the spec's table between them, so a code cannot be
        // quietly dropped from both.
        assertEquals(
            "the declaration's handled and unhandled sets must not share a code",
            emptySet<String>(),
            (contract.recognisedFromServer.toSet() + contract.mintedByClient.toSet()) intersect unhandled,
        )
    }

    @Test
    fun `declared bounded shapes are the shapes the code mints`() {
        // `http_<status>` carries the status of an unparseable error body; it is a shape rather
        // than a code, so it cannot be enumerated and is asserted as a pattern instead.
        val shapes = contract.boundedShapes.map { Regex(it) }
        assertTrue("http_503 should match a declared shape", shapes.any { it.matches("http_503") })
        assertFalse("http_5 is not a status code", shapes.any { it.matches("http_5") })
    }

    @Test
    fun `the declaration names where the contract lives and when it was checked`() {
        assertTrue(
            "the declaration must point at the spec it claims to match",
            contract.specDocument.contains("cloud-assistant.md"),
        )
        assertTrue(
            "the declaration must pin a spec revision, so a stale claim is visible",
            contract.specRevision.matches(Regex("[0-9a-f]{40}")),
        )
    }

    private companion object {
        const val CONTRACT_FILE = "cloud-contract-codes.json"
    }
}
