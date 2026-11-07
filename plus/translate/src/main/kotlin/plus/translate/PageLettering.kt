package plus.translate

import java.util.Locale

/**
 * Plans how a page's translations look, the way a letterer would: the original letters are
 * measured first, then erased, and each translation is set at the original's letter size (one
 * size for all bubbles on the page), in its case, following the bubble's shape, where the
 * original was. Narration over art becomes light letters with a dark outline, as such text is
 * drawn, over the cleaned art.
 *
 * Pure Kotlin on ARGB pixels: [PageTypesetter] draws the result on Android, tests check it on the JVM.
 */
object PageLettering {

    /** The fonts a page can use: the lettering font if it has all of a text's letters, else the system one. */
    fun interface Fonts {
        fun measurer(text: String, bold: Boolean): TextMeasurer
    }

    class Lettering(
        val region: TextRegion,
        val block: TextBlock,
        val font: TextMeasurer,
        val fill: Int,
        /** Outline colour around the letters; `null`: none. */
        val outline: Int?,
        /** Outline thickness as a share of the text size. */
        val outlineWidth: Float = 0f,
    )

    /** Erases the originals in [pixels] and returns what to draw. */
    fun plan(
        pixels: IntArray,
        width: Int,
        height: Int,
        regions: List<TextRegion>,
        language: String,
        fonts: Fonts,
        /** Receives a line per region about what was found, for tuning. */
        log: ((String) -> Unit)? = null,
    ): List<Lettering> {
        val locale = Locale.forLanguageTag(language)
        // Bubbles the model put beside their letters are moved onto them first
        val placed = regions.mapIndexed { i, region ->
            if (region.type != RegionType.Bubble || inBubble(pixels, width, height, region.box)) {
                region
            } else {
                val others = regions.filterIndexed { j, _ -> j != i }.map { it.box }
                TextSnap.candidates(pixels, width, height, region.box, others)
                    .take(MAX_SNAP_TRIES)
                    .firstOrNull { inBubble(pixels, width, height, it) }
                    ?.let { region.copy(box = it) } ?: region
            }
        }

        // 1. Everything is found and measured on the original page, before anything is erased
        val plans = placed.map { region -> analyze(pixels, width, height, region, locale, fonts) }

        if (log != null) {
            plans.forEach { p ->
                val b = p.bubble
                log(
                    "${p.region.type} ${p.region.box} '${p.region.text.take(20)}' whole=${b?.rows != null} " +
                        "onArt=${b?.onArt} cover=${b?.coverage} floating=${p.floating} " +
                        "ink=${p.ink?.box} lines=${p.ink?.lines} core=${p.ink?.core} size=${p.size}",
                )
            }
        }

        // 2. Erase
        plans.forEach { plan ->
            val bubble = plan.bubble ?: return@forEach
            if (plan.floating) return@forEach
            if (bubble.onArt) {
                val ink = plan.ink ?: return@forEach
                val outline = (ink.core * OUTLINE_SEARCH).toInt().coerceAtLeast(2)
                // The letters found and the model's box: a line's ends and a capital's top are easy to miss
                val box = Box(
                    minOf(ink.box.left, plan.region.box.left),
                    minOf(ink.box.top, plan.region.box.top),
                    maxOf(ink.box.right, plan.region.box.right),
                    maxOf(ink.box.bottom, plan.region.box.bottom),
                ).grown((ink.core * ART_CLEAN_MARGIN).toInt(), width, height)
                ArtCleaner.clean(pixels, width, height, box, outline)
            } else {
                BubbleEraser.paint(pixels, width, bubble)
            }
        }

        // 3. One letter size for the page's bubbles (and one for its captions), as a letterer keeps
        val pageSize = mutableMapOf<RegionType, Float>()
        for (type in listOf(RegionType.Bubble, RegionType.Caption)) {
            val sizes = plans.filter { it.region.type == type }.mapNotNull { it.size }.sorted()
            if (sizes.isNotEmpty()) pageSize[type] = sizes[sizes.size / 2]
        }
        val anySize = pageSize[RegionType.Bubble] ?: pageSize[RegionType.Caption]

        // 4. Set the text
        return plans.mapNotNull { plan ->
            val region = plan.region
            val common = pageSize[region.type]
            val own = plan.size
            val size = when {
                region.type == RegionType.Sfx -> null
                own == null -> common ?: anySize
                common != null && own in common * (1 - SAME_SIZE)..common * (1 + SAME_SIZE) -> common
                else -> own
            } ?: width * DEFAULT_SIZE
            layout(plan, size, width, height)
        }
    }

