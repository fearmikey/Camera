#include "PanoramaEngine.hpp"

#include <opencv2/calib3d.hpp>
#include <opencv2/core/ocl.hpp>
#include <opencv2/imgproc.hpp>
#include <android/log.h>
#include <algorithm>
#include <cmath>

#define LOG_TAG "PanoramaEngineNDK"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

constexpr double kPi = 3.14159265358979323846;
constexpr int kTrackWidth = 300;         // upright tracking width in px
constexpr double kPreviewScale = 0.5;    // preview strip relative to tracking scale
constexpr float kTargetStep = 0.33f;     // next dot this far from the last keyframe (fraction of width)
constexpr float kAlignRadius = 0.045f;   // dot counts as inside the target circle (ring radius on screen)
constexpr float kStillSpeed = 0.12f;     // below this many frame widths per second counts as paused
constexpr int64_t kHoldNs = 400'000'000; // hold the dot in the circle, paused, this long to capture
constexpr float kTooFastStep = 0.10f;    // per-frame motion that counts as too fast
constexpr size_t kMaxKeyframes = 48;
constexpr int kLostAfterFrames = 4;

void rotateUpright(const cv::Mat& src, cv::Mat& dst, int degrees) {
    switch (degrees) {
        case 90: cv::rotate(src, dst, cv::ROTATE_90_CLOCKWISE); break;
        case 180: cv::rotate(src, dst, cv::ROTATE_180); break;
        case 270: cv::rotate(src, dst, cv::ROTATE_90_COUNTERCLOCKWISE); break;
        default: dst = src.clone(); break;
    }
}

/**
 * Inverse maps from a cylindrical image to a planar (pinhole) image of size [src]
 * with focal length [f]. The cylinder image keeps the source height and is just wide
 * enough to hold the projected frame. [mask] marks pixels that come from the frame.
 */
void buildCylinderMaps(cv::Size src, double f, cv::Mat& mapX, cv::Mat& mapY, cv::Mat& mask) {
    const double cx = (src.width - 1) * 0.5;
    const double cy = (src.height - 1) * 0.5;
    const double halfTheta = std::atan((src.width * 0.5) / f);
    const int cylW = 2 * static_cast<int>(std::ceil(f * halfTheta));
    const int cylH = src.height;
    const double ccx = (cylW - 1) * 0.5;
    mapX.create(cylH, cylW, CV_32F);
    mapY.create(cylH, cylW, CV_32F);
    mask.create(cylH, cylW, CV_8U);
    std::vector<double> tanT(cylW), invCos(cylW);
    for (int u = 0; u < cylW; ++u) {
        const double theta = (u - ccx) / f;
        tanT[u] = std::tan(theta);
        invCos[u] = 1.0 / std::cos(theta);
    }
    for (int v = 0; v < cylH; ++v) {
        auto* mx = mapX.ptr<float>(v);
        auto* my = mapY.ptr<float>(v);
        auto* mk = mask.ptr<uint8_t>(v);
        const double dy = v - cy;
        for (int u = 0; u < cylW; ++u) {
            const double x = f * tanT[u] + cx;
            const double y = dy * invCos[u] + cy;
            mx[u] = static_cast<float>(x);
            my[u] = static_cast<float>(y);
            mk[u] = (x >= 0 && x <= src.width - 1 && y >= 0 && y <= src.height - 1) ? 255 : 0;
        }
    }
}

/** Laplacian-pyramid blend; mask is 8-bit, 255 selects [a]. */
cv::Mat multiBandBlend(const cv::Mat& a, const cv::Mat& b, const cv::Mat& mask, int levels) {
    const int align = 1 << levels;
    const int padR = (align - a.cols % align) % align;
    const int padB = (align - a.rows % align) % align;
    cv::Mat af, bf, mf;
    a.convertTo(af, CV_32FC3);
    b.convertTo(bf, CV_32FC3);
    mask.convertTo(mf, CV_32F, 1.0 / 255.0);
    if (padR || padB) {
        cv::copyMakeBorder(af, af, 0, padB, 0, padR, cv::BORDER_REFLECT);
        cv::copyMakeBorder(bf, bf, 0, padB, 0, padR, cv::BORDER_REFLECT);
        cv::copyMakeBorder(mf, mf, 0, padB, 0, padR, cv::BORDER_REPLICATE);
    }

    std::vector<cv::Mat> blended(levels + 1);
    cv::Mat ga = af, gb = bf, gm = mf;
    for (int l = 0; l <= levels; ++l) {
        cv::Mat la, lb, nextA, nextB, nextM;
        if (l < levels) {
            cv::pyrDown(ga, nextA);
            cv::pyrDown(gb, nextB);
            cv::pyrDown(gm, nextM);
            cv::Mat upA, upB;
            cv::pyrUp(nextA, upA, ga.size());
            cv::pyrUp(nextB, upB, gb.size());
            la = ga - upA;
            lb = gb - upB;
        } else {
            la = ga;
            lb = gb;
        }
        cv::Mat m3, inv3;
        cv::Mat ch[] = {gm, gm, gm};
        cv::merge(ch, 3, m3);
        inv3 = cv::Scalar::all(1.0) - m3;
        blended[l] = la.mul(m3) + lb.mul(inv3);
        ga = nextA;
        gb = nextB;
        gm = nextM;
    }
    cv::Mat out = blended[levels];
    for (int l = levels - 1; l >= 0; --l) {
        cv::Mat up;
        cv::pyrUp(out, up, blended[l].size());
        out = up + blended[l];
    }
    cv::Mat result;
    out(cv::Rect(0, 0, a.cols, a.rows)).convertTo(result, CV_8UC3);
    return result;
}

/**
 * Minimum-cost top-to-bottom seam through columns [x0, x1) of the overlap. Returns the
 * seam column (relative to the ROI) for every row.
 */
