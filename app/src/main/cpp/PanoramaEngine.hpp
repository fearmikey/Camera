#pragma once

#include <opencv2/core.hpp>
#include <opencv2/features2d.hpp>
#include <cstdint>
#include <mutex>
#include <vector>

/**
 * Sweep panorama engine.
 *
 * Capture: every analysis frame is projected onto a cylinder (so a camera pan becomes a
 * pure 2D translation), tracked against the most recent keyframe with ORB features and a
 * 1-point translation RANSAC (phase correlation as fallback), and a new keyframe is taken
 * once the view has moved far enough and the frame is sharp.
 *
 * Stitch: pairwise offsets are refined at half resolution, vertical drift is levelled,
 * exposure is matched per seam, min-cost seams are cut through each overlap and blended
 * locally with a Laplacian pyramid, and the result is cropped to the largest clean
 * rectangle.
 *
 * Only core/imgproc/features2d symbols are used (cv::detail is not exported by the
 * Maven OpenCV library).
 */
class PanoramaEngine {
public:
    enum Status : int {
        kTracking = 0,
        kKeyframe = 1,
        kLost = 2,
        kTooFast = 3,
        kComplete = 4,
    };

    struct Stats {
        int status = kTracking;
        int keyframes = 0;
        float sweepDegrees = 0.f;   // horizontal angle covered so far
        float verticalOffset = 0.f; // current view vs first keyframe, fraction of frame height (+ = camera aims lower)
        float stripX = 0.f;         // left edge of current view in the preview strip (0..1)
        float stripWidth = 0.f;     // width of the view in the preview strip (0..1)
        int direction = 0;          // -1 left, +1 right, 0 unknown yet
        float keyframeDx = 0.f;     // current view minus last keyframe, fraction of frame width
        float targetStep = 0.f;     // next dot's distance from the last keyframe, fraction of frame width
        int aligned = 0;            // 1 while the next dot is inside the target circle
    };

    /** @param tanHalfFovLongSide tan(horizontal FOV / 2) along the analysis frame's long side. */
    explicit PanoramaEngine(float tanHalfFovLongSide);

    Stats processFrame(const uint8_t* rgba, int width, int height, int stride, int rotationDegrees,
                       int64_t timestampNs);

    bool previewSize(int& width, int& height);
    bool renderPreview(uint8_t* rgbaOut, int width, int height, int stride);

    /** Builds the panorama (RGBA) and frees the keyframes. */
    bool stitch();
    bool result(cv::Mat& out);

private:
    struct Keyframe {
        cv::Mat full;                        // upright BGR, analysis resolution (cylindrical after stitch() starts)
        cv::Mat gray;                        // cylindrical gray, tracking scale
        cv::Mat preview;                     // cylindrical RGBA, preview scale
        cv::Mat previewMask;
        std::vector<cv::KeyPoint> keypoints; // on gray
        cv::Mat descriptors;
        cv::Point2f pos;                     // tracking-scale cylinder position relative to keyframe 0
    };

    void init(int uprightW, int uprightH, int longSide);
    void buildTrackingGeometry();
    void calibrateLive(const cv::Mat& planarGray);
    void addKeyframe(const cv::Mat& raw, int rotation, const cv::Mat& colorSmall, const cv::Mat& cylGray,
                     std::vector<cv::KeyPoint>&& kps, cv::Mat&& desc, cv::Point2f pos);
    Stats makeStats(int status, cv::Point2f pos) const;
    /** Returns a corrected focal length (0 = keep metadata) and per-pair offsets at the chosen focal. */
    double estimateFocal(std::vector<cv::Point2d>& offsets, std::vector<char>& valid) const;
    float previewOriginX() const;
    void updateCurrentPreview(const cv::Mat& colorSmall, cv::Point2f pos);

    bool registerTranslation(const std::vector<cv::KeyPoint>& kpA, const cv::Mat& descA,
                             const std::vector<cv::KeyPoint>& kpB, const cv::Mat& descB,
                             cv::Point2f predicted, float radius, float threshold, size_t minInliers,
                             cv::Point2f& out) const;

    float mTanHalfFov;
    bool mInitialized = false;
    bool mStitched = false;

    int mW = 0, mH = 0;              // upright analysis frame size
    double mFocal = 0;               // focal length in analysis pixels
    double mTrackScale = 0.25;
    cv::Size mSmall;                 // upright tracking size
    cv::Mat mSmallMapX, mSmallMapY;  // planar -> cylinder at tracking scale
    cv::Mat mSmallMask, mSmallMaskEroded;
    cv::Mat mHann;
    int mCylSmallW = 0;
    int mPreviewW = 0, mPreviewH = 0; // preview strip covers 360 degrees

    cv::Ptr<cv::ORB> mOrb;
    cv::Ptr<cv::ORB> mCalibOrb;          // planar features for live focal calibration
    bool mCalibrated = false;
    cv::Mat mCalibColor;                  // keyframe 0, upright RGBA at tracking scale
    std::vector<cv::KeyPoint> mCalibKp;
    cv::Mat mCalibDesc;
    cv::Ptr<cv::DescriptorMatcher> mMatcher;

    std::vector<Keyframe> mKeyframes;
    cv::Point2f mPrevPos{0.f, 0.f};
    int mLostFrames = 0;
    int mDirection = 0;
    int64_t mLastTs = 0;             // previous frame timestamp (ns)
    double mSpeed = 0;               // smoothed pan speed, frame widths per second
    int64_t mHoldStart = -1;         // when the dot entered the circle with the camera paused
    bool mAligned = false;
    cv::Mat mCurPreview, mCurPreviewMask; // live view at preview scale
    cv::Point2f mCurPos{0.f, 0.f};
    Stats mLastStats;

    cv::Mat mResult; // RGBA
    std::mutex mMutex;
};
