// A compiled AUTHORITY for O_DIRECTORY (reviewer 87e2e450, round 4): the value comes from THIS
// translation unit's <fcntl.h> as compiled by the NDK for EACH ABI, so it is correct by
// construction for whichever architecture the library was built for - no per-ABI table, no
// inference from device capability lists.
#include <fcntl.h>
#include <jni.h>

extern "C" JNIEXPORT jint JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_upload_queue_DirectoryFlagJni_nativeODirectory(JNIEnv*, jobject) {
    return O_DIRECTORY;
}
