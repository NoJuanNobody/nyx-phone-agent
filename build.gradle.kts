plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
}

// Modules that target Android (apply the Android Gradle Plugin in their own
// build.gradle.kts). They are excluded from the pure-JVM configuration below.
val androidModules = setOf("agent-app")

allprojects {
    group = "com.nyx"
    version = "0.1.0-SNAPSHOT"

    tasks.withType<Test> {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }
}

subprojects {
    // Android modules configure themselves via the Android Gradle Plugin.
    if (name in androidModules) return@subprojects

    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
    apply(plugin = "java")

    dependencies {
        "implementation"("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        "implementation"("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
        "testImplementation"("org.junit.jupiter:junit-jupiter:5.11.3")
        "testImplementation"("org.junit.jupiter:junit-jupiter-engine:5.11.3")
        "testImplementation"("org.jetbrains.kotlin:kotlin-test-junit5:2.0.21")
        "testImplementation"("io.mockk:mockk:1.13.13")
        "testImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
        "testImplementation"("app.cash.turbine:turbine:1.2.0")
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xjsr305=strict")
        }
    }

    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
