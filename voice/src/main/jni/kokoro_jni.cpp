#include <jni.h>
#include <android/log.h>

#define LOG_TAG "KokoroJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" {

/**
 * Stub implementation of the Kokoro/Piper TTS JNI bridge.
 *
 * In production this function will:
 *   1. Accept a UTF-8 text string and a voice identifier integer.
 *   2. Pass them to the Kokoro (or Piper) ONNX inference engine to synthesise
 *      22 kHz 16-bit mono PCM audio.
 *   3. Return the synthesised samples as a Java short[].
 *
 * The Kokoro native library is not bundled in CI; this stub returns an empty
 * short[] so callers can distinguish stub mode and proceed without crashing.
 */
JNIEXPORT jshortArray JNICALL
Java_com_nyx_voice_tts_KokoroTtsEngine_kokoroSynthesize(
        JNIEnv *env,
        jobject /* thiz */,
        jstring text,
        jint voiceId) {

    const char *textChars = env->GetStringUTFChars(text, nullptr);
    LOGI("kokoroSynthesize called: voiceId=%d text='%s' (stub)", (int)voiceId, textChars);
    env->ReleaseStringUTFChars(text, textChars);

    // Return an empty array; real implementation returns synthesised PCM data.
    return env->NewShortArray(0);
}

} // extern "C"
