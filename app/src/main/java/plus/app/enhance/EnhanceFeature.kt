package plus.app.enhance

import eu.kanade.tachiyomi.R
import plus.app.PlusFeature
import plus.app.PlusGraph
import plus.core.pipeline.PageProcessor

/** Placeholder until the feature's own branch (relad/enhance) fills it in. */
object EnhanceFeature : PlusFeature {
    override val id = "enhance"
    override val titleRes = R.string.plus_feature_enhance
    override val summaryRes = R.string.plus_feature_enhance_summary

    override fun createProcessor(graph: PlusGraph): PageProcessor? = null
}
