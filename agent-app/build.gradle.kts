plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.nyx.agent.app"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // android.* stubs return defaults instead of throwing, so MockK-based
            // unit tests can exercise the JVM logic without an emulator. Robolectric
            // (used where real Intent/Uri behavior is needed) requires android resources.
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":agent-core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation("androidx.core:core-ktx:1.13.1")

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.jupiter.engine)
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.0.21")
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    // Robolectric runs the few tests that need real Android value-object behavior
    // (Intent/Uri/ComponentName) on the JVM. It is JUnit4-based, so the vintage
    // engine runs it alongside the JUnit5 tests.
    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.robolectric)
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.11.3")
}
