package plus.app

import androidx.annotation.StringRes
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.BuildConfig
import plus.app.colorize.ColorizeFeature
import plus.app.enhance.EnhanceFeature
import plus.app.panels.PanelsFeature
import plus.app.translate.TranslateFeature
import plus.core.pipeline.DemoProcessor
import plus.core.pipeline.PageProcessor

/**
 * A Re:Lad feature on the Re:Lad screen. Each feature lives in its own package
 * (plus.app.translate, plus.app.enhance...), so feature branches never edit the same file.
 */
interface PlusFeature {
    val id: String

    @get:StringRes
    val titleRes: Int

    @get:StringRes
    val summaryRes: Int

    /** Shown only in Re:Lad Dev builds. */
    val devOnly: Boolean get() = false

    /**
     * `false` for a feature that changes how the reader moves through pages, not the page images
     * (panel-by-panel reading). It has no page step, and changing its settings reloads no pages.
     */
    val processesPages: Boolean get() = true

    /** The page step of this feature, or `null` while the feature is not built yet ("coming soon"). */
    fun createProcessor(graph: PlusGraph): PageProcessor?

    /** The feature's own settings, opened from the Re:Lad screen. `null` if it has none. */
    fun settingsScreen(): Screen? = null
}

object PlusFeatures {

    val isDevBuild: Boolean = BuildConfig.DEBUG

    val all: List<PlusFeature> = listOf(
        TranslateFeature,
        EnhanceFeature,
        ColorizeFeature,
        PanelsFeature,
        PipelineCheckFeature,
    )
}

/** Developer check: a red frame on every page proves pages go through the pipeline. */
object PipelineCheckFeature : PlusFeature {
    override val id = "pipeline_check"
    override val titleRes = eu.kanade.tachiyomi.R.string.plus_feature_pipeline_check
    override val summaryRes = eu.kanade.tachiyomi.R.string.plus_feature_pipeline_check_summary
    override val devOnly = true

    override fun createProcessor(graph: PlusGraph): PageProcessor = DemoProcessor(enabled = { true })
}
