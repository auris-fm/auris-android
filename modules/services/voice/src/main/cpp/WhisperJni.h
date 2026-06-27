// WhisperJni.h
#pragma once

#include <jni.h>

extern "C" {

JNIEXPORT jboolean JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_asr_WhisperNative_init(
    JNIEnv* env,
    jclass /* clazz */,
    jstring model_path,
    jboolean use_gpu
);

JNIEXPORT jstring JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_asr_WhisperNative_transcribe(
    JNIEnv* env,
    jclass /* clazz */,
    jstring model_path,
    jshortArray pcm_data,
    jint sample_rate,
    jboolean use_gpu
);

JNIEXPORT void JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_asr_WhisperNative_setPipelineCachePath(
    JNIEnv* env,
    jclass /* clazz */,
    jstring cache_path
);

JNIEXPORT void JNICALL
Java_au_com_shiftyjelly_pocketcasts_voicecontrol_asr_WhisperNative_freeModel(
    JNIEnv* env,
    jclass /* clazz */
);

} // extern "C"
