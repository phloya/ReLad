// Re:Lad core module: what all Re:Lad features share — the page processing pipeline and its cache,
// AI provider connectors, secret storage and settings. See plus/README.md and plus/DECISIONS.md.
plugins {
    alias(mihonx.plugins.android.library)
    alias(mihonx.plugins.spotless)

    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "plus.core"
}

dependencies {
    api(projects.core.common)

    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp.core)

    testImplementation(libs.bundles.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
