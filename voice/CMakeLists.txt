cmake_minimum_required(VERSION 3.22.1)
project(nyx_voice_jni)

# Gather JNI stub sources
set(JNI_SOURCES
    src/main/jni/whisper_jni.cpp
    src/main/jni/kokoro_jni.cpp
)

add_library(
    nyx_voice_jni
    SHARED
    ${JNI_SOURCES}
)

# Link against NDK-provided Android and log libraries
find_library(android-lib android)
find_library(log-lib log)

target_link_libraries(
    nyx_voice_jni
    ${android-lib}
    ${log-lib}
)

target_include_directories(nyx_voice_jni PRIVATE
    ${CMAKE_SOURCE_DIR}/src/main/jni
)