std::vector<int> findSeam(const cv::Mat& a, const cv::Mat& aMask, const cv::Mat& b, const cv::Mat& bMask,
                          int x0, int x1) {
    const int rows = a.rows;
    const int w = x1 - x0;
    const float mid = (w - 1) * 0.5f;
    cv::Mat acc(rows, w, CV_32F);
    cv::Mat from(rows, w, CV_8S);
    for (int y = 0; y < rows; ++y) {
        const auto* pa = a.ptr<cv::Vec3b>(y) + x0;
        const auto* pb = b.ptr<cv::Vec3b>(y) + x0;
        const auto* ma = aMask.ptr<uint8_t>(y) + x0;
        const auto* mb = bMask.ptr<uint8_t>(y) + x0;
        auto* row = acc.ptr<float>(y);
        auto* fr = from.ptr<int8_t>(y);
        const float* prev = y > 0 ? acc.ptr<float>(y - 1) : nullptr;
        for (int x = 0; x < w; ++x) {
            float cost = 0.02f * std::abs(x - mid); // mild pull toward the overlap center
            if (ma[x] && mb[x]) {
                cost += static_cast<float>(std::abs(pa[x][0] - pb[x][0]) + std::abs(pa[x][1] - pb[x][1]) +
                                           std::abs(pa[x][2] - pb[x][2]));
            }
            if (!prev) {
                row[x] = cost;
                fr[x] = 0;
                continue;
            }
            float best = prev[x];
            int8_t dir = 0;
            if (x > 0 && prev[x - 1] < best) { best = prev[x - 1]; dir = -1; }
            if (x + 1 < w && prev[x + 1] < best) { best = prev[x + 1]; dir = 1; }
            row[x] = cost + best;
            fr[x] = dir;
        }
    }
    std::vector<int> seam(rows);
    const auto* last = acc.ptr<float>(rows - 1);
    int x = static_cast<int>(std::min_element(last, last + w) - last);
    for (int y = rows - 1; y >= 0; --y) {
        seam[y] = x0 + x;
        x += from.at<int8_t>(y, x);
        x = std::clamp(x, 0, w - 1);
    }
    return seam;
}

using PointPairs = std::pair<std::vector<cv::Point2f>, std::vector<cv::Point2f>>;

/**
 * How far the matches deviate from a pure translation once both frames are projected onto
 * a cylinder of focal [f]: per pair, the mean truncated squared distance (in radians, so
 * there is no bias toward small f) from the median displacement; summed over pairs.
 */
double cylinderResidual(const std::vector<PointPairs>& pairs, double f, double cap) {
    double total = 0;
    std::vector<float> dx, dy;
    std::vector<cv::Point2f> d;
    for (const auto& [a, b] : pairs) {
        d.resize(a.size());
        dx.resize(a.size());
        dy.resize(a.size());
        for (size_t j = 0; j < a.size(); ++j) {
            const double ua = std::atan2(a[j].x, f), va = a[j].y / std::hypot(a[j].x, f);
            const double ub = std::atan2(b[j].x, f), vb = b[j].y / std::hypot(b[j].x, f);
            d[j] = cv::Point2f(static_cast<float>(ua - ub), static_cast<float>(va - vb));
            dx[j] = d[j].x;
            dy[j] = d[j].y;
        }
        const size_t mid = a.size() / 2;
        std::nth_element(dx.begin(), dx.begin() + mid, dx.end());
        std::nth_element(dy.begin(), dy.begin() + mid, dy.end());
        const cv::Point2f t(dx[mid], dy[mid]);
        double sum = 0;
        for (const auto& p : d) {
            const cv::Point2f e = p - t;
            sum += std::min(static_cast<double>(e.dot(e)), cap);
        }
        total += sum / static_cast<double>(a.size());
    }
    return total;
}

/**
 * 1-D search over f (log grid, then fine) for the cylinder on which the matches move most
 * like a pure translation. Returns true when the data clearly prefers [bestF] to [prior].
 */
bool fitFocal(const std::vector<PointPairs>& pairs, double prior, double& bestF) {
    bestF = prior;
    if (pairs.empty()) return false;
    const double cap = std::pow(4.0 / prior, 2.0); // ~4 px at the prior focal
    const double priorCost = cylinderResidual(pairs, prior, cap);
    double bestCost = priorCost;
    auto scan = [&](double lo, double hi, int steps) {
        for (int k = 0; k <= steps; ++k) {
            const double f = lo * std::pow(hi / lo, static_cast<double>(k) / steps);
            const double c = cylinderResidual(pairs, f, cap);
            if (c < bestCost) { bestCost = c; bestF = f; }
        }
    };
    scan(0.4 * prior, 2.5 * prior, 60);
    scan(bestF / 1.05, bestF * 1.05, 20);
    LOGI("focal fit: %zu pair(s), best %.1f (cost %.3g) vs prior %.1f (cost %.3g)",
         pairs.size(), bestF, bestCost, prior, priorCost);
    // More pairs = more evidence, so a smaller improvement is already meaningful. (With
    // correct metadata the best cost is within a few percent of the prior's.)
    const double required = pairs.size() >= 4 ? 0.85 : 0.7;
    return bestCost <= required * priorCost;
}

/** Median cylinder offset (pixels at focal [f]) of the second view relative to the first. */
cv::Point2d pairOffset(const PointPairs& pp, double f) {
    std::vector<double> dx(pp.first.size()), dy(pp.first.size());
    for (size_t j = 0; j < pp.first.size(); ++j) {
        const auto& a = pp.first[j];
        const auto& b = pp.second[j];
        dx[j] = f * (std::atan2(a.x, f) - std::atan2(b.x, f));
        dy[j] = f * (a.y / std::hypot(a.x, f) - b.y / std::hypot(b.x, f));
    }
    const size_t mid = dx.size() / 2;
    std::nth_element(dx.begin(), dx.begin() + static_cast<long>(mid), dx.end());
    std::nth_element(dy.begin(), dy.begin() + static_cast<long>(mid), dy.end());
    return {dx[mid], dy[mid]};
}

