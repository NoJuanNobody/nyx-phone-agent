plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Thin installable host for the agent-app daemon library.
//
// agent-app is a com.android.library (no APK, no launcher) designed to be baked
// into the Nyx system image. This module wraps it in a com.android.application so
// the daemon can be installed and started on a stock device/emulator for testing.
android {
    namespace = "com.nyx.agent.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nyx.agent"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // agent-core pulls in logback (ktor logging) transitively; its jars carry
    // duplicate JDK metadata files that collide when merged into the APK.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":agent-app"))
    implementation(project(":agent-core"))
    implementation(project(":skills"))
    implementation(project(":llm"))
    implementation(libs.kotlinx.coroutines.core)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
