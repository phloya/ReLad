package plus.enhance

import android.graphics.Bitmap
import java.io.Closeable

/** Kotlin side of upscaler_jni.cpp: one loaded network. Not thread-safe; use from one thread at a time. */
class NativeUpscaler private constructor(private var handle: Long, val scale: Int) : Closeable {

    class UpscaleException(message: String) : Exception(message)

    val usesGpu: Boolean get() = nativeUsesGpu(handle)

    /** Upscales [input] (ARGB_8888) into a new bitmap [scale] times larger. */
    fun process(input: Bitmap): Bitmap {
        check(handle != 0L) { "Closed" }
        val source = if (input.config == Bitmap.Config.ARGB_8888) input else input.copy(Bitmap.Config.ARGB_8888, false)
        val output = Bitmap.createBitmap(source.width * scale, source.height * scale, Bitmap.Config.ARGB_8888)
        val code = nativeProcess(handle, source, output)
        if (source !== input) source.recycle()
        if (code != 0) {
            output.recycle()
            throw UpscaleException(
                when (code) {
                    CANCELLED -> "Cancelled"
                    ERROR_NETWORK -> "The neural network failed"
                    else -> "Bad image (code $code)"
                },
            )
        }
        return output
    }

    /** Stops a running [process] after the current tile. */
    fun cancel() {
        if (handle != 0L) nativeCancel(handle)
    }

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    companion object {
        private const val ERROR_NETWORK = 3
        private const val CANCELLED = 4

        init {
            System.loadLibrary("relad_enhance")
        }

        val hasGpu: Boolean get() = nativeHasGpu()
        val gpuName: String? get() = nativeGpuName()

        fun load(files: ModelFiles, model: EnhanceModel, useGpu: Boolean, tileSize: Int): NativeUpscaler {
            val handle = nativeCreate(
                files.param.absolutePath,
                files.weights.absolutePath,
                model.scale,
                model.prepadding,
                tileSize,
                useGpu,
            )
            if (handle == 0L) throw UpscaleException("Could not load the model ${model.id}")
            return NativeUpscaler(handle, model.scale)
        }

        @JvmStatic private external fun nativeHasGpu(): Boolean

        @JvmStatic private external fun nativeGpuName(): String?

        @JvmStatic private external fun nativeCreate(
            paramPath: String,
            modelPath: String,
            scale: Int,
            prepadding: Int,
            tileSize: Int,
            useGpu: Boolean,
        ): Long

        @JvmStatic private external fun nativeUsesGpu(handle: Long): Boolean

        @JvmStatic private external fun nativeProcess(handle: Long, input: Bitmap, output: Bitmap): Int

        @JvmStatic private external fun nativeCancel(handle: Long)

        @JvmStatic private external fun nativeDestroy(handle: Long)
    }
}