/** Largest axis-aligned rectangle that is fully covered (sampled search). */
cv::Rect largestCoveredRect(const cv::Mat& cover) {
    const int W = cover.cols, H = cover.rows;
    std::vector<int> top(W, H), bottom(W, 0);
    for (int y = 0; y < H; ++y) {
        const auto* row = cover.ptr<uint8_t>(y);
        for (int x = 0; x < W; ++x) {
            if (row[x]) {
                if (y < top[x]) top[x] = y;
                bottom[x] = y + 1;
            }
        }
    }
    constexpr int kSamples = 64;
    cv::Rect best(0, 0, 0, 0);
    long bestArea = 0;
    for (int ti = 0; ti < kSamples; ++ti) {
        const int t = ti * (H / 2) / kSamples;
        for (int bi = 0; bi < kSamples; ++bi) {
            const int b = H - bi * (H / 2) / kSamples;
            if (b - t <= 0) continue;
            int runStart = -1;
            for (int x = 0; x <= W; ++x) {
                const bool ok = x < W && top[x] <= t && bottom[x] >= b;
                if (ok && runStart < 0) runStart = x;
                if (!ok && runStart >= 0) {
                    const long area = static_cast<long>(x - runStart) * (b - t);
                    if (area > bestArea) {
                        bestArea = area;
                        best = cv::Rect(runStart, t, x - runStart, b - t);
                    }
                    runStart = -1;
                }
            }
        }
    }
    return best;
}

} // namespace

PanoramaEngine::PanoramaEngine(float tanHalfFovLongSide) {
    // OpenCL in the Maven OpenCV build can hang on some mobile GPU drivers.
    cv::ocl::setUseOpenCL(false);
    mTanHalfFov = (tanHalfFovLongSide > 0.15f && tanHalfFovLongSide < 3.0f)
                  ? tanHalfFovLongSide
                  : static_cast<float>(std::tan(35.0 * kPi / 180.0));
    mOrb = cv::ORB::create(600, 1.2f, 4, 15, 0, 2, cv::ORB::HARRIS_SCORE, 15, 10);
    mCalibOrb = cv::ORB::create(800, 1.2f, 4, 15, 0, 2, cv::ORB::HARRIS_SCORE, 15, 10);
    mMatcher = cv::DescriptorMatcher::create(cv::DescriptorMatcher::BRUTEFORCE_HAMMING);
}

void PanoramaEngine::init(int uprightW, int uprightH, int longSide) {
    mW = uprightW;
    mH = uprightH;
    mFocal = (longSide * 0.5) / mTanHalfFov;
    mTrackScale = std::min(1.0, static_cast<double>(kTrackWidth) / mW);
    mSmall = cv::Size(cvRound(mW * mTrackScale), cvRound(mH * mTrackScale));
    mInitialized = true;
    buildTrackingGeometry();
}

void PanoramaEngine::buildTrackingGeometry() {
    buildCylinderMaps(mSmall, mFocal * mTrackScale, mSmallMapX, mSmallMapY, mSmallMask);
    cv::erode(mSmallMask, mSmallMaskEroded, cv::Mat(), cv::Point(-1, -1), 8);
    mCylSmallW = mSmallMask.cols;
    cv::createHanningWindow(mHann, mSmallMask.size(), CV_32F);
    const double circumference = 2.0 * kPi * mFocal * mTrackScale;
    mPreviewW = std::max(1, static_cast<int>(std::ceil(circumference * kPreviewScale)));
    mPreviewH = std::max(1, static_cast<int>(std::ceil(mSmall.height * kPreviewScale)));
    LOGI("tracking geometry: frame %dx%d, focal %.1fpx, tracking %dx%d, cylinder width %d, preview %dx%d",
         mW, mH, mFocal, mSmall.width, mSmall.height, mCylSmallW, mPreviewW, mPreviewH);
}

