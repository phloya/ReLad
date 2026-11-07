// Re:Lad scan enhancement: runs an image network (Real-CUGAN) over a page, tile by tile.
//
// Written for Re:Lad. The tiling scheme (tile + border padding, sizes rounded up to even, output
// starting at the tile's top-left) and the idea of sharing the SE blocks' pooled features between
// tiles follow realcugan-ncnn-vulkan and waifu2x-ncnn-vulkan by nihui (MIT License,
// https://github.com/nihui/realcugan-ncnn-vulkan); see plus/LICENSES.md.
// Unlike those, pre- and post-processing run on the CPU with ncnn's own pixel helpers; only the
// network runs on the GPU. Simpler, slightly slower.
//
// Why shared features: Real-CUGAN's SE blocks average each feature map over the whole input. Run on
// tiles separately, every tile gets its own average and flat areas change tone from tile to tile.
// So the page's tiles are first run up to the last SE block, their averages ("gap0".."gap3") are
// averaged (weighted by tile area), and every tile is then run with those page-wide values.
// realcugan's default (small 32 px sample tiles) washed clean pages out in our tests: the averages
// of such small inputs are dominated by their borders. Real tiles cost about 1.5x a plain run and
// come out like the whole page processed at once (plus/tools/enhance-bench).

#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cstring>
#include <string>
#include <vector>

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
    bool reflectBorder = true;
    std::string inputBlob;
    std::string outputBlob;
    std::vector<std::string> sharedBlobs;
    std::atomic<bool> cancelled{false};
};

// Result codes shared with NativeUpscaler.kt
constexpr int OK = 0;
constexpr int ERROR_BITMAP = 1;
constexpr int ERROR_SIZE = 2;
constexpr int ERROR_NETWORK = 3;
constexpr int CANCELLED = 4;

/**
 * The network input for the tile [tx, tx+tileW) x [ty, ty+tileH): the tile, its real neighbourhood
 * up to prepadding pixels, the page edge mirrored where the page ends, sizes rounded up to even.
 */
ncnn::Mat tileInput(const Upscaler* u, const unsigned char* in, int w, int h, int tx, int ty, int tileW, int tileH) {
    const int pad = u->prepadding;
    const int padRight = pad + (tileW + 1) / 2 * 2 - tileW;
    const int padBottom = pad + (tileH + 1) / 2 * 2 - tileH;

    const int x0 = std::max(tx - pad, 0);
    const int y0 = std::max(ty - pad, 0);
    const int x1 = std::min(tx + tileW + padRight, w);
    const int y1 = std::min(ty + tileH + padBottom, h);

    ncnn::Mat region = ncnn::Mat::from_pixels_roi(in, ncnn::Mat::PIXEL_RGBA2RGB, w, h, x0, y0, x1 - x0, y1 - y0);
    const float norm[3] = {1 / 255.f, 1 / 255.f, 1 / 255.f};
    region.substract_mean_normalize(nullptr, norm);

    const int top = pad - (ty - y0);
    const int bottom = (ty + tileH + padBottom) - y1;
    const int left = pad - (tx - x0);
    const int right = (tx + tileW + padRight) - x1;
    // Mirroring needs more pixels than it mirrors; tiny pages repeat the edge instead
    const bool canReflect = u->reflectBorder && region.w > std::max(left, right) && region.h > std::max(top, bottom);
    ncnn::Mat padded;
    ncnn::copy_make_border(
        region, padded, top, bottom, left, right,
        canReflect ? ncnn::BORDER_REFLECT : ncnn::BORDER_REPLICATE, 0.f, u->net.opt);
    return padded;
}

/** Page-wide values of the shared blobs, averaged over the page's tiles. Empty if the model has none. */
int sharedFeatures(Upscaler* u, const unsigned char* in, int w, int h, std::vector<ncnn::Mat>& out) {
    out.clear();
    if (u->sharedBlobs.empty()) return OK;

    const int tile = u->tileSize;
    std::vector<ncnn::Mat> sums;
    double area = 0;
    for (int ty = 0; ty < h; ty += tile) {
        const int tileH = std::min(tile, h - ty);
        for (int tx = 0; tx < w; tx += tile) {
            if (u->cancelled.load()) return CANCELLED;
            const int tileW = std::min(tile, w - tx);
            const float weight = (float)tileW * (float)tileH;

            ncnn::Mat input = tileInput(u, in, w, h, tx, ty, tileW, tileH);
            ncnn::Extractor ex = u->net.create_extractor();
            if (ex.input(u->inputBlob.c_str(), input) != 0) return ERROR_NETWORK;
            for (size_t i = 0; i < u->sharedBlobs.size(); i++) {
                ncnn::Mat feature;
                if (ex.extract(u->sharedBlobs[i].c_str(), feature) != 0) {
                    LOGE("no blob %s", u->sharedBlobs[i].c_str());
                    return ERROR_NETWORK;
                }
                if (sums.size() <= i) {
                    sums.push_back(feature.clone());
                    float* sum = sums[i];
                    for (size_t k = 0; k < sums[i].total(); k++) sum[k] *= weight;
                } else {
                    float* sum = sums[i];
                    const float* f = feature;
                    const size_t n = std::min(sums[i].total(), feature.total());
                    for (size_t k = 0; k < n; k++) sum[k] += f[k] * weight;
                }
            }
            area += weight;
        }
    }
    if (area == 0) return OK;
    for (ncnn::Mat& sum : sums) {
        float* p = sum;
        for (size_t k = 0; k < sum.total(); k++) p[k] = (float)(p[k] / area);
    }
    out = sums;
    return OK;
}