    /**
     * The cap height the original text has if it fills its box: from the box's area and the number
     * of characters (average width and line spacing of comic lettering). `null` for a tiny text.
     */
    private fun roughCap(region: TextRegion, cjk: Boolean): Float? {
        val length = region.text.trim().length
        if (length < MIN_ROUGH_LENGTH) return null
        val area = region.box.width.toFloat() * region.box.height * BOX_FILL
        return if (cjk) {
            kotlin.math.sqrt(area / (length * CJK_CELL * CJK_CELL)) * CJK_TO_CAP
        } else {
            kotlin.math.sqrt(area / (length * CHAR_WIDTH * LINE_PITCH)) * CAP_OF_SIZE
        }
    }

    /** [box] has dark letters in it and sits in a closed bubble. */
    private fun inBubble(pixels: IntArray, width: Int, height: Int, box: Box): Boolean {
        val bubble = BubbleEraser.analyze(pixels, width, height, box, searchBox(box, width, height))
        if (bubble.rows == null || bubble.onArt || bubble.coverage < MIN_COVERAGE ||
            bubble.fill < MIN_FILL
        ) {
            return false
        }
        val ink = InkMeasure.measure(
            pixels,
            width,
            height,
            box,
            lightLetters = false,
            vertical = false,
            inside = bubble.room,
        )
        return ink != null && ink.core >= MIN_CORE && ink.core <= height * MAX_CORE
    }

    /** By the longer side: a column of vertical Japanese text sits in a bubble several times wider. */
    private fun searchBox(box: Box, width: Int, height: Int) =
        box.grown((maxOf(box.width, box.height) * SEARCH_MARGIN).toInt(), width, height)

    private class Plan(
        val region: TextRegion,
        val text: String,
        val font: TextMeasurer,
        val bubble: BubbleEraser.Result?,
        val ink: InkMeasure.Ink?,
        /** The size the original letters ask for; `null` if they couldn't be measured. */
        val size: Float?,
        /** Distance between lines as a share of the size. */
        val lineHeight: Float,
        val locale: Locale,
    ) {
        /**
         * A bubble whose box is in no light area (the model put it on the art and no bubble was
         * found near): nothing is erased, the text is drawn outlined so the art isn't covered.
         */
        val floating: Boolean
            get() = region.type == RegionType.Bubble && bubble != null && !bubble.onArt &&
                (bubble.erased == null || bubble.coverage < FLOATING_COVERAGE || bubble.fill < FLOATING_FILL)
    }

    private fun analyze(
        pixels: IntArray,
        width: Int,
        height: Int,
        region: TextRegion,
        locale: Locale,
        fonts: Fonts,
    ): Plan {
        val upper = region.type == RegionType.Sfx || LetterCase.upper(region.text, region.type)
        val text = LetterCase.apply(region.translation, upper, locale)
        val font = fonts.measurer(text, bold = region.type == RegionType.Sfx)
        if (!region.type.erasesOriginal) return Plan(region, text, font, null, null, null, font.lineHeight, locale)

        val bubble = BubbleEraser.analyze(
            pixels = pixels,
            width = width,
            height = height,
            textBox = region.box,
            searchBox = searchBox(region.box, width, height),
        )
        val cjkSource = LetterCase.isCjk(region.text)
        val vertical = cjkSource && region.box.height > region.box.width * VERTICAL_RATIO
        // In a closed bubble every letter inside is this text's (the model's box may be off by a
        // line); not too far from the box though: two joined bubbles hold two texts
        val measureBox = if (bubble.rows != null && bubble.erased != null) {
            region.box.grown((region.box.height * MEASURE_REACH).toInt(), width, height)
                .let {
                    Box(
                        maxOf(it.left, bubble.erased.left),
                        maxOf(it.top, bubble.erased.top),
                        minOf(it.right, bubble.erased.right),
                        minOf(it.bottom, bubble.erased.bottom),
                    )
                }
                .takeIf { it.width > 0 && it.height > 0 } ?: region.box
        } else {
            region.box
        }
        val ink = InkMeasure.measure(
            pixels,
            width,
            height,
            measureBox,
            lightLetters = bubble.onArt,
            vertical = vertical,
            inside = bubble.room.takeIf { !bubble.onArt },
        )
            ?.takeIf { it.core >= MIN_CORE && it.core <= height * MAX_CORE }

        val cjkOutput = LetterCase.isCjk(text)
        val originalUpper = LetterCase.upper(region.text, region.type)
        // The original's cap height as its box and length suggest: rough, but never far off
        val roughCap = roughCap(region, cjkSource)
        val size = ink?.let {
            // What the original's measured band is in our font: cap height, x-height or a CJK character
            val measuredCap = when {
                cjkSource -> it.core * CJK_TO_CAP
                originalUpper -> it.core
                else -> it.core * font.capHeight / font.xHeight
            }
            // Art read as letters (or letters as art) gives a size far from what fits the box
            val cap = if (roughCap == null ||
                measuredCap in roughCap * MIN_TRUST..roughCap * MAX_TRUST
            ) {
                measuredCap
            } else {
                roughCap
            }
            if (cjkOutput) cap / CJK_CAP else cap / font.capHeight
        }
        // The letterer's line spacing when it can be read off the original (lines of the same case)
        val measured = ink?.pitch?.takeIf { !vertical && !cjkSource && originalUpper == upper }?.let { pitch ->
            pitch / ink.core * (if (upper) font.capHeight else font.xHeight)
        }
        val lineHeight = (measured ?: font.lineHeight * NATURAL_SPACING).coerceIn(MIN_LINE_HEIGHT, MAX_LINE_HEIGHT)
        return Plan(region, text, font, bubble, ink, size, lineHeight, locale)
    }

