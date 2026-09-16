package au.com.shiftyjelly.pocketcasts.voicecontrol.benchmark

import java.io.File

/**
 * Index over the persisted per-case results file used for resume.
 *
 * Keyed by variant: the same utterance set is measured once per variant, so
 * resume must skip `(variant, case_id)` pairs — a global case-id set would
 * make a post-variant-A restart skip every case for variant B too.
 */
object BenchmarkResultsIndex {

    fun loadDoneByVariant(resultsFile: File): Map<String, Set<String>> {
        if (!resultsFile.exists()) return emptyMap()
        val byVariant = mutableMapOf<String, MutableSet<String>>()
        resultsFile.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            runCatching {
                val obj = org.json.JSONObject(line)
                val variantKey = obj.getString("variant_key")
                val caseId = obj.getJSONObject("case").getString("case_id")
                byVariant.getOrPut(variantKey) { mutableSetOf() }.add(caseId)
            }
        }
        return byVariant
    }
}