int processTiles(Upscaler* u, const unsigned char* in, int w, int h, unsigned char* out, int outStride) {
    const int scale = u->scale;
    const int tile = u->tileSize;

    std::vector<ncnn::Mat> shared;
    const int sharedResult = sharedFeatures(u, in, w, h, shared);
    if (sharedResult != OK) return sharedResult;

    for (int ty = 0; ty < h; ty += tile) {
        const int tileH = std::min(tile, h - ty);
        for (int tx = 0; tx < w; tx += tile) {
            if (u->cancelled.load()) return CANCELLED;
            const int tileW = std::min(tile, w - tx);

            ncnn::Mat input = tileInput(u, in, w, h, tx, ty, tileW, tileH);
            ncnn::Mat result;
            {
                ncnn::Extractor ex = u->net.create_extractor();
                bool ok = ex.input(u->inputBlob.c_str(), input) == 0;
                for (size_t i = 0; ok && i < shared.size(); i++) {
                    ok = ex.input(u->sharedBlobs[i].c_str(), shared[i]) == 0;
                }
                if (!ok || ex.extract(u->outputBlob.c_str(), result) != 0) {
                    LOGE("network failed at tile %d,%d", tx, ty);
                    return ERROR_NETWORK;
                }
            }
            if (result.w < tileW * scale || result.h < tileH * scale) {
                LOGE("unexpected output %dx%d for tile %dx%d", result.w, result.h, tileW, tileH);
                return ERROR_NETWORK;
            }

            // The network crops the padding itself: the output starts at the tile's top-left
            const float denorm[3] = {255.f, 255.f, 255.f};
            result.substract_mean_normalize(nullptr, denorm);
            ncnn::Mat cropped;
            ncnn::copy_cut_border(result, cropped, 0, result.h - tileH * scale, 0, result.w - tileW * scale, u->net.opt);
            unsigned char* dst = out + (size_t)(ty * scale) * outStride + (size_t)(tx * scale) * 4;
            cropped.to_pixels(dst, ncnn::Mat::PIXEL_RGB2RGBA, outStride);
        }
    }
    return OK;
}

std::string toString(JNIEnv* env, jstring s) {
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(s, chars);
    return result;
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
    jint scale, jint prepadding, jint tileSize, jboolean useGpu,
    jstring inputBlob, jstring outputBlob, jobjectArray sharedBlobs, jboolean reflectBorder) {
    auto* u = new Upscaler();
    u->scale = scale;
    u->prepadding = prepadding;
    u->tileSize = tileSize;
    u->gpu = useGpu && ncnn::get_gpu_count() > 0;
    u->reflectBorder = reflectBorder;
    u->inputBlob = toString(env, inputBlob);
    u->outputBlob = toString(env, outputBlob);
    for (jsize i = 0; i < env->GetArrayLength(sharedBlobs); i++) {
        auto blob = (jstring)env->GetObjectArrayElement(sharedBlobs, i);
        u->sharedBlobs.push_back(toString(env, blob));
        env->DeleteLocalRef(blob);
    }

    u->net.opt.use_vulkan_compute = u->gpu;
    u->net.opt.use_fp16_packed = true;
    u->net.opt.use_fp16_storage = true;
    u->net.opt.use_fp16_arithmetic = false;
    u->net.opt.num_threads = ncnn::get_big_cpu_count();
    if (u->gpu) u->net.set_vulkan_device(ncnn::get_default_gpu_index());

    const std::string param = toString(env, paramPath);
    const std::string model = toString(env, modelPath);
    if (u->net.load_param(param.c_str()) != 0 || u->net.load_model(model.c_str()) != 0) {
        LOGE("could not load model");
        delete u;
        return 0;
    }
    LOGI("model loaded, gpu=%d, tile=%d, shared blobs=%d", u->gpu ? 1 : 0, tileSize, (int)u->sharedBlobs.size());
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
            u, static_cast<const unsigned char*>(inPixels), (int)inInfo.width, (int)inInfo.height,
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
