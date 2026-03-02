#include "hook_opensles.h"
#include "pcm_bridge.h"
#include <android/log.h>
#include <dlfcn.h>
#include <cstring>
#include <unordered_map>
#include <mutex>
#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>
#include "dobby.h"

#define LOG_TAG "CS-OpenSLHook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ============================================================
// Tracking structures
// ============================================================

struct RecorderCallbackInfo {
    slAndroidSimpleBufferQueueCallback originalCallback;
    void* originalContext;
    // Last enqueued buffer info
    void* lastBuffer;
    SLuint32 lastBufferSize;
    // Audio params
    SLuint32 sampleRate;   // milliHz (e.g. 44100000)
    SLuint32 channels;
};

static std::mutex g_sl_mutex;
static std::unordered_map<SLAndroidSimpleBufferQueueItf, RecorderCallbackInfo> g_recorder_map;

// Track which buffer queue interfaces belong to recorders (not players)
static std::unordered_map<SLAndroidSimpleBufferQueueItf, bool> g_is_recorder_bq;

// Track recorder SLObjectItf instances (from CreateAudioRecorder) and their audio params
struct RecorderObjectInfo {
    SLuint32 sampleRate;   // milliHz
    SLuint32 channels;
};
static std::unordered_map<SLObjectItf, RecorderObjectInfo> g_recorder_objects;

// ============================================================
// Original function pointers (vtable entries)
// ============================================================

typedef SLresult (*fn_RegisterCallback)(
    SLAndroidSimpleBufferQueueItf self,
    slAndroidSimpleBufferQueueCallback callback,
    void* pContext);

typedef SLresult (*fn_Enqueue)(
    SLAndroidSimpleBufferQueueItf self,
    const void* pBuffer,
    SLuint32 size);

static fn_RegisterCallback orig_RegisterCallback = nullptr;
static fn_Enqueue orig_Enqueue = nullptr;

// Track whether we've already hooked vtable functions
static bool g_vtable_hooked = false;

// Original slCreateEngine
typedef SLresult (*fn_slCreateEngine)(
    SLObjectItf* pEngine,
    SLuint32 numOptions,
    const SLEngineOption* pEngineOptions,
    SLuint32 numInterfaces,
    const SLInterfaceID* pInterfaceIds,
    const SLboolean* pInterfaceRequired);

static fn_slCreateEngine orig_slCreateEngine = nullptr;

// Original function pointers for engine/object vtable hooks
typedef SLresult (*fn_CreateAudioRecorder)(
    SLEngineItf self,
    SLObjectItf* pRecorder,
    SLDataSource* pAudioSrc,
    SLDataSink* pAudioSnk,
    SLuint32 numInterfaces,
    const SLInterfaceID* pInterfaceIds,
    const SLboolean* pInterfaceRequired);

typedef SLresult (*fn_GetInterface)(
    SLObjectItf self,
    const SLInterfaceID iid,
    void* pInterface);

typedef void (*fn_Destroy)(SLObjectItf self);

static fn_CreateAudioRecorder orig_CreateAudioRecorder = nullptr;
static fn_GetInterface orig_GetInterface = nullptr;
static fn_Destroy orig_Destroy = nullptr;
static bool g_engine_vtable_hooked = false;
static bool g_object_vtable_hooked = false;

// ============================================================
// Wrapped buffer queue callback
// ============================================================

static void wrapped_bq_callback(SLAndroidSimpleBufferQueueItf bq, void* context) {
    RecorderCallbackInfo info{};
    {
        std::lock_guard<std::mutex> lock(g_sl_mutex);
        auto it = g_recorder_map.find(bq);
        if (it != g_recorder_map.end()) {
            info = it->second;
        }
    }

    // 先覆盖缓冲区中的真实麦克风数据，再调用原始回调
    // 这样原始回调读取到的就是假数据，避免时序问题
    if (info.lastBuffer && info.lastBufferSize > 0) {
        int sampleRate = info.sampleRate / 1000; // milliHz → Hz
        if (sampleRate <= 0) sampleRate = 44100;
        int channels = info.channels;
        if (channels <= 0) channels = 1;

        fill_fake_pcm(info.lastBuffer, info.lastBufferSize, sampleRate, channels);
    }

    // Call original callback — it will see fake data and typically re-enqueue next buffer
    if (info.originalCallback) {
        info.originalCallback(bq, info.originalContext);
    }
}

