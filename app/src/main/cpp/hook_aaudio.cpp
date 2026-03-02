#include "hook_aaudio.h"
#include "pcm_bridge.h"
#include <android/log.h>
#include <dlfcn.h>
#include <cstring>
#include <unordered_map>
#include <mutex>
#include "dobby.h"

#define LOG_TAG "CS-AAudioHook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ============================================================
// AAudio type definitions (from aaudio/AAudio.h)
// ============================================================

typedef int32_t aaudio_result_t;
typedef int32_t aaudio_direction_t;
typedef int32_t aaudio_format_t;

#define AAUDIO_DIRECTION_INPUT 1
#define AAUDIO_FORMAT_PCM_I16 1
#define AAUDIO_FORMAT_PCM_FLOAT 2
#define AAUDIO_OK 0

typedef struct AAudioStreamStruct AAudioStream;
typedef struct AAudioStreamBuilderStruct AAudioStreamBuilder;

typedef int32_t (*AAudioStream_dataCallback)(
    AAudioStream* stream,
    void* userData,
    void* audioData,
    int32_t numFrames);

// ============================================================
// Original function pointers
// ============================================================

typedef aaudio_result_t (*fn_AAudioStream_read)(
    AAudioStream* stream, void* buffer, int32_t numFrames, int64_t timeoutNanos);

typedef void (*fn_AAudioStreamBuilder_setDataCallback)(
    AAudioStreamBuilder* builder, AAudioStream_dataCallback callback, void* userData);

typedef aaudio_result_t (*fn_AAudioStreamBuilder_openStream)(
    AAudioStreamBuilder* builder, AAudioStream** stream);

typedef aaudio_direction_t (*fn_AAudioStream_getDirection)(AAudioStream* stream);
typedef int32_t (*fn_AAudioStream_getSampleRate)(AAudioStream* stream);
typedef int32_t (*fn_AAudioStream_getChannelCount)(AAudioStream* stream);
typedef aaudio_format_t (*fn_AAudioStream_getFormat)(AAudioStream* stream);
typedef void (*fn_AAudioStreamBuilder_setDirection)(AAudioStreamBuilder* builder, aaudio_direction_t direction);

typedef aaudio_result_t (*fn_AAudioStream_close)(AAudioStream* stream);

static fn_AAudioStream_read orig_AAudioStream_read = nullptr;
static fn_AAudioStreamBuilder_setDataCallback orig_setDataCallback = nullptr;
static fn_AAudioStreamBuilder_openStream orig_openStream = nullptr;
static fn_AAudioStream_close orig_AAudioStream_close = nullptr;

// Query functions — resolved via dlsym, not hooked
static fn_AAudioStream_getDirection fn_getDirection = nullptr;
static fn_AAudioStream_getSampleRate fn_getSampleRate = nullptr;
static fn_AAudioStream_getChannelCount fn_getChannelCount = nullptr;
static fn_AAudioStream_getFormat fn_getFormat = nullptr;

// ============================================================
// Stream tracking
// ============================================================

struct StreamInfo {
    bool isInput;
    int32_t sampleRate;
    int32_t channelCount;
    aaudio_format_t format;
};

static std::mutex g_stream_mutex;
static std::unordered_map<AAudioStream*, StreamInfo> g_stream_map;

// Builder → callback mapping for callback-mode hooking
struct BuilderCallbackInfo {
    AAudioStream_dataCallback originalCallback;
    void* originalUserData;
};

static std::mutex g_builder_mutex;
static std::unordered_map<AAudioStreamBuilder*, BuilderCallbackInfo> g_builder_cb_map;
static std::unordered_map<AAudioStream*, BuilderCallbackInfo> g_stream_cb_map;

// ============================================================
// Helper: check if stream is input (recording)
// ============================================================

static bool is_input_stream(AAudioStream* stream) {
    std::lock_guard<std::mutex> lock(g_stream_mutex);
    auto it = g_stream_map.find(stream);
    if (it != g_stream_map.end()) {
        return it->second.isInput;
    }
    return false;
}

static StreamInfo get_stream_info(AAudioStream* stream) {
    std::lock_guard<std::mutex> lock(g_stream_mutex);
    auto it = g_stream_map.find(stream);
    if (it != g_stream_map.end()) {
        return it->second;
    }
    // Fallback: query directly
    StreamInfo info{};
    if (fn_getDirection) info.isInput = (fn_getDirection(stream) == AAUDIO_DIRECTION_INPUT);
    if (fn_getSampleRate) info.sampleRate = fn_getSampleRate(stream);
    if (fn_getChannelCount) info.channelCount = fn_getChannelCount(stream);
    if (fn_getFormat) info.format = fn_getFormat(stream);
    // 安全回退：防止查询函数返回 0 导致 sizeBytes=0 跳过替换
    if (info.sampleRate <= 0) info.sampleRate = 44100;
    if (info.channelCount <= 0) info.channelCount = 1;
    if (info.format == 0) info.format = AAUDIO_FORMAT_PCM_I16;
    // 缓存查询结果，避免重复查询
    if (info.isInput) {
        g_stream_map[stream] = info;
    }
    return info;
}

