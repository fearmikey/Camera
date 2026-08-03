#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include "PanoramaEngine.hpp"

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_fearmikey_camera_data_engine_processing_NativePanoramaBridge_nativeInit(
        JNIEnv* env, jobject /* thiz */, jdouble blurThreshold, jfloat matchRatio) {
    __android_log_print(ANDROID_LOG_INFO, "PanoramaEngineNDK", "nativeInit called: blur=%.1f, ratio=%.2f", blurThreshold, matchRatio);
    auto* engine = new PanoramaEngine(blurThreshold, matchRatio);
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT jboolean JNICALL
Java_com_fearmikey_camera_data_engine_processing_NativePanoramaBridge_nativeProcessFrame(
        JNIEnv* env, jobject /* thiz */, jlong enginePtr, jobject buffer,
        jint width, jint height, jint stride, jint format, jint rotationDegrees, jlong timestamp) {

    auto* engine = reinterpret_cast<PanoramaEngine*>(enginePtr);
    if (!engine) return JNI_FALSE;

    auto* bufferAddr = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (!bufferAddr) return JNI_FALSE;

    bool success = engine->processFrame(bufferAddr, width, height, stride, format, rotationDegrees, timestamp);
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

    bool success = engine->finalizeStitch();

    if (success) {
        cv::Mat outputMat;
        if (engine->getResult(outputMat) && !outputMat.empty()) {
            cv::Mat bitmapMat(static_cast<int>(info.height), static_cast<int>(info.width), CV_8UC4, pixels, static_cast<int>(info.stride));

            // Clear the target bitmap (ensures alpha is 255 for non-filled areas)
            bitmapMat.setTo(cv::Scalar(0, 0, 0, 255));

            // Fit-to-center scaling logic
            float scale = std::min(static_cast<float>(info.width) / static_cast<float>(outputMat.cols),
                                 static_cast<float>(info.height) / static_cast<float>(outputMat.rows));

            int newW = static_cast<int>(static_cast<float>(outputMat.cols) * scale);
            int newH = static_cast<int>(static_cast<float>(outputMat.rows) * scale);

            int offsetX = (static_cast<int>(info.width) - newW) / 2;
            int offsetY = (static_cast<int>(info.height) - newH) / 2;

            cv::Mat targetROI = bitmapMat(cv::Rect(offsetX, offsetY, newW, newH));
            cv::resize(outputMat, targetROI, targetROI.size(), 0, 0, cv::INTER_LANCZOS4);

            __android_log_print(ANDROID_LOG_INFO, "PanoramaEngineNDK", "Panorama rendered to bitmap: %dx%d (original: %dx%d)", newW, newH, outputMat.cols, outputMat.rows);
        } else {
            success = false;
        }
    }

    AndroidBitmap_unlockPixels(env, outputBitmap);
    return success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jintArray JNICALL
Java_com_fearmikey_camera_data_engine_processing_NativePanoramaBridge_nativeGetResultSize(
        JNIEnv* env, jobject /* thiz */, jlong enginePtr) {

    auto* engine = reinterpret_cast<PanoramaEngine*>(enginePtr);
    jintArray result = env->NewIntArray(2);
    if (!engine) return result;

    cv::Mat outputMat;
    if (engine->getResult(outputMat)) {
        jint dims[2] = {outputMat.cols, outputMat.rows};
        env->SetIntArrayRegion(result, 0, 2, dims);
    }

    return result;
}

JNIEXPORT void JNICALL
Java_com_fearmikey_camera_data_engine_processing_NativePanoramaBridge_nativeRelease(
        JNIEnv* env, jobject /* thiz */, jlong enginePtr) {
    auto* engine = reinterpret_cast<PanoramaEngine*>(enginePtr);
    delete engine;
}

JNIEXPORT void JNICALL
Java_com_fearmikey_camera_data_engine_processing_NativePanoramaBridge_nativeReset(
        JNIEnv* env, jobject /* thiz */, jlong enginePtr) {
    auto* engine = reinterpret_cast<PanoramaEngine*>(enginePtr);
    if (engine) engine->reset();
}

}
