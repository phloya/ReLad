// Re:Lad panel-by-panel reading: finds the panels of a page on the phone, without a network or a model,
// orders them for manga (right to left) or comics (left to right) and plans where the reader zooms.
// See plus/DECISIONS.md, "Чтение по кадрам".
plugins {
    alias(mihonx.plugins.android.library)
    alias(mihonx.plugins.spotless)

    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "plus.panels"
}

dependencies {
    implementation(projects.plus.core)

    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