// ============================================================
// Hooked: RegisterCallback
// ============================================================

static SLresult hooked_RegisterCallback(
    SLAndroidSimpleBufferQueueItf self,
    slAndroidSimpleBufferQueueCallback callback,
    void* pContext) {

    // Check if this is a recorder buffer queue
    bool isRecorder = false;
    {
        std::lock_guard<std::mutex> lock(g_sl_mutex);
        auto it = g_is_recorder_bq.find(self);
        if (it != g_is_recorder_bq.end()) {
            isRecorder = it->second;
        }
    }

    if (isRecorder) {
        LOGI("RegisterCallback on recorder buffer queue — wrapping callback");

        {
            std::lock_guard<std::mutex> lock(g_sl_mutex);
            auto& info = g_recorder_map[self];
            info.originalCallback = callback;
            info.originalContext = pContext;
        }

        return orig_RegisterCallback(self, wrapped_bq_callback, nullptr);
    }

    // Not a recorder — passthrough
    return orig_RegisterCallback(self, callback, pContext);
}

// ============================================================
// Hooked: Enqueue — track buffer address and size
// ============================================================

static SLresult hooked_Enqueue(
    SLAndroidSimpleBufferQueueItf self,
    const void* pBuffer,
    SLuint32 size) {

    {
        std::lock_guard<std::mutex> lock(g_sl_mutex);
        auto it = g_recorder_map.find(self);
        if (it != g_recorder_map.end()) {
            it->second.lastBuffer = const_cast<void*>(pBuffer);
            it->second.lastBufferSize = size;
        }
    }

    return orig_Enqueue(self, pBuffer, size);
}

// ============================================================
// Hook vtable entries using Dobby inline hook
// ============================================================

static bool try_hook_vtable(SLAndroidSimpleBufferQueueItf bq) {
    if (g_vtable_hooked || !bq) return false;

    // The OpenSL ES interface is a pointer to a vtable struct.
    // SLAndroidSimpleBufferQueueItf is actually:
    //   struct SLAndroidSimpleBufferQueueItf_ {
    //       SLresult (*RegisterCallback)(...);
    //       SLresult (*Clear)(...);
    //       SLresult (*Enqueue)(...);
    //       SLresult (*GetState)(...);
    //   };
    // And SLAndroidSimpleBufferQueueItf = SLAndroidSimpleBufferQueueItf_**

    // Dereference to get vtable pointer
    void** vtable = const_cast<void**>(reinterpret_cast<const void* const*>(*bq));
    if (!vtable) return false;

    // vtable[0] = RegisterCallback
    // vtable[2] = Enqueue (index depends on interface version)
    // But for SLAndroidSimpleBufferQueueItf:
    //   [0] = RegisterCallback
    //   [1] = Clear
    //   [2] = Enqueue
    //   [3] = GetState

    void* registerCb_addr = vtable[0];
    void* enqueue_addr = vtable[2];

    if (registerCb_addr) {
        if (DobbyHook(registerCb_addr, reinterpret_cast<dobby_dummy_func_t>(hooked_RegisterCallback),
                       reinterpret_cast<dobby_dummy_func_t*>(&orig_RegisterCallback)) == 0) {
            LOGI("Hooked SLAndroidSimpleBufferQueueItf::RegisterCallback");
        } else {
            LOGE("Failed to hook RegisterCallback");
        }
    }

    if (enqueue_addr) {
        if (DobbyHook(enqueue_addr, reinterpret_cast<dobby_dummy_func_t>(hooked_Enqueue),
                       reinterpret_cast<dobby_dummy_func_t*>(&orig_Enqueue)) == 0) {
            LOGI("Hooked SLAndroidSimpleBufferQueueItf::Enqueue");
        } else {
            LOGE("Failed to hook Enqueue");
        }
    }

    g_vtable_hooked = true;
    return true;
}

