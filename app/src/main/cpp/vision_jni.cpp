// FOSS computational-photography primitives built on OpenCV (Apache-2.0).
// Replaces the previous TensorFlow Lite / MediaPipe based features so the app
// ships without any proprietary or telemetry-bearing dependencies.
//
// Bound to: com.fearmikey.camera.data.engine.processing.NativeVisionBridge

#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/photo.hpp>
#include <opencv2/objdetect.hpp>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <vector>

#define LOG_TAG "VisionNDK"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// ---------------------------------------------------------------------------
// Bitmap helpers
// ---------------------------------------------------------------------------

class LockedBitmap {
public:
    LockedBitmap(JNIEnv* env, jobject bitmap) : mEnv(env), mBitmap(bitmap) {
        if (AndroidBitmap_getInfo(env, bitmap, &mInfo) < 0) return;
        if (mInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
            LOGE("Bitmap must be ARGB_8888");
            return;
        }
        if (AndroidBitmap_lockPixels(env, bitmap, &mPixels) < 0) {
            mPixels = nullptr;
            return;
        }
        mMat = cv::Mat(static_cast<int>(mInfo.height), static_cast<int>(mInfo.width), CV_8UC4,
                       mPixels, static_cast<size_t>(mInfo.stride));
    }

    ~LockedBitmap() {
        if (mPixels) AndroidBitmap_unlockPixels(mEnv, mBitmap);
    }

    bool ok() const { return mPixels != nullptr; }
    cv::Mat& mat() { return mMat; }

private:
    JNIEnv* mEnv;
    jobject mBitmap;
    AndroidBitmapInfo mInfo{};
    void* mPixels = nullptr;
    cv::Mat mMat;
};

void writeRgbToRgba(const cv::Mat& rgb, cv::Mat& rgba) {
    cv::cvtColor(rgb, rgba, cv::COLOR_RGB2RGBA);
}

void rotateInPlace(cv::Mat& img, int degrees) {
    switch (((degrees % 360) + 360) % 360) {
        case 90: cv::rotate(img, img, cv::ROTATE_90_CLOCKWISE); break;
        case 180: cv::rotate(img, img, cv::ROTATE_180); break;
        case 270: cv::rotate(img, img, cv::ROTATE_90_COUNTERCLOCKWISE); break;
        default: break;
    }
}

// Converts a YUV_420_888 image (as three plane pointers with strides) to RGB.
bool yuv420ToRgb(const uint8_t* y, const uint8_t* u, const uint8_t* v,
                 int width, int height, int yRowStride, int uvRowStride, int uvPixelStride,
                 cv::Mat& rgbOut) {
    cv::Mat yMat(height, width, CV_8UC1, const_cast<uint8_t*>(y), static_cast<size_t>(yRowStride));
    if (uvPixelStride == 2) {
        // Semi-planar. NV21 if V comes first in memory, else NV12.
        if (v < u) {
            cv::Mat uv(height / 2, width / 2, CV_8UC2, const_cast<uint8_t*>(v), static_cast<size_t>(uvRowStride));
            cv::cvtColorTwoPlane(yMat, uv, rgbOut, cv::COLOR_YUV2RGB_NV21);
        } else {
            cv::Mat uv(height / 2, width / 2, CV_8UC2, const_cast<uint8_t*>(u), static_cast<size_t>(uvRowStride));
            cv::cvtColorTwoPlane(yMat, uv, rgbOut, cv::COLOR_YUV2RGB_NV12);
        }
        return true;
    }
    if (uvPixelStride == 1) {
        // Fully planar: pack into a contiguous I420 buffer.
        cv::Mat i420(height + height / 2, width, CV_8UC1);
        yMat.copyTo(i420(cv::Rect(0, 0, width, height)));
        uint8_t* dst = i420.ptr<uint8_t>(height);
        const int cw = width / 2;
        const int ch = height / 2;
        for (int r = 0; r < ch; ++r) {
            std::memcpy(dst + r * cw, u + r * uvRowStride, static_cast<size_t>(cw));
        }
        dst += cw * ch;
        for (int r = 0; r < ch; ++r) {
            std::memcpy(dst + r * cw, v + r * uvRowStride, static_cast<size_t>(cw));
        }
        cv::cvtColor(i420, rgbOut, cv::COLOR_YUV2RGB_I420);
        return true;
    }
    return false;
}