PanoramaEngine::Stats PanoramaEngine::processFrame(const uint8_t* rgba, int width, int height, int stride,
                                                   int rotationDegrees, int64_t timestampNs) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mStitched) {
        Stats s = mLastStats;
        s.status = kComplete;
        return s;
    }

    const bool swap = rotationDegrees == 90 || rotationDegrees == 270;
    const int uprightW = swap ? height : width;
    const int uprightH = swap ? width : height;
    if (!mInitialized) {
        init(uprightW, uprightH, std::max(width, height));
    } else if (uprightW != mW || uprightH != mH) {
        Stats s = mLastStats;
        s.status = kLost;
        return s;
    }

    cv::Mat raw(height, width, CV_8UC4, const_cast<uint8_t*>(rgba), static_cast<size_t>(stride));
    const cv::Size smallRaw = swap ? cv::Size(mSmall.height, mSmall.width) : mSmall;
    cv::Mat colorSmallRaw, colorSmall, gray, cylGray;
    cv::resize(raw, colorSmallRaw, smallRaw, 0, 0, cv::INTER_AREA);
    rotateUpright(colorSmallRaw, colorSmall, rotationDegrees);
    cv::cvtColor(colorSmall, gray, cv::COLOR_RGBA2GRAY);
    if (!mKeyframes.empty() && !mCalibrated) calibrateLive(gray);
    cv::remap(gray, cylGray, mSmallMapX, mSmallMapY, cv::INTER_LINEAR, cv::BORDER_CONSTANT);

    std::vector<cv::KeyPoint> kps;
    cv::Mat desc;
    mOrb->detectAndCompute(cylGray, mSmallMaskEroded, kps, desc);

    if (mKeyframes.empty()) {
        mCalibColor = colorSmall.clone();
        mCalibOrb->detectAndCompute(gray, cv::noArray(), mCalibKp, mCalibDesc);
        addKeyframe(raw, rotationDegrees, colorSmall, cylGray, std::move(kps), std::move(desc), {0.f, 0.f});
        mPrevPos = {0.f, 0.f};
        mLastTs = timestampNs;
        updateCurrentPreview(colorSmall, mPrevPos);
        mLastStats = makeStats(kKeyframe, mPrevPos);
        return mLastStats;
    }

    const Keyframe& kf = mKeyframes.back();
    const cv::Point2f predicted = mPrevPos - kf.pos;
    cv::Point2f t;
    bool ok = registerTranslation(kf.keypoints, kf.descriptors, kps, desc, predicted,
                                  0.25f * mCylSmallW, 2.5f, 10, t);
    if (!ok) {
        // Low texture (sky, walls): phase correlation on the whole cylinder image.
        cv::Mat a, b;
        kf.gray.convertTo(a, CV_32F);
        cylGray.convertTo(b, CV_32F);
        double response = 0;
        const cv::Point2d shift = cv::phaseCorrelate(a, b, mHann, &response);
        const cv::Point2f candidate(static_cast<float>(-shift.x), static_cast<float>(-shift.y));
        const float dev = static_cast<float>(cv::norm(candidate - predicted));
        if (response > 0.12 && dev < 0.12f * mCylSmallW) {
            t = candidate;
            ok = true;
        }
    }
    if (!ok) {
        ++mLostFrames;
        mAligned = false;
        mHoldStart = -1;
        mLastTs = timestampNs;
        mLastStats = makeStats(mLostFrames >= kLostAfterFrames ? kLost : kTracking, mPrevPos);
        return mLastStats;
    }
    mLostFrames = 0;

    const cv::Point2f pos = kf.pos + t;
    const float step = static_cast<float>(cv::norm(pos - mPrevPos));
    mPrevPos = pos;
    int status = step > kTooFastStep * mCylSmallW ? kTooFast : kTracking;

    updateCurrentPreview(colorSmall, pos);
    if (mDirection == 0 && std::abs(pos.x) > 0.08f * mCylSmallW) mDirection = pos.x > 0 ? 1 : -1;

    // Smoothed angular speed in frame widths per second (frame-rate independent).
    double dt = mLastTs > 0 ? static_cast<double>(timestampNs - mLastTs) * 1e-9 : 1.0 / 30.0;
    dt = std::clamp(dt, 0.005, 0.5);
    mLastTs = timestampNs;
    const double speed = step / static_cast<double>(mCylSmallW) / dt;
    mSpeed = 0.5 * mSpeed + 0.5 * speed;
    mAligned = false;

    if (mDirection != 0) {
        // The next dot sits kTargetStep past the last keyframe, on the starting horizon.
        const float target = kf.pos.x + static_cast<float>(mDirection) * kTargetStep * mCylSmallW;
        const float errX = (pos.x - target) / static_cast<float>(mCylSmallW);
        const float errY = pos.y / static_cast<float>(mCylSmallW);
        mAligned = std::hypot(errX, errY) < kAlignRadius;

        // Only a deliberate pause on the dot captures; panning past it does nothing (the UI
        // then points back to it), exactly like the stock camera.
        if (mAligned && mSpeed < kStillSpeed) {
            if (mHoldStart < 0) mHoldStart = timestampNs;
        } else {
            mHoldStart = -1;
        }
        // The still hold itself rules out motion blur (no sharpness check: Laplacian variance
        // depends on scene texture and would reject plain walls/sky).
        const bool take = mHoldStart >= 0 && timestampNs - mHoldStart >= kHoldNs;
        if (take) {
            addKeyframe(raw, rotationDegrees, colorSmall, cylGray, std::move(kps), std::move(desc), pos);
            status = kKeyframe;
            mAligned = false; // the next dot appears
            mHoldStart = -1;
            LOGI("keyframe %zu at x=%.1f y=%.1f", mKeyframes.size(), pos.x, pos.y);
        }
    }

    const double covered = std::abs(pos.x) + mCylSmallW;
    if (covered >= 2.0 * kPi * mFocal * mTrackScale || mKeyframes.size() >= kMaxKeyframes) {
        status = kComplete;
    }
    mLastStats = makeStats(status, pos);
    return mLastStats;
}

void PanoramaEngine::addKeyframe(const cv::Mat& raw, int rotation, const cv::Mat& colorSmall,
                                 const cv::Mat& cylGray, std::vector<cv::KeyPoint>&& kps, cv::Mat&& desc,
                                 cv::Point2f pos) {
    Keyframe kf;
    cv::Mat bgr;
    cv::cvtColor(raw, bgr, cv::COLOR_RGBA2BGR);
    rotateUpright(bgr, kf.full, rotation);
    kf.gray = cylGray.clone();
    cv::Mat cylColor;
    cv::remap(colorSmall, cylColor, mSmallMapX, mSmallMapY, cv::INTER_LINEAR, cv::BORDER_CONSTANT);
    cv::resize(cylColor, kf.preview, cv::Size(), kPreviewScale, kPreviewScale, cv::INTER_AREA);
    cv::resize(mSmallMask, kf.previewMask, kf.preview.size(), 0, 0, cv::INTER_NEAREST);
    kf.keypoints = std::move(kps);
    kf.descriptors = std::move(desc);
    kf.pos = pos;
    mKeyframes.push_back(std::move(kf));
}

/**
 * Live self-calibration (until it succeeds once): planar matches against keyframe 0 are
 * cleaned with a homography (exact for any rotation, whatever the focal length) and, once
 * the camera has turned far enough, used to fit the focal length. A clearly better fit
 * rebuilds the tracking cylinder and keyframe 0 so tracking holds with bad lens metadata.
 */
