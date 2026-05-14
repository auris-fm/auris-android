// LmJni.h
#pragma once

#include <jni.h>

extern "C" {

JNIEXPORT jstring JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_intent_LmNative_parseIntent(
    JNIEnv* env,
    jclass /* clazz */,
    jstring model_path,
    jstring prompt
);

} // extern "C"
