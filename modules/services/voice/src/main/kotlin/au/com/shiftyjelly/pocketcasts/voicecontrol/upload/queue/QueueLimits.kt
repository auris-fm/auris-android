package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

// Compiled-ceiling limits for the bounded contribution queue (spec: fixed application/server
// limits; values are resource defaults, validated against ceilings at construction). Full queue
// NEVER stalls the capture callback: admission is denied instead.
data class QueueLimits(
    val maxQueueBytes: Long,
    val maxQueueRecords: Int,
    val maxClipMs: Long,
    val maxClipBytes: Long, // includes WAV header
    val maxMetadataBytes: Int,
    val maxQueueAgeSeconds: Long,
    val maxUploadAttempts: Int,
    // Spec voice-recording-uploads.md: finite resource defaults for the retry policy, validated against
    // compiled ceilings like every other limit.
    val retryInitialS: Long,
    val retryMaxS: Long,
) {
    init {
        require(maxQueueBytes in 1..MAX_QUEUE_BYTES_CEILING) { "max_queue_bytes outside ceiling" }
        require(maxQueueRecords in 1..MAX_QUEUE_RECORDS_CEILING) { "max_queue_records outside ceiling" }
        require(maxClipMs in 1..MAX_CLIP_MS_CEILING) { "max_clip_ms outside ceiling" }
        require(maxClipBytes in WavWriter.HEADER_BYTES + 1..MAX_CLIP_BYTES_CEILING) { "max_clip_bytes outside ceiling" }
        require(maxMetadataBytes in 1..MAX_METADATA_BYTES_CEILING) { "max_metadata_bytes outside ceiling" }
        require(maxQueueAgeSeconds >= 1) { "max_queue_age_s must be positive" }
        require(maxUploadAttempts >= 1) { "max_upload_attempts must be positive" }
        require(retryInitialS in 1..MAX_RETRY_INITIAL_S_CEILING) { "retry_initial_s outside ceiling" }
        require(retryMaxS in 1..MAX_RETRY_MAX_S_CEILING) { "retry_max_s outside ceiling" }
    }

    companion object {
        const val MAX_QUEUE_BYTES_CEILING = 512L * 1024 * 1024
        const val MAX_QUEUE_RECORDS_CEILING = 5_000
        const val MAX_CLIP_MS_CEILING = 120_000L
        const val MAX_CLIP_BYTES_CEILING = 64L * 1024 * 1024
        const val MAX_METADATA_BYTES_CEILING = 1 shl 20
        const val MAX_RETRY_INITIAL_S_CEILING = 3_600L
        const val MAX_RETRY_MAX_S_CEILING = 7L * 24 * 3_600

        /** Compiled ceiling for the canonical record bytes (spec: 1 MiB, NOT resource-tunable). */
        const val CANONICAL_MAX_BYTES = 1 shl 20

        /** Spec resource defaults (validated against the compiled ceilings above). */
        fun defaults(): QueueLimits = QueueLimits(
            maxQueueBytes = 64L * 1024 * 1024,
            maxQueueRecords = 200,
            maxClipMs = 30_000L,
            maxClipBytes = 2L * 1024 * 1024,
            maxMetadataBytes = 64 * 1024,
            maxQueueAgeSeconds = 7 * 24 * 3600L,
            maxUploadAttempts = 8,
            retryInitialS = 2,
            retryMaxS = 3_600,
        )
    }
}