void PanoramaEngine::calibrateLive(const cv::Mat& gray) {
    if (mCalibDesc.rows < 30) {
        mCalibrated = true; // too little texture to ever calibrate; keep the metadata
        return;
    }
    std::vector<cv::KeyPoint> kp;
    cv::Mat ds;
    mCalibOrb->detectAndCompute(gray, cv::noArray(), kp, ds);
    if (ds.rows < 30) return;
    std::vector<std::vector<cv::DMatch>> knn;
    mMatcher->knnMatch(mCalibDesc, ds, knn, 2);
    const cv::Point2f center(mSmall.width * 0.5f, mSmall.height * 0.5f);
    const auto toFull = static_cast<float>(1.0 / mTrackScale);
    std::vector<cv::Point2f> a, b;
    for (const auto& m : knn) {
        if (m.size() < 2 || m[0].distance >= 0.75f * m[1].distance) continue;
        a.push_back((mCalibKp[m[0].queryIdx].pt - center) * toFull);
        b.push_back((kp[m[0].trainIdx].pt - center) * toFull);
    }
    if (a.size() < 30) return;
    cv::Mat inliers;
    const cv::Mat H = cv::findHomography(a, b, cv::RANSAC, 2.0 * toFull, inliers);
    if (H.empty() || cv::countNonZero(inliers) < 25) return;
    PointPairs pp;
    std::vector<float> shift;
    for (int j = 0; j < inliers.rows; ++j) {
        if (!inliers.at<uint8_t>(j)) continue;
        pp.first.push_back(a[j]);
        pp.second.push_back(b[j]);
        shift.push_back(std::abs(a[j].x - b[j].x));
    }
    std::nth_element(shift.begin(), shift.begin() + static_cast<long>(shift.size() / 2), shift.end());
    if (shift[shift.size() / 2] < 0.12f * static_cast<float>(mW)) return; // not enough rotation yet

    mCalibrated = true;
    double f = mFocal;
    if (!fitFocal({pp}, mFocal, f) || std::abs(f / mFocal - 1.0) <= 0.05) return;
    LOGI("live focal %.1f -> %.1f", mFocal, f);
    mFocal = f;
    buildTrackingGeometry();

    Keyframe& kf0 = mKeyframes.front();
    cv::Mat gray0, cyl0;
    cv::cvtColor(mCalibColor, gray0, cv::COLOR_RGBA2GRAY);
    cv::remap(gray0, cyl0, mSmallMapX, mSmallMapY, cv::INTER_LINEAR, cv::BORDER_CONSTANT);
    kf0.gray = cyl0;
    mOrb->detectAndCompute(cyl0, mSmallMaskEroded, kf0.keypoints, kf0.descriptors);
    cv::Mat cylColor;
    cv::remap(mCalibColor, cylColor, mSmallMapX, mSmallMapY, cv::INTER_LINEAR, cv::BORDER_CONSTANT);
    cv::resize(cylColor, kf0.preview, cv::Size(), kPreviewScale, kPreviewScale, cv::INTER_AREA);
    cv::resize(mSmallMask, kf0.previewMask, kf0.preview.size(), 0, 0, cv::INTER_NEAREST);
    const cv::Point2d off = pairOffset(pp, mFocal) * mTrackScale;
    mPrevPos = cv::Point2f(static_cast<float>(off.x), static_cast<float>(off.y));
    mLostFrames = 0;
}

float PanoramaEngine::previewOriginX() const {
    // Leftward sweeps grow from the right edge of the 360-degree strip.
    const float stripTrackW = static_cast<float>(mPreviewW / kPreviewScale);
    return mDirection < 0 ? stripTrackW - static_cast<float>(mCylSmallW) : 0.f;
}

PanoramaEngine::Stats PanoramaEngine::makeStats(int status, cv::Point2f pos) const {
    Stats s;
    s.status = status;
    s.keyframes = static_cast<int>(mKeyframes.size());
    s.direction = mDirection;
    const double fSmall = mFocal * mTrackScale;
    s.sweepDegrees = static_cast<float>((std::abs(pos.x) + mCylSmallW) / fSmall * 180.0 / kPi);
    s.verticalOffset = mSmall.height > 0 ? pos.y / static_cast<float>(mSmall.height) : 0.f;
    const float stripTrackW = static_cast<float>(mPreviewW / kPreviewScale);
    s.stripX = (previewOriginX() + pos.x) / stripTrackW;
    s.stripWidth = static_cast<float>(mCylSmallW) / stripTrackW;
    // Dot geometry in fractions of the (planar) frame width, as seen in the viewfinder.
    const float frameW = static_cast<float>(mSmall.width);
    if (!mKeyframes.empty()) s.keyframeDx = (pos.x - mKeyframes.back().pos.x) / frameW;
    s.targetStep = kTargetStep * static_cast<float>(mCylSmallW) / frameW;
    s.aligned = mAligned ? 1 : 0;
    return s;
}

void PanoramaEngine::updateCurrentPreview(const cv::Mat& colorSmall, cv::Point2f pos) {
    cv::Mat cylColor;
    cv::remap(colorSmall, cylColor, mSmallMapX, mSmallMapY, cv::INTER_LINEAR, cv::BORDER_CONSTANT);
    cv::resize(cylColor, mCurPreview, cv::Size(), kPreviewScale, kPreviewScale, cv::INTER_AREA);
    cv::resize(mSmallMask, mCurPreviewMask, mCurPreview.size(), 0, 0, cv::INTER_NEAREST);
    mCurPos = pos;
}

