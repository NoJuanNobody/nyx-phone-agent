plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.nyx.llm"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

// NOTE: the native llama.cpp build (externalNativeBuild + CMakeLists.txt) is intentionally
// omitted here — LlamaCppBackend compiles fine (its `external` fns and System.loadLibrary
// only bind at runtime) and we never instantiate it. The active backend is
// OpenRouterLlmBackend (cloud, OpenAI-compatible) per the LLM-backend decision; on-device
// engines come later behind the same LlmInferenceEngine interface.

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.11.3")
}
