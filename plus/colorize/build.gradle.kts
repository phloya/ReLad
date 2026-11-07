// Re:Lad colorization: an image model colors a black-and-white page; Re:Lad keeps the original
// lines and text and takes only colors and shading from the model. See plus/DECISIONS.md, "Раскраска".
plugins {
    alias(mihonx.plugins.android.library)
    alias(mihonx.plugins.spotless)

    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "plus.colorize"
}

dependencies {
    implementation(projects.plus.core)

    testImplementation(libs.bundles.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
