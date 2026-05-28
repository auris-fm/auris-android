// LmJni.h
#pragma once

#include <jni.h>

extern "C" {

JNIEXPORT jboolean JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_intent_LmNative_prewarmModel(
    JNIEnv* env,
    jclass /* clazz */,
    jstring model_path,
    jstring fixed_prefix
);

JNIEXPORT jstring JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_intent_LmNative_parseIntent(
    JNIEnv* env,
    jclass /* clazz */,
    jstring model_path,
    jstring suffix
);

} // extern "C"