// ============================================================
// Hooked: AAudioStream_read (blocking-mode recording)
// ============================================================

static aaudio_result_t hooked_AAudioStream_read(
    AAudioStream* stream, void* buffer, int32_t numFrames, int64_t timeoutNanos) {

    aaudio_result_t result = orig_AAudioStream_read(stream, buffer, numFrames, timeoutNanos);

    if (result <= 0) return result;

    // Only process input streams
    StreamInfo info = get_stream_info(stream);
    if (!info.isInput) return result;

    int bytesPerFrame = info.channelCount;
    if (info.format == AAUDIO_FORMAT_PCM_I16) {
        bytesPerFrame *= 2;
    } else if (info.format == AAUDIO_FORMAT_PCM_FLOAT) {
        bytesPerFrame *= 4;
    } else {
        bytesPerFrame *= 2; // default to 16-bit
    }

    int sizeBytes = result * bytesPerFrame;

    // For float format, we need a temporary int16 buffer then convert
    if (info.format == AAUDIO_FORMAT_PCM_FLOAT) {
        int i16Size = result * info.channelCount * 2;
        int8_t* tempBuf = new int8_t[i16Size];

        int rc = fill_fake_pcm(tempBuf, i16Size, info.sampleRate, info.channelCount);
        if (rc >= 0) {
            // Convert int16 → float
            int16_t* src = reinterpret_cast<int16_t*>(tempBuf);
            float* dst = static_cast<float*>(buffer);
            int sampleCount = result * info.channelCount;
            for (int i = 0; i < sampleCount; i++) {
                dst[i] = src[i] / 32768.0f;
            }
        }
        delete[] tempBuf;
    } else {
        // PCM_I16 — direct replacement
        fill_fake_pcm(buffer, sizeBytes, info.sampleRate, info.channelCount);
    }

    return result;
}

// ============================================================
// Wrapped data callback for callback-mode recording
// ============================================================

static int32_t wrapped_data_callback(
    AAudioStream* stream, void* userData, void* audioData, int32_t numFrames) {

    // Find original callback
    BuilderCallbackInfo cbInfo{};
    {
        std::lock_guard<std::mutex> lock(g_builder_mutex);
        auto it = g_stream_cb_map.find(stream);
        if (it != g_stream_cb_map.end()) {
            cbInfo = it->second;
        }
    }

    // Call original callback first
    int32_t result = numFrames;
    if (cbInfo.originalCallback) {
        result = cbInfo.originalCallback(stream, cbInfo.originalUserData, audioData, numFrames);
    }

    // Only process input streams
    StreamInfo info = get_stream_info(stream);
    if (!info.isInput) return result;

    int framesProcessed = (result > 0) ? result : numFrames;

    if (info.format == AAUDIO_FORMAT_PCM_FLOAT) {
        int i16Size = framesProcessed * info.channelCount * 2;
        int8_t* tempBuf = new int8_t[i16Size];

        int rc = fill_fake_pcm(tempBuf, i16Size, info.sampleRate, info.channelCount);
        if (rc >= 0) {
            int16_t* src = reinterpret_cast<int16_t*>(tempBuf);
            float* dst = static_cast<float*>(audioData);
            int sampleCount = framesProcessed * info.channelCount;
            for (int i = 0; i < sampleCount; i++) {
                dst[i] = src[i] / 32768.0f;
            }
        }
        delete[] tempBuf;
    } else {
        int sizeBytes = framesProcessed * info.channelCount * 2;
        fill_fake_pcm(audioData, sizeBytes, info.sampleRate, info.channelCount);
    }

    return result;
}

// ============================================================
// Hooked: AAudioStreamBuilder_setDataCallback
// ============================================================

static void hooked_setDataCallback(
    AAudioStreamBuilder* builder, AAudioStream_dataCallback callback, void* userData) {

    // Store original callback info keyed by builder
    {
        std::lock_guard<std::mutex> lock(g_builder_mutex);
        g_builder_cb_map[builder] = {callback, userData};
    }

    // Register our wrapper callback instead
    orig_setDataCallback(builder, wrapped_data_callback, nullptr);
}

// ============================================================
// Hooked: AAudioStreamBuilder_openStream
// ============================================================

