cmake_minimum_required(VERSION 3.22.1)

project("nyx_llm_jni")

add_library(
    nyx_llm_jni
    SHARED
    src/main/jni/llama_jni.cpp
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(
    nyx_llm_jni
    ${android-lib}
    ${log-lib}
)
