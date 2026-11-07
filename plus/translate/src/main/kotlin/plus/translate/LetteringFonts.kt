package plus.translate

import android.content.res.AssetManager
import android.graphics.Typeface

/**
 * The comic lettering font: Shantell Sans (SIL Open Font License 1.1, no reserved name; free to
 * ship in a paid app). Hand-lettered capitals close to manga lettering, with Latin, Cyrillic
 * (incl. Kazakh and Ukrainian letters) and Vietnamese. Text it has no letters for (CJK, Thai,
 * Arabic) is set in the system font instead. See plus/LICENSES.md.
 */
class LetteringFonts(
    /** Dialogue and narration; `null`: the system font. */
    val regular: Typeface?,
    /** Sound effects. */
    val bold: Typeface?,
) {
    companion object {
        const val ASSET = "relad/fonts/ShantellSans.ttf"

        /** Letters a bit narrower than the font's own, as manga lettering is. */
        const val SCALE_X = 0.95f

        val SYSTEM = LetteringFonts(null, null)

        fun load(assets: AssetManager): LetteringFonts = try {
            LetteringFonts(instance(assets, REGULAR_WEIGHT), instance(assets, BOLD_WEIGHT))
        } catch (e: Exception) {
            SYSTEM
        }

        private fun instance(assets: AssetManager, weight: Int): Typeface? = Typeface.Builder(assets, ASSET)
            // No "informal" bounce: steady letters like a letterer's font
            .setFontVariationSettings("'wght' $weight, 'INFM' 0, 'BNCE' 0, 'SPAC' 0")
            .build()

        private const val REGULAR_WEIGHT = 560
        private const val BOLD_WEIGHT = 800
    }
}
