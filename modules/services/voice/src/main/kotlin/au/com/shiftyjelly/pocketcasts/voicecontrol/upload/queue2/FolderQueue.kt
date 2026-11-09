package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue2

import java.io.File
import java.util.UUID
import timber.log.Timber

/**
 * The queue is a directory of recordings, one folder each.
 *
 * A recording is two files - `audio.wav` and `metadata.json` - written under a temporary folder name
 * and published with a single rename. That rename is the only atomic step, so a crash either leaves
 * a complete recording or leaves a temporary folder that startup deletes. There is no index, ledger,
 * lock or recovery pass: the directories ARE the state.
 *
 * Losing a recording is acceptable for this feature. What is not acceptable is the queue stalling or
 * the app crashing, so every failure path drops the one recording involved and carries on.
 */
class FolderQueue(
    private val root: File,
    private val maxRecordings: Int = DEFAULT_MAX_RECORDINGS,
    private val log: (String) -> Unit = { Timber.tag(TAG).i(it) },
) {
    /** Directory holding the published recording folders. */
    private val recordingsDir = File(root, RECORDINGS)
    private val tempDir = File(root, TEMP)

    /** A published recording, as the queue sees it. */
    data class Recording(
        val captureId: String,
        val folder: File,
        val audio: File,
        val metadata: File,
        val publishedAtMs: Long,
    ) {
        fun delete(): Boolean = folder.deleteRecursively()
    }

    init {
        recordingsDir.mkdirs()
        tempDir.mkdirs()
    }

    /**
     * Delete anything left under a temporary name.
     *
     * A temporary folder is by definition an interrupted write, so the recording in it was never
     * published and cannot be resumed. Called at startup; safe to call more than once.
     */
    fun discardIncompleteWrites() {
        val stale = tempDir.listFiles() ?: return
        for (dir in stale) {
            if (dir.deleteRecursively()) {
                log("upload queue: discarded an interrupted write")
            }
        }
    }

    /** Published recordings, oldest first by publish time then id, so the order is total. */
    fun recordings(): List<Recording> {
        val dirs = recordingsDir.listFiles() ?: return emptyList()
        return dirs.mapNotNull { toRecording(it) }
            .sortedWith(compareBy({ it.publishedAtMs }, { it.captureId }))
    }

    fun count(): Int = recordingsDir.listFiles()?.count { isComplete(it) } ?: 0

    /**
     * Publish one recording: write both files under a temporary name, then rename.
     *
     * Returns the published recording, or null when it was dropped. A drop is not an error the
     * caller has to handle - the recording is gone and the next one proceeds - but it is always
     * logged with a running count, because a queue that is silently full looks exactly like a queue
     * that is working.
     */
    fun publish(
        captureId: String,
        audioWriter: (File) -> Unit,
        metadataBytes: ByteArray,
        nowMs: Long = System.currentTimeMillis(),
    ): Recording? {
        if (count() >= maxRecordings) {
            recordDrop("queue full")
            return null
        }
        val temp = File(tempDir, "tmp-${UUID.randomUUID()}")
        val published = File(recordingsDir, captureId)
        return try {
            if (!temp.mkdirs()) {
                recordDrop("could not create a working folder")
                return null
            }
            audioWriter(File(temp, AUDIO))
            val metaFile = File(temp, METADATA)
            metaFile.writeBytes(metadataBytes)
            // Stamp the publish time on disk so oldest-first survives a restart.
            metaFile.setLastModified(nowMs)
            // One rename publishes it. Everything before this point is invisible to the uploader.
            if (!temp.renameTo(published)) {
                recordDrop("could not publish")
                temp.deleteRecursively()
                return null
            }
            Recording(captureId, published, File(published, AUDIO), File(published, METADATA), nowMs)
        } catch (t: Throwable) {
            // A failed write drops this one recording. It must never propagate and stop collection.
            recordDrop("write failed: ${t.javaClass.simpleName}")
            temp.deleteRecursively()
            null
        }
    }

    /** Delete one recording and log why. Every deletion path goes through here. */
    fun delete(recording: Recording, reason: String) {
        if (recording.delete()) {
            log("upload queue: deleted a recording ($reason)")
        } else {
            log("upload queue: could not delete a recording ($reason)")
        }
    }

    /** Delete every recording: sharing was turned off, or the account changed. */
    fun deleteAll(reason: String) {
        val all = recordingsDir.listFiles() ?: return
        var n = 0
        for (dir in all) if (dir.deleteRecursively()) n++
        if (n > 0) log("upload queue: deleted $n recordings ($reason)")
    }

    /**
     * Drop recordings that are past the retention bound without sending them. Returns how many went.
     */
    fun deleteExpired(nowMs: Long, maxAgeMs: Long = MAX_AGE_MS): Int {
        var n = 0
        for (recording in recordings()) {
            if (nowMs - recording.publishedAtMs > maxAgeMs) {
                delete(recording, "older than the retention bound")
                n++
            }
        }
        return n
    }

    private fun recordDrop(why: String) {
        droppedCount++
        log("upload queue: dropped a recording ($why); dropped $droppedCount this run")
    }

    private fun toRecording(dir: File): Recording? {
        val audio = File(dir, AUDIO)
        val metadata = File(dir, METADATA)
        if (!audio.isFile || !metadata.isFile) {
            // Never published, or damaged: the spec deletes a recording that cannot be read.
            dir.deleteRecursively()
            log("upload queue: deleted an unreadable recording")
            return null
        }
        // Publish time is the metadata file's mtime, written inside the temporary folder and
        // carried across the rename. It is the best available record of when this was queued, and
        // it survives a restart because it lives on disk with the recording.
        return Recording(dir.name, dir, audio, metadata, metadata.lastModified())
    }

    private fun isComplete(dir: File): Boolean = File(dir, AUDIO).isFile && File(dir, METADATA).isFile

    /** Drops since construction. Reported through the log; not persisted. */
    var droppedCount: Int = 0
        private set

    companion object {
        private const val TAG = "VoiceUploadQueue2"
        const val RECORDINGS = "recordings"
        const val TEMP = "temp"
        const val AUDIO = "audio.wav"
        const val METADATA = "metadata.json"
        const val DEFAULT_MAX_RECORDINGS = 32
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
