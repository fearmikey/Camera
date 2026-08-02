#include <jni.h>
#include <android/bitmap.h>
#include "PanoramaEngine.hpp"

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_fearmikey_camera_data_engine_processing_NativePanoramaBridge_nativeInit(
        JNIEnv* env, jobject /* thiz */, jdouble blurThreshold, jfloat matchRatio) {
    auto* engine = new PanoramaEngine(blurThreshold, matchRatio);
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT jboolean JNICALL
Java_com_fearmikey_camera_data_engine_processing_NativePanoramaBridge_nativeProcessFrame(
        JNIEnv* env, jobject /* thiz */, jlong enginePtr, jobject buffer,
        jint width, jint height, jint stride, jint format, jlong timestamp) {

    auto* engine = reinterpret_cast<PanoramaEngine*>(enginePtr);
    if (!engine) return JNI_FALSE;

    auto* bufferAddr = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (!bufferAddr) return JNI_FALSE;

    bool success = engine->processFrame(bufferAddr, width, height, stride, format, timestamp);
    return success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_fearmikey_camera_data_engine_processing_NativePanoramaBridge_nativeFinalizeStitch(
        JNIEnv* env, jobject /* thiz */, jlong enginePtr, jobject outputBitmap) {

    auto* engine = reinterpret_cast<PanoramaEngine*>(enginePtr);
    if (!engine) return JNI_FALSE;

    AndroidBitmapInfo info;
    void* pixels = nullptr;

    if (AndroidBitmap_getInfo(env, outputBitmap, &info) < 0) return JNI_FALSE;
    if (AndroidBitmap_lockPixels(env, outputBitmap, &pixels) < 0) return JNI_FALSE;

    cv::Mat outputMat;
    bool success = engine->finalizeStitch(outputMat);

    if (success && !outputMat.empty()) {
        cv::Mat bitmapMat(info.height, info.width, CV_8UC4, pixels, info.stride);
        cv::resize(outputMat, bitmapMat, bitmapMat.size());
    }

    AndroidBitmap_unlockPixels(env, outputBitmap);
    return success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_fearmikey_camera_data_engine_processing_NativePanoramaBridge_nativeRelease(
        JNIEnv* env, jobject /* thiz */, jlong enginePtr) {
    auto* engine = reinterpret_cast<PanoramaEngine*>(enginePtr);
    delete engine;
}

}