bool PanoramaEngine::registerTranslation(const std::vector<cv::KeyPoint>& kpA, const cv::Mat& descA,
                                         const std::vector<cv::KeyPoint>& kpB, const cv::Mat& descB,
                                         cv::Point2f predicted, float radius, float threshold,
                                         size_t minInliers, cv::Point2f& out) const {
    if (descA.rows < static_cast<int>(minInliers) || descB.rows < static_cast<int>(minInliers)) return false;
    std::vector<std::vector<cv::DMatch>> knn;
    mMatcher->knnMatch(descB, descA, knn, 2);

    // Displacement t = posB - posA = ptA - ptB for every plausible match.
    std::vector<cv::Point2f> d;
    d.reserve(knn.size());
    for (const auto& m : knn) {
        if (m.empty()) continue;
        if (m.size() >= 2 && m[0].distance >= 0.8f * m[1].distance) continue;
        const cv::Point2f disp = kpA[m[0].trainIdx].pt - kpB[m[0].queryIdx].pt;
        if (cv::norm(disp - predicted) <= radius) d.push_back(disp);
    }
    if (d.size() < minInliers) return false;

    // 1-point RANSAC: a translation is fully defined by a single match.
    const float thr2 = threshold * threshold;
    auto countInliers = [&](cv::Point2f c, cv::Point2f* mean) {
        size_t n = 0;
        cv::Point2f sum(0.f, 0.f);
        for (const auto& p : d) {
            const cv::Point2f e = p - c;
            if (e.dot(e) <= thr2) {
                ++n;
                sum += p;
            }
        }
        if (mean && n) *mean = sum * (1.f / static_cast<float>(n));
        return n;
    };
    const size_t stride = std::max<size_t>(1, d.size() / 300);
    size_t bestCount = 0;
    cv::Point2f best;
    for (size_t i = 0; i < d.size(); i += stride) {
        const size_t c = countInliers(d[i], nullptr);
        if (c > bestCount) {
            bestCount = c;
            best = d[i];
        }
    }
    cv::Point2f mean = best;
    countInliers(best, &mean);
    bestCount = countInliers(mean, &mean);
    if (bestCount < minInliers || bestCount * 10 < d.size() * 3) return false;
    out = mean;
    return true;
}

/**
 * Self-calibration: estimates the focal length (analysis pixels) from homographies between
 * adjacent planar keyframes, so a wrong lens/sensor report (emulators, some OEMs, digital
 * zoom) doesn't bend the panorama. Returns 0 when the estimates aren't trustworthy.
 */
double PanoramaEngine::estimateFocal(std::vector<cv::Point2d>& offsets, std::vector<char>& valid) const {
    const size_t n = mKeyframes.size();
    offsets.assign(n > 0 ? n - 1 : 0, cv::Point2d());
    valid.assign(n > 0 ? n - 1 : 0, 0);
    if (n < 2) return 0;
    constexpr double kScale = 0.5;
    auto orb = cv::ORB::create(2000, 1.2f, 6, 31, 0, 2, cv::ORB::HARRIS_SCORE, 31, 10);
    const cv::Size half(cvRound(mW * kScale), cvRound(mH * kScale));
    const cv::Point2f center(half.width * 0.5f, half.height * 0.5f);
    auto features = [&](const cv::Mat& img, std::vector<cv::KeyPoint>& kp, cv::Mat& ds) {
        cv::Mat small, gray;
        cv::resize(img, small, half, 0, 0, cv::INTER_AREA);
        cv::cvtColor(small, gray, cv::COLOR_BGR2GRAY);
        orb->detectAndCompute(gray, cv::noArray(), kp, ds);
    };
    std::vector<PointPairs> pairs;
    std::vector<size_t> pairIndex; // pairs[k] is between keyframes pairIndex[k] and pairIndex[k] + 1
    std::vector<cv::KeyPoint> prevKp, curKp;
    cv::Mat prevDs, curDs;
    features(mKeyframes[0].full, prevKp, prevDs);
    for (size_t i = 1; i < n; ++i) {
        features(mKeyframes[i].full, curKp, curDs);
        if (prevDs.rows >= 40 && curDs.rows >= 40) {
            std::vector<std::vector<cv::DMatch>> knn;
            mMatcher->knnMatch(prevDs, curDs, knn, 2);
            std::vector<cv::Point2f> a, b;
            for (const auto& m : knn) {
                if (m.size() < 2 || m[0].distance >= 0.75f * m[1].distance) continue;
                a.push_back(prevKp[m[0].queryIdx].pt - center);
                b.push_back(curKp[m[0].trainIdx].pt - center);
            }
            if (a.size() >= 40) {
                // A homography is exact for any rotation, so its inliers are clean matches
                // regardless of the focal length; keep them (in full-res pixels).
                cv::Mat inliers;
                const cv::Mat H = cv::findHomography(a, b, cv::RANSAC, 2.0, inliers);
                if (!H.empty() && cv::countNonZero(inliers) >= 30) {
                    PointPairs pp;
                    for (int j = 0; j < inliers.rows; ++j) {
                        if (!inliers.at<uint8_t>(j)) continue;
                        pp.first.push_back(a[j] * static_cast<float>(1.0 / kScale));
                        pp.second.push_back(b[j] * static_cast<float>(1.0 / kScale));
                    }
                    pairs.push_back(std::move(pp));
                    pairIndex.push_back(i - 1);
                }
            }
        }
        prevKp = std::move(curKp);
        prevDs = curDs.clone();
        curKp.clear();
    }
    if (pairs.empty()) return 0;
    double bestF = mFocal;
    const bool useFit = fitFocal(pairs, mFocal, bestF);
    const double f = useFit ? bestF : mFocal;
    for (size_t k = 0; k < pairs.size(); ++k) {
        offsets[pairIndex[k]] = pairOffset(pairs[k], f);
        valid[pairIndex[k]] = 1;
    }
    return useFit ? bestF : 0;
}

bool PanoramaEngine::previewSize(int& width, int& height) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (!mInitialized) return false;
    width = mPreviewW;
    height = mPreviewH;
    return true;
}

