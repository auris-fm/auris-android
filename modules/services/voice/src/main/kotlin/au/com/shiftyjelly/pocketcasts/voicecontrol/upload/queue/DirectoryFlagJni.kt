package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

/**
 * Access to the COMPILED O_DIRECTORY value from the NDK translation unit
 * (DirectoryFlagJni.cpp), which reads it from <fcntl.h> as compiled for each ABI. This is a
 * native CONSTANT AUTHORITY, not an inference: the flag the library returns is the one the
 * platform headers gave the code that will issue the sibling syscalls.
 *
 * An absent native library (JVM unit tests, or a build without the .so) yields null - the
 * caller must treat that as UNSUPPORTED, never fall back to a guessed value.
 */
object DirectoryFlagJni {
    @Volatile
    private var loadAttempted: Boolean = false

    private fun ensureLoaded() {
        if (!loadAttempted) {
            synchronized(this) {
                if (!loadAttempted) {
                    runCatching { System.loadLibrary("pocketcasts_voice_flags") }
                    loadAttempted = true
                }
            }
        }
    }

    /** Bound to DirectoryFlagJni.cpp; only valid once the native library has loaded. */
    external fun nativeODirectory(): Int

    fun oDirectoryOrNull(): Int? {
        ensureLoaded()
        return runCatching { nativeODirectory() }.getOrNull()
    }
}
