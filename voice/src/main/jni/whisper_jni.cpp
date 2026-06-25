#include <jni.h>
#include <android/log.h>
#include <string>

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" {

/**
 * Stub implementation of the Whisper STT JNI bridge.
 *
 * In production this function will:
 *   1. Accept 16 kHz 16-bit PCM samples as a jshortArray.
 *   2. Pass them to whisper.cpp's whisper_full() inference pipeline.
 *   3. Collect segment text from whisper_full_get_segment_text() and return
 *      the concatenated transcription as a Java String.
 *
 * The actual whisper.cpp native library is not bundled in CI; this stub
 * returns the string "stub" so that unit tests can verify the JNI signature
 * and call path without requiring native library linkage.
 */
JNIEXPORT jstring JNICALL
Java_com_nyx_voice_stt_WhisperSttEngine_whisperTranscribe(
        JNIEnv *env,
        jobject /* thiz */,
        jshortArray samples,
        jint sampleRate) {

    jsize length = env->GetArrayLength(samples);
    LOGI("whisperTranscribe called: %d samples @ %d Hz (stub)", (int)length, (int)sampleRate);

    // Stub: real implementation feeds `samples` into whisper.cpp and returns
    // the decoded text.  Return a sentinel so callers can detect stub mode.
    return env->NewStringUTF("stub");
}

} // extern "C"