bool PanoramaEngine::renderPreview(uint8_t* rgbaOut, int width, int height, int stride) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (!mInitialized || width != mPreviewW || height != mPreviewH) return false;
    cv::Mat canvas(height, width, CV_8UC4, rgbaOut, static_cast<size_t>(stride));
    canvas.setTo(cv::Scalar::all(0));
    const float origin = previewOriginX();
    const cv::Rect bounds(0, 0, width, height);
    for (const auto& kf : mKeyframes) {
        const int x = cvRound((origin + kf.pos.x) * kPreviewScale);
        const int y = cvRound(kf.pos.y * kPreviewScale);
        const cv::Rect dst = cv::Rect(x, y, kf.preview.cols, kf.preview.rows) & bounds;
        if (dst.area() <= 0) continue;
        const cv::Rect src(dst.x - x, dst.y - y, dst.width, dst.height);
        kf.preview(src).copyTo(canvas(dst), kf.previewMask(src));
    }
    // The live view on top, so the strip follows the camera between keyframes.
    if (!mCurPreview.empty()) {
        const int x = cvRound((origin + mCurPos.x) * kPreviewScale);
        const int y = cvRound(mCurPos.y * kPreviewScale);
        const cv::Rect dst = cv::Rect(x, y, mCurPreview.cols, mCurPreview.rows) & bounds;
        if (dst.area() > 0) {
            const cv::Rect src(dst.x - x, dst.y - y, dst.width, dst.height);
            mCurPreview(src).copyTo(canvas(dst), mCurPreviewMask(src));
        }
    }
    return true;
}

bool PanoramaEngine::result(cv::Mat& out) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mResult.empty()) return false;
    out = mResult;
    return true;
}

