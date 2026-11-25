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

#include "PortraitEngine.hpp"

#include <algorithm>
#include <array>
#include <climits>
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

inline double srgbToLinear(double c) {
    return c <= 0.04045 ? c / 12.92 : std::pow((c + 0.055) / 1.055, 2.4);
}

inline double linearToSrgb(double c) {
    return c <= 0.0031308 ? c * 12.92 : 1.055 * std::pow(c, 1.0 / 2.4) - 0.055;
}

// Night tone mapping parameters (linear light unless noted). See nightToneMap.
constexpr double kNightKey = 0.14;         // target log-average luminance (a third of a stop under middle grey)
constexpr double kNightHighTarget = 0.8;   // ...but the 90th percentile lands at most here (pre shoulder)
constexpr double kNightHighPercentile = 0.90;
constexpr double kNightMaxGain = 32.0;     // brightening cap (5 EV), incl. the capture plan's shortfall
constexpr double kNightBlackPercentile = 0.005;
constexpr double kNightMaxBlack = 0.02;    // black point never above this...
constexpr double kNightBlackShare = 0.6;   // ...nor above this share of the channel's log-average
constexpr double kNightContrast = 1.35;    // S-curve slope at the pivot (sRGB-encoded) at full strength
constexpr double kNightPivot = 0.40;       // sRGB-encoded value the S-curve leaves alone (~ the key)
constexpr double kNightSaturation = 1.15;  // chroma scale at full strength
constexpr double kNightFullLookStops = 2.0;  // contrast/saturation reach full strength at this gain (EV)
// From this gain on, chroma is denoised at 1/4 resolution: color blotches are the ugliest part
// of a 4+ EV push, at the price of slightly rounded corners on small saturated shapes.
constexpr double kNightStrongChromaGain = 16.0;
// Luma sharpening after the merge (the frames are captured with EDGE_MODE_OFF).
constexpr double kNightSharpenSigma = 1.2;
constexpr double kNightSharpenAmount = 0.8;
constexpr int kNightSharpenCoring = 2 * 257;  // ~2 8-bit levels: leave residual noise alone

// Long exposure merge parameters (see longExposureMerge).
constexpr double kLongTrailClipLo = 0.90;  // brightest frame value (sRGB) where trail recovery starts
constexpr double kLongTrailClipHi = 0.99;
constexpr float kLongTrailExcessLo = 0.10f;  // linear peak-over-mean needed (static areas: ~0)
constexpr float kLongTrailExcessHi = 0.35f;
constexpr float kLongTrailDarkLo = 0.08f;    // linear mean where recovery starts fading out:
constexpr float kLongTrailDarkHi = 0.30f;    // only emissive lights in dark surroundings saturate
constexpr double kLongSharpenAmount = 0.6;
constexpr double kLongMaxGain = 4.0;

inline uint32_t hash32(uint32_t x) {
    x ^= x >> 16;
    x *= 0x7feb352dU;
    x ^= x >> 15;
    x *= 0x846ca68bU;
    x ^= x >> 16;
    return x;
}

