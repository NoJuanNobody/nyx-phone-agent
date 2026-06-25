#include <jni.h>
#include <string>
#include <android/log.h>

#define LOG_TAG "NyxLlmJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_com_nyx_llm_backends_LlamaCppBackend_llamaGenerate(
        JNIEnv* env,
        jobject /* this */,
        jstring prompt,
        jint maxTokens,
        jstring modelPath) {
    // Stub implementation — replace with actual llama.cpp inference calls in production.
    LOGI("llamaGenerate called (stub): maxTokens=%d", maxTokens);
    return env->NewStringUTF("stub");
}
