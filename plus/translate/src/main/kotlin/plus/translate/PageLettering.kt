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
        /** The lines are drawn turned by this many degrees (clockwise) around ([pivotX], [pivotY]). */
        val angle: Float = 0f,
        val pivotX: Float = 0f,
        val pivotY: Float = 0f,
        /** The letters drawn this much (share of the size) bolder than the font. */
        val thicken: Float = 0f,
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
        // Only dots and marks (……!!): nothing to translate, the original stays as drawn
        val own = ownBoxes(regions.filterNot { wordless(it.text) })
        val inPlace = own.map { region ->
            if (region.type == RegionType.Bubble) bubbleInk(pixels, width, height, region.box) else null
        }
        // The letters' size on this page, from the bubbles the model placed well: "letters" found
        // much bigger than that are art (an eye on a white panel), not a bubble's text
        val pageCore = inPlace.mapNotNull { it?.core }.sorted()
            .takeIf { it.size >= MIN_PAGE_CORES }?.let { it[it.size / 2] }
        // Bubbles the model put beside their letters are moved onto them first
        val placed = own.mapIndexed { i, region ->
            if (region.type != RegionType.Bubble || inPlace[i] != null) {
                region
            } else {
                val others = own.filterIndexed { j, _ -> j != i }.map { it.box }
                // The first nearby place inside a closed bubble with letters; the box then goes onto
                // those letters (the bubble's, found whole: a place may catch only a bit of them)
                // (never onto another text's letters: a bubble with two texts holds both)
                val letters = TextSnap.candidates(pixels, width, height, region.box, others)
                    .take(MAX_SNAP_TRIES)
                    .firstNotNullOfOrNull { candidate ->
                        bubbleLetters(pixels, width, height, candidate)
                            ?.takeIf { pageCore == null || it.core <= pageCore * MAX_SNAP_CORE }?.box
                            ?.takeIf { found -> others.none { share(found, it) >= OTHERS_LETTERS } }
                    }
                when {
                    letters == null -> region
                    // The box was on these letters already (part of them)
                    share(letters, region.box) >= ALREADY_ON -> region
                    else -> region.copy(box = letters)
                }
            }
        }

        // 1. Everything is found and measured on the original page, before anything is erased
        val plans = placed.map { region -> analyze(pixels, width, height, region, locale, fonts) }

        if (log != null) {
            plans.forEach { p ->
                val b = p.bubble
                val s = p.sfx ?: p.art
                log(
                    "${p.region.type} ${p.region.box} '${p.region.text.take(20)}' whole=${b?.rows != null} " +
                        "onArt=${b?.onArt} cover=${b?.coverage} floating=${p.floating} " +
                        "ink=${p.ink?.box} lines=${p.ink?.lines} core=${p.ink?.core} size=${p.size}" +
                        (
                            s?.let {
                                " ${if (p.art != null) "art" else "letters"}: light=${it.light} " +
                                    "pieces=${it.pieces} height=${it.letterHeight} " +
                                    "length=${it.length} angle=${it.angle} edge=${it.edgeWidth} stroke=${it.stroke}"
                            } ?: ""
                            ),
                )
            }
        }

        // 2. Erase
        plans.forEach { plan ->
            val bubble = plan.bubble ?: return@forEach
            if (plan.floating || plan.art != null) return@forEach
            if (bubble.onArt) {
                // Letters not measured: the model's box, with as thick an outline and margin as letters ever have
                val ink = plan.ink
                val core = ink?.core ?: Float.MAX_VALUE
                // (hatched art read as letters gives a huge band: an outline is never that thick)
                val outline = minOf(core * OUTLINE_SEARCH, width * MAX_ART_OUTLINE).toInt().coerceAtLeast(2)
                // The letters found and the model's box: a line's ends and a capital's top are easy to miss
                val inkBox = ink?.box ?: plan.region.box
                val box = Box(
                    minOf(inkBox.left, plan.region.box.left),
                    minOf(inkBox.top, plan.region.box.top),
                    maxOf(inkBox.right, plan.region.box.right),
                    maxOf(inkBox.bottom, plan.region.box.bottom),
                ).grown(minOf(core * ART_CLEAN_MARGIN, width * MAX_ART_MARGIN).toInt(), width, height)
                ArtCleaner.clean(pixels, width, height, box, outline)
            } else {
                BubbleEraser.paint(pixels, width, bubble)
            }
        }
        // Sound effects' letters: the art behind them is drawn on from the art around (no letters
        // copied in from another sound effect)
        val drawn = plans.mapNotNull { it.sfx ?: it.art }
        if (drawn.isNotEmpty()) {
            val letters = BooleanArray(width * height)
            for (sfx in drawn) {
                for (i in sfx.mask.indices) {
                    if (sfx.mask[i]) {
                        letters[
                            (sfx.area.top + i / sfx.area.width) * width + sfx.area.left +
                                i % sfx.area.width,
                        ] =
                            true
                    }
                }
            }
            drawn.forEach { Inpaint.fill(pixels, width, height, it.area, it.mask, letters) }
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
            // Other texts' places, so that two texts in one bubble don't run into each other
            val others = plans.filter { it !== plan && it.region.type.erasesOriginal && it.bubble?.onArt != true }
                .map { it.ink?.box ?: it.region.box }
            plan.sfx?.let { sfxLettering(plan, it, width, height) } ?: layout(plan, size, width, height, others)
        }
    }

    /** How much of [b] lies in [a]. */
    private fun share(a: Box, b: Box): Float {
        val overlap = maxOf(0, minOf(a.right, b.right) - maxOf(a.left, b.left)).toLong() *
            maxOf(0, minOf(a.bottom, b.bottom) - maxOf(a.top, b.top))
        return overlap.toFloat() / (b.width.toLong() * b.height).coerceAtLeast(1)
    }

    /** No word in [text]: dots, marks, a cut-off breath (……ッ!!). */
    private fun wordless(text: String) = text.none { it.isLetterOrDigit() && it !in CUT_OFF }

    /**
     * Each text's own box: a box that holds another text's box as well (two texts in one bubble,
     * one boxed with the whole bubble) is cut down to its largest part beside the other text.
     */
    private fun ownBoxes(regions: List<TextRegion>): List<TextRegion> = regions.mapIndexed { i, region ->
        if (!region.type.erasesOriginal) return@mapIndexed region
        var box = region.box
        for ((j, other) in regions.withIndex()) {
            if (j == i || !other.type.erasesOriginal) continue
            val o = other.box
            val area = box.width.toLong() * box.height
            if (share(box, o) < CONTAINED || area < o.width.toLong() * o.height * BIGGER) continue
            val part = listOf(
                Box(box.left, box.top, box.right, o.top),
                Box(box.left, o.bottom, box.right, box.bottom),
                Box(box.left, box.top, o.left, box.bottom),
                Box(o.right, box.top, box.right, box.bottom),
            ).filter { it.width > 0 && it.height > 0 }.maxByOrNull { it.width.toLong() * it.height } ?: continue
            if (part.width.toLong() * part.height >= area * MIN_PART) box = part
        }
        region.copy(box = box)
    }

    /**
     * The cap height the original text has if it fills its box: from the box's area and the number
     * of characters (average width and line spacing of comic lettering). `null` for a tiny text.
     */
    private fun roughCap(region: TextRegion, cjk: Boolean, fill: Float = BOX_FILL): Float? {
        val length = region.text.trim().length
        if (length < MIN_ROUGH_LENGTH) return null
        val area = region.box.width.toFloat() * region.box.height * fill
        return if (cjk) {
            kotlin.math.sqrt(area / (length * CJK_CELL * CJK_CELL)) * CJK_TO_CAP
        } else {
            kotlin.math.sqrt(area / (length * CHAR_WIDTH * LINE_PITCH)) * CAP_OF_SIZE
        }
    }

    /** The dark letters in [box] when it lies in a closed bubble, or `null`. */
    private fun bubbleInk(pixels: IntArray, width: Int, height: Int, box: Box): InkMeasure.Ink? {
        val bubble = closedBubble(pixels, width, height, box) ?: return null
        val ink = InkMeasure.measure(
            pixels,
            width,
            height,
            box,
            lightLetters = false,
            vertical = false,
            inside = bubble.room,
        )
        return ink?.takeIf { it.core >= MIN_CORE && it.core <= height * MAX_CORE }
    }

    /** The letters of the closed bubble [box] lies in, or `null` if it lies in none (or one without letters). */
    private fun bubbleLetters(pixels: IntArray, width: Int, height: Int, box: Box): InkMeasure.Ink? {
        val bubble = closedBubble(pixels, width, height, box) ?: return null
        val lobe = bubble.erased ?: return null
        val ink = InkMeasure.measure(
            pixels,
            width,
            height,
            lobe,
            lightLetters = false,
            vertical = false,
            inside = bubble.room,
        )
        return ink?.takeIf { it.core >= MIN_CORE && it.core <= height * MAX_CORE }
    }

    /** The closed bubble [box] lies in: almost all of the box inside its light area, which is light but for letters. */
    private fun closedBubble(pixels: IntArray, width: Int, height: Int, box: Box): BubbleEraser.Result? {
        val bubble = BubbleEraser.analyze(pixels, width, height, box, searchBox(box, width, height))
        return bubble.takeIf { it.rows != null && !it.onArt && it.coverage >= MIN_COVERAGE && it.fill >= MIN_FILL }
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
        /** A sound effect's letters, found to be erased and drawn again. */
        val sfx: SfxLetters.Letters? = null,
        /** Narration's letters on the art, found like a sound's: erased the same way, set as text. */
        val art: SfxLetters.Letters? = null,
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
        val text = LetterCase.apply(LetterCase.withoutQuotes(region.translation, region.text), upper, locale)
        val font = fonts.measurer(text, bold = region.type == RegionType.Sfx)
        if (!region.type.erasesOriginal) {
            // A sound effect's letters with an edge are erased and the translation drawn like them
            SfxLetters.find(pixels, width, height, region.box, region.text)?.let { letters ->
                // A sound written thin (by hand) in the regular weight, a fat one in the bold
                val weight = if (letters.stroke < letters.letterHeight * THIN_SFX) fonts.measurer(text, false) else font
                return Plan(region, text, weight, null, null, null, weight.lineHeight, locale, letters)
            }
            // Others drawn on plain light background (not over art) are erased like a bubble's
            // text: the translation alone looks cleaner than over the original's letters
            val plain = BubbleEraser.analyze(pixels, width, height, region.box, searchBox(region.box, width, height))
                .takeIf { !it.onArt && it.coverage >= MIN_COVERAGE && it.fill >= MIN_FILL }
            return Plan(region, text, font, plain, null, null, font.lineHeight, locale)
        }

        val sign = region.type == RegionType.Other
        val found = BubbleEraser.analyze(
            pixels = pixels,
            width = width,
            height = height,
            textBox = region.box,
            searchBox = searchBox(region.box, width, height),
            // An open sign is erased only in its box: letters followed on would take its pictures too
            paintBox = region.box.expanded(if (sign) SIGN_MARGIN else BubbleEraser.PAINT_MARGIN, width, height),
            followLetters = !sign,
        )
        // Narration on the art (light letters with a dark edge, no box around them): its letters are
        // found like a sound's, and the art behind them drawn on. Only when (nearly) all of them are
        // found: a part erased would leave the rest beside the translation
        val captionOnArt = !sign && region.type == RegionType.Caption && !found.onArt && found.coverage < ART_COVERAGE
        val art = if (!sign && (found.onArt || captionOnArt)) {
            SfxLetters.find(pixels, width, height, region.box, region.text)
                ?.takeIf { it.pieces >= region.text.count(Char::isLetterOrDigit) * ART_PIECES }
        } else {
            null
        }
        if (art != null) return artPlan(region, text, font, art, locale)
        // ...otherwise its light letters are cleaned off the art (painting the light found around
        // them, as in a bubble, would leave white blots on the art)
        val bubble = if (captionOnArt) BubbleEraser.Result(null, WHITE, onArt = true) else found
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
        // The original's cap height as its box and length suggest: rough, but never far off. A sign
        // erased whole is filled by its text: the model's box may hold only part of it
        val signArea = bubble.erased?.takeIf { sign && bubble.rows != null }
        val roughCap = if (signArea != null) {
            roughCap(region.copy(box = signArea), cjkSource, SIGN_FILL)
        } else {
            roughCap(region, cjkSource)
        }
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
        } ?: roughCap?.takeIf { region.type == RegionType.Bubble || region.type == RegionType.Caption }?.let { cap ->
            // Letters not measured (slanted lines read as one): the rough size from the box, so a big
            // shout stays big instead of taking the page's usual size
            if (cjkOutput) cap / CJK_CAP else cap / font.capHeight
        }
        // The letterer's line spacing when it can be read off the original (lines of the same case)
        val measured = ink?.pitch?.takeIf { !vertical && !cjkSource && originalUpper == upper }?.let { pitch ->
            pitch / ink.core * (if (upper) font.capHeight else font.xHeight)
        }
        val lineHeight = (measured ?: font.lineHeight * NATURAL_SPACING).coerceIn(MIN_LINE_HEIGHT, MAX_LINE_HEIGHT)
        return Plan(region, text, font, bubble, ink, size, lineHeight, locale)
    }

    /** Narration whose letters on the art were found: set as text on the art, as big as they were. */
    private fun artPlan(
        region: TextRegion,
        text: String,
        font: TextMeasurer,
        art: SfxLetters.Letters,
        locale: Locale,
    ): Plan {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = -1
        var bottom = -1
        for (i in art.mask.indices) {
            if (!art.mask[i]) continue
            val x = art.area.left + i % art.area.width
            val y = art.area.top + i / art.area.width
            left = minOf(left, x)
            top = minOf(top, y)
            right = maxOf(right, x + 1)
            bottom = maxOf(bottom, y + 1)
        }
        val lines = art.lines.sortedBy { it.centerY }
        val pitch = lines.zipWithNext { a, b -> kotlin.math.hypot(b.centerX - a.centerX, b.centerY - a.centerY) }
            .sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
        // The letters' height across the line, edge included: a capital with its outline
        val cap = if (LetterCase.upper(region.text, region.type)) {
            art.letterHeight
        } else {
            art.letterHeight * font.capHeight / font.xHeight
        }
        val ink = InkMeasure.Ink(
            Box(left, top, right, bottom),
            lines.size.coerceAtLeast(1),
            art.letterHeight,
            pitch,
            WHITE,
        )
        val lineHeight = pitch?.let { it / art.letterHeight * font.capHeight }
            ?.coerceIn(MIN_LINE_HEIGHT, MAX_LINE_HEIGHT) ?: (font.lineHeight * NATURAL_SPACING)
        return Plan(
            region,
            text,
            font,
            BubbleEraser.Result(null, WHITE, onArt = true),
            ink,
            cap / font.capHeight,
            lineHeight,
            locale,
            art = art,
        )
    }

    private fun layout(plan: Plan, size: Float, width: Int, height: Int, others: List<Box>): Lettering? {
        val region = plan.region
        val font = plan.font
        if (plan.text.isBlank()) return null
        val pieces = TextPieces.of(plan.text, font, plan.locale)
        val bubble = plan.bubble
        val ink = plan.ink
        // A sign erased whole gets its text in its middle: the model's box may hold only part of it
        val wholeSign = region.type == RegionType.Other && bubble?.rows != null && bubble.erased != null
        val anchor = (if (wholeSign) bubble?.erased else null ?: ink?.box ?: region.box)!!
            .let { (it.top + it.bottom) / 2f }

        val onArt = bubble?.onArt == true
        val sfx = region.type == RegionType.Sfx || plan.floating
        val room: Room
        val style: TextBlockLayout.Style
        var target = size
        when {
            sfx -> {
                // A floating line may grow downwards: its box was only roughly where the text is
                room = if (plan.floating) {
                    Room.of(region.box.grown(region.box.height, width, height))
                } else {
                    // A little over the original's edges, so the translation covers it
                    Room.of(region.box.grown((region.box.height * SFX_OVER).toInt(), width, height))
                }
                style = TextBlockLayout.Style(font, plan.lineHeight, 0f, 0f)
                // About the original's size: a smaller translation mixes with the original's letters
                if (region.type == RegionType.Sfx) {
                    target = minOf(region.box.height * SFX_FILL / font.capHeight, width * SFX_MAX_SIZE)
                    // A sound is never split mid-word: its longest word fits the original's width
                    val widest = pieces.widest
                    if (widest > 0) target = minOf(target, region.box.width * SFX_WIDTH_FILL / widest)
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
        // Lines centred where the original's were (measured), in a bubble shared with another text
        // kept off its place
        val anchorX = if (wholeSign) null else ink?.box?.let { (it.left + it.right) / 2f }
        val ownRoom = if (sfx ||
            onArt
        ) {
            room
        } else {
            room.excluding(others, anchorX ?: (region.box.left + region.box.right) / 2f, OTHERS_MARGIN)
        }
        val block =
            (
                if (ownRoom !==
                    room
                ) {
                    TextBlockLayout.place(pieces, ownRoom, anchor, target, minSize, style, anchorX)
                } else {
                    null
                }
                )
                ?: TextBlockLayout.place(pieces, room, anchor, target, minSize, style, anchorX)
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

    /**
     * A sound effect drawn again where the original's letters were, the way they were drawn: as
     * high, along the same slant, in their colours with as thick an edge. On one line, smaller if
     * it is longer than the original; on two if one would get much smaller.
     */
    private fun sfxLettering(plan: Plan, letters: SfxLetters.Letters, width: Int, height: Int): Lettering? {
        val font = plan.font
        val words = plan.text.split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return null
        // The original's capital height: lowercase letters were measured at their x-height
        val lower = !LetterCase.upper(plan.region.text, RegionType.Sfx)
        val cap = letters.letterHeight * (if (lower) font.capHeight / font.xHeight else 1f)
        val original = cap / font.capHeight
        val full = original.coerceAtMost(width * SFX_REDRAWN_MAX)
        fun widthOf(line: List<String>) = font.width(line.joinToString(" ")).coerceAtLeast(EPSILON)

        // On the original's lines when it was drawn on two or three and there are words enough
        val drawnLines = letters.lines
        val texts: List<String>
        val places: List<SfxLetters.Line>
        var size: Float
        val onDrawnLines = drawnLines.size >= 2 && words.size >= drawnLines.size
        if (onDrawnLines) {
            val split = splits(words, drawnLines.size).minBy { parts -> parts.maxOf { widthOf(it) } }
            texts = split.map { it.joinToString(" ") }
            places = drawnLines
            size = minOf(full, texts.indices.minOf { places[it].length * SFX_LENGTH / widthOf(split[it]) })
        } else {
            val room = letters.length * SFX_LENGTH
            val middle = SfxLetters.Line(letters.centerX, letters.centerY, letters.length)
            var lines = listOf(words)
            size = minOf(full, room / widthOf(words))
            if (size < full * ONE_LINE && words.size >= 2) {
                val two = splits(words, 2).minBy { parts -> parts.maxOf { widthOf(it) } }
                val twoSize = minOf(full, room / two.maxOf { widthOf(it) })
                if (twoSize > size * TWO_LINES_GAIN) {
                    lines = two
                    size = twoSize
                }
            }
            texts = lines.map { it.joinToString(" ") }
            places = List(lines.size) { middle }
        }
        // Not much smaller than the original, but not running far past it either
        val widest = texts.maxOf { font.width(it) }.coerceAtLeast(EPSILON)
        size =
            maxOf(
                size,
                minOf(full * SFX_MIN_SCALE, letters.length * MAX_OVERRUN / widest),
                width * MIN_SIZE,
                MIN_PIXELS,
            )

        // Lines are placed in the turned frame around the sound's middle, then the whole is turned
        val radians = Math.toRadians(letters.angle.toDouble())
        val cos = kotlin.math.cos(radians).toFloat()
        val sin = kotlin.math.sin(radians).toFloat()
        // Each line's middle in the frame (along, across) and on the page
        val stacked = (texts.size - 1) / 2f
        fun frame(k: Int, size: Float): Pair<Float, Float> {
            val place = places[k]
            val dx = place.centerX - letters.centerX
            val dy = place.centerY - letters.centerY
            val across = if (onDrawnLines) -dx * sin + dy * cos else (k - stacked) * size * SFX_LINE_HEIGHT
            return (dx * cos + dy * sin) to across
        }
        // On the page: a line that would run off it moves in along itself, or if the page is too
        // narrow there, gets smaller
        val margin = width * PAGE_MARGIN
        fun room(k: Int, size: Float): ClosedFloatingPointRange<Float> {
            val (along, across) = frame(k, size)
            val x = letters.centerX + along * cos - across * sin
            val y = letters.centerY + along * sin + across * cos
            val half = font.width(texts[k]) * size / 2
            var lo = -Float.MAX_VALUE
            var hi = Float.MAX_VALUE
            for ((position, step, limit) in listOf(Triple(x, cos, width.toFloat()), Triple(y, sin, height.toFloat()))) {
                if (kotlin.math.abs(step) < EPSILON) continue
                val a = (margin - position) / step
                val b = (limit - margin - position) / step
                lo = maxOf(lo, minOf(a, b) + half)
                hi = minOf(hi, maxOf(a, b) - half)
            }
            return lo..hi
        }
        for (k in texts.indices) {
            val fits = room(k, size)
            if (fits.start > fits.endInclusive) {
                // Too long for the page here: as much smaller as it is over
                val over = fits.start - fits.endInclusive
                val length = font.width(texts[k]) * size
                size *= ((length - over) / length).coerceIn(SFX_MIN_SCALE, 1f)
            }
        }
        val block = TextBlock(
            size,
            texts.mapIndexed { k, text ->
                val (along, across) = frame(k, size)
                val fits = room(k, size)
                val shift = if (fits.start <= fits.endInclusive) 0f.coerceIn(fits.start, fits.endInclusive) else 0f
                TextBlock.Line(
                    text,
                    letters.centerX + along + shift,
                    letters.centerY + across + size * font.capHeight / 2,
                )
            },
        )
        return Lettering(
            plan.region,
            block,
            font,
            fill = letters.fill,
            outline = letters.edge,
            // Drawn on both sides of the letters' outline, half of it shows; as thick as the original's
            outlineWidth = (2 * letters.edgeWidth / original).coerceIn(MIN_EDGE, MAX_EDGE),
            angle = letters.angle,
            pivotX = letters.centerX,
            pivotY = letters.centerY,
            // Sound effects are drawn fat: as fat as the original's letters, if the font is thinner
            thicken = if (LetterCase.isCjk(plan.region.text)) {
                0f
            } else {
                ((letters.stroke / letters.letterHeight - FONT_STROKE) * font.capHeight).coerceIn(0f, MAX_THICKEN)
            },
        )
    }

    /** Every way to cut [words] into [parts] lines, in order. */
    private fun splits(words: List<String>, parts: Int): List<List<List<String>>> {
        if (parts == 1) return listOf(listOf(words))
        return (1..words.size - parts + 1).flatMap { k ->
            splits(words.drop(k), parts - 1).map { rest -> listOf(words.take(k)) + rest }
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
        if (plan.region.type == RegionType.Sfx) {
            // A sound in one piece, a word per line, even if it runs a little out of its box
            val words = TextPieces.of(plan.text, plan.font, plan.locale)
            val half = maxOf(words.widest * size, plan.region.box.width.toFloat()) / 2 + 1
            val middle = (plan.region.box.left + plan.region.box.right) / 2f
            val wide = Room.of(Box((middle - half).toInt(), 0, (middle + half).toInt() + 1, height))
            return TextBlockLayout.place(
                words,
                wide,
                anchor,
                size,
                size,
                TextBlockLayout.Style(style.measurer, style.lineHeight, 0f, 0f),
            )
        }
        var left = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        for (y in room.top until room.bottom) {
            room.between(y.toFloat(), y + 1f)?.let {
                left = minOf(left, it.first.toInt())
                right = maxOf(right, it.second.toInt())
            }
        }
        if (right <= left) return null
        val words = TextPieces.of(plan.text, plan.font, plan.locale)
        // Smaller first, in the bubble's shape: a letterer shrinks a line before splitting a word
        // (as small as it takes for the longest word to fit the bubble's width, if that's smaller)
        val wordFits = if (words.widest > 0) (right - left) * WORD_FIT / words.widest else size
        val smallest = maxOf(minOf(size * LAST_RESORT_SCALE, wordFits), MIN_PIXELS)
        TextBlockLayout.place(
            words,
            room,
            anchor,
            size,
            smallest,
            style,
            plan.ink?.box?.let {
                (it.left + it.right) / 2f
            },
        )
            ?.let { return it }
        // Then out of the shape (the bubble's width, any height); only then a word split by letters
        val tall = Room.of(Box(left, 0, right + 1, height))
        val loose = TextBlockLayout.Style(style.measurer, style.lineHeight, 0f, 0f)
        return TextBlockLayout.place(words, tall, anchor, size, smallest, loose)
            ?: TextBlockLayout.place(TextPieces.characters(plan.text, plan.font), tall, anchor, size, size, loose)
    }

    /** How far around the text box the bubble may extend, as a share of the box's longer side. */
    private const val SEARCH_MARGIN = 1.0f

    /** Kana that end a breath rather than spell a word: "……ッ!!" is a gasp, not text to translate. */
    private const val CUT_OFF = "ッっー"

    /** Before a word is split, text may get this much smaller than its smallest normal size... */
    private const val LAST_RESORT_SCALE = 0.7f

    /** The longest word takes at most this share of the bubble's widest row. */
    private const val WORD_FIT = 0.65f

    /** ...but never smaller than this, in pixels: unreadable. */
    private const val MIN_PIXELS = 9f
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

    /** A box holding this share of another, bigger by this much, holds the other's text too... */
    private const val CONTAINED = 0.7f
    private const val BIGGER = 1.5f

    /** ...and is cut down to its part beside it, if that is at least this share of it. */
    private const val MIN_PART = 0.2f

    /** A sign's text takes about this share of the sign. */
    private const val SIGN_FILL = 0.45f

    /** On a sign not erased whole, only this little around the model's box is erased. */
    private const val SIGN_MARGIN = 0.08f

    /** Below these the box is not in a bubble at all (looser than for moving a box: an open bubble is uneven). */
    private const val FLOATING_COVERAGE = 0.6f
    private const val FLOATING_FILL = 0.6f

    /** Light letters are looked for this much around their box (share of their height). */
    private const val ART_CLEAN_MARGIN = 0.6f

    /** Space kept to another text in the same bubble, in pixels. */
    private const val OTHERS_MARGIN = 6

    /** In a closed bubble, letters are looked for this many box heights around the box. */
    private const val MEASURE_REACH = 0.75f

    /** A box moved onto letters holding this share of another text's box would take its text... */
    private const val OTHERS_LETTERS = 0.5f

    /** ...and letters holding this share of the box itself were under it already. */
    private const val ALREADY_ON = 0.8f

    /** A caption with less of its box than this in a light area is on the art. */
    private const val ART_COVERAGE = 0.5f

    /** Narration on the art is erased like a sound's when this many pieces per letter of it are found. */
    private const val ART_PIECES = 0.75f

    /** Light letters' outline is never thicker than this share of the page width... */
    private const val MAX_ART_OUTLINE = 0.006f

    /** ...nor cleaned further than this around them. */
    private const val MAX_ART_MARGIN = 0.012f

    /** Places tried for a bubble whose box is beside its letters. */
    private const val MAX_SNAP_TRIES = 150

    /**
     * A box moves onto letters at most this many times the page's letter size (measured on at
     * least [MIN_PAGE_CORES] bubbles in place): a shout is bigger, an eye drawn on a white panel more so.
     */
    private const val MAX_SNAP_CORE = 2.5f
    private const val MIN_PAGE_CORES = 2

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

    private const val SFX_FILL = 0.85f

    /** A redrawn sound may be this much longer than the original before it gets smaller... */
    private const val SFX_LENGTH = 1.25f

    /** ...down to this share of the original's size... */
    private const val SFX_MIN_SCALE = 0.5f

    /** ...but below this share it is set on two lines, if they are this much bigger. */
    private const val ONE_LINE = 0.7f
    private const val TWO_LINES_GAIN = 1.15f
    private const val SFX_LINE_HEIGHT = 1.0f

    /** A redrawn sound keeps this far from the page's edge (share of the page width). */
    private const val PAGE_MARGIN = 0.015f

    /** A redrawn sound runs at most this many times the original's length. */
    private const val MAX_OVERRUN = 1.8f

    /** Letters with strokes thinner than this share of their height are written, not drawn fat. */
    private const val THIN_SFX = 0.12f

    /** A redrawn sound's edge: at least and at most this share of its size. */
    private const val MIN_EDGE = 0.08f
    private const val MAX_EDGE = 0.5f
    private const val EPSILON = 1e-3f

    /** The bold lettering font's stroke as a share of its capitals' height (measured like [SfxLetters] does). */
    private const val FONT_STROKE = 0.25f
    private const val MAX_THICKEN = 0.15f
    private const val SFX_MAX_SIZE = 0.09f

    /** A redrawn sound is at most this share of the page width high. */
    private const val SFX_REDRAWN_MAX = 0.3f
    private const val SFX_OUTLINE = 0.24f
    private const val SFX_OVER = 0.15f
    private const val SFX_WIDTH_FILL = 1.1f

    private const val LIGHT_FILL = 190
    private const val DARK_FILL = 110
    private const val DARK_BACKGROUND = 128

    private const val WHITE = -0x1
    private const val BLACK = -0x1000000
    private const val DARK_OUTLINE = 0xFF141414.toInt()
}
