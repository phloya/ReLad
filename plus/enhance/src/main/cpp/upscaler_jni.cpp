// Re:Lad scan enhancement: runs a waifu2x-style image network over a page, tile by tile.
//
// Written for Re:Lad. The tiling scheme (tile + replicated border padding, output cropped to the
// tile) and the CUnet blob names follow waifu2x-ncnn-vulkan by nihui (MIT License,
// https://github.com/nihui/waifu2x-ncnn-vulkan); see plus/LICENSES.md.
// Unlike waifu2x-ncnn-vulkan, pre- and post-processing run on the CPU with ncnn's own pixel
// helpers; only the network runs on the GPU. Simpler, slightly slower.

#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cstring>

#include "cpu.h"
#include "gpu.h"
#include "net.h"

#define TAG "ReLadEnhance"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Upscaler {
    ncnn::Net net;
    int scale = 2;
    int prepadding = 18;
    int tileSize = 200;
    bool gpu = false;
    std::atomic<bool> cancelled{false};
};

// Result codes shared with NativeUpscaler.kt
constexpr int OK = 0;
constexpr int ERROR_BITMAP = 1;
constexpr int ERROR_SIZE = 2;
constexpr int ERROR_NETWORK = 3;
constexpr int CANCELLED = 4;

int processTiles(Upscaler* u, const unsigned char* in, int w, int h, int inStride, unsigned char* out, int outStride) {
    const int scale = u->scale;
    const int tile = u->tileSize;
    const int pad = u->prepadding;
    const float norm[3] = {1 / 255.f, 1 / 255.f, 1 / 255.f};
    const float denorm[3] = {255.f, 255.f, 255.f};

    for (int ty = 0; ty < h; ty += tile) {
        const int tileH = std::min(tile, h - ty);
        // The network needs even sizes when scaling by 2: round the bottom padding up
        const int padBottom = pad + (tileH + 1) / 2 * 2 - tileH;

        for (int tx = 0; tx < w; tx += tile) {
            if (u->cancelled.load()) return CANCELLED;

            const int tileW = std::min(tile, w - tx);
            const int padRight = pad + (tileW + 1) / 2 * 2 - tileW;

            // Tile plus as much real neighbourhood as the page has
            const int x0 = std::max(tx - pad, 0);
            const int y0 = std::max(ty - pad, 0);
            const int x1 = std::min(tx + tileW + padRight, w);
            const int y1 = std::min(ty + tileH + padBottom, h);

            ncnn::Mat region = ncnn::Mat::from_pixels_roi(
                in, ncnn::Mat::PIXEL_RGBA2RGB, w, h, x0, y0, x1 - x0, y1 - y0);
            (void)inStride;
            region.substract_mean_normalize(nullptr, norm);

            // Where the page ends, repeat its edge pixels
            ncnn::Mat padded;
            ncnn::copy_make_border(
                region, padded,
                pad - (ty - y0), (ty + tileH + padBottom) - y1,
                pad - (tx - x0), (tx + tileW + padRight) - x1,
                ncnn::BORDER_REPLICATE, 0.f, u->net.opt);

            ncnn::Mat result;
            {
                ncnn::Extractor ex = u->net.create_extractor();
                if (ex.input("Input1", padded) != 0 || ex.extract("Eltwise4", result) != 0) {
                    LOGE("network failed at tile %d,%d", tx, ty);
                    return ERROR_NETWORK;
                }
            }
            if (result.w < tileW * scale || result.h < tileH * scale) {
                LOGE("unexpected output %dx%d for tile %dx%d", result.w, result.h, tileW, tileH);
                return ERROR_NETWORK;
            }

            // The network crops the padding itself: the output starts at the tile's top-left
            result.substract_mean_normalize(nullptr, denorm);
            ncnn::Mat cropped;
            ncnn::copy_cut_border(result, cropped, 0, result.h - tileH * scale, 0, result.w - tileW * scale, u->net.opt);
            unsigned char* dst = out + (size_t)(ty * scale) * outStride + (size_t)(tx * scale) * 4;
            cropped.to_pixels(dst, ncnn::Mat::PIXEL_RGB2RGBA, outStride);
        }
    }
    return OK;
}

} // namespace

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM*, void*) {
    ncnn::create_gpu_instance();
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNI_OnUnload(JavaVM*, void*) {
    ncnn::destroy_gpu_instance();
}

JNIEXPORT jboolean JNICALL
Java_plus_enhance_NativeUpscaler_nativeHasGpu(JNIEnv*, jclass) {
    return ncnn::get_gpu_count() > 0;
}

JNIEXPORT jstring JNICALL
Java_plus_enhance_NativeUpscaler_nativeGpuName(JNIEnv* env, jclass) {
    if (ncnn::get_gpu_count() == 0) return nullptr;
    return env->NewStringUTF(ncnn::get_gpu_info(ncnn::get_default_gpu_index()).device_name());
}

JNIEXPORT jlong JNICALL
Java_plus_enhance_NativeUpscaler_nativeCreate(
    JNIEnv* env, jclass, jstring paramPath, jstring modelPath,
    jint scale, jint prepadding, jint tileSize, jboolean useGpu) {
    auto* u = new Upscaler();
    u->scale = scale;
    u->prepadding = prepadding;
    u->tileSize = tileSize;
    u->gpu = useGpu && ncnn::get_gpu_count() > 0;

    u->net.opt.use_vulkan_compute = u->gpu;
    u->net.opt.use_fp16_packed = true;
    u->net.opt.use_fp16_storage = true;
    u->net.opt.use_fp16_arithmetic = false;
    u->net.opt.num_threads = ncnn::get_big_cpu_count();
    if (u->gpu) u->net.set_vulkan_device(ncnn::get_default_gpu_index());

    const char* param = env->GetStringUTFChars(paramPath, nullptr);
    const char* model = env->GetStringUTFChars(modelPath, nullptr);
    const bool loaded = u->net.load_param(param) == 0 && u->net.load_model(model) == 0;
    env->ReleaseStringUTFChars(paramPath, param);
    env->ReleaseStringUTFChars(modelPath, model);
    if (!loaded) {
        LOGE("could not load model");
        delete u;
        return 0;
    }
    LOGI("model loaded, gpu=%d, tile=%d", u->gpu ? 1 : 0, tileSize);
    return reinterpret_cast<jlong>(u);
}

JNIEXPORT jboolean JNICALL
Java_plus_enhance_NativeUpscaler_nativeUsesGpu(JNIEnv*, jclass, jlong handle) {
    return reinterpret_cast<Upscaler*>(handle)->gpu;
}

JNIEXPORT jint JNICALL
Java_plus_enhance_NativeUpscaler_nativeProcess(JNIEnv* env, jclass, jlong handle, jobject input, jobject output) {
    auto* u = reinterpret_cast<Upscaler*>(handle);
    u->cancelled = false;

    AndroidBitmapInfo inInfo, outInfo;
    if (AndroidBitmap_getInfo(env, input, &inInfo) < 0 || AndroidBitmap_getInfo(env, output, &outInfo) < 0) {
        return ERROR_BITMAP;
    }
    if (inInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || outInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return ERROR_BITMAP;
    }
    if (outInfo.width != inInfo.width * u->scale || outInfo.height != inInfo.height * u->scale) {
        return ERROR_SIZE;
    }

    void* inPixels = nullptr;
    void* outPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, input, &inPixels) < 0) return ERROR_BITMAP;
    if (AndroidBitmap_lockPixels(env, output, &outPixels) < 0) {
        AndroidBitmap_unlockPixels(env, input);
        return ERROR_BITMAP;
    }

    // from_pixels_roi expects tightly packed rows; Android bitmaps of ARGB_8888 are (stride = width * 4)
    int result = ERROR_BITMAP;
    if (inInfo.stride == inInfo.width * 4) {
        result = processTiles(
            u, static_cast<const unsigned char*>(inPixels), (int)inInfo.width, (int)inInfo.height, (int)inInfo.stride,
            static_cast<unsigned char*>(outPixels), (int)outInfo.stride);
    }

    AndroidBitmap_unlockPixels(env, output);
    AndroidBitmap_unlockPixels(env, input);
    return result;
}

JNIEXPORT void JNICALL
Java_plus_enhance_NativeUpscaler_nativeCancel(JNIEnv*, jclass, jlong handle) {
    reinterpret_cast<Upscaler*>(handle)->cancelled = true;
}

JNIEXPORT void JNICALL
Java_plus_enhance_NativeUpscaler_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<Upscaler*>(handle);
}

} // extern "C"
