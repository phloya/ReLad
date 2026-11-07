package plus.enhance

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import plus.core.image.PageBitmaps
import plus.core.pipeline.PageContext
import plus.core.pipeline.PageProcessor
import plus.core.pipeline.ProcessedPage

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
    )

    override val id = "enhance"
    override val order = 200

    private val mutex = Mutex()
    private var loaded: NativeUpscaler? = null
    private var loadedKey: String? = null

    override fun signature(context: PageContext): String {
        val s = settings()
        val models = s.models.values.joinToString(",") { it.id }
        return "v$VERSION|${s.strength ?: "auto"}|white=${s.whitenPaper}|max=${s.maxInputSide}|$models"
    }

    override suspend fun process(input: ByteArray, context: PageContext): ProcessedPage {
        val s = settings()
        val size = PageBitmaps.size(input) ?: error("Not an image")
        if (maxOf(size.width, size.height) > s.maxInputSide) return ProcessedPage(input)

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
                )
                val bytes = PageBitmaps.encode(result, Bitmap.CompressFormat.JPEG, quality = 95)
                result.recycle()
                ProcessedPage(bytes)
            }
        }
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
        val strength: Strength,
        val jpegQuality: Int?,
        val whitened: Boolean,
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
