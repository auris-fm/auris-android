package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance

/**
 * STRICT, BOUNDED accounting documents (reviewer 82cdd583 #4).
 *
 * The previous reader was an unbounded `readText` plus regex extraction, which accepted duplicate
 * fields, extra fields, and silently took the first numeric value it saw. Accounting must never be
 * inferred from a lenient parse: a document is either EXACTLY the canonical form we wrote, or it is
 * damaged and admission closes. Nothing here rewrites a damaged document back to zero.
 */
internal object AllowanceDocuments {
    /** No accounting artifact may ever exceed this; a larger one is damaged, not parsed. */
    const val MAX_BYTES = 4 * 1024

    /**
     * Parses a flat JSON object whose keys are exactly [required] (no extras, no duplicates) and
     * whose values are strings or non-negative integers. Returns null for anything else.
     */
    fun parseObject(text: String, required: Set<String>): Map<String, String>? {
        if (text.isEmpty() || text.length > MAX_BYTES) return null
        if (!text.startsWith("{") || !text.endsWith("}")) return null
        val body = text.substring(1, text.length - 1)
        if (body.isEmpty()) return null
        val result = LinkedHashMap<String, String>()
        var i = 0
        while (i < body.length) {
            if (body[i] != '"') return null
            val keyEnd = body.indexOf('"', i + 1)
            if (keyEnd < 0) return null
            val key = body.substring(i + 1, keyEnd)
            // A duplicate key means two writers disagreed: damaged, never "last one wins".
            if (result.containsKey(key)) return null
            if (keyEnd + 1 >= body.length || body[keyEnd + 1] != ':') return null
            var v = keyEnd + 2
            val value: String
            if (v < body.length && body[v] == '"') {
                val end = body.indexOf('"', v + 1)
                if (end < 0) return null
                value = body.substring(v + 1, end)
                v = end + 1
            } else {
                val start = v
                while (v < body.length && body[v] != ',') {
                    if (!body[v].isDigit()) return null
                    v++
                }
                value = body.substring(start, v)
                if (value.isEmpty()) return null
            }
            result[key] = value
            if (v < body.length) {
                if (body[v] != ',') return null
                i = v + 1
                if (i >= body.length) return null
            } else {
                i = v
            }
        }
        return result.takeIf { it.keys == required }
    }

    fun genesisDocs(): Set<String> = setOf("schema", "state", "id", "binding", "equality", "window")
    fun ledgerDocs(): Set<String> = setOf("schema", "id", "window", "chargedRecords", "chargedBytes")
    fun intentDocs(): Set<String> = setOf("schema", "state", "transaction", "account", "window", "preRecords", "preBytes", "targetRecords", "targetBytes")

    fun genesis(id: String, binding: String, equality: String, window: String, established: Boolean): String = "{\"schema\":$SCHEMA,\"state\":\"${if (established) STATE_ESTABLISHED else STATE_INITIALIZING}\"," +
        "\"id\":\"$id\",\"binding\":\"$binding\",\"equality\":\"$equality\",\"window\":\"$window\"}"

    fun ledger(id: String, window: String, records: Int, bytes: Long): String = "{\"schema\":$SCHEMA,\"id\":\"$id\",\"window\":\"$window\",\"chargedRecords\":$records,\"chargedBytes\":$bytes}"

    fun intent(
        state: String,
        transaction: String,
        account: String,
        window: String,
        preRecords: Int,
        preBytes: Long,
        targetRecords: Int,
        targetBytes: Long,
    ): String = "{\"schema\":$SCHEMA,\"state\":\"$state\",\"transaction\":\"$transaction\",\"account\":\"$account\"," +
        "\"window\":\"$window\",\"preRecords\":$preRecords,\"preBytes\":$preBytes," +
        "\"targetRecords\":$targetRecords,\"targetBytes\":$targetBytes}"

    const val SCHEMA = 1
    const val STATE_INITIALIZING = "INITIALIZING"
    const val STATE_ESTABLISHED = "ESTABLISHED"
    const val STATE_PENDING = "PENDING"
    const val STATE_SETTLED = "SETTLED"
}
