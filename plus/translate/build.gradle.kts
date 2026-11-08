// Re:Lad page translation: finds text on a page with a multimodal AI model, erases the original
// in bubbles and sets the translation in its place. See plus/DECISIONS.md, "Перевод".
plugins {
    alias(mihonx.plugins.android.library)
    alias(mihonx.plugins.spotless)

    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "plus.translate"
}

dependencies {
    implementation(projects.plus.core)

    testImplementation(libs.bundles.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
