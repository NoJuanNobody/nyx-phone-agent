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

    dependencies {
        "implementation"(libs.kotlinx.coroutines.core)
        "implementation"(libs.kotlinx.serialization.json)
        "testImplementation"(libs.junit.jupiter)
        "testImplementation"(libs.mockk)
        "testImplementation"(libs.kotlinx.coroutines.test)
        "testImplementation"(libs.turbine)
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            jvmTarget = "17"
            freeCompilerArgs = listOf("-Xjsr305=strict")
        }
    }

    java {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
