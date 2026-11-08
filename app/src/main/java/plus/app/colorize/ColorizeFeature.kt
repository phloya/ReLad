package plus.app.colorize

import eu.kanade.tachiyomi.R
import plus.app.PlusFeature
import plus.app.PlusGraph
import plus.core.pipeline.PageProcessor

/** Placeholder until the feature's own branch (relad/colorize) fills it in. */
object ColorizeFeature : PlusFeature {
    override val id = "colorize"
    override val titleRes = R.string.plus_feature_colorize
    override val summaryRes = R.string.plus_feature_colorize_summary

    override fun createProcessor(graph: PlusGraph): PageProcessor? = null
}
