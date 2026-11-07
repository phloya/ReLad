package plus.app.translate

import eu.kanade.tachiyomi.R
import plus.app.PlusFeature
import plus.app.PlusGraph
import plus.core.pipeline.PageProcessor

/** Placeholder until the feature's own branch (relad/translate) fills it in. */
object TranslateFeature : PlusFeature {
    override val id = "translate"
    override val titleRes = R.string.plus_feature_translate
    override val summaryRes = R.string.plus_feature_translate_summary

    override fun createProcessor(graph: PlusGraph): PageProcessor? = null
}
