#pragma once

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

// Called from hook_aaudio.cpp / hook_opensles.cpp
void install_aaudio_hooks();
void install_opensles_hooks();

#ifdef __cplusplus
}
#endif