// ============================================================
// Hooked: CreateAudioRecorder — 捕获录音器的音频参数
// ============================================================

static SLresult hooked_CreateAudioRecorder(
    SLEngineItf self,
    SLObjectItf* pRecorder,
    SLDataSource* pAudioSrc,
    SLDataSink* pAudioSnk,
    SLuint32 numInterfaces,
    const SLInterfaceID* pInterfaceIds,
    const SLboolean* pInterfaceRequired) {

    SLresult result = orig_CreateAudioRecorder(self, pRecorder, pAudioSrc, pAudioSnk,
                                                numInterfaces, pInterfaceIds, pInterfaceRequired);

    if (result == SL_RESULT_SUCCESS && pRecorder && *pRecorder) {
        RecorderObjectInfo objInfo{};

        // 从 data sink 中提取音频格式参数
        if (pAudioSnk && pAudioSnk->pFormat) {
            SLDataFormat_PCM* fmt = static_cast<SLDataFormat_PCM*>(pAudioSnk->pFormat);
            if (fmt->formatType == SL_DATAFORMAT_PCM) {
                objInfo.sampleRate = fmt->samplesPerSec; // milliHz
                objInfo.channels = fmt->numChannels;
                LOGI("CreateAudioRecorder: sampleRate=%u milliHz, channels=%u",
                     objInfo.sampleRate, objInfo.channels);
            }
        }

        std::lock_guard<std::mutex> lock(g_sl_mutex);
        g_recorder_objects[*pRecorder] = objInfo;
    }

    return result;
}

// ============================================================
// Hooked: GetInterface — 标记录音器的 buffer queue 并传递音频参数
// ============================================================

static SLresult hooked_GetInterface(
    SLObjectItf self,
    const SLInterfaceID iid,
    void* pInterface) {

    SLresult result = orig_GetInterface(self, iid, pInterface);

    if (result == SL_RESULT_SUCCESS && pInterface) {
        std::lock_guard<std::mutex> lock(g_sl_mutex);
        auto it = g_recorder_objects.find(self);
        if (it != g_recorder_objects.end()) {
            // 这是一个录音器对象的 GetInterface 调用
            SLAndroidSimpleBufferQueueItf bq =
                *static_cast<SLAndroidSimpleBufferQueueItf*>(pInterface);
            if (bq) {
                g_is_recorder_bq[bq] = true;
                // 将音频参数传递给 recorder map
                auto& info = g_recorder_map[bq];
                info.sampleRate = it->second.sampleRate;
                info.channels = it->second.channels;
                LOGI("GetInterface: marked bq as recorder, sampleRate=%u channels=%u",
                     info.sampleRate, info.channels);
            }
        }
    }

    return result;
}

// ============================================================
// Hooked: Destroy — 清理录音器对象跟踪
// ============================================================

static void hooked_Destroy(SLObjectItf self) {
    {
        std::lock_guard<std::mutex> lock(g_sl_mutex);
        g_recorder_objects.erase(self);
    }
    orig_Destroy(self);
}

// ============================================================
// Hook engine vtable to intercept CreateAudioRecorder
// ============================================================

