// Host-build stand-in for <android/log.h>.
// NativeVadProcessor.cpp logs through __android_log_print; a no-op keeps the production source
// untouched while letting the frame loop run on the host.
#pragma once
#ifdef __cplusplus
extern "C" {
#endif
#define ANDROID_LOG_INFO 4
static inline int __android_log_print(int prio, const char* tag, const char* fmt, ...) {
    (void)prio; (void)tag; (void)fmt;
    return 0;
}
#ifdef __cplusplus
}
#endif
