// WhisperJni.h
#pragma once

#include <jni.h>

extern "C" {

JNIEXPORT jstring JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_asr_WhisperNative_transcribe(
    JNIEnv* env,
    jclass /* clazz */,
    jstring model_path,
    jshortArray pcm_data,
    jint sample_rate
);

} // extern "C"
