package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

/**
 * The upload network constraint. With the remote sharing policy removed, the compiled default
 * (ANY) is the only value production uses; the closed set stays for the platform-scheduler
 * mapping and tests.
 */
enum class UploadNetwork {
    /** Any connected network. */
    ANY,

    /** Unmetered networks only. */
    UNMETERED,
    ;

    companion object {
        /** The compiled default — no remote upload_network input remains. */
        fun compiledDefault(): UploadNetwork = ANY
    }
}