    private fun layout(plan: Plan, size: Float, width: Int, height: Int): Lettering? {
        val region = plan.region
        val font = plan.font
        if (plan.text.isBlank()) return null
        val pieces = TextPieces.of(plan.text, font, plan.locale)
        val bubble = plan.bubble
        val ink = plan.ink
        val anchor = (ink?.box ?: region.box).let { (it.top + it.bottom) / 2f }

        val onArt = bubble?.onArt == true
        val sfx = region.type == RegionType.Sfx || plan.floating
        val room: Room
        val style: TextBlockLayout.Style
        var target = size
        when {
            sfx -> {
                // A floating line may grow downwards: its box was only roughly where the text is
                room =
                    if (plan.floating) {
                        Room.of(
                            region.box.grown(region.box.height, width, height),
                        )
                    } else {
                        Room.of(region.box)
                    }
                style = TextBlockLayout.Style(font, plan.lineHeight, 0f, 0f)
                // A sound effect's translation stays small: the drawn original is part of the art
                if (region.type == RegionType.Sfx) {
                    target = minOf(region.box.height * SFX_FILL / font.capHeight, width * SFX_MAX_SIZE)
                }
            }
            onArt || bubble?.room == null -> {
                // Text on the art (or nothing found around it): about where and as wide as the original
                val base = ink?.box ?: region.box
                val grow = (base.width * ART_WIDEN).toInt()
                val box = Box(
                    (base.left - grow).coerceAtLeast(0),
                    (base.top - base.height).coerceAtLeast(0),
                    (base.right + grow).coerceAtMost(width),
                    (base.bottom + base.height).coerceAtMost(height),
                )
                room = Room.of(box)
                style = TextBlockLayout.Style(font, plan.lineHeight, 0f, 0f)
            }
            else -> {
                room = bubble.room
                val whole = bubble.rows != null
                style = TextBlockLayout.Style(
                    measurer = font,
                    lineHeight = plan.lineHeight,
                    padShare = if (whole) BUBBLE_PAD else OPEN_PAD,
                    minPad = if (whole) BUBBLE_MIN_PAD else OPEN_MIN_PAD,
                )
            }
        }
        val minSize = maxOf(width * MIN_SIZE, target * MIN_SCALE).coerceAtMost(target)
        val block = TextBlockLayout.place(pieces, room, anchor, target, minSize, style)
            ?: fallback(plan, room, anchor, minSize, height, style)
            ?: return null

        val background = bubble?.background ?: WHITE
        return when {
            sfx -> Lettering(region, block, font, BLACK, WHITE, SFX_OUTLINE)
            onArt -> Lettering(
                region,
                block,
                font,
                fill = ink?.color?.takeIf { BubbleEraser.luminance(it) >= LIGHT_FILL } ?: WHITE,
                outline = DARK_OUTLINE,
                outlineWidth = ART_OUTLINE,
            )
            BubbleEraser.luminance(background) < DARK_BACKGROUND -> Lettering(region, block, font, WHITE, null)
            // The original's ink colour (a scan's black is rarely pure black)
            else -> Lettering(
                region,
                block,
                font,
                ink?.color?.takeIf { BubbleEraser.luminance(it) < DARK_FILL } ?: BLACK,
                null,
            )
        }
    }