inline float smoothstepf(float lo, float hi, float x) {
    const float t = std::clamp((x - lo) / (hi - lo), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

// 8-bit sRGB code value -> linear light.
const std::array<float, 256>& srgb8ToLinear() {
    static const std::array<float, 256> lut = [] {
        std::array<float, 256> t{};
        for (int i = 0; i < 256; ++i) t[i] = static_cast<float>(srgbToLinear(i / 255.0));
        return t;
    }();
    return lut;
}

struct FinishParams {
    int chromaDenoiseScale = 0;  // 0 = off, else chroma median at 1/scale resolution
    double saturation = 1.0;     // chroma scale
    double sharpenAmount = 0.0;
    int sharpenCoring = kNightSharpenCoring;  // 16-bit luma differences below this are left alone
};

// Shared 16-bit finishing for merged stacks. Releases `rgb16` (CV_16UC3, display-encoded).
//  1. YCrCb; optional chroma median at reduced resolution (gain amplifies color blotches)
//     and chroma scale (saturation).
//  2. Cored unsharp mask on luma: frames are captured with EDGE_MODE_OFF, so this restores
//     the detail the ISP's edge enhancement would add, once, without sharpening noise.
//  3. Triangular dither to 8 bits. Staying at 16 bits until here keeps smooth gradients
//     (dark walls, skies) free of contour bands.
void finishRgb16(cv::Mat& rgb16, const FinishParams& params, cv::Mat& rgbOut) {
    std::vector<cv::Mat> ch;
    {
        cv::Mat ycc;
        cv::cvtColor(rgb16, ycc, cv::COLOR_RGB2YCrCb);
        rgb16.release();
        cv::split(ycc, ch);
    }
    const cv::Size full = ch[0].size();
    const bool saturate = std::abs(params.saturation - 1.0) > 1e-3;
    for (int c = 1; c <= 2; ++c) {
        if (params.chromaDenoiseScale > 0) {
            const cv::Size reduced(std::max(1, full.width / params.chromaDenoiseScale),
                                   std::max(1, full.height / params.chromaDenoiseScale));
            cv::Mat small;
            cv::resize(ch[c], small, reduced, 0, 0, cv::INTER_AREA);
            cv::medianBlur(small, small, 5);
            cv::resize(small, ch[c], full, 0, 0, cv::INTER_LINEAR);
        }
        // 16-bit YCrCb chroma is centered on 32768.
        if (saturate) ch[c].convertTo(ch[c], CV_16U, params.saturation, 32768.0 * (1.0 - params.saturation));
    }
    if (params.sharpenAmount > 0.0) {
        cv::Mat blur;
        cv::GaussianBlur(ch[0], blur, cv::Size(0, 0), kNightSharpenSigma);
        cv::Mat& luma = ch[0];
        const double amount = params.sharpenAmount;
        const int coring = params.sharpenCoring;
        cv::parallel_for_(cv::Range(0, luma.rows), [&](const cv::Range& range) {
            for (int r = range.start; r < range.end; ++r) {
                auto* y = luma.ptr<uint16_t>(r);
                const auto* b = blur.ptr<uint16_t>(r);
                for (int c = 0; c < luma.cols; ++c) {
                    const int d = static_cast<int>(y[c]) - static_cast<int>(b[c]);
                    const int mag = std::max(std::abs(d) - coring, 0);
                    if (mag == 0) continue;
                    const double boost = amount * (d > 0 ? mag : -mag);
                    y[c] = cv::saturate_cast<uint16_t>(y[c] + boost);
                }
            }
        });
    }
    cv::Mat ycc;
    cv::merge(ch, ycc);
    ch.clear();
    cv::cvtColor(ycc, rgb16, cv::COLOR_YCrCb2RGB);
    ycc.release();

    // Triangular dither (+-1 level) down to 8 bits.
    rgbOut.create(rgb16.rows, rgb16.cols, CV_8UC3);
    cv::parallel_for_(cv::Range(0, rgb16.rows), [&](const cv::Range& range) {
        for (int r = range.start; r < range.end; ++r) {
            const auto* src = rgb16.ptr<uint16_t>(r);
            auto* dst = rgbOut.ptr<uint8_t>(r);
            const int n = rgb16.cols * 3;
            const uint32_t rowSeed = static_cast<uint32_t>(r) * 0x9E3779B9U;
            for (int i = 0; i < n; ++i) {
                const uint32_t h = hash32(rowSeed ^ static_cast<uint32_t>(i));
                // Sum of two uniform [0, 257) values minus 257: triangular in (-257, 257).
                const int noise = static_cast<int>(((h & 0xFFFFU) + (h >> 16)) * 257U / 65536U) - 257;
                dst[i] = cv::saturate_cast<uint8_t>((static_cast<int>(src[i]) + noise + 128) / 257);
            }
        }
    });
    rgb16.release();
}

// Night tone mapping of an accumulated stack (CV_16UC3 sum of `frames` 8-bit RGB frames).
// Releases `acc`. The ISP output is display-encoded, so brightening it directly (the old
// gamma lift) muddies and flattens the image. Everything below runs at 16 bits and is only
// dithered down to 8 bits at the very end: a smooth dark wall spans just a few 8-bit levels,
// and any 8-bit intermediate step turns the brightened gradient into visible contour bands.
// The look follows what a phone night mode produces: a bright frame (log-average near middle
// grey) with firm contrast and blacks that stay black, instead of the scene's own dimness.
//  1. Measure the merged frame in linear light on an area-averaged 1/8 copy:
//     - black point per channel: a low percentile (the sensor/ISP pedestal, which a large gain
//       would turn into a colored haze), capped so it can never eat the picture;
//     - log-average and 90th percentile luminance above the black point.
//     Gain = kNightKey / log-average, limited so the 90th percentile is not pushed past
//     kNightHighTarget (a lit facade under a black sky sets the exposure, not the sky), never
//     below 1 (never darker than the frames) or `minGain` (exposure the capture plan could
//     not reach), at most kNightMaxGain.
//  2. Per pixel: a LUT per channel indexed by the exact frame sum linearizes, subtracts the
//     black point and multiplies by the gain; an extended Reinhard shoulder with white point
//     = gain (original white stays white) rolls street lights off instead of clipping them;
//     an S-curve around kNightPivot gives back the contrast the roll-off took and lets the
//     deepest shadows (and their noise) fall to black. Shoulder and S-curve are one factor
//     per pixel, so colors keep their hue. Black point, contrast and saturation fade in with
//     the brightening, so a scene that needed none comes out unchanged.
//  3. YCrCb clean-up: chroma median (the gain amplifies color blotches; coarser at high
//     gains), chroma scale, cored unsharp mask on luma (gentler at high gains, where the
//     "detail" is mostly noise).
//  4. Triangular dither to 8 bits.
void nightToneMap(cv::Mat& acc, int frames, double minGain, cv::Mat& rgbOut) {
    const double toUnit = 1.0 / (255.0 * frames);
    constexpr double kLumWeight[3] = {0.2126, 0.7152, 0.0722};

    // --- 1. statistics on a small copy ---
    cv::Mat small;
    const int sw = std::max(1, acc.cols / 8);
    const int sh = std::max(1, acc.rows / 8);
    cv::resize(acc, small, cv::Size(sw, sh), 0, 0, cv::INTER_AREA);
    const size_t count = static_cast<size_t>(sw) * sh;
    std::array<std::vector<float>, 3> lin;  // linear channel values, pixel order
    for (auto& v : lin) v.resize(count);
    double rawLogSum = 0.0;
    double channelLogSum[3] = {0.0, 0.0, 0.0};
    for (int r = 0; r < small.rows; ++r) {
        const auto* p = small.ptr<uint16_t>(r);
        size_t i = static_cast<size_t>(r) * sw;
        for (int c = 0; c < small.cols; ++c, p += 3, ++i) {
            double y = 0.0;
            for (int k = 0; k < 3; ++k) {
                const double v = srgbToLinear(p[k] * toUnit);
                lin[k][i] = static_cast<float>(v);
                channelLogSum[k] += std::log(1e-4 + v);
                y += kLumWeight[k] * v;
            }
            rawLogSum += std::log(1e-4 + y);
        }
    }
    const double rawLogAvg = std::exp(rawLogSum / static_cast<double>(count));
    const auto percentile = [](std::vector<float> v, double p) {  // by value: reorders its copy
        auto nth = v.begin() + static_cast<long>(static_cast<double>(v.size() - 1) * p);
        std::nth_element(v.begin(), nth, v.end());
        return static_cast<double>(*nth);
    };
    // The pedestal only shows once it is amplified: a scene that needs no brightening keeps
    // its own blacks (and comes out unchanged). Each channel's black point is capped by that
    // channel's own level: clipped sensor noise leaves more pedestal in the channels white
    // balance amplifies most (purple shadows), while a flat scene (fog, a bare wall) must not
    // lose most of its signal.
    const double need = std::clamp(std::log2(kNightKey / rawLogAvg) / kNightFullLookStops, 0.0, 1.0);
    double black[3];
    for (int k = 0; k < 3; ++k) {
        const double channelLogAvg = std::exp(channelLogSum[k] / static_cast<double>(count));
        const double cap = std::min(kNightMaxBlack, kNightBlackShare * channelLogAvg) * need;
        black[k] = std::clamp(percentile(lin[k], kNightBlackPercentile), 0.0, cap);
    }

    std::vector<float> lum(count);
    double logSum = 0.0;
    for (size_t i = 0; i < count; ++i) {
        double y = 0.0;
        for (int k = 0; k < 3; ++k) {
            y += kLumWeight[k] * std::max(0.0, lin[k][i] - black[k]) / (1.0 - black[k]);
        }
        lum[i] = static_cast<float>(y);
        logSum += std::log(1e-4 + y);
    }
    const double logAvg = std::exp(logSum / static_cast<double>(count));
    const double high = percentile(std::move(lum), kNightHighPercentile);

    const double autoGain = std::min(kNightKey / std::max(logAvg, 1e-4), kNightHighTarget / std::max(high, 1e-4));
    const double gain = std::clamp(std::max(autoGain, minGain), 1.0, kNightMaxGain);
    const double stops = std::log2(gain);
    const double look = std::clamp(stops / kNightFullLookStops, 0.0, 1.0);
    const double contrast = 1.0 + (kNightContrast - 1.0) * look;
    LOGI("Night tone map: logAvg %.4f p90 %.4f black %.4f/%.4f/%.4f minGain %.2f -> gain %.2f contrast %.2f",
         logAvg, high, black[0], black[1], black[2], minGain, gain, contrast);

    // --- 2. tone curve ---
    // Frame sum (exact; no intermediate rounding) -> linear light above black, x gain.
    const int lutSize = 255 * frames + 1;
    std::array<std::vector<float>, 3> toScene;
    for (int k = 0; k < 3; ++k) {
        toScene[k].resize(static_cast<size_t>(lutSize));
        for (int i = 0; i < lutSize; ++i) {
            const double x = srgbToLinear(i / static_cast<double>(lutSize - 1));
            toScene[k][i] = static_cast<float>(std::max(0.0, x - black[k]) / (1.0 - black[k]) * gain);
        }
    }
    // Both curves below are driven by the pixel's largest channel and applied as one factor to
    // all three, so colors keep their hue and saturation: per channel, a warm window or a neon
    // sign rolls off to white and the S-curve oversaturates every dark tone.
    // S-curve, as a factor per linear value: two power segments in sRGB-encoded space that
    // meet at the pivot with slope `contrast`. 1/65535 linear steps are ~0.05 8-bit levels
    // even in deep shadows.
    constexpr int kEncSize = 65536;
    std::vector<float> curve(kEncSize);
    std::vector<uint16_t> encode(kEncSize);  // linear [0, 1] -> 16-bit sRGB
    for (int i = 0; i < kEncSize; ++i) {
        const double v = i / static_cast<double>(kEncSize - 1);
        const double e = linearToSrgb(v);
        const double s = e < kNightPivot
            ? kNightPivot * std::pow(e / kNightPivot, contrast)
            : 1.0 - (1.0 - kNightPivot) * std::pow((1.0 - e) / (1.0 - kNightPivot), contrast);
        curve[i] = i == 0 ? 0.0f : static_cast<float>(srgbToLinear(s) / v);
        encode[i] = cv::saturate_cast<uint16_t>(e * 65535.0 + 0.5);
    }
    // Extended Reinhard shoulder with white point = gain: slope 1 in the shadows, the brightest
    // input maps to exactly 1, identity when gain == 1.
    const float invWhiteSq = static_cast<float>(1.0 / (gain * gain));
    cv::Mat rgb16(acc.rows, acc.cols, CV_16UC3);
    cv::parallel_for_(cv::Range(0, acc.rows), [&](const cv::Range& range) {
        for (int r = range.start; r < range.end; ++r) {
            const auto* src = acc.ptr<uint16_t>(r);
            auto* dst = rgb16.ptr<uint16_t>(r);
            for (int c = 0; c < acc.cols; ++c, src += 3, dst += 3) {
                float x[3];
                for (int k = 0; k < 3; ++k) x[k] = toScene[k][std::min<int>(src[k], lutSize - 1)];
                const float m = std::max({x[0], x[1], x[2]});
                const float shoulder = (1.0f + m * invWhiteSq) / (1.0f + m);
                const float top = std::min(m * shoulder, 1.0f);
                const float factor = shoulder * curve[static_cast<int>(top * (kEncSize - 1) + 0.5f)];
                for (int k = 0; k < 3; ++k) {
                    const float y = std::min(x[k] * factor, 1.0f);
                    dst[k] = encode[static_cast<int>(y * (kEncSize - 1) + 0.5f)];
                }
            }
        }
    });
    acc.release();

    // --- 3./4. chroma denoise + saturation + luma sharpening at 16 bits, dither to 8 bits ---
    FinishParams finish;
    finish.chromaDenoiseScale = gain >= kNightStrongChromaGain ? 4 : 2;
    finish.saturation = 1.0 + (kNightSaturation - 1.0) * look;
    finish.sharpenAmount = kNightSharpenAmount / (1.0 + 0.25 * stops);
    finish.sharpenCoring = static_cast<int>(kNightSharpenCoring * (1.0 + 0.5 * stops));
    finishRgb16(rgb16, finish, rgbOut);
}

// Long exposure merge. `sum` is the CV_32FC3 linear-light sum of `frames` aligned frames,
// `peak` their per-pixel 8-bit maximum. Releases both.
//  - The mean in linear light is what one continuous exposure records. Averaging the
//    display-encoded values instead (the old merge) darkens and muddies light trails and
//    white water, and truncating that to 8 bits banded smooth skies.
//  - Light-trail recovery: a headlight that crosses a pixel clips in the frame(s) it appears
//    in, so the mean (1/N of the clipped value) leaves it as a faint gray trail, while a real
//    long exposure would saturate it. Where the brightest frame clipped, stands well above
//    the mean (moving, not a static highlight) and the surroundings are dark (an emissive
//    light, not a white car in daylight), the result blends toward the peak.
//  - `gain` (exposure the capture plan could not reach) is applied in linear light with an
//    extended-Reinhard roll-off (identity at gain 1), then finishRgb16 sharpens and dithers.
void longExposureMerge(cv::Mat& sum, cv::Mat& peak, int frames, double gain, cv::Mat& rgbOut) {
    gain = std::clamp(gain, 1.0, kLongMaxGain);
    const float invFrames = 1.0f / static_cast<float>(frames);
    const auto& toLinear = srgb8ToLinear();

    std::array<float, 256> clipWeight{};
    for (int i = 0; i < 256; ++i) {
        clipWeight[i] = smoothstepf(static_cast<float>(kLongTrailClipLo), static_cast<float>(kLongTrailClipHi),
                                    static_cast<float>(i / 255.0));
    }
    // Linear [0, 1] (before gain) -> gain + roll-off -> 16-bit sRGB. 1/65535 linear steps are
    // ~0.05 8-bit levels even in the deepest shadows.
    constexpr int kEncSize = 65536;
    std::vector<uint16_t> encode(kEncSize);
    const double white = gain;
    for (int i = 0; i < kEncSize; ++i) {
        const double x = i / static_cast<double>(kEncSize - 1) * gain;
        const double y = x * (1.0 + x / (white * white)) / (1.0 + x);
        encode[i] = cv::saturate_cast<uint16_t>(linearToSrgb(std::clamp(y, 0.0, 1.0)) * 65535.0 + 0.5);
    }

    cv::Mat rgb16(sum.rows, sum.cols, CV_16UC3);
    cv::parallel_for_(cv::Range(0, sum.rows), [&](const cv::Range& range) {
        for (int r = range.start; r < range.end; ++r) {
            const auto* s = sum.ptr<float>(r);
            const auto* p = peak.ptr<uint8_t>(r);
            auto* d = rgb16.ptr<uint16_t>(r);
            for (int c = 0; c < sum.cols; ++c, s += 3, p += 3, d += 3) {
                float mean[3];
                float top[3];
                float excess = 0.0f;
                float meanMax = 0.0f;
                for (int k = 0; k < 3; ++k) {
                    mean[k] = s[k] * invFrames;
                    top[k] = toLinear[p[k]];
                    excess = std::max(excess, top[k] - mean[k]);
                    meanMax = std::max(meanMax, mean[k]);
                }
                float w = clipWeight[std::max({p[0], p[1], p[2]})];
                if (w > 0.0f) {
                    w *= smoothstepf(kLongTrailExcessLo, kLongTrailExcessHi, excess) *
                         (1.0f - smoothstepf(kLongTrailDarkLo, kLongTrailDarkHi, meanMax));
                }
                for (int k = 0; k < 3; ++k) {
                    const float v = std::clamp(mean[k] + w * (top[k] - mean[k]), 0.0f, 1.0f);
                    d[k] = encode[static_cast<int>(v * (kEncSize - 1) + 0.5f)];
                }
            }
        }
    });
    sum.release();
    peak.release();
    LOGI("Long exposure merge: %d frames, gain %.2f", frames, gain);

    FinishParams finish;
    finish.chromaDenoiseScale = gain > 2.0 ? 2 : 0;
    finish.sharpenAmount = kLongSharpenAmount;
    finishRgb16(rgb16, finish, rgbOut);
}

// ---------------------------------------------------------------------------
// Stacker: aligned temporal merge of a YUV burst (Night and Long exposure).
// ---------------------------------------------------------------------------

double median(std::vector<double>& v) {
    std::sort(v.begin(), v.end());
    const size_t n = v.size();
    return n % 2 ? v[n / 2] : 0.5 * (v[n / 2 - 1] + v[n / 2]);
}

struct NightStacker {
    struct Patch {
        cv::Point2f center;  // full-resolution center in the reference frame
        cv::Mat ref;         // CV_32F full-resolution luma patch of the reference frame
    };

    int width;
    int height;
    bool longExposure;
    int frames = 0;
    static constexpr int kAlignScale = 4;
    static constexpr int kPatch = 512;  // full-resolution patch for sub-pixel refinement
    static constexpr int kMaxPatches = 3;
    cv::Mat refSmall;      // downscaled luma of the reference frame
    std::vector<Patch> patches;
    cv::Mat hann;          // window for phase correlation
    cv::Mat accumulator;   // Night: CV_16UC3 running sum of 8-bit frames (fits up to 257 frames)
    cv::Mat linearSum;     // Long exposure: CV_32FC3 running sum in linear light
    cv::Mat peak;          // Long exposure: CV_8UC3 per-pixel maximum (light trails)
    cv::Ptr<cv::AlignMTB> mtb = cv::createAlignMTB(5, 4, true);

    NightStacker(int w, int h, bool longExp) : width(w), height(h), longExposure(longExp) {}

    // Picks up to kMaxPatches well-textured kPatch x kPatch regions in different parts of the
    // frame (the center may be a blank wall) as references for sub-pixel refinement. With
    // several patches a median outvotes one that sits on a moving subject (the most textured
    // region of a waterfall shot is often the water itself), which would otherwise drag every
    // frame along with the motion and smear the static scene.
    void setReference(const cv::Mat& y, const cv::Mat& small) {
        refSmall = small.clone();
        patches.clear();
        std::vector<cv::Point2f> centers;
        const int win = kPatch / kAlignScale;
        if (small.cols > win * 2 && small.rows > win * 2) {
            cv::Mat lap;
            cv::Laplacian(small, lap, CV_32F, 3);
            lap = cv::abs(lap);
            cv::Mat energy;
            cv::boxFilter(lap, energy, CV_32F, cv::Size(win, win));
            // Centers whose window stays inside the frame, with a margin for the shake.
            const cv::Rect valid(win, win, small.cols - 2 * win, small.rows - 2 * win);
            const cv::Mat e = energy(valid);
            // Strongest center in each cell of a 3x3 grid, then the strongest cells first.
            std::vector<std::pair<double, cv::Point>> cand;
            for (int gy = 0; gy < 3; ++gy) {
                for (int gx = 0; gx < 3; ++gx) {
                    const int x0 = gx * e.cols / 3, x1 = (gx + 1) * e.cols / 3;
                    const int y0 = gy * e.rows / 3, y1 = (gy + 1) * e.rows / 3;
                    if (x1 <= x0 || y1 <= y0) continue;
                    double mx = 0.0;
                    cv::Point loc;
                    cv::minMaxLoc(e(cv::Rect(x0, y0, x1 - x0, y1 - y0)), nullptr, &mx, nullptr, &loc);
                    cand.emplace_back(mx, loc + cv::Point(x0 + valid.x, y0 + valid.y));
                }
            }
            std::sort(cand.begin(), cand.end(), [](const auto& a, const auto& b) { return a.first > b.first; });
            for (const auto& [en, pt] : cand) {
                if (static_cast<int>(centers.size()) == kMaxPatches) break;
                // Flat regions: phase correlation would only lock onto noise.
                if (en < 0.25 * cand.front().first) break;
                const cv::Point2f c(static_cast<float>(pt.x * kAlignScale), static_cast<float>(pt.y * kAlignScale));
                const bool apart = std::all_of(centers.begin(), centers.end(), [&](const cv::Point2f& o) {
                    return std::abs(o.x - c.x) >= kPatch || std::abs(o.y - c.y) >= kPatch;
                });
                if (apart) centers.push_back(c);
            }
        }
        if (centers.empty()) centers.emplace_back(width / 2.0f, height / 2.0f);
        for (const cv::Point2f& c : centers) {
            Patch p;
            p.center = c;
            cv::getRectSubPix(y, cv::Size(kPatch, kPatch), c, p.ref, CV_32F);
            patches.push_back(std::move(p));
        }
        cv::createHanningWindow(hann, cv::Size(kPatch, kPatch), CV_32F);
    }

    // Mean absolute difference between patch `p` and frame `y` shifted by `t`
    // (aligned(x) = y(x - t)).
    static double patchError(const cv::Mat& y, const Patch& p, const cv::Point2d& t) {
        cv::Mat patch;
        cv::getRectSubPix(y, p.ref.size(),
                          cv::Point2f(p.center.x - static_cast<float>(t.x), p.center.y - static_cast<float>(t.y)),
                          patch, CV_32F);
        return cv::norm(patch, p.ref, cv::NORM_L1) / static_cast<double>(p.ref.total());
    }

    // Sub-pixel shift for one patch: phase correlation around the coarse shift. Each candidate
    // is checked against the reference, so a failed correlation (no texture, motion) falls
    // back to the coarse shift.
    cv::Point2d refinePatch(const cv::Mat& y, const Patch& p, const cv::Point2d& coarse) const {
        cv::Mat patch;
        cv::getRectSubPix(y, p.ref.size(),
                          cv::Point2f(p.center.x - static_cast<float>(coarse.x),
                                      p.center.y - static_cast<float>(coarse.y)),
                          patch, CV_32F);
        const cv::Point2d pc = cv::phaseCorrelate(p.ref, patch, hann);
        if (std::abs(pc.x) > 2.0 * kAlignScale || std::abs(pc.y) > 2.0 * kAlignScale) return coarse;
        cv::Point2d best = coarse;
        double bestErr = patchError(y, p, coarse);
        for (const cv::Point2d& cand : {coarse + pc, coarse - pc}) {
            const double err = patchError(y, p, cand);
            if (err < bestErr) {
                bestErr = err;
                best = cand;
            }
        }
        return best;
    }

    // Refines the coarse (4-pixel-step) MTB shift to sub-pixel precision: the per-axis median
    // of the per-patch refinements.
    cv::Point2d refineShift(const cv::Mat& y, const cv::Point2d& coarse) const {
        if (patches.empty()) return coarse;
        std::vector<double> xs;
        std::vector<double> ys;
        for (const Patch& p : patches) {
            const cv::Point2d s = refinePatch(y, p, coarse);
            xs.push_back(s.x);
            ys.push_back(s.y);
        }
        return {median(xs), median(ys)};
    }

    // Adds an aligned 8-bit RGB frame. Night sums the display-encoded values (its tone map
    // linearizes the exact sum through a LUT); Long exposure sums linear light, as a single
    // continuous exposure would, and tracks the per-pixel peak for light-trail recovery.
    void accumulate(const cv::Mat& rgb) {
        if (!longExposure) {
            if (accumulator.empty()) {
                rgb.convertTo(accumulator, CV_16UC3);
            } else {
                cv::add(accumulator, rgb, accumulator, cv::noArray(), CV_16UC3);
            }
            return;
        }
        if (linearSum.empty()) {
            linearSum = cv::Mat::zeros(rgb.size(), CV_32FC3);
            peak = rgb.clone();
        } else {
            cv::max(peak, rgb, peak);
        }
        const auto& toLinear = srgb8ToLinear();
        cv::parallel_for_(cv::Range(0, rgb.rows), [&](const cv::Range& range) {
            for (int r = range.start; r < range.end; ++r) {
                const auto* src = rgb.ptr<uint8_t>(r);
                auto* dst = linearSum.ptr<float>(r);
                const int n = rgb.cols * 3;
                for (int i = 0; i < n; ++i) dst[i] += toLinear[src[i]];
            }
        });
    }
};

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
// Night mode / Long exposure
// ===========================================================================

JNIEXPORT jlong JNICALL
Java_com_fearmikey_camera_data_engine_processing_NativeVisionBridge_nativeNightCreate(
        JNIEnv*, jobject, jint width, jint height, jboolean longExposure) {
    return reinterpret_cast<jlong>(new NightStacker(width, height, longExposure == JNI_TRUE));
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
            s->setReference(yMat, small);
            s->accumulate(rgb);
        } else {
            const cv::Point coarse = s->mtb->calculateShift(s->refSmall, small);
            const double maxShift = 0.04 * small.cols;
            if (std::abs(coarse.x) > maxShift || std::abs(coarse.y) > maxShift) {
                LOGI("Night: rejecting frame with shift %d,%d", coarse.x, coarse.y);
                return JNI_FALSE;
            }
            // MTB works on the 1/4-scale frame, so on its own it is only accurate to ~4 px,
            // which smears fine detail across the stack. Refine to sub-pixel.
            const cv::Point2d shift = s->refineShift(
                    yMat, cv::Point2d(coarse.x * NightStacker::kAlignScale, coarse.y * NightStacker::kAlignScale));
            cv::Mat aligned;
            if (std::abs(shift.x) > 0.05 || std::abs(shift.y) > 0.05) {
                cv::Mat m = (cv::Mat_<double>(2, 3) << 1, 0, shift.x, 0, 1, shift.y);
                cv::warpAffine(rgb, aligned, m, rgb.size(), cv::INTER_CUBIC, cv::BORDER_REFLECT);
            } else {
                aligned = rgb;
            }
            s->accumulate(aligned);
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
        JNIEnv* env, jobject, jlong ptr, jobject outBitmap, jint rotationDegrees, jfloat minGain) {
    auto* s = reinterpret_cast<NightStacker*>(ptr);
    if (!s || s->frames == 0) return JNI_FALSE;
    LockedBitmap out(env, outBitmap);
    if (!out.ok()) return JNI_FALSE;

    try {
        cv::Mat rgb;
        if (s->longExposure) {
            longExposureMerge(s->linearSum, s->peak, s->frames, static_cast<double>(minGain), rgb);
        } else {
            nightToneMap(s->accumulator, s->frames, static_cast<double>(minGain), rgb);
        }
        s->accumulator.release();
        rotateInPlace(rgb, rotationDegrees);
        if (rgb.cols != out.mat().cols || rgb.rows != out.mat().rows) {
            LOGE("Night: output bitmap size mismatch");
            return JNI_FALSE;
        }
        writeRgbToRgba(rgb, out.mat());
        LOGI("%s: merged %d frames", s->longExposure ? "Long exposure" : "Night", s->frames);
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
// Portrait: face-seeded segmentation + lens bokeh, in place (PortraitEngine.cpp)
// ===========================================================================

JNIEXPORT jboolean JNICALL
Java_com_fearmikey_camera_data_engine_processing_NativeVisionBridge_nativePortraitBokeh(
        JNIEnv* env, jobject, jobject bitmap, jfloatArray faceBoxes, jfloat strength) {
    std::vector<cv::Rect2f> faces;
    if (faceBoxes) {
        const jsize n = env->GetArrayLength(faceBoxes) / 4;
        std::vector<jfloat> v(static_cast<size_t>(n) * 4);
        if (n > 0) env->GetFloatArrayRegion(faceBoxes, 0, n * 4, v.data());
        for (jsize i = 0; i < n; ++i) {
            const float l = v[i * 4], t = v[i * 4 + 1], r = v[i * 4 + 2], b = v[i * 4 + 3];
            if (r > l && b > t) faces.emplace_back(l, t, r - l, b - t);
        }
    }

    LockedBitmap bmp(env, bitmap);
    if (!bmp.ok()) return JNI_FALSE;
    try {
        portrait::Stats stats;
        portrait::renderBokeh(bmp.mat(), faces, strength, &stats);
        LOGI("Portrait: faces=%d noise=%.2f subject=%.2f", stats.faces, stats.noiseSigma, stats.subjectArea);
        return JNI_TRUE;
    } catch (const std::exception& e) {  // cv::Exception, std::bad_alloc
        LOGE("Portrait failed: %s", e.what());
        return JNI_FALSE;
    }
}

// ===========================================================================
// Palm detection: face-calibrated skin segmentation + hand-shape scoring.
// Returns null if no open palm, otherwise {left, top, right, bottom} normalized.
// otherFaces: normalized {l, t, r, b} per other person (may be null); their skin is ignored.
// ===========================================================================

JNIEXPORT jfloatArray JNICALL
Java_com_fearmikey_camera_data_engine_processing_NativeVisionBridge_nativeDetectOpenPalm(
        JNIEnv* env, jobject, jobject bitmap, jfloat faceL, jfloat faceT, jfloat faceR, jfloat faceB,
        jfloatArray otherFaces) {
    if (!(faceR > faceL && faceB > faceT)) return nullptr;
    std::vector<jfloat> others;
    if (otherFaces) {
        const jsize n = env->GetArrayLength(otherFaces) / 4 * 4;
        others.resize(static_cast<size_t>(n));
        if (n > 0) env->GetFloatArrayRegion(otherFaces, 0, n, others.data());
    }
    LockedBitmap bmp(env, bitmap);
    if (!bmp.ok()) return nullptr;

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

        // Calibrate the skin model from the cheeks and nose (below the eyes and brows, above
        // the mouth), the most uniformly skin-colored part of the face box.
        cv::Rect sample(face.x + face.width / 4, face.y + static_cast<int>(face.height * 0.48),
                        std::max(1, face.width / 2), std::max(1, static_cast<int>(face.height * 0.24)));
        sample &= cv::Rect(0, 0, w, h);
        if (sample.area() < 4) return nullptr;
        cv::Scalar mean, stddev;
        cv::meanStdDev(ycrcb(sample), mean, stddev);
        // Palms are usually a little paler/pinker than the face: keep some slack.
        const double crTol = std::max(2.5 * stddev[1], 11.0);
        const double cbTol = std::max(2.5 * stddev[2], 11.0);

        cv::Mat skin;
        cv::inRange(ycrcb,
                    cv::Scalar(std::max(0.0, mean[0] * 0.35), mean[1] - crTol, mean[2] - cbTol),
                    cv::Scalar(255, mean[1] + crTol, mean[2] + cbTol), skin);

        // Remove faces and necks (yours and anyone else's) so they are not mistaken for a hand.
        auto removeFaceAndNeck = [&](const cv::Rect& f) {
            cv::Rect r(f.x - f.width / 6, f.y - f.height / 6, f.width + f.width / 3,
                       static_cast<int>(f.height * 1.9));
            r &= cv::Rect(0, 0, w, h);
            if (r.area() > 0) skin(r).setTo(0);
        };
        removeFaceAndNeck(face);
        for (size_t i = 0; i + 3 < others.size(); i += 4) {
            removeFaceAndNeck(cv::Rect(static_cast<int>(others[i] * w), static_cast<int>(others[i + 1] * h),
                                       static_cast<int>((others[i + 2] - others[i]) * w),
                                       static_cast<int>((others[i + 3] - others[i + 1]) * h)));
        }

        // Scale the clean-up to the face size. Keep the close small: a larger one fills the
        // gaps between fingers that are only slightly apart.
        auto oddKernel = [](int k) { k = std::max(3, k); return k | 1; };
        const int openK = oddKernel(face.width / 36);
        const int closeK = oddKernel(face.width / 20);
        cv::morphologyEx(skin, skin, cv::MORPH_OPEN,
                         cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(openK, openK)));
        cv::morphologyEx(skin, skin, cv::MORPH_CLOSE,
                         cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(closeK, closeK)));

        std::vector<std::vector<cv::Point>> contours;
        cv::findContours(skin, contours, cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE);

        const double faceArea = face.area();
        // A hand (wrist to fingertips) is about as long as the face box is tall.
        const int maxHandH = std::max(8, static_cast<int>(1.5 * face.height));
        double bestScore = 0;
        cv::Rect bestBox;

        for (size_t ci = 0; ci < contours.size(); ++ci) {
            if (cv::contourArea(contours[ci]) < 0.3 * faceArea) continue;
            std::vector<cv::Point> contour = contours[ci];
            cv::Rect box = cv::boundingRect(contour);

            // A bare forearm, or arm + shoulder, merges with the hand into one tall blob that
            // failed the size/aspect checks. Fingers point up, so keep only the top of it.
            if (box.height > maxHandH) {
                const cv::Rect top(box.x, box.y, box.width, maxHandH);
                cv::Mat blob = cv::Mat::zeros(top.size(), CV_8UC1);
                cv::drawContours(blob, contours, static_cast<int>(ci), cv::Scalar(255), cv::FILLED,
                                 cv::LINE_8, cv::noArray(), INT_MAX, -top.tl());
                std::vector<std::vector<cv::Point>> parts;
                cv::findContours(blob, parts, cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE, top.tl());
                if (parts.empty()) continue;
                contour = *std::max_element(parts.begin(), parts.end(), [](const auto& a, const auto& b) {
                    return cv::contourArea(a) < cv::contourArea(b);
                });
                box = cv::boundingRect(contour);
            }

            const double area = cv::contourArea(contour);
            if (area < 0.3 * faceArea || area > 3.5 * faceArea) continue;
            const double aspect = static_cast<double>(box.height) / std::max(1, box.width);
            if (aspect < 0.6 || aspect > 2.6) continue;

            std::vector<int> hullIdx;
            cv::convexHull(contour, hullIdx, false, false);
            if (hullIdx.size() < 4) continue;
            std::vector<cv::Point> hullPts;
            hullPts.reserve(hullIdx.size());
            for (int i : hullIdx) hullPts.push_back(contour[static_cast<size_t>(i)]);
            const double solidity = area / std::max(1.0, cv::contourArea(hullPts));
            std::sort(hullIdx.begin(), hullIdx.end());
            std::vector<cv::Vec4i> defects;
            cv::convexityDefects(contour, hullIdx, defects);

            // Valleys between fingers (or thumb and index finger). Widely spread fingers give
            // several deep, narrow ones; a relaxed open hand gives shallower ones, usually at
            // least the thumb's.
            int gaps = 0;
            int deepGaps = 0;
            const double minDepth = std::max(2.0, 0.07 * box.height);
            for (const auto& d : defects) {
                const cv::Point& s = contour[d[0]];
                const cv::Point& e = contour[d[1]];
                const cv::Point& f = contour[d[2]];
                const double depth = d[3] / 256.0;
                if (depth < minDepth) continue;
                const double angle = angleBetween(s, f, e);
                if (angle > CV_PI * 0.6) continue;
                // Fingers point upward: the valley lies below both tips.
                if (f.y < std::min(s.y, e.y)) continue;
                gaps++;
                if (depth >= 0.15 * box.height && angle <= CV_PI * 0.5) deepGaps++;
            }

            // Without spread fingers the shape alone is weaker evidence, so also require the
            // pose: held beside the head (from above it to about chin height), fully in view
            // (blobs running into the crop edge are usually background), and not a perfectly
            // convex block (walls, door frames).
            const double cy = box.y + box.height * 0.5;
            const bool atHeadHeight = cy >= face.y - 1.6 * face.height && cy <= face.y + 1.3 * face.height;
            const bool inside = box.x > 1 && box.y > 1 && box.x + box.width < w - 1 &&
                                box.y + box.height < h - 1;
            const bool posed = atHeadHeight && inside && solidity <= 0.97;

            double score = 0;
            if (deepGaps >= 3) {
                score = 3.0 + deepGaps;                        // fingers spread wide
            } else if (posed && gaps >= 1 && solidity >= 0.7 && aspect >= 1.0) {
                score = 2.0 + 0.3 * gaps + solidity;           // relaxed open hand
            } else if (posed && gaps == 0 && solidity >= 0.85 && aspect >= 1.25 && aspect <= 2.4 &&
                       area >= 0.45 * faceArea && area <= 2.5 * faceArea) {
                score = 1.0 + solidity;                        // fingers and thumb together
            }
            if (score > bestScore) {
                bestScore = score;
                bestBox = box;
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
// QR code + 1-D barcode decoding (OpenCV objdetect).
//
// Returns an interleaved array {format0, payload0, format1, payload1, ...} of raw
// bytes (payloads are not guaranteed to be valid UTF-8, so Kotlin decodes them),
// or null when nothing was decoded. For code i, cornersOut[8i..8i+7] receives the
// four (x, y) corners normalized to the bitmap size. At most cornersOut.length / 8
// codes are returned. tryHarder adds an inverted-image pass (for still images).
// ===========================================================================

JNIEXPORT jobjectArray JNICALL
Java_com_fearmikey_camera_data_engine_processing_NativeVisionBridge_nativeDecodeCodes(
        JNIEnv* env, jobject, jobject bitmap, jfloatArray cornersOut, jboolean tryHarder) {
    struct Found {
        std::string format;
        std::string text;
        float corners[8];
    };
    const size_t maxCodes = cornersOut ? static_cast<size_t>(env->GetArrayLength(cornersOut) / 8) : 0;
    if (maxCodes == 0) return nullptr;

    cv::Mat gray;
    {
        LockedBitmap bmp(env, bitmap);
        if (!bmp.ok()) return nullptr;
        cv::cvtColor(bmp.mat(), gray, cv::COLOR_RGBA2GRAY);
    }
    if (gray.empty()) return nullptr;
    const float w = static_cast<float>(gray.cols);
    const float h = static_cast<float>(gray.rows);

    std::vector<Found> found;
    // Detector point outputs are N x 4 CV_32FC2 (layout varies); flatten to (4N) x 2 floats.
    auto toQuads = [](const cv::Mat& pts) -> cv::Mat {
        if (pts.empty()) return {};
        cv::Mat f;
        pts.convertTo(f, CV_32F);
        f = f.reshape(1, static_cast<int>(f.total() * f.channels() / 2));
        return f.cols == 2 ? f : cv::Mat();
    };
    auto add = [&](const std::string& format, const std::string& text, const cv::Mat& quads, size_t index) {
        if (text.empty() || found.size() >= maxCodes) return;
        for (const auto& f : found) if (f.text == text) return;
        Found f{format, text, {}};
        const int row = static_cast<int>(index * 4);
        if (quads.rows >= row + 4) {
            for (int i = 0; i < 4; ++i) {
                f.corners[2 * i] = std::clamp(quads.at<float>(row + i, 0) / w, 0.f, 1.f);
                f.corners[2 * i + 1] = std::clamp(quads.at<float>(row + i, 1) / h, 0.f, 1.f);
            }
        }
        found.push_back(std::move(f));
    };
    auto decodeQr = [&](const cv::Mat& img) {
        try {
            // Aruco-based detector: more robust to blur/perspective and finds several codes.
            static thread_local cv::QRCodeDetectorAruco aruco;
            std::vector<std::string> texts;
            cv::Mat pts;
            if (aruco.detectAndDecodeMulti(img, texts, pts)) {
                cv::Mat quads = toQuads(pts);
                for (size_t i = 0; i < texts.size(); ++i) add("QR_CODE", texts[i], quads, i);
            }
        } catch (const cv::Exception& e) {
            LOGE("QR (aruco) decode failed: %s", e.what());
        }
        if (!found.empty()) return;
        try {
            // The classic detector still wins on some small, sharp codes.
            static thread_local cv::QRCodeDetector classic;
            cv::Mat pts;
            std::string text = classic.detectAndDecode(img, pts);
            add("QR_CODE", text, toQuads(pts), 0);
        } catch (const cv::Exception& e) {
            LOGE("QR decode failed: %s", e.what());
        }
    };
    auto decodeBarcodes = [&](const cv::Mat& img) {
        try {
            static thread_local cv::barcode::BarcodeDetector barcode;
            std::vector<std::string> texts, types;
            cv::Mat pts;
            if (barcode.detectAndDecodeWithType(img, texts, types, pts)) {
                cv::Mat quads = toQuads(pts);
                for (size_t i = 0; i < texts.size(); ++i) {
                    add(i < types.size() && !types[i].empty() ? types[i] : "BARCODE", texts[i], quads, i);
                }
            }
        } catch (const cv::Exception& e) {
            LOGE("Barcode decode failed: %s", e.what());
        }
    };

    decodeQr(gray);
    decodeBarcodes(gray);
    if (found.empty() && tryHarder) {
        // Light-on-dark (inverted) QR codes.
        cv::Mat inverted;
        cv::bitwise_not(gray, inverted);
        decodeQr(inverted);
    }
    if (found.empty()) return nullptr;

    jclass byteArrayClass = env->FindClass("[B");
    if (!byteArrayClass) return nullptr;
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(found.size() * 2), byteArrayClass, nullptr);
    if (!result) return nullptr;
    std::vector<float> corners(found.size() * 8, 0.f);
    for (size_t i = 0; i < found.size(); ++i) {
        const std::string* parts[2] = {&found[i].format, &found[i].text};
        for (int p = 0; p < 2; ++p) {
            const auto len = static_cast<jsize>(parts[p]->size());
            jbyteArray bytes = env->NewByteArray(len);
            if (!bytes) return nullptr;
            env->SetByteArrayRegion(bytes, 0, len, reinterpret_cast<const jbyte*>(parts[p]->data()));
            env->SetObjectArrayElement(result, static_cast<jsize>(i * 2 + p), bytes);
            env->DeleteLocalRef(bytes);
        }
        std::copy(found[i].corners, found[i].corners + 8, corners.begin() + static_cast<long>(i * 8));
    }
    env->SetFloatArrayRegion(cornersOut, 0, static_cast<jsize>(corners.size()), corners.data());
    return result;
}

}  // extern "C"
