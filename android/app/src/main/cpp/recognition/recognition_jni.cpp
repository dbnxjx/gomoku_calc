// App 调用棋盘识别的 JNI 接口，见 com.gomocalc.app.recognition.RecognitionNative。
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <jni.h>

#include <algorithm>
#include <memory>
#include <mutex>

#include "recognition_core.h"

namespace {

std::mutex g_mutex;
std::unique_ptr<recog::Recognizer> g_recognizer;

bool bitmapToImage(JNIEnv* env, jobject bitmap, recog::Image& out)
{
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
        return false;
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return false;
    out = recog::Image(static_cast<int>(info.width), static_cast<int>(info.height));
    for (uint32_t y = 0; y < info.height; y++)
    {
        const uint8_t* row = static_cast<const uint8_t*>(pixels) + static_cast<size_t>(y) * info.stride;
        uint8_t* dst = out.rgb.data() + static_cast<size_t>(y) * info.width * 3;
        for (uint32_t x = 0; x < info.width; x++)
        {
            dst[x * 3] = row[x * 4];
            dst[x * 3 + 1] = row[x * 4 + 1];
            dst[x * 3 + 2] = row[x * 4 + 2];
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return true;
}

bool imageToBitmap(JNIEnv* env, const recog::Image& image, jobject bitmap)
{
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888
        || static_cast<int>(info.width) != image.w || static_cast<int>(info.height) != image.h)
        return false;
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return false;
    for (int y = 0; y < image.h; y++)
    {
        uint8_t* row = static_cast<uint8_t*>(pixels) + static_cast<size_t>(y) * info.stride;
        const uint8_t* src = image.rgb.data() + static_cast<size_t>(y) * image.w * 3;
        for (int x = 0; x < image.w; x++)
        {
            row[x * 4] = src[x * 3];
            row[x * 4 + 1] = src[x * 3 + 1];
            row[x * 4 + 2] = src[x * 3 + 2];
            row[x * 4 + 3] = 255;
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return true;
}

bool readQuad(JNIEnv* env, jfloatArray array, recog::Quad& quad)
{
    if (!array || env->GetArrayLength(array) != 8) return false;
    env->GetFloatArrayRegion(array, 0, 8, &quad.p[0][0]);
    return true;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_gomocalc_app_recognition_RecognitionNative_nativeInit(JNIEnv* env, jclass, jobject assetManager)
{
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_recognizer) return JNI_TRUE;
    AAssetManager* manager = AAssetManager_fromJava(env, assetManager);
    auto recognizer = std::make_unique<recog::Recognizer>();
    if (recognizer->cornerNet().load_param(manager, "recognition/corner.ncnn.param") != 0
        || recognizer->cornerNet().load_model(manager, "recognition/corner.ncnn.bin") != 0
        || recognizer->stoneNet().load_param(manager, "recognition/stone.ncnn.param") != 0
        || recognizer->stoneNet().load_model(manager, "recognition/stone.ncnn.bin") != 0)
        return JNI_FALSE;
    g_recognizer = std::move(recognizer);
    return JNI_TRUE;
}

// rgba 是相机分析帧的直接缓冲区；rotation 是把它转正要顺时针旋转的角度。
// 返回 {分数, 能否构成棋盘(0/1), x0, y0, ..., x3, y3}，坐标是转正后图像上 0~1 的比例。
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_gomocalc_app_recognition_RecognitionNative_nativeDetectLive(JNIEnv* env, jclass, jobject rgba, jint width, jint height, jint stride, jint rotation)
{
    const uint8_t* src = static_cast<const uint8_t*>(env->GetDirectBufferAddress(rgba));
    if (!src) return nullptr;
    const bool swapSides = rotation == 90 || rotation == 270;
    recog::Image image(swapSides ? height : width, swapSides ? width : height);
    for (int y = 0; y < height; y++)
    {
        const uint8_t* row = src + static_cast<size_t>(y) * stride;
        for (int x = 0; x < width; x++)
        {
            int dx = x, dy = y;
            if (rotation == 90)
            {
                dx = height - 1 - y;
                dy = x;
            }
            else if (rotation == 180)
            {
                dx = width - 1 - x;
                dy = height - 1 - y;
            }
            else if (rotation == 270)
            {
                dx = y;
                dy = width - 1 - x;
            }
            uint8_t* out = image.rgb.data() + (static_cast<size_t>(dy) * image.w + dx) * 3;
            out[0] = row[x * 4];
            out[1] = row[x * 4 + 1];
            out[2] = row[x * 4 + 2];
        }
    }
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_recognizer) return nullptr;
    const recog::Detection d = g_recognizer->detectLive(image);
    float values[10] = {d.score, d.plausible ? 1.f : 0.f};
    for (int k = 0; k < 4; k++)
    {
        values[2 + k * 2] = d.quad.p[k][0] / image.w;
        values[3 + k * 2] = d.quad.p[k][1] / image.h;
    }
    jfloatArray result = env->NewFloatArray(10);
    env->SetFloatArrayRegion(result, 0, 10, values);
    return result;
}

// manualQuad 为空时自动定位。返回 JSON，见 Recognition::toJson。
extern "C" JNIEXPORT jstring JNICALL
Java_com_gomocalc_app_recognition_RecognitionNative_nativeRecognize(JNIEnv* env, jclass, jobject bitmap, jfloatArray manualQuad)
{
    recog::Image image;
    if (!bitmapToImage(env, bitmap, image)) return nullptr;
    recog::Quad quad;
    const bool manual = readQuad(env, manualQuad, quad);
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_recognizer) return nullptr;
    const recog::Recognition r = g_recognizer->recognize(image, manual ? &quad : nullptr);
    return env->NewStringUTF(r.toJson().c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_gomocalc_app_recognition_RecognitionNative_nativeRenderCrop(JNIEnv* env, jclass, jobject source, jfloatArray quadArray, jint n, jobject target)
{
    recog::Image image;
    recog::Quad quad;
    if (!bitmapToImage(env, source, image) || !readQuad(env, quadArray, quad)) return JNI_FALSE;
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, target, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    const recog::Image crop = recog::renderCrop(image, quad, n, static_cast<int>(info.width));
    return imageToBitmap(env, crop, target) ? JNI_TRUE : JNI_FALSE;
}
