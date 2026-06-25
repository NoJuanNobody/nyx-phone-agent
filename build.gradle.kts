plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

allprojects {
    group = "com.nyx"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }
}