static void try_hook_engine_vtable(SLEngineItf engine) {
    if (g_engine_vtable_hooked || !engine) return;

    // SLEngineItf is SLEngineItf_** -> vtable
    void** vtable = const_cast<void**>(reinterpret_cast<const void* const*>(*engine));
    if (!vtable) return;

    // SLEngineItf_ vtable layout:
    //   [0] = CreateLEDDevice
    //   [1] = CreateVibraDevice
    //   [2] = CreateAudioPlayer
    //   [3] = CreateAudioRecorder
    //   ... etc
    void* createRecorder_addr = vtable[3];

    if (createRecorder_addr) {
        if (DobbyHook(createRecorder_addr,
                       reinterpret_cast<dobby_dummy_func_t>(hooked_CreateAudioRecorder),
                       reinterpret_cast<dobby_dummy_func_t*>(&orig_CreateAudioRecorder)) == 0) {
            LOGI("Hooked SLEngineItf::CreateAudioRecorder");
        } else {
            LOGE("Failed to hook CreateAudioRecorder");
        }
    }

    g_engine_vtable_hooked = true;
}

static void try_hook_object_vtable(SLObjectItf obj) {
    if (g_object_vtable_hooked || !obj) return;

    void** vtable = const_cast<void**>(reinterpret_cast<const void* const*>(*obj));
    if (!vtable) return;

    // SLObjectItf_ vtable layout:
    //   [0] = Realize
    //   [1] = Resume
    //   [2] = GetState
    //   [3] = GetInterface
    //   [4] = RegisterCallback
    //   [5] = AbortAsyncOperation
    //   [6] = Destroy
    //   [7] = SetPriority
    //   [8] = GetPriority
    //   [9] = SetLossOfControlInterfaces

    void* getInterface_addr = vtable[3];
    void* destroy_addr = vtable[6];

    if (getInterface_addr) {
        if (DobbyHook(getInterface_addr,
                       reinterpret_cast<dobby_dummy_func_t>(hooked_GetInterface),
                       reinterpret_cast<dobby_dummy_func_t*>(&orig_GetInterface)) == 0) {
            LOGI("Hooked SLObjectItf::GetInterface");
        } else {
            LOGE("Failed to hook GetInterface");
        }
    }

    if (destroy_addr) {
        if (DobbyHook(destroy_addr,
                       reinterpret_cast<dobby_dummy_func_t>(hooked_Destroy),
                       reinterpret_cast<dobby_dummy_func_t*>(&orig_Destroy)) == 0) {
            LOGI("Hooked SLObjectItf::Destroy");
        } else {
            LOGE("Failed to hook Destroy");
        }
    }

    g_object_vtable_hooked = true;
}

// ============================================================
// Try to resolve vtable by creating a temporary recorder
// ============================================================

