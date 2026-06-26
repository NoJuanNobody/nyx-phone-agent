# Android source (excluded from the JVM build)

Kotlin under `src/androidMain` / `src/androidTest` imports `android.*` / `androidx.*`
and is **not** compiled by the current pure-JVM Gradle build (the Kotlin/JVM plugin
only compiles `src/main` and `src/test`). It is preserved here, not deleted.

Standing up the Android (+NDK) build to compile this source is tracked in issue #101.
