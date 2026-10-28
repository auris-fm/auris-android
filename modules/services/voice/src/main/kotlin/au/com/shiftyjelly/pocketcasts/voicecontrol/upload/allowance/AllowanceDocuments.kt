package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.allowance

/**
 * The canonical flat accounting documents (reviewer 82cdd583 #4, 8b5fc0c9 #2).
 *
 * Each document is a FLAT object with an EXACT key set: a duplicate key, an extra field, a
 * non-numeric numeric, a control character or quoted value is damage. That strictness is the point -
 * a lenient reader would accept a rewritten counter or an injected field and then treat damaged
 * accounting as valid usage. [MAX_BYTES] bounds every read so a large file cannot be parsed at all.
 */
object AllowanceDocuments {
    const val MAX_BYTES: Int = 4_096
    const val SCHEMA: Int = 1

    const val STATE_INITIALIZING = "INITIALIZING"
    const val STATE_ESTABLISHED = "ESTABLISHED"
    const val STATE_PENDING = "PENDING"
    const val STATE_SETTLED = "SETTLED"

    private val NUMERIC_KEYS = setOf("schema", "preRecords", "preBytes", "targetRecords", "targetBytes", "chargedRecords", "chargedBytes")
    private val STRING_KEYS = setOf("state", "id", "binding", "equality", "window", "transaction", "incarnation")

    fun genesisDocs(): Set<String> = setOf("schema", "state", "id", "binding", "equality", "window")

    fun ledgerDocs(): Set<String> = setOf("schema", "id", "window", "chargedRecords", "chargedBytes")

    fun intentDocs(): Set<String> = setOf("schema", "state", "transaction", "binding", "window", "incarnation", "preRecords", "preBytes", "targetRecords", "targetBytes")

    /** The canonical ledger a control starts from, so mutations are applied to a real document. */
    fun ledgerDocument(id: String, window: String, chargedRecords: Int, chargedBytes: Long): String = ledger(id, window, chargedRecords, chargedBytes)

    fun genesis(id: String, binding: String, equality: String, window: String, state: String): String = join(
        "schema" to SCHEMA.toString(),
        "state" to state,
        "id" to id,
        "binding" to binding,
        "equality" to equality,
        "window" to window,
    )

    fun ledger(id: String, window: String, chargedRecords: Int, chargedBytes: Long): String = join(
        "schema" to SCHEMA.toString(),
        "id" to id,
        "window" to window,
        "chargedRecords" to chargedRecords.toString(),
        "chargedBytes" to chargedBytes.toString(),
    )

    fun intent(
        state: String,
        transaction: String,
        binding: String,
        window: String,
        incarnation: String,
        preRecords: Int,
        preBytes: Long,
        targetRecords: Int,
        targetBytes: Long,
    ): String = join(
        "schema" to SCHEMA.toString(),
        "state" to state,
        "transaction" to transaction,
        "binding" to binding,
        "window" to window,
        "incarnation" to incarnation,
        "preRecords" to preRecords.toString(),
        "preBytes" to preBytes.toString(),
        "targetRecords" to targetRecords.toString(),
        "targetBytes" to targetBytes.toString(),
    )

    private fun join(vararg pairs: Pair<String, String>): String = pairs.joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) -> "\"$key\":\"$value\"" }

    /**
     * Strict flat-object parse. Returns null for anything that is not EXACTLY [required]: wrong
     * length, unbalanced braces, duplicate keys, extra or missing keys, non-numeric numerics, unsafe
     * value characters.
     */
    fun parseObject(text: String?, required: Set<String>): Map<String, String>? {
        if (text == null) return null
        if (text.length > MAX_BYTES) return null
        // A control may mutate a document after its trailing brace; trim the RESULT, never a
        // substring taken before it, or a duplicate appended at the end would be silently dropped.
        val trimmed = text.trim()
        if (trimmed.length < 2 || trimmed.first() != '{' || trimmed.last() != '}') return null
        val body = trimmed.substring(1, trimmed.length - 1)
        if (body.isEmpty()) return null
        val result = LinkedHashMap<String, String>(required.size)
        var index = 0
        while (index < body.length) {
            if (index > 0) {
                if (body[index] != ',') return null
                index += 1
            }
            if (index >= body.length || body[index] != '"') return null
            val keyEnd = body.indexOf('"', index + 1)
            if (keyEnd < 0) return null
            val key = body.substring(index + 1, keyEnd)
            if (key !in required) return null
            // A duplicate key means two writers or a rewrite: damage, never last-one-wins.
            if (result.containsKey(key)) return null
            index = keyEnd + 1
            if (index >= body.length || body[index] != ':') return null
            index += 1
            if (index >= body.length || body[index] != '"') return null
            val valueStart = index + 1
            val valueEnd = body.indexOf('"', valueStart)
            if (valueEnd < 0) return null
            val value = body.substring(valueStart, valueEnd)
            if (!isSafeValue(key, value)) return null
            result[key] = value
            index = valueEnd + 1
        }
        return result.takeIf { it.keys == required }
    }

    private fun isSafeValue(key: String, value: String): Boolean {
        if (value.any { it < ' ' || it == '"' || it == '\\' }) return false
        return when (key) {
            in NUMERIC_KEYS -> value.isNotEmpty() && value.all { it in '0'..'9' }
            in STRING_KEYS -> value.isNotEmpty() && value.all { it.isLetterOrDigit() || it in "-_.:@" }
            else -> false
        }
    }
}
