package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import timber.log.Timber

/**
 * One attempt to obtain the CLOUD fingerprint reference for one episode, in one preparation
 * generation.
 *
 * This object owns the whole cloud-reference lifecycle so the rules live in one place instead of
 * being spread across the manager's transcript path:
 *
 * - it claims the attempt (so two preparations cannot both fetch),
 * - it performs the I/O and returns an [Outcome] WITHOUT publishing anything,
 * - the caller publishes that outcome only if this generation is still current.
 *
 * That last rule is why the I/O result is a value rather than a side effect: a fetch started for
 * episode A can finish after a stop or a switch to B, and must not install A's matcher or state
 * into B. The manager checks the generation before applying an outcome; a discarded acquisition
 * simply drops its result.
 *
 * Definitive answers (403/404) are NOT owned here. They outlive a single generation — going A → B
 * → A in one process must not ask again — so the manager keeps them per episode, under its lock.
 */
internal class CloudReferenceAcquisition(
    val generation: Long,
    val episodeUuid: String,
    private val podcastUuid: String,
    private val baseUrl: String,
    private val fetcher: CloudFingerprintReferenceFetcher,
    private val store: CloudReferenceStore,
) {
    /** Where this attempt stands. Only the manager publishes it, and only for a live generation. */
    enum class Status { NotTried, Fetching, Ready, Failed }

    @Volatile
    var status: Status = Status.NotTried
        private set

    /** The result of the I/O, with nothing published. */
    sealed interface Outcome {
        /** A usable matcher, freshly fetched, revalidated, or restored from the store. */
        data class Ready(val matcher: CloudReferenceMatcher) : Outcome

        /** The server definitively has no reference for this episode (403/404). */
        data object DefinitiveMiss : Outcome

        /** Transient: offline, timeout, 5xx, unusable token. The next play or resume may retry. */
        data object Retryable : Outcome
    }

    /** Claims the attempt. Returns false when one is already in flight or already succeeded. */
    fun claim(): Boolean {
        if (status == Status.Fetching || status == Status.Ready) return false
        status = Status.Fetching
        return true
    }

    /**
     * Performs the fetch and returns what happened. Publishes NOTHING: the caller decides whether
     * this generation is still the current one before applying the outcome.
     *
     * - Fresh 200: persist payload + new ETag, use it.
     * - 304 against the stored ETag: keep and decode the stored payload; if that payload is missing
     *   or corrupt, one unconditional refetch, classified exactly like a first fetch.
     * - 403/404: definitive, for this episode, for this process.
     * - Anything else: retryable. A stored reference that still decodes is used regardless, because
     *   a failed revalidation must never drop alignment the store already supports.
     */
    suspend fun acquire(): Outcome {
        val stored = store.load(episodeUuid)
        val fetched = fetcher.fetchReference(baseUrl, episodeUuid, podcastUuid, stored?.etag)
        val outcome = when (fetched) {
            is CloudFingerprintReferenceFetcher.Fetch.Fresh -> {
                store.save(episodeUuid, fetched.payload, fetched.etag)
                Timber.d("FingerprintTimingManager: cloud reference loaded (${fetched.matcher.count} checkpoints) for $episodeUuid")
                Outcome.Ready(fetched.matcher)
            }

            is CloudFingerprintReferenceFetcher.Fetch.NotModified -> {
                val kept = stored?.let { fetcher.buildMatcher(it.payload) }
                if (kept != null) {
                    Timber.d("FingerprintTimingManager: cloud reference revalidated (304) for $episodeUuid")
                    Outcome.Ready(kept)
                } else {
                    // 304 but the stored payload is missing or corrupt: one unconditional refetch.
                    val refetched = fetcher.fetchReference(baseUrl, episodeUuid, podcastUuid, null)
                    when {
                        refetched is CloudFingerprintReferenceFetcher.Fetch.Fresh -> {
                            store.save(episodeUuid, refetched.payload, refetched.etag)
                            Timber.d("FingerprintTimingManager: cloud reference refetched (${refetched.matcher.count} checkpoints) for $episodeUuid")
                            Outcome.Ready(refetched.matcher)
                        }

                        // The refetch gets the SAME classification as a first fetch.
                        refetched is CloudFingerprintReferenceFetcher.Fetch.NotAvailable -> Outcome.DefinitiveMiss

                        else -> Outcome.Retryable
                    }
                }
            }

            CloudFingerprintReferenceFetcher.Fetch.NotAvailable -> Outcome.DefinitiveMiss

            CloudFingerprintReferenceFetcher.Fetch.Unavailable -> {
                val kept = stored?.let { fetcher.buildMatcher(it.payload) }
                if (kept != null) {
                    Timber.d("FingerprintTimingManager: revalidation failed; keeping stored cloud reference for $episodeUuid")
                    Outcome.Ready(kept)
                } else {
                    Outcome.Retryable
                }
            }
        }
        status = when (outcome) {
            is Outcome.Ready -> Status.Ready

            // A definitive answer leaves this attempt finished; the manager records it per episode.
            Outcome.DefinitiveMiss -> Status.Failed

            Outcome.Retryable -> Status.Failed
        }
        if (outcome !is Outcome.Ready) {
            val how = if (outcome is Outcome.DefinitiveMiss) "definitive 403/404" else "retryable"
            Timber.d("FingerprintTimingManager: cloud reference unavailable for $episodeUuid ($how)")
        }
        return outcome
    }
}