// Global tone curve used by night mode: lifts shadows based on mean luminance
// and applies gentle local contrast via CLAHE on the L channel.
void nightToneMap(cv::Mat& rgb) {
    cv::Mat lab;
    cv::cvtColor(rgb, lab, cv::COLOR_RGB2Lab);
    std::vector<cv::Mat> ch;
    cv::split(lab, ch);

    double meanL = cv::mean(ch[0])[0] / 255.0;
    if (meanL > 0.01 && meanL < 0.42) {
        // gamma so that the mean maps to ~0.42, limited to avoid washing out.
        double gamma = std::clamp(std::log(0.42) / std::log(meanL), 0.45, 1.0);
        cv::Mat lut(1, 256, CV_8UC1);
        for (int i = 0; i < 256; ++i) {
            lut.at<uint8_t>(i) = cv::saturate_cast<uint8_t>(std::pow(i / 255.0, gamma) * 255.0);
        }
        cv::LUT(ch[0], lut, ch[0]);
    }

    auto clahe = cv::createCLAHE(1.6, cv::Size(8, 8));
    clahe->apply(ch[0], ch[0]);

    cv::merge(ch, lab);
    cv::cvtColor(lab, rgb, cv::COLOR_Lab2RGB);
}

// ---------------------------------------------------------------------------
// Night stacker: aligned temporal averaging of a YUV burst.
// ---------------------------------------------------------------------------

struct NightStacker {
    int width;
    int height;
    int frames = 0;
    static constexpr int kAlignScale = 4;
    cv::Mat refSmall;      // downscaled luma of the reference frame
    cv::Mat accumulator;   // CV_16UC3 running sum (fits up to 257 frames)
    cv::Ptr<cv::AlignMTB> mtb = cv::createAlignMTB(5, 4, true);

    NightStacker(int w, int h) : width(w), height(h) {}
};

// ---------------------------------------------------------------------------
// Portrait helpers
// ---------------------------------------------------------------------------

// Keeps only connected components that overlap the seed rectangle (or the
// largest component if there is no seed).
void keepSubjectComponents(cv::Mat& mask, const cv::Rect& seed) {
    cv::Mat labels, stats, centroids;
    int n = cv::connectedComponentsWithStats(mask, labels, stats, centroids, 8, CV_32S);
    if (n <= 1) return;

    std::vector<uint8_t> keep(static_cast<size_t>(n), 0);
    if (seed.area() > 0) {
        for (int i = 1; i < n; ++i) {
            cv::Rect r(stats.at<int>(i, cv::CC_STAT_LEFT), stats.at<int>(i, cv::CC_STAT_TOP),
                       stats.at<int>(i, cv::CC_STAT_WIDTH), stats.at<int>(i, cv::CC_STAT_HEIGHT));
            if ((r & seed).area() > 0) keep[i] = 1;
        }
    }
    bool any = std::any_of(keep.begin(), keep.end(), [](uint8_t k) { return k != 0; });
    if (!any) {
        int best = 1;
        for (int i = 2; i < n; ++i) {
            if (stats.at<int>(i, cv::CC_STAT_AREA) > stats.at<int>(best, cv::CC_STAT_AREA)) best = i;
        }
        keep[best] = 1;
    }
    for (int r = 0; r < mask.rows; ++r) {
        const int* l = labels.ptr<int>(r);
        uint8_t* m = mask.ptr<uint8_t>(r);
        for (int c = 0; c < mask.cols; ++c) m[c] = keep[l[c]] ? 255 : 0;
    }
}

// Normalized-convolution blur: blurs only background pixels so the subject's
// colours do not bleed into the bokeh halo.
cv::Mat backgroundBlur(const cv::Mat& rgbF, const cv::Mat& bgWeight, double sigma) {
    cv::Mat w3;
    cv::Mat wch[] = {bgWeight, bgWeight, bgWeight};
    cv::merge(wch, 3, w3);
    cv::Mat num, den;
    cv::GaussianBlur(rgbF.mul(w3), num, cv::Size(0, 0), sigma);
    cv::GaussianBlur(bgWeight, den, cv::Size(0, 0), sigma);
    den = cv::max(den, 1e-3);
    cv::Mat den3;
    cv::Mat dch[] = {den, den, den};
    cv::merge(dch, 3, den3);
    return num / den3;
}

