pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "nyx-phone-agent"

include(":agent-core")
include(":agent-app")
include(":agent-launcher")
include(":skills")
include(":llm")
include(":backend")
