package au.com.shiftyjelly.pocketcasts.voicecontrol.benchmark

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Resume indexing over the persisted per-case results: keyed by variant so a
 * restart after variant A completes cannot skip variant B's cases (the
 * cross-variant skip bug from amendment review).
 */
@RunWith(RobolectricTestRunner::class)
class BenchmarkResultsIndexTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `missing file yields empty index`() {
        assertEquals(emptyMap<String, Set<String>>(), BenchmarkResultsIndex.loadDoneByVariant(File(tmp.root, "nope.jsonl")))
    }

    @Test
    fun `keys done cases per variant, not globally`() {
        val f = tmp.newFile("results.jsonl")
        f.writeText(
            """
            {"variant_key":"a","case":{"case_id":"c1"}}
            {"variant_key":"a","case":{"case_id":"c2"}}
            {"variant_key":"b","case":{"case_id":"c1"}}
            """.trimIndent(),
        )
        val index = BenchmarkResultsIndex.loadDoneByVariant(f)
        assertEquals(setOf("c1", "c2"), index["a"])
        assertEquals(setOf("c1"), index["b"])
    }

    @Test
    fun `malformed lines are skipped without failing the resume`() {
        val f = tmp.newFile("results.jsonl")
        f.writeText(
            """
            not json
            {"variant_key":"a"}
            {"variant_key":"a","case":{"case_id":"c1"}}
            """.trimIndent(),
        )
        val index = BenchmarkResultsIndex.loadDoneByVariant(f)
        assertEquals(setOf("c1"), index["a"])
    }
}
