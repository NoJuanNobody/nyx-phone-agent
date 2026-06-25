plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

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
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "java")

    dependencies {
        "implementation"("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        "implementation"("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
        "testImplementation"("org.junit.jupiter:junit-jupiter:5.11.3")
        "testImplementation"("org.junit.jupiter:junit-jupiter-engine:5.11.3")
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
