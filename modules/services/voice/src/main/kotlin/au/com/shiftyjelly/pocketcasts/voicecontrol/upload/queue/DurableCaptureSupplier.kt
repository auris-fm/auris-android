package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.CanonicalJson
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordNode
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record.RecordValidator
import java.io.File

/**
 * The DURABLE CAPTURE SUPPLIER the assembly needs: given a capture id that the queue listed as ready, it
 * returns the audio file and the record that was written beside it at admission time.
 *
 * WHY IT RE-READS RATHER THAN CACHING (spec 128415b0): the record that goes on the wire must be the one the
 * queue actually durably published for THIS capture, not a reconstruction from current settings. Re-reading
 * the `<id>.metadata.json` sibling means the uploaded record keeps the `original_format` and
 * `conversion_version` recorded when the audio was produced, even if the app's conversion settings have
 * changed since. A missing or unreadable pair is `null` - which the assembly holds rather than sends,
 * because "nothing on disk" is not an acceptance and not a transport outcome.
 */
class DurableCaptureSupplier(private val root: File) {

    fun capture(captureId: String): ContributionUploadAssembly.Capture? {
        // A capture id reaches this from the queue, but it also becomes a path: refuse anything that could
        // escape the capture directory rather than sanitising it later.
        if (!captureId.matches(UUID_RE)) return null
        val directory = File(root, CAPTURES_DIR)
        val audio = File(directory, captureId + SUFFIX_WAV)
        val metadata = File(directory, captureId + SUFFIX_META)
        if (!audio.isFile || !metadata.isFile) return null
        // The pair must be COMPLETE and damage-free: a truncated read is a capture we cannot describe, and
        // uploading audio with a record that does not match it is worse than holding it.
        val text = runCatching { metadata.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        val node = runCatching { CanonicalJson.parse(text) }.getOrNull() ?: return null
        val record = node as? RecordNode.Obj ?: return null
        // The record must be VALID and must describe exactly THESE bytes and THIS capture, or the upload
        // would claim something the audio does not support.
        val valid = RecordValidator.validate(record) as? RecordValidator.Result.Ok ?: return null
        if (valid.record.captureId != captureId) return null
        if (valid.record.audio.byteCount != audio.length()) return null
        return ContributionUploadAssembly.Capture(record, audio)
    }

    private companion object {
        const val CAPTURES_DIR = "contributions"
        const val SUFFIX_WAV = ".recording.wav"
        const val SUFFIX_META = ".metadata.json"
        val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}
