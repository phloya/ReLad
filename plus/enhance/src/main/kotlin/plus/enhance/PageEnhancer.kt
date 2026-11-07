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
 * The enhancement step of the page pipeline: denoises and upscales the page ×2 on the phone.
 * One page at a time (the GPU is shared); the loaded network is kept between pages.
 */
class PageEnhancer(
    private val settings: () -> Settings,
    private val store: ModelStore,
) : PageProcessor {

    class Settings(
        val model: EnhanceModel,
        val useGpu: Boolean,
        /** Pages already larger than this on their longest side are left as they are. */
        val maxInputSide: Int,
    )

    override val id = "enhance"
    override val order = 200

    private val mutex = Mutex()
    private var loaded: NativeUpscaler? = null
    private var loadedKey: String? = null

    override fun signature(context: PageContext): String {
        val s = settings()
        return "v$VERSION|${s.model.id}|max=${s.maxInputSide}"
    }

    override suspend fun process(input: ByteArray, context: PageContext): ProcessedPage {
        val s = settings()
        val size = PageBitmaps.size(input) ?: error("Not an image")
        if (maxOf(size.width, size.height) > s.maxInputSide) return ProcessedPage(input)

        val files = store.get(s.model)
        return mutex.withLock {
            withContext(Dispatchers.Default) {
                val upscaler = upscaler(s, files)
                val bitmap = PageBitmaps.decode(input)
                val started = System.currentTimeMillis()
                val result = try {
                    upscaler.process(bitmap)
                } finally {
                    bitmap.recycle()
                }
                lastRun = Run(size.width, size.height, System.currentTimeMillis() - started, upscaler.usesGpu)
                val bytes = PageBitmaps.encode(result, Bitmap.CompressFormat.JPEG, quality = 95)
                result.recycle()
                ProcessedPage(bytes)
            }
        }
    }

    /** Last processed page, for the settings screen ("1000×1500 in 2.1 s on the GPU"). */
    class Run(val width: Int, val height: Int, val millis: Long, val gpu: Boolean)

    @Volatile
    var lastRun: Run? = null
        private set

    private fun upscaler(s: Settings, files: ModelFiles): NativeUpscaler {
        val key = "${s.model.id}|${s.useGpu}"
        loaded?.takeIf { loadedKey == key }?.let { return it }
        loaded?.close()
        return NativeUpscaler.load(files, s.model, s.useGpu, TILE_SIZE).also {
            loaded = it
            loadedKey = key
        }
    }

    private companion object {
        const val VERSION = 1
        const val TILE_SIZE = 200
    }
}
