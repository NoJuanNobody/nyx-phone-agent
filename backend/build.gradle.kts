plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.coroutines.core)

    testImplementation(libs.coroutines.test)
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.mockk)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.test {
    useJUnitPlatform()
}