    /** Doesn't fit: the smallest size, as many lines as it takes, words split if one is too long. */
    private fun fallback(
        plan: Plan,
        room: Room,
        anchor: Float,
        size: Float,
        height: Int,
        style: TextBlockLayout.Style,
    ): TextBlock? {
        var left = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        for (y in room.top until room.bottom) {
            room.between(y.toFloat(), y + 1f)?.let {
                left = minOf(left, it.first.toInt())
                right = maxOf(right, it.second.toInt())
            }
        }
        if (right <= left) return null
        val tall = Room.of(Box(left, 0, right + 1, height))
        val loose = TextBlockLayout.Style(style.measurer, style.lineHeight, 0f, 0f)
        return TextBlockLayout.place(TextPieces.of(plan.text, plan.font, plan.locale), tall, anchor, size, size, loose)
            ?: TextBlockLayout.place(TextPieces.characters(plan.text, plan.font), tall, anchor, size, size, loose)
    }

    /** How far around the text box the bubble may extend, as a share of the box's longer side. */
    private const val SEARCH_MARGIN = 1.0f
    private const val VERTICAL_RATIO = 1.2f

    /** The measured letter size is trusted within this range around the rough one from the box. */
    private const val MIN_TRUST = 0.55f
    private const val MAX_TRUST = 1.7f
    private const val MIN_ROUGH_LENGTH = 12
    private const val BOX_FILL = 0.8f

    /** Comic lettering: average character width and line pitch in sizes, cap height in sizes. */
    private const val CHAR_WIDTH = 0.6f
    private const val LINE_PITCH = 1.35f
    private const val CAP_OF_SIZE = 0.7f
    private const val CJK_CELL = 1.05f

    /** A bubble's text box is at least this much inside its light area... */
    private const val MIN_COVERAGE = 0.8f

    /** ...and the area is this light: only its letters are dark. */
    private const val MIN_FILL = 0.8f

    /** Below these the box is not in a bubble at all (looser than for moving a box: an open bubble is uneven). */
    private const val FLOATING_COVERAGE = 0.6f
    private const val FLOATING_FILL = 0.6f

    /** Light letters are looked for this much around their box (share of their height). */
    private const val ART_CLEAN_MARGIN = 0.6f

    /** In a closed bubble, letters are looked for this many box heights around the box. */
    private const val MEASURE_REACH = 0.75f

    /** Places tried for a bubble whose box is beside its letters. */
    private const val MAX_SNAP_TRIES = 80

    /** A measured band smaller than this (pixels) or larger than this share of the page is not letters. */
    private const val MIN_CORE = 4f
    private const val MAX_CORE = 0.08f

    /** Cap height of a translation set for a CJK original, as a share of the original's character size. */
    private const val CJK_TO_CAP = 0.6f

    /** A CJK character's ink height as a share of the text size. */
    private const val CJK_CAP = 0.88f

    /** Bubbles whose own size is within this share of the page's keep the page's size. */
    private const val SAME_SIZE = 0.4f

    private const val DEFAULT_SIZE = 0.022f
    private const val MIN_SIZE = 0.011f

    /** Text is set at most this much smaller than the original before it gives up the shape. */
    private const val MIN_SCALE = 0.7f

    private const val NATURAL_SPACING = 0.95f
    private const val MIN_LINE_HEIGHT = 1.05f
    private const val MAX_LINE_HEIGHT = 1.6f

    /** Space between the text and a closed bubble's outline: this share of the row's width, at least this many caps. */
    private const val BUBBLE_PAD = 0.1f
    private const val BUBBLE_MIN_PAD = 0.5f
    private const val OPEN_PAD = 0.03f
    private const val OPEN_MIN_PAD = 0.25f

    private const val ART_WIDEN = 0.1f

    /** How far a light letter's dark outline reaches, as a share of the letters' height. */
    private const val OUTLINE_SEARCH = 0.45f
    private const val ART_OUTLINE = 0.22f

    private const val SFX_FILL = 0.6f
    private const val SFX_MAX_SIZE = 0.055f
    private const val SFX_OUTLINE = 0.18f

    private const val LIGHT_FILL = 190
    private const val DARK_FILL = 110
    private const val DARK_BACKGROUND = 128

    private const val WHITE = -0x1
    private const val BLACK = -0x1000000
    private const val DARK_OUTLINE = 0xFF141414.toInt()
}