static void resolve_vtable_via_temp_recorder() {
    SLObjectItf engineObj = nullptr;
    SLEngineItf engine = nullptr;
    SLObjectItf recorderObj = nullptr;

    // Create temporary engine
    SLresult result;
    if (orig_slCreateEngine) {
        result = orig_slCreateEngine(&engineObj, 0, nullptr, 0, nullptr, nullptr);
    } else {
        result = slCreateEngine(&engineObj, 0, nullptr, 0, nullptr, nullptr);
    }

    if (result != SL_RESULT_SUCCESS || !engineObj) {
        LOGI("Could not create temp engine for vtable resolution");
        return;
    }

    result = (*engineObj)->Realize(engineObj, SL_BOOLEAN_FALSE);
    if (result != SL_RESULT_SUCCESS) {
        (*engineObj)->Destroy(engineObj);
        return;
    }

    result = (*engineObj)->GetInterface(engineObj, SL_IID_ENGINE, &engine);
    if (result != SL_RESULT_SUCCESS) {
        (*engineObj)->Destroy(engineObj);
        return;
    }

    // Hook engine vtable (CreateAudioRecorder) 和 object vtable (GetInterface/Destroy)
    // 用临时对象的 vtable 地址来 hook，因为所有同类对象共享相同的 vtable
    try_hook_engine_vtable(engine);
    try_hook_object_vtable(engineObj);

    // Create a minimal audio recorder to get buffer queue interface
    SLDataLocator_IODevice loc_dev = {
        SL_DATALOCATOR_IODEVICE,
        SL_IODEVICE_AUDIOINPUT,
        SL_DEFAULTDEVICEID_AUDIOINPUT,
        nullptr
    };
    SLDataSource audioSrc = {&loc_dev, nullptr};

    SLDataLocator_AndroidSimpleBufferQueue loc_bq = {
        SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE, 1
    };
    SLDataFormat_PCM format_pcm = {
        SL_DATAFORMAT_PCM,
        1,                              // channels
        SL_SAMPLINGRATE_16,             // 16kHz
        SL_PCMSAMPLEFORMAT_FIXED_16,
        SL_PCMSAMPLEFORMAT_FIXED_16,
        SL_SPEAKER_FRONT_CENTER,
        SL_BYTEORDER_LITTLEENDIAN
    };
    SLDataSink audioSnk = {&loc_bq, &format_pcm};

    const SLInterfaceID ids[] = {SL_IID_ANDROIDSIMPLEBUFFERQUEUE};
    const SLboolean req[] = {SL_BOOLEAN_TRUE};

    result = (*engine)->CreateAudioRecorder(engine, &recorderObj, &audioSrc,
                                            &audioSnk, 1, ids, req);

    if (result != SL_RESULT_SUCCESS || !recorderObj) {
        LOGI("Temp recorder creation failed (result=%d) — will hook via slCreateEngine fallback",
             result);
        (*engineObj)->Destroy(engineObj);
        return;
    }

    result = (*recorderObj)->Realize(recorderObj, SL_BOOLEAN_FALSE);
    if (result != SL_RESULT_SUCCESS) {
        (*recorderObj)->Destroy(recorderObj);
        (*engineObj)->Destroy(engineObj);
        return;
    }

    SLAndroidSimpleBufferQueueItf bq = nullptr;
    result = (*recorderObj)->GetInterface(recorderObj, SL_IID_ANDROIDSIMPLEBUFFERQUEUE, &bq);
    if (result == SL_RESULT_SUCCESS && bq) {
        try_hook_vtable(bq);
    }

    // Clean up temporary objects
    (*recorderObj)->Destroy(recorderObj);
    (*engineObj)->Destroy(engineObj);

    LOGI("vtable resolution via temp recorder %s", g_vtable_hooked ? "succeeded" : "failed");
}

// ============================================================
// Fallback: Hook slCreateEngine to intercept recorder creation
// ============================================================

static SLresult hooked_slCreateEngine(
    SLObjectItf* pEngine,
    SLuint32 numOptions,
    const SLEngineOption* pEngineOptions,
    SLuint32 numInterfaces,
    const SLInterfaceID* pInterfaceIds,
    const SLboolean* pInterfaceRequired) {

    SLresult result = orig_slCreateEngine(pEngine, numOptions, pEngineOptions,
                                          numInterfaces, pInterfaceIds, pInterfaceRequired);

    if (result == SL_RESULT_SUCCESS && !g_vtable_hooked) {
        LOGI("slCreateEngine called — attempting deferred vtable resolution");
        // Try again with the app's engine active
        resolve_vtable_via_temp_recorder();
    }

    return result;
}

// ============================================================
// Install hooks
// ============================================================

void install_opensles_hooks() {
    void* handle = dlopen("libOpenSLES.so", RTLD_NOW);
    if (!handle) {
        LOGI("libOpenSLES.so not available — skipping OpenSL ES hooks");
        return;
    }

    // Try to resolve vtable immediately
    resolve_vtable_via_temp_recorder();

    if (!g_vtable_hooked) {
        // Fallback: hook slCreateEngine to do deferred resolution
        void* sym = dlsym(handle, "slCreateEngine");
        if (sym) {
            if (DobbyHook(sym, reinterpret_cast<dobby_dummy_func_t>(hooked_slCreateEngine),
                           reinterpret_cast<dobby_dummy_func_t*>(&orig_slCreateEngine)) == 0) {
                LOGI("Hooked slCreateEngine for deferred vtable resolution");
            } else {
                LOGE("Failed to hook slCreateEngine");
            }
        }
    }

    LOGI("OpenSL ES hooks installation complete (vtable_hooked=%d)", g_vtable_hooked);
}
