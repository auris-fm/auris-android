package au.com.shiftyjelly.pocketcasts.crashlogging

import java.io.File

/**
 * Queues an encrypted log and reports whether it was accepted.
 *
 * The SDK's enqueue returns Unit, so a caller cannot tell a queued log from a
 * refused one. Anything describing the log elsewhere -- the crash-data provider's
 * `uuid` extra -- needs that answer, or it points triage at a log that will never
 * exist. Depending on this capability rather than on a concrete wrapper type means
 * a future guard cannot silently reintroduce the dangling reference.
 */
fun interface LogQueueing {
    fun tryEnqueueLog(uuid: String, file: File, shouldUploadImmediately: Boolean): Boolean
}
