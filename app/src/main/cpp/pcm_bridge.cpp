#include "pcm_bridge.h"
#include <android/log.h>
#include <cstring>
#include <pthread.h>

#define LOG_TAG "CS-PcmBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static jclass g_hook_class = nullptr;
static jmethodID g_fill_method = nullptr;
static volatile bool g_bridge_ready = false;

// Per-thread JNIEnv cache
static pthread_key_t g_env_key;
static pthread_once_t g_env_key_once = PTHREAD_ONCE_INIT;

// Track whether we attached this thread ourselves
struct EnvInfo {
    JNIEnv* env;
    bool attached_by_us;
};

static void env_key_destructor(void* data) {
    if (!data) return;
    EnvInfo* info = static_cast<EnvInfo*>(data);
    // Only detach threads that we attached — never detach JVM-managed threads
    if (info->attached_by_us) {
        JavaVM* vm = get_java_vm();
        if (vm) {
            vm->DetachCurrentThread();
        }
    }
    delete info;
}

static void create_env_key() {
    pthread_key_create(&g_env_key, env_key_destructor);
}

static JNIEnv* get_env() {
    JavaVM* vm = get_java_vm();
    if (!vm) return nullptr;

    pthread_once(&g_env_key_once, create_env_key);

    EnvInfo* cached = static_cast<EnvInfo*>(pthread_getspecific(g_env_key));
    if (cached && cached->env) return cached->env;

    JNIEnv* env = nullptr;

    // Try GetEnv first (thread may already be attached by JVM)
    jint rc = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (rc == JNI_OK) {
        EnvInfo* info = new EnvInfo{env, false}; // not attached by us
        pthread_setspecific(g_env_key, info);
        return env;
    }

    // Attach this native thread to the JVM
    JavaVMAttachArgs args;
    args.version = JNI_VERSION_1_6;
    args.name = const_cast<char*>("CS-NativeAudio");
    args.group = nullptr;

    rc = vm->AttachCurrentThread(&env, &args);
    if (rc != JNI_OK) {
        LOGE("AttachCurrentThread failed: %d", rc);
        return nullptr;
    }

    EnvInfo* info = new EnvInfo{env, true}; // attached by us — will detach on thread exit
    pthread_setspecific(g_env_key, info);
    return env;
}

void pcm_bridge_init(JNIEnv* env, jclass hookClass) {
    if (!hookClass) {
        LOGE("pcm_bridge_init: hookClass is null");
        return;
    }

    g_hook_class = static_cast<jclass>(env->NewGlobalRef(hookClass));

    g_fill_method = env->GetStaticMethodID(g_hook_class, "fillNativeBuffer", "([BIII)I");
    if (!g_fill_method) {
        LOGE("pcm_bridge_init: GetStaticMethodID fillNativeBuffer failed");
        env->ExceptionClear();
        return;
    }

    g_bridge_ready = true;
    LOGI("pcm_bridge_init: bridge ready");
}

int fill_fake_pcm(void* buffer, int sizeBytes, int sampleRate, int channels) {
    if (!g_bridge_ready || sizeBytes <= 0) {
        // Bridge not ready — fill silence as fallback
        if (buffer && sizeBytes > 0) {
            memset(buffer, 0, sizeBytes);
        }
        return sizeBytes;
    }

    JNIEnv* env = get_env();
    if (!env) {
        memset(buffer, 0, sizeBytes);
        return sizeBytes;
    }

    jbyteArray jbuf = env->NewByteArray(sizeBytes);
    if (!jbuf) {
        LOGE("fill_fake_pcm: NewByteArray(%d) failed", sizeBytes);
        env->ExceptionClear();
        memset(buffer, 0, sizeBytes);
        return sizeBytes;
    }

    jint result = env->CallStaticIntMethod(g_hook_class, g_fill_method,
                                           jbuf, sizeBytes, sampleRate, channels);

    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        env->DeleteLocalRef(jbuf);
        memset(buffer, 0, sizeBytes);
        return sizeBytes;
    }

    if (result < 0) {
        // -1 means hook is not enabled: don't overwrite buffer (passthrough)
        env->DeleteLocalRef(jbuf);
        return -1;
    }

    // Copy Java byte[] back to native buffer
    env->GetByteArrayRegion(jbuf, 0, sizeBytes, static_cast<jbyte*>(buffer));
    env->DeleteLocalRef(jbuf);

    return sizeBytes;
}