bool PanoramaEngine::stitch() {
    std::lock_guard<std::mutex> lock(mMutex);
    mStitched = true;
    if (mKeyframes.size() < 2) {
        LOGE("stitch: only %zu keyframe(s)", mKeyframes.size());
        mKeyframes.clear();
        return false;
    }
    try {
        // Work left to right regardless of sweep direction.
        if (mDirection < 0) std::reverse(mKeyframes.begin(), mKeyframes.end());
        const int n = static_cast<int>(mKeyframes.size());

        // 0. Trust the images over the lens metadata when they clearly disagree. This also
        //    yields a robust offset per pair (from all clean matches) for step 2.
        std::vector<cv::Point2d> pairOffsets;
        std::vector<char> pairValid;
        const double fEst = estimateFocal(pairOffsets, pairValid);
        if (fEst > 0 && std::abs(fEst / mFocal - 1.0) > 0.05) {
            LOGI("stitch: focal %.1f -> %.1f (self-calibrated)", mFocal, fEst);
            mFocal = fEst;
        }

        // 1. Cylindrical projection at full analysis resolution (in place, to cap memory).
        cv::Mat mapX, mapY, mask, map1, map2;
        buildCylinderMaps(cv::Size(mW, mH), mFocal, mapX, mapY, mask);
        cv::convertMaps(mapX, mapY, map1, map2, CV_16SC2);
        mapX.release();
        mapY.release();
        for (auto& kf : mKeyframes) {
            cv::Mat cyl;
            cv::remap(kf.full, cyl, map1, map2, cv::INTER_LINEAR, cv::BORDER_CONSTANT);
            kf.full = cyl;
            kf.gray.release();
            kf.preview.release();
            kf.previewMask.release();
        }
        map1.release();
        map2.release();
        const int cw = mask.cols, ch = mask.rows;

        // 2. Refine each pair at half resolution (tracking ran at ~1/4).
        cv::Mat halfMask;
        cv::resize(mask, halfMask, cv::Size(), 0.5, 0.5, cv::INTER_NEAREST);
        cv::erode(halfMask, halfMask, cv::Mat(), cv::Point(-1, -1), 6);
        auto refineOrb = cv::ORB::create(2500, 1.2f, 6, 31, 0, 2, cv::ORB::HARRIS_SCORE, 31, 10);
        auto features = [&](const cv::Mat& img, std::vector<cv::KeyPoint>& kp, cv::Mat& ds) {
            cv::Mat half, gray;
            cv::resize(img, half, halfMask.size(), 0, 0, cv::INTER_AREA);
            cv::cvtColor(half, gray, cv::COLOR_BGR2GRAY);
            refineOrb->detectAndCompute(gray, halfMask, kp, ds);
        };
        const double inv = 1.0 / mTrackScale;
        std::vector<cv::Point2d> pos(n);
        pos[0] = {0, 0};
        std::vector<cv::KeyPoint> prevKp, curKp;
        cv::Mat prevDs, curDs;
        features(mKeyframes[0].full, prevKp, prevDs);
        int refined = 0;
        for (int i = 1; i < n; ++i) {
            const cv::Point2f predicted = pairValid[i - 1]
                ? cv::Point2f(static_cast<float>(pairOffsets[i - 1].x), static_cast<float>(pairOffsets[i - 1].y))
                : (mKeyframes[i].pos - mKeyframes[i - 1].pos) * static_cast<float>(inv);
            features(mKeyframes[i].full, curKp, curDs);
            cv::Point2f t;
            cv::Point2d delta(predicted.x, predicted.y);
            // Generous: tracking ran at ~1/4 scale, possibly with a focal estimate that was
            // corrected above.
            const float radius = std::max(16.f, 0.08f * cw * 0.5f);
            if (registerTranslation(prevKp, prevDs, curKp, curDs, predicted * 0.5f, radius, 1.5f, 15, t)) {
                delta = cv::Point2d(t.x * 2.0, t.y * 2.0);
                ++refined;
            }
            pos[i] = pos[i - 1] + delta;
            prevKp = std::move(curKp);
            prevDs = curDs.clone();
            curKp.clear();
        }
        LOGI("stitch: %d frames, %d pairs refined", n, refined);

        // 3. Level the sweep: remove the linear vertical trend (camera roll / drift).
        if (n >= 3) {
            double sx = 0, sy = 0, sxx = 0, sxy = 0;
            for (const auto& p : pos) { sx += p.x; sy += p.y; sxx += p.x * p.x; sxy += p.x * p.y; }
            const double denom = n * sxx - sx * sx;
            if (std::abs(denom) > 1e-6) {
                const double slope = (n * sxy - sx * sy) / denom;
                for (auto& p : pos) p.y -= slope * (p.x - pos[0].x);
            }
        }

        // 4. Canvas.
        double minX = 1e18, minY = 1e18, maxX = -1e18, maxY = -1e18;
        for (const auto& p : pos) {
            minX = std::min(minX, p.x);
            minY = std::min(minY, p.y);
            maxX = std::max(maxX, p.x + cw);
            maxY = std::max(maxY, p.y + ch);
        }
        const int CW = static_cast<int>(std::ceil(maxX - minX)) + 1;
        const int CH = static_cast<int>(std::ceil(maxY - minY)) + 1;
        if (CW <= 0 || CH <= 0 || static_cast<double>(CW) * CH > 150e6) {
            LOGE("stitch: invalid canvas %dx%d", CW, CH);
            mKeyframes.clear();
            return false;
        }
        LOGI("stitch: canvas %dx%d", CW, CH);
        cv::Mat canvas(CH, CW, CV_8UC3, cv::Scalar::all(0));
        cv::Mat cover(CH, CW, CV_8U, cv::Scalar(0));
        const cv::Rect canvasRect(0, 0, CW, CH);

        // 5. Composite left to right: gain match, min-cost seam, local multi-band blend.
        int prevRight = 0;
        for (int i = 0; i < n; ++i) {
            cv::Mat img = mKeyframes[i].full;
            const int ox = cvRound(pos[i].x - minX);
            const int oy = cvRound(pos[i].y - minY);
            const cv::Rect fr = cv::Rect(ox, oy, cw, ch) & canvasRect;
            const cv::Rect src(fr.x - ox, fr.y - oy, fr.width, fr.height);
            if (fr.area() <= 0) continue;

            if (i > 0) {
                cv::Mat both = cover(fr) & mask(src);
                if (cv::countNonZero(both) > 2000) {
                    const cv::Scalar ma = cv::mean(canvas(fr), both);
                    const cv::Scalar mb = cv::mean(img(src), both);
                    cv::Scalar gain;
                    for (int c = 0; c < 3; ++c) {
                        gain[c] = mb[c] > 1.0 ? std::clamp(ma[c] / mb[c], 0.8, 1.25) : 1.0;
                    }
                    cv::multiply(img, gain, img);
                }
            }

            const int overlap = prevRight - ox;
            if (i == 0 || overlap <= 16) {
                img(src).copyTo(canvas(fr), mask(src));
                cover(fr).setTo(255, mask(src));
                prevRight = ox + cw;
                continue;
            }

            const int center = (ox + prevRight) / 2;
            const int blendHalf = std::max(4, std::min(64, overlap / 6));
            const int search = std::max(1, std::min(static_cast<int>(0.15 * cw), overlap / 2 - blendHalf - 2));
            const int rx0 = std::max(ox, center - search - blendHalf);
            const int rx1 = std::min(prevRight, center + search + blendHalf);
            const cv::Rect roi(rx0, 0, rx1 - rx0, CH);

            // B layer (the new frame) covering the ROI.
            cv::Mat a = canvas(roi).clone();
            cv::Mat aM = cover(roi).clone();
            cv::Mat b(CH, roi.width, CV_8UC3, cv::Scalar::all(0));
            cv::Mat bM(CH, roi.width, CV_8U, cv::Scalar(0));
            const cv::Rect inter = fr & roi;
            if (inter.area() > 0) {
                const cv::Rect s(inter.x - ox, inter.y - oy, inter.width, inter.height);
                const cv::Rect d(inter.x - rx0, inter.y, inter.width, inter.height);
                img(s).copyTo(b(d));
                mask(s).copyTo(bM(d));
            }

            const int sx0 = std::max(0, center - search - rx0);
            const int sx1 = std::min(roi.width, center + search - rx0);
            const std::vector<int> seam = findSeam(a, aM, b, bM, sx0, std::max(sx0 + 1, sx1));
            cv::Mat sel(CH, roi.width, CV_8U, cv::Scalar(0));
            for (int y = 0; y < CH; ++y) {
                sel.row(y).colRange(0, std::clamp(seam[y], 0, roi.width)).setTo(255);
            }
            sel.setTo(0, aM == 0);
            sel.setTo(255, (bM == 0) & (aM != 0));

            // Fill holes so the pyramid never pulls in black.
            cv::Mat aFill = a.clone(), bFill = b.clone();
            b.copyTo(aFill, aM == 0);
            a.copyTo(bFill, bM == 0);
            const int minSide = std::min(roi.width, CH);
            int levels = 1;
            while (levels < 5 && (minSide >> (levels + 1)) >= 8) ++levels;
            const cv::Mat blended = multiBandBlend(aFill, bFill, sel, levels);
            const cv::Mat any = aM | bM;
            blended.copyTo(canvas(roi), any);
            cover(roi).setTo(255, bM);

            // Everything of the new frame right of the blend band.
            const cv::Rect right = cv::Rect(rx1, oy, ox + cw - rx1, ch) & canvasRect;
            if (right.area() > 0) {
                const cv::Rect s(right.x - ox, right.y - oy, right.width, right.height);
                img(s).copyTo(canvas(right), mask(s));
                cover(right).setTo(255, mask(s));
            }
            prevRight = ox + cw;
            mKeyframes[i].full.release();
        }
        mKeyframes.clear();

        // 6. Crop to the largest fully covered rectangle.
        cv::Rect crop = largestCoveredRect(cover);
        if (crop.width < CW / 4 || crop.height < CH / 4) {
            LOGE("stitch: crop %dx%d too small, keeping bounding box", crop.width, crop.height);
            crop = cv::boundingRect(cover);
        }
        cv::cvtColor(canvas(crop), mResult, cv::COLOR_BGR2RGBA);
        LOGI("stitch: result %dx%d", mResult.cols, mResult.rows);
        return !mResult.empty();
    } catch (const cv::Exception& e) {
        LOGE("stitch failed: %s", e.what());
        mKeyframes.clear();
        mResult.release();
        return false;
    }
}
