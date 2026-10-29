package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.CanonicalJson
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode
import java.security.MessageDigest

/**
 * THE metadata envelope the transport actually sends: canonical record + required digest member.
 * The allowance metadata bound is enforced against THIS full representation (reviewer 55433b71
 * follow-up #3) - a bound that fits the record alone but not its required digest/envelope refuses.
 * ONE definition: the transport's send path and the producer's persistence check share this.
 */
object UploadMetadataEnvelope {
    fun bytes(record: RecordNode.Obj): ByteArray {
        val canonical = CanonicalJson.canonicalBytes(record)
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical).joinToString("") { "%02x".format(it) }
        // Exactly the two members the intake reads, in this order, digest over the CLIENT bytes.
        val json = "{\"record\":" + canonical.toString(Charsets.UTF_8) + ",\"record_sha256\":\"" + digest + "\"}"
        return json.toByteArray(Charsets.UTF_8)
    }
}
