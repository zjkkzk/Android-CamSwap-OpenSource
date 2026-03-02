#pragma once

#include <jni.h>
#include <cstdint>

#ifdef __cplusplus
extern "C" {
#endif

// Initialize the JNI bridge — must be called with the correct classloader context.
// Pass the NativeAudioHook class from Java to avoid classloader issues in Xposed.
void pcm_bridge_init(JNIEnv* env, jclass hookClass);

// Fill buffer with fake PCM data from Java side.
// Returns number of bytes filled, or -1 if hook is not enabled (passthrough).
int fill_fake_pcm(void* buffer, int sizeBytes, int sampleRate, int channels);

// Get the JavaVM pointer (defined in native_audio_hook.cpp)
JavaVM* get_java_vm();

#ifdef __cplusplus
}
#endif
