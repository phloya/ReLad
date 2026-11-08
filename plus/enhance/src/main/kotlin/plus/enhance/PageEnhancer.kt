package plus.enhance

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import plus.core.ai.AiException
import plus.core.image.PageBitmaps
import plus.core.pipeline.PageContext
import plus.core.pipeline.PageProcessor
import plus.core.pipeline.ProcessedPage
import plus.enhance.cloud.ReplicateModel
import plus.enhance.cloud.ReplicateUpscaler

/**
 * The enhancement step of the page pipeline: whitens the paper, removes noise and JPEG blocks and
 * upscales the page ×2 on the phone. One page at a time (the GPU is shared); the loaded network is
 * kept between pages.
 */
class PageEnhancer(
    private val settings: () -> Settings,
    private val store: ModelStore,
) : PageProcessor {

    class Settings(
        /** `null`: pick per page from how compressed it is ([ScanQuality]). */
        val strength: Strength?,
        val whitenPaper: Boolean,
        val useGpu: Boolean,
        /** Pages already larger than this on their longest side are left as they are. */
        val maxInputSide: Int,
        val models: Map<Strength, EnhanceModel> = EnhanceModels.cugan,
        /** Not `null`: upscale on Replicate instead of the phone. */
        val cloud: Cloud? = null,
    )

    /** [upscaler] is `null` when no token is saved. */
    class Cloud(val upscaler: ReplicateUpscaler?, val model: ReplicateModel)

    override val id = "enhance"
    override val order = 200

    private val mutex = Mutex()
    private var loaded: NativeUpscaler? = null
    private var loadedKey: String? = null

    override fun signature(context: PageContext): String {
        val s = settings()
        s.cloud?.let { cloud ->
            val m = cloud.model
            val common = "white=${s.whitenPaper}|max=${s.maxInputSide}"
            return "v$VERSION|replicate|${m.model}|${m.imageField}|${m.extraInput}|$common"
        }
        val models = s.models.values.joinToString(",") { it.id }
        return "v$VERSION|${s.strength ?: "auto"}|white=${s.whitenPaper}|max=${s.maxInputSide}|$models"
    }

    override suspend fun process(input: ByteArray, context: PageContext): ProcessedPage {
        val s = settings()
        val size = PageBitmaps.size(input) ?: error("Not an image")
        if (maxOf(size.width, size.height) > s.maxInputSide) return ProcessedPage(input)
        s.cloud?.let { return processInCloud(input, size, s, it) }

        val strength = s.strength ?: ScanQuality.strengthFor(input)
        val model = s.models.getValue(strength)
        val files = store.get(model)
        return mutex.withLock {
            withContext(Dispatchers.Default) {
                val upscaler = upscaler(s, model, files)
                val bitmap = PageBitmaps.decode(input)
                val started = System.currentTimeMillis()
                val prepared = prepare(bitmap, whitenPaper = s.whitenPaper, smoothTone = true)
                val result = try {
                    upscaler.process(bitmap)
                } finally {
                    bitmap.recycle()
                }
                prepared.tone?.let { restoreTexture(result, it) }
                lastRun = Run(
                    width = size.width,
                    height = size.height,
                    millis = System.currentTimeMillis() - started,
                    gpu = upscaler.usesGpu,
                    strength = strength,
                    jpegQuality = ScanQuality.jpegQuality(input),
                    whitened = prepared.whitened,
                    cloudModel = null,
                    toneShare = prepared.tone?.share,
                )
                val bytes = PageBitmaps.encode(result, Bitmap.CompressFormat.JPEG, quality = 95)
                result.recycle()
                ProcessedPage(bytes)
            }
        }
    }

    private suspend fun processInCloud(
        input: ByteArray,
        size: PageBitmaps.Size,
        s: Settings,
        cloud: Cloud,
    ): ProcessedPage {
        val upscaler = cloud.upscaler ?: throw AiException("No Replicate token", AiException.Kind.NoKey)
        val started = System.currentTimeMillis()
        // Whitening is cheap and makes the model's job easier; the page is sent as it is otherwise
        var whitened = false
        val page = if (s.whitenPaper) {
            withContext(Dispatchers.Default) {
                val bitmap = PageBitmaps.decode(input)
                try {
                    whitened = prepare(bitmap, whitenPaper = true, smoothTone = false).whitened
                    if (whitened) PageBitmaps.encode(bitmap, Bitmap.CompressFormat.JPEG, quality = 95) else input
                } finally {
                    bitmap.recycle()
                }
            }
        } else {
            input
        }
        val result = upscaler.upscale(page, PageBitmaps.mimeType(page), cloud.model)
        PageBitmaps.size(result)
            ?: throw AiException("Replicate returned something that is not an image", AiException.Kind.BadResponse)
        lastRun = Run(
            width = size.width,
            height = size.height,
            millis = System.currentTimeMillis() - started,
            gpu = false,
            strength = null,
            jpegQuality = ScanQuality.jpegQuality(input),
            whitened = whitened,
            cloudModel = cloud.model.name,
        )
        return ProcessedPage(result)
    }

    private class Prepared(val whitened: Boolean, val tone: Screentone.Smoothed?)

    /**
     * Gets [bitmap] ready for the network, in place: whitens the paper and smooths screentone the
     * network would turn into "worms" ([Screentone]; its texture goes back in [restoreTexture]).
     * Pages for the cloud keep their tone: those are other models.
     */
    private fun prepare(bitmap: Bitmap, whitenPaper: Boolean, smoothTone: Boolean): Prepared {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val gains = if (whitenPaper) PaperWhitening.analyze(pixels) else null
        gains?.let { PaperWhitening.apply(pixels, it) }
        val tone = if (smoothTone) Screentone.smooth(pixels, w, h) else null
        if (gains != null || tone != null) bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return Prepared(whitened = gains != null, tone = tone)
    }

    /** Puts the screentone's texture back into the network's [result], a band of rows at a time. */
    private fun restoreTexture(result: Bitmap, tone: Screentone.Smoothed) {
        val w = result.width
        val band = IntArray(w * TEXTURE_BAND)
        for (y in 0 until result.height step TEXTURE_BAND) {
            val rows = minOf(TEXTURE_BAND, result.height - y)
            result.getPixels(band, 0, w, 0, y, w, rows)
            tone.restoreTexture(band, y, rows)
            result.setPixels(band, 0, w, 0, y, w, rows)
        }
    }

    /** Last processed page, for the settings screen ("1000×1500 in 2.1 s on the GPU, strong cleaning"). */
    class Run(
        val width: Int,
        val height: Int,
        val millis: Long,
        val gpu: Boolean,
        /** `null` in the cloud. */
        val strength: Strength?,
        val jpegQuality: Int?,
        val whitened: Boolean,
        /** Set when the page was upscaled on Replicate. */
        val cloudModel: String?,
        /** Share of the page smoothed as screentone, `null` if none was ([Screentone]). */
        val toneShare: Float? = null,
    )

    @Volatile
    var lastRun: Run? = null
        private set

    private fun upscaler(s: Settings, model: EnhanceModel, files: ModelFiles): NativeUpscaler {
        val key = "${model.id}|${s.useGpu}"
        loaded?.takeIf { loadedKey == key }?.let { return it }
        loaded?.close()
        loaded = null
        return NativeUpscaler.load(files, model, s.useGpu, TILE_SIZE).also {
            loaded = it
            loadedKey = key
        }
    }

    private companion object {
        /**
         * 1: waifu2x. 2: Real-CUGAN, SE features from 32 px samples (washed clean pages out).
         * 3: Real-CUGAN, SE features from the real tiles.
         * 4: unresolvable screentone smoothed before the network (the tone came out flat, "soapy").
         * 5: its texture put back after the network.
         * 6: automatic strength only Conservative or Light (no Medium/Strong).
         */
        const val VERSION = 6
        const val TILE_SIZE = 200
        const val TEXTURE_BAND = 128
    }
}
