package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record

/**
 * Immutable JSON value tree for contribution records. Provenance/digest code builds this once;
 * [CanonicalJson] serializes it. Absent fields are simply not present in [RecordNode.Obj.fields]
 * — never null placeholders, never empty-string success (spec: missing text stays absent with a
 * stage state). Duplicate keys and non-finite numbers are rejected at construction.
 */
sealed interface RecordNode {
    object Null : RecordNode
    data class Bool(val value: Boolean) : RecordNode
    data class Num(val value: Double) : RecordNode {
        init {
            require(value.isFinite()) { "non-finite number" }
        }
    }

    companion object {
        /** Integers keep exactness through the canonical number formatter. */
        fun ofInt(value: Long): Num = Num(value.toDouble())
    }

    data class Str(val value: String) : RecordNode
    data class Arr(val items: List<RecordNode>) : RecordNode
    data class Obj(val fields: Map<String, RecordNode>) : RecordNode {
        init {
            // Map construction dedupes silently; producers must use the builder which rejects dupes.
        }
    }

    /** Builder that FAILS on duplicate keys (RFC 8785 input conformity + spec rejection rule). */
    class ObjBuilder {
        private val fields = LinkedHashMap<String, RecordNode>()
        fun put(key: String, value: RecordNode): ObjBuilder {
            require(!fields.containsKey(key)) { "duplicate key '$key'" }
            fields[key] = value
            return this
        }

        /** Conditionally includes [value] under [key]; false branch records NOTHING (absent). */
        fun putIfPresent(key: String, value: RecordNode?): ObjBuilder = apply {
            if (value != null) put(key, value)
        }

        fun build(): Obj = Obj(fields.toMap())
    }
}
