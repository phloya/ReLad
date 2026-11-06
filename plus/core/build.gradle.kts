// Re:Lad core module: shared building blocks for Re:Lad features (settings, own database,
// feature flags, model downloads). Filled in module M1; see plus/PLAN.md.
plugins {
    alias(mihonx.plugins.android.library)
    alias(mihonx.plugins.spotless)
}

android {
    namespace = "plus.core"
}
