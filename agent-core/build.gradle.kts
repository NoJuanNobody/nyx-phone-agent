dependencies {
    // agent-core uses only JDK NIO (java.net.UnixDomainSocketAddress + AsynchronousChannel),
    // kotlinx-coroutines/serialization (from the root build), and slf4j (via logback).
    // The previous ktor-* deps were never imported and referenced a non-existent artifact
    // (io.ktor:ktor-server-unixsocket), which broke the build and CI.
    implementation(libs.logback.classic)
}
