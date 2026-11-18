#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <opencv2/imgproc.hpp>
#include "PanoramaEngine.hpp"

#define JNI_FN(name) Java_com_fearmikey_camera_data_engine_processing_NativePanoramaBridge_##name

namespace {

PanoramaEngine* engineFrom(jlong ptr) { return reinterpret_cast<PanoramaEngine*>(ptr); }

/** Locks an RGBA_8888 bitmap and runs [fn](pixels, info). */
template <typename Fn>
bool withBitmap(JNIEnv* env, jobject bitmap, Fn fn) {
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return false;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return false;
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0 || !pixels) return false;
    const bool ok = fn(static_cast<uint8_t*>(pixels), info);
    AndroidBitmap_unlockPixels(env, bitmap);
    return ok;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
JNI_FN(nativeCreate)(JNIEnv*, jobject, jfloat tanHalfFovLongSide) {
    return reinterpret_cast<jlong>(new PanoramaEngine(tanHalfFovLongSide));
}

/**
 * Fills stats = [status, keyframes, sweepDegrees, verticalOffset, stripX, stripWidth, direction,
 * keyframeDx, targetStep, aligned] and returns the status.
 */
JNIEXPORT jint JNICALL
JNI_FN(nativeProcessFrame)(JNIEnv* env, jobject, jlong ptr, jobject buffer, jint width, jint height,
                           jint stride, jint rotationDegrees, jlong timestampNs, jfloatArray stats) {
    auto* engine = engineFrom(ptr);
    if (!engine) return PanoramaEngine::kLost;
    auto* data = static_cast<const uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (!data) return PanoramaEngine::kLost;
    const auto s = engine->processFrame(data, width, height, stride, rotationDegrees, timestampNs);
    if (stats && env->GetArrayLength(stats) >= 10) {
        const jfloat values[10] = {
                static_cast<jfloat>(s.status), static_cast<jfloat>(s.keyframes), s.sweepDegrees,
                s.verticalOffset, s.stripX, s.stripWidth, static_cast<jfloat>(s.direction),
                s.keyframeDx, s.targetStep, static_cast<jfloat>(s.aligned)};
        env->SetFloatArrayRegion(stats, 0, 10, values);
    }
    return s.status;
}

/** [width, height] of the 360-degree preview strip, or [0, 0] before the first frame. */
JNIEXPORT jintArray JNICALL
JNI_FN(nativeGetPreviewSize)(JNIEnv* env, jobject, jlong ptr) {
    jintArray out = env->NewIntArray(2);
    int w = 0, h = 0;
    if (auto* engine = engineFrom(ptr); engine && engine->previewSize(w, h)) {
        const jint dims[2] = {w, h};
        env->SetIntArrayRegion(out, 0, 2, dims);
    }
    return out;
}

JNIEXPORT jboolean JNICALL
JNI_FN(nativeRenderPreview)(JNIEnv* env, jobject, jlong ptr, jobject bitmap) {
    auto* engine = engineFrom(ptr);
    if (!engine) return JNI_FALSE;
    return withBitmap(env, bitmap, [&](uint8_t* px, const AndroidBitmapInfo& info) {
        return engine->renderPreview(px, static_cast<int>(info.width), static_cast<int>(info.height),
                                     static_cast<int>(info.stride));
    }) ? JNI_TRUE : JNI_FALSE;
}

/** Stitches and returns [width, height] of the result, or [0, 0] on failure. */
JNIEXPORT jintArray JNICALL
JNI_FN(nativeStitch)(JNIEnv* env, jobject, jlong ptr) {
    jintArray out = env->NewIntArray(2);
    auto* engine = engineFrom(ptr);
    cv::Mat result;
    if (engine && engine->stitch() && engine->result(result)) {
        const jint dims[2] = {result.cols, result.rows};
        env->SetIntArrayRegion(out, 0, 2, dims);
    }
    return out;
}

/** Copies the stitched result into a bitmap of exactly the size returned by nativeStitch. */
JNIEXPORT jboolean JNICALL
JNI_FN(nativeCopyResult)(JNIEnv* env, jobject, jlong ptr, jobject bitmap) {
    auto* engine = engineFrom(ptr);
    cv::Mat result;
    if (!engine || !engine->result(result)) return JNI_FALSE;
    return withBitmap(env, bitmap, [&](uint8_t* px, const AndroidBitmapInfo& info) {
        if (static_cast<int>(info.width) != result.cols || static_cast<int>(info.height) != result.rows) {
            return false;
        }
        cv::Mat dst(result.rows, result.cols, CV_8UC4, px, info.stride);
        result.copyTo(dst);
        return true;
    }) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
JNI_FN(nativeRelease)(JNIEnv*, jobject, jlong ptr) {
    delete engineFrom(ptr);
}

/** Upright, downscaled copy of an RGBA frame into [bitmap] (idle live thumbnail). */
JNIEXPORT jboolean JNICALL
JNI_FN(nativeThumbnail)(JNIEnv* env, jobject, jobject buffer, jint width, jint height, jint stride,
                        jint rotationDegrees, jobject bitmap) {
    auto* data = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (!data) return JNI_FALSE;
    return withBitmap(env, bitmap, [&](uint8_t* px, const AndroidBitmapInfo& info) {
        cv::Mat raw(height, width, CV_8UC4, data, static_cast<size_t>(stride));
        cv::Mat dst(static_cast<int>(info.height), static_cast<int>(info.width), CV_8UC4, px, info.stride);
        const bool swap = rotationDegrees == 90 || rotationDegrees == 270;
        const cv::Size pre = swap ? cv::Size(dst.rows, dst.cols) : dst.size();
        cv::Mat small;
        cv::resize(raw, small, pre, 0, 0, cv::INTER_AREA);
        switch (rotationDegrees) {
            case 90: cv::rotate(small, dst, cv::ROTATE_90_CLOCKWISE); break;
            case 180: cv::rotate(small, dst, cv::ROTATE_180); break;
            case 270: cv::rotate(small, dst, cv::ROTATE_90_COUNTERCLOCKWISE); break;
            default: small.copyTo(dst); break;
        }
        return dst.data == px; // must not have reallocated
    }) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