// ---------------------------------------------------------------------------
// Palm detection helpers
// ---------------------------------------------------------------------------

double angleBetween(const cv::Point& s, const cv::Point& f, const cv::Point& e) {
    cv::Point2d a = s - f;
    cv::Point2d b = e - f;
    double denom = std::sqrt(a.dot(a)) * std::sqrt(b.dot(b));
    if (denom < 1e-6) return CV_PI;
    return std::acos(std::clamp(a.dot(b) / denom, -1.0, 1.0));
}

}  // namespace

extern "C" {

// ===========================================================================
// Night mode
// ===========================================================================

JNIEXPORT jlong JNICALL
Java_com_fearmikey_camera_data_engine_processing_NativeVisionBridge_nativeNightCreate(
        JNIEnv*, jobject, jint width, jint height) {
    return reinterpret_cast<jlong>(new NightStacker(width, height));
}

JNIEXPORT jboolean JNICALL
Java_com_fearmikey_camera_data_engine_processing_NativeVisionBridge_nativeNightAddFrame(
        JNIEnv* env, jobject, jlong ptr, jobject yBuf, jobject uBuf, jobject vBuf,
        jint yRowStride, jint uvRowStride, jint uvPixelStride) {
    auto* s = reinterpret_cast<NightStacker*>(ptr);
    if (!s) return JNI_FALSE;
    auto* y = static_cast<uint8_t*>(env->GetDirectBufferAddress(yBuf));
    auto* u = static_cast<uint8_t*>(env->GetDirectBufferAddress(uBuf));
    auto* v = static_cast<uint8_t*>(env->GetDirectBufferAddress(vBuf));
    if (!y || !u || !v) return JNI_FALSE;

    try {
        cv::Mat rgb;
        if (!yuv420ToRgb(y, u, v, s->width, s->height, yRowStride, uvRowStride, uvPixelStride, rgb)) {
            return JNI_FALSE;
        }

        cv::Mat yMat(s->height, s->width, CV_8UC1, y, static_cast<size_t>(yRowStride));
        cv::Mat small;
        cv::resize(yMat, small, cv::Size(s->width / NightStacker::kAlignScale, s->height / NightStacker::kAlignScale),
                   0, 0, cv::INTER_AREA);

        if (s->frames == 0) {
            s->refSmall = small.clone();
            rgb.convertTo(s->accumulator, CV_16UC3);
        } else {
            cv::Point shift = s->mtb->calculateShift(s->refSmall, small);
            const double maxShift = 0.04 * small.cols;
            if (std::abs(shift.x) > maxShift || std::abs(shift.y) > maxShift) {
                LOGI("Night: rejecting frame with shift %d,%d", shift.x, shift.y);
                return JNI_FALSE;
            }
            cv::Mat aligned;
            if (shift.x != 0 || shift.y != 0) {
                cv::Mat m = (cv::Mat_<double>(2, 3) << 1, 0, shift.x * NightStacker::kAlignScale,
                                                       0, 1, shift.y * NightStacker::kAlignScale);
                cv::warpAffine(rgb, aligned, m, rgb.size(), cv::INTER_LINEAR, cv::BORDER_REFLECT);
            } else {
                aligned = rgb;
            }
            cv::add(s->accumulator, aligned, s->accumulator, cv::noArray(), CV_16UC3);
        }
        s->frames++;
        return JNI_TRUE;
    } catch (const cv::Exception& e) {
        LOGE("Night add frame failed: %s", e.what());
        return JNI_FALSE;
    }
}

JNIEXPORT jint JNICALL
Java_com_fearmikey_camera_data_engine_processing_NativeVisionBridge_nativeNightFrameCount(
        JNIEnv*, jobject, jlong ptr) {
    auto* s = reinterpret_cast<NightStacker*>(ptr);
    return s ? s->frames : 0;
}

JNIEXPORT jboolean JNICALL
Java_com_fearmikey_camera_data_engine_processing_NativeVisionBridge_nativeNightFinish(
        JNIEnv* env, jobject, jlong ptr, jobject outBitmap, jint rotationDegrees, jboolean toneMap) {
    auto* s = reinterpret_cast<NightStacker*>(ptr);
    if (!s || s->frames == 0) return JNI_FALSE;
    LockedBitmap out(env, outBitmap);
    if (!out.ok()) return JNI_FALSE;

    try {
        cv::Mat rgb;
        s->accumulator.convertTo(rgb, CV_8UC3, 1.0 / s->frames);
        s->accumulator.release();
        if (toneMap) nightToneMap(rgb);
        rotateInPlace(rgb, rotationDegrees);
        if (rgb.cols != out.mat().cols || rgb.rows != out.mat().rows) {
            LOGE("Night: output bitmap size mismatch");
            return JNI_FALSE;
        }
        writeRgbToRgba(rgb, out.mat());
        LOGI("Night: merged %d frames", s->frames);
        return JNI_TRUE;
    } catch (const cv::Exception& e) {
        LOGE("Night finish failed: %s", e.what());
        return JNI_FALSE;
    }
}

JNIEXPORT void JNICALL
Java_com_fearmikey_camera_data_engine_processing_NativeVisionBridge_nativeNightRelease(
        JNIEnv*, jobject, jlong ptr) {
    delete reinterpret_cast<NightStacker*>(ptr);
}

// ===========================================================================
// HDR: MTB alignment + Mertens exposure fusion
// ===========================================================================

JNIEXPORT jboolean JNICALL
Java_com_fearmikey_camera_data_engine_processing_NativeVisionBridge_nativeFuseExposures(
        JNIEnv* env, jobject, jobjectArray inputs, jobject outBitmap) {
    const jsize count = env->GetArrayLength(inputs);
    if (count < 2) return JNI_FALSE;

    try {
        std::vector<cv::Mat> frames;
        frames.reserve(static_cast<size_t>(count));
        for (jsize i = 0; i < count; ++i) {
            jobject bmp = env->GetObjectArrayElement(inputs, i);
            {
                LockedBitmap in(env, bmp);
                if (!in.ok()) return JNI_FALSE;
                cv::Mat rgb;
                cv::cvtColor(in.mat(), rgb, cv::COLOR_RGBA2RGB);
                frames.push_back(rgb);
            }
            env->DeleteLocalRef(bmp);
        }
        for (const auto& f : frames) {
            if (f.size() != frames[0].size()) return JNI_FALSE;
        }

        std::vector<cv::Mat> aligned;
        cv::createAlignMTB(6, 4, true)->process(frames, aligned);
        frames.clear();

        cv::Mat fused;
        cv::createMergeMertens(1.0f, 1.0f, 1.0f)->process(aligned, fused);
        aligned.clear();

        cv::Mat rgb8;
        fused.convertTo(rgb8, CV_8UC3, 255.0);

        LockedBitmap out(env, outBitmap);
        if (!out.ok() || out.mat().size() != rgb8.size()) return JNI_FALSE;
        writeRgbToRgba(rgb8, out.mat());
        return JNI_TRUE;
    } catch (const cv::Exception& e) {
        LOGE("HDR fusion failed: %s", e.what());
        return JNI_FALSE;
    }
}

// ===========================================================================
// Portrait: face-seeded GrabCut segmentation + background bokeh (in place)
// ===========================================================================

JNIEXPORT jboolean JNICALL
Java_com_fearmikey_camera_data_engine_processing_NativeVisionBridge_nativePortraitBokeh(
        JNIEnv* env, jobject, jobject bitmap, jfloat faceL, jfloat faceT, jfloat faceR, jfloat faceB,
        jfloat strength) {
    LockedBitmap bmp(env, bitmap);
    if (!bmp.ok()) return JNI_FALSE;
    cv::Mat& rgba = bmp.mat();

    try {
        const int fullW = rgba.cols;
        const int fullH = rgba.rows;
        const double scale = 640.0 / std::max(fullW, fullH);
        cv::Size smallSize(std::max(1, static_cast<int>(fullW * scale)), std::max(1, static_cast<int>(fullH * scale)));

        cv::Mat smallRgb;
        {
            cv::Mat smallRgba;
            cv::resize(rgba, smallRgba, smallSize, 0, 0, cv::INTER_AREA);
            cv::cvtColor(smallRgba, smallRgb, cv::COLOR_RGBA2RGB);
        }
        const int w = smallRgb.cols;
        const int h = smallRgb.rows;

        cv::Mat gcMask(h, w, CV_8UC1, cv::Scalar(cv::GC_BGD));
        cv::Mat bgdModel, fgdModel;
        cv::Rect seed;
        const bool hasFace = faceR > faceL && faceB > faceT;

        if (hasFace) {
            cv::Rect face(static_cast<int>(faceL * w), static_cast<int>(faceT * h),
                          static_cast<int>((faceR - faceL) * w), static_cast<int>((faceB - faceT) * h));
            face &= cv::Rect(0, 0, w, h);
            const int fw = face.width;
            const int fh = face.height;

            // Probable person region: head + shoulders/torso down to the bottom.
            cv::Rect person(face.x - static_cast<int>(1.4 * fw), face.y - static_cast<int>(0.6 * fh),
                            static_cast<int>(3.8 * fw), h);
            person &= cv::Rect(0, 0, w, h);
            gcMask.setTo(cv::Scalar(cv::GC_PR_BGD));
            gcMask(person).setTo(cv::Scalar(cv::GC_PR_FGD));

            // Definite background: a thin frame border away from the subject.
            const int border = std::max(2, w / 40);
            cv::Mat borderMask(h, w, CV_8UC1, cv::Scalar(0));
            borderMask(cv::Rect(0, 0, w, border)).setTo(255);
            borderMask(cv::Rect(0, 0, border, h)).setTo(255);
            borderMask(cv::Rect(w - border, 0, border, h)).setTo(255);
            borderMask(person).setTo(0);
            gcMask.setTo(cv::Scalar(cv::GC_BGD), borderMask);

            // Definite foreground: the inner face and a neck/chest strip.
            cv::Rect innerFace(face.x + fw / 5, face.y + fh / 6, fw * 3 / 5, fh * 2 / 3);
            cv::Rect chest(face.x + fw / 4, face.y + fh, fw / 2, static_cast<int>(0.8 * fh));
            innerFace &= cv::Rect(0, 0, w, h);
            chest &= cv::Rect(0, 0, w, h);
            if (innerFace.area() > 0) gcMask(innerFace).setTo(cv::Scalar(cv::GC_FGD));
            if (chest.area() > 0) gcMask(chest).setTo(cv::Scalar(cv::GC_FGD));

            seed = face;
            cv::grabCut(smallRgb, gcMask, cv::Rect(), bgdModel, fgdModel, 5, cv::GC_INIT_WITH_MASK);
        } else {
            // No face found: assume a centered subject.
            cv::Rect rect(w / 6, h / 10, w * 2 / 3, h * 9 / 10 - 1);
            seed = cv::Rect(w * 2 / 5, h * 2 / 5, w / 5, h / 5);
            cv::grabCut(smallRgb, gcMask, rect, bgdModel, fgdModel, 5, cv::GC_INIT_WITH_RECT);
        }

        cv::Mat fg = (gcMask == cv::GC_FGD) | (gcMask == cv::GC_PR_FGD);
        cv::morphologyEx(fg, fg, cv::MORPH_OPEN, cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(5, 5)));
        cv::morphologyEx(fg, fg, cv::MORPH_CLOSE, cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(9, 9)));
        keepSubjectComponents(fg, seed);

        // Soft alpha at preview scale, then upscale to full resolution.
        cv::Mat alphaSmall;
        fg.convertTo(alphaSmall, CV_32F, 1.0 / 255.0);
        cv::GaussianBlur(alphaSmall, alphaSmall, cv::Size(0, 0), std::max(1.5, w / 300.0));

        // Blurred background is computed at reduced resolution (cheap, and the
        // upscale adds extra softness) then blended at full resolution.
        const double blurScale = 0.25;
        cv::Size blurSize(std::max(1, static_cast<int>(fullW * blurScale)), std::max(1, static_cast<int>(fullH * blurScale)));
        cv::Mat midRgb;
        {
            cv::Mat midRgba;
            cv::resize(rgba, midRgba, blurSize, 0, 0, cv::INTER_AREA);
            cv::cvtColor(midRgba, midRgb, cv::COLOR_RGBA2RGB);
        }
        cv::Mat midF;
        midRgb.convertTo(midF, CV_32FC3);
        cv::Mat alphaMid;
        cv::resize(alphaSmall, alphaMid, blurSize, 0, 0, cv::INTER_LINEAR);
        cv::Mat bgWeight = 1.0 - alphaMid;
        const double sigma = std::max(2.0, blurSize.width * 0.012 * std::clamp(static_cast<double>(strength), 0.2, 2.0));
        cv::Mat blurredMid = backgroundBlur(midF, bgWeight, sigma);

        cv::Mat blurred8;
        blurredMid.convertTo(blurred8, CV_8UC3);
        cv::Mat blurredFull;
        cv::resize(blurred8, blurredFull, rgba.size(), 0, 0, cv::INTER_LINEAR);
        cv::Mat alphaFull;
        cv::resize(alphaSmall, alphaFull, rgba.size(), 0, 0, cv::INTER_LINEAR);

        // Blend row by row to avoid allocating full-resolution float images.
        for (int r = 0; r < fullH; ++r) {
            uint8_t* dst = rgba.ptr<uint8_t>(r);
            const uint8_t* bg = blurredFull.ptr<uint8_t>(r);
            const float* a = alphaFull.ptr<float>(r);
            for (int c = 0; c < fullW; ++c) {
                const float al = a[c];
                const float ib = 1.0f - al;
                dst[c * 4 + 0] = cv::saturate_cast<uint8_t>(dst[c * 4 + 0] * al + bg[c * 3 + 0] * ib);
                dst[c * 4 + 1] = cv::saturate_cast<uint8_t>(dst[c * 4 + 1] * al + bg[c * 3 + 1] * ib);
                dst[c * 4 + 2] = cv::saturate_cast<uint8_t>(dst[c * 4 + 2] * al + bg[c * 3 + 2] * ib);
                dst[c * 4 + 3] = 255;
            }
        }
        LOGI("Portrait: segmented (face=%d)", hasFace ? 1 : 0);
        return JNI_TRUE;
    } catch (const cv::Exception& e) {
        LOGE("Portrait failed: %s", e.what());
        return JNI_FALSE;
    }
}