static aaudio_result_t hooked_openStream(
    AAudioStreamBuilder* builder, AAudioStream** stream) {

    aaudio_result_t result = orig_openStream(builder, stream);

    if (result != AAUDIO_OK || !stream || !*stream) return result;

    AAudioStream* s = *stream;

    // Query stream properties
    StreamInfo info{};
    if (fn_getDirection) info.isInput = (fn_getDirection(s) == AAUDIO_DIRECTION_INPUT);
    if (fn_getSampleRate) info.sampleRate = fn_getSampleRate(s);
    if (fn_getChannelCount) info.channelCount = fn_getChannelCount(s);
    if (fn_getFormat) info.format = fn_getFormat(s);

    {
        std::lock_guard<std::mutex> lock(g_stream_mutex);
        g_stream_map[s] = info;
    }

    // Transfer builder callback info to stream
    {
        std::lock_guard<std::mutex> lock(g_builder_mutex);
        auto it = g_builder_cb_map.find(builder);
        if (it != g_builder_cb_map.end()) {
            g_stream_cb_map[s] = it->second;
            g_builder_cb_map.erase(it);
        }
    }

    if (info.isInput) {
        LOGI("Input stream opened: sampleRate=%d channels=%d format=%d",
             info.sampleRate, info.channelCount, info.format);
    }

    return result;
}

// ============================================================
// Hooked: AAudioStream_close — 清理 stream map 防止内存泄漏
// ============================================================

static aaudio_result_t hooked_AAudioStream_close(AAudioStream* stream) {
    {
        std::lock_guard<std::mutex> lock(g_stream_mutex);
        g_stream_map.erase(stream);
    }
    {
        std::lock_guard<std::mutex> lock(g_builder_mutex);
        g_stream_cb_map.erase(stream);
    }
    return orig_AAudioStream_close(stream);
}

// ============================================================
// Install hooks
// ============================================================

void install_aaudio_hooks() {
    void* handle = dlopen("libaaudio.so", RTLD_NOW);
    if (!handle) {
        LOGI("libaaudio.so not available — skipping AAudio hooks");
        return;
    }

    // Resolve query functions (not hooked)
    fn_getDirection = reinterpret_cast<fn_AAudioStream_getDirection>(
        dlsym(handle, "AAudioStream_getDirection"));
    fn_getSampleRate = reinterpret_cast<fn_AAudioStream_getSampleRate>(
        dlsym(handle, "AAudioStream_getSampleRate"));
    fn_getChannelCount = reinterpret_cast<fn_AAudioStream_getChannelCount>(
        dlsym(handle, "AAudioStream_getChannelCount"));
    fn_getFormat = reinterpret_cast<fn_AAudioStream_getFormat>(
        dlsym(handle, "AAudioStream_getFormat"));

    // Hook AAudioStream_read
    void* sym_read = dlsym(handle, "AAudioStream_read");
    if (sym_read) {
        if (DobbyHook(sym_read, reinterpret_cast<dobby_dummy_func_t>(hooked_AAudioStream_read),
                       reinterpret_cast<dobby_dummy_func_t*>(&orig_AAudioStream_read)) == 0) {
            LOGI("Hooked AAudioStream_read");
        } else {
            LOGE("Failed to hook AAudioStream_read");
        }
    }

    // Hook AAudioStreamBuilder_setDataCallback
    void* sym_setCb = dlsym(handle, "AAudioStreamBuilder_setDataCallback");
    if (sym_setCb) {
        if (DobbyHook(sym_setCb, reinterpret_cast<dobby_dummy_func_t>(hooked_setDataCallback),
                       reinterpret_cast<dobby_dummy_func_t*>(&orig_setDataCallback)) == 0) {
            LOGI("Hooked AAudioStreamBuilder_setDataCallback");
        } else {
            LOGE("Failed to hook AAudioStreamBuilder_setDataCallback");
        }
    }

    // Hook AAudioStreamBuilder_openStream
    void* sym_open = dlsym(handle, "AAudioStreamBuilder_openStream");
    if (sym_open) {
        if (DobbyHook(sym_open, reinterpret_cast<dobby_dummy_func_t>(hooked_openStream),
                       reinterpret_cast<dobby_dummy_func_t*>(&orig_openStream)) == 0) {
            LOGI("Hooked AAudioStreamBuilder_openStream");
        } else {
            LOGE("Failed to hook AAudioStreamBuilder_openStream");
        }
    }

    // Hook AAudioStream_close — 清理 stream map 防止内存泄漏
    void* sym_close = dlsym(handle, "AAudioStream_close");
    if (sym_close) {
        if (DobbyHook(sym_close, reinterpret_cast<dobby_dummy_func_t>(hooked_AAudioStream_close),
                       reinterpret_cast<dobby_dummy_func_t*>(&orig_AAudioStream_close)) == 0) {
            LOGI("Hooked AAudioStream_close");
        } else {
            LOGE("Failed to hook AAudioStream_close");
        }
    }

    LOGI("AAudio hooks installation complete");
}
