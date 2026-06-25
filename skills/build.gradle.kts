plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.nyx.agent.skills"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }
}

dependencies {
    implementation(project(":agent-core"))
    implementation(libs.androidx.core)
    implementation(libs.coroutines.core)
}