// ===========================================================================
// Palm detection: face-calibrated skin segmentation + convexity defects.
// Returns null if no open palm, otherwise {left, top, right, bottom} normalized.
// ===========================================================================

JNIEXPORT jfloatArray JNICALL
Java_com_fearmikey_camera_data_engine_processing_NativeVisionBridge_nativeDetectOpenPalm(
        JNIEnv* env, jobject, jobject bitmap, jfloat faceL, jfloat faceT, jfloat faceR, jfloat faceB) {
    LockedBitmap bmp(env, bitmap);
    if (!bmp.ok()) return nullptr;
    if (!(faceR > faceL && faceB > faceT)) return nullptr;

    try {
        cv::Mat rgb;
        cv::cvtColor(bmp.mat(), rgb, cv::COLOR_RGBA2RGB);
        const int w = rgb.cols;
        const int h = rgb.rows;
        cv::GaussianBlur(rgb, rgb, cv::Size(5, 5), 0);

        cv::Mat ycrcb;
        cv::cvtColor(rgb, ycrcb, cv::COLOR_RGB2YCrCb);

        cv::Rect face(static_cast<int>(faceL * w), static_cast<int>(faceT * h),
                      static_cast<int>((faceR - faceL) * w), static_cast<int>((faceB - faceT) * h));
        face &= cv::Rect(0, 0, w, h);
        if (face.area() < 16) return nullptr;

        // Calibrate the skin model from the central part of the face.
        cv::Rect sample(face.x + face.width / 4, face.y + face.height / 4, face.width / 2, face.height / 2);
        cv::Scalar mean, stddev;
        cv::meanStdDev(ycrcb(sample), mean, stddev);
        const double crTol = std::max(2.5 * stddev[1], 8.0);
        const double cbTol = std::max(2.5 * stddev[2], 8.0);

        cv::Mat skin;
        cv::inRange(ycrcb,
                    cv::Scalar(std::max(0.0, mean[0] * 0.35), mean[1] - crTol, mean[2] - cbTol),
                    cv::Scalar(255, mean[1] + crTol, mean[2] + cbTol), skin);

        // Remove the face and neck so they are not mistaken for a hand.
        cv::Rect faceAndNeck(face.x - face.width / 6, face.y - face.height / 6,
                             face.width + face.width / 3, static_cast<int>(face.height * 1.9));
        faceAndNeck &= cv::Rect(0, 0, w, h);
        skin(faceAndNeck).setTo(0);

        cv::morphologyEx(skin, skin, cv::MORPH_OPEN, cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(3, 3)));
        cv::morphologyEx(skin, skin, cv::MORPH_CLOSE, cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(7, 7)));

        std::vector<std::vector<cv::Point>> contours;
        cv::findContours(skin, contours, cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE);

        const double faceArea = face.area();
        double bestScore = 0;
        cv::Rect bestBox;

        for (const auto& contour : contours) {
            const double area = cv::contourArea(contour);
            if (area < 0.35 * faceArea || area > 5.0 * faceArea) continue;
            cv::Rect box = cv::boundingRect(contour);
            const double aspect = static_cast<double>(box.height) / std::max(1, box.width);
            if (aspect < 0.7 || aspect > 2.6) continue;

            std::vector<int> hullIdx;
            cv::convexHull(contour, hullIdx, false, false);
            if (hullIdx.size() < 4) continue;
            std::sort(hullIdx.begin(), hullIdx.end());
            std::vector<cv::Vec4i> defects;
            cv::convexityDefects(contour, hullIdx, defects);

            int fingerGaps = 0;
            const double minDepth = 0.15 * box.height;
            for (const auto& d : defects) {
                const cv::Point& s = contour[d[0]];
                const cv::Point& e = contour[d[1]];
                const cv::Point& f = contour[d[2]];
                const double depth = d[3] / 256.0;
                if (depth < minDepth) continue;
                if (angleBetween(s, f, e) > CV_PI * 0.5) continue;
                // Fingers point upwards: the valley lies below both tips.
                if (f.y < std::min(s.y, e.y)) continue;
                fingerGaps++;
            }

            if (fingerGaps >= 3) {
                // Prefer larger, more hand-sized blobs (fill ratio of the bounding box).
                const double fill = area / std::max(1.0, static_cast<double>(box.area()));
                const double score = fingerGaps + fill;
                if (score > bestScore) {
                    bestScore = score;
                    bestBox = box;
                }
            }
        }

        if (bestBox.area() == 0) return nullptr;
        jfloatArray result = env->NewFloatArray(4);
        jfloat values[4] = {
                static_cast<float>(bestBox.x) / w,
                static_cast<float>(bestBox.y) / h,
                static_cast<float>(bestBox.x + bestBox.width) / w,
                static_cast<float>(bestBox.y + bestBox.height) / h,
        };
        env->SetFloatArrayRegion(result, 0, 4, values);
        return result;
    } catch (const cv::Exception& e) {
        LOGE("Palm detection failed: %s", e.what());
        return nullptr;
    }
}

// ===========================================================================
// QR code decoding (OpenCV QRCodeDetector). Returns the payload or null.
// ===========================================================================

JNIEXPORT jstring JNICALL
Java_com_fearmikey_camera_data_engine_processing_NativeVisionBridge_nativeDecodeQr(
        JNIEnv* env, jobject, jobject bitmap) {
    LockedBitmap bmp(env, bitmap);
    if (!bmp.ok()) return nullptr;
    try {
        cv::Mat gray;
        cv::cvtColor(bmp.mat(), gray, cv::COLOR_RGBA2GRAY);
        static thread_local cv::QRCodeDetector detector;
        cv::Mat points;
        std::string text = detector.detectAndDecode(gray, points);
        if (text.empty()) return nullptr;
        return env->NewStringUTF(text.c_str());
    } catch (const cv::Exception& e) {
        LOGE("QR decode failed: %s", e.what());
        return nullptr;
    }
}

}  // extern "C"
