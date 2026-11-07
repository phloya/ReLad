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
                val whitened = s.whitenPaper && whiten(bitmap)
                val result = try {
                    upscaler.process(bitmap)
                } finally {
                    bitmap.recycle()
                }
                lastRun = Run(
                    width = size.width,
                    height = size.height,
                    millis = System.currentTimeMillis() - started,
                    gpu = upscaler.usesGpu,
                    strength = strength,
                    jpegQuality = ScanQuality.jpegQuality(input),
                    whitened = whitened,
                    cloudModel = null,
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
                    whitened = whiten(bitmap)
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

    /** Whitens the paper of [bitmap] in place; `false` if the page was left as it is. */
    private fun whiten(bitmap: Bitmap): Boolean {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val gains = PaperWhitening.analyze(pixels) ?: return false
        PaperWhitening.apply(pixels, gains)
        bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return true
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
         */
        const val VERSION = 3
        const val TILE_SIZE = 200
    }
}
