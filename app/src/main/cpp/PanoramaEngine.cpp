#include "PanoramaEngine.hpp"
#include <android/log.h>

#define LOG_TAG "PanoramaEngineNDK"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

PanoramaEngine::PanoramaEngine(double blurThreshold, float matchRatio)
    : mBlurThreshold(blurThreshold), mMatchRatio(matchRatio) {
    mFeatureDetector = cv::ORB::create(2000);
    mMatcher = cv::DescriptorMatcher::create(cv::DescriptorMatcher::BRUTEFORCE_HAMMING);
    mAccumulatedH = cv::Mat::eye(3, 3, CV_64F);
}

PanoramaEngine::~PanoramaEngine() {
    reset();
}

void PanoramaEngine::reset() {
    std::lock_guard<std::mutex> lock(mPipelineMutex);
    mFrames.clear();
    mAccumulatedH = cv::Mat::eye(3, 3, CV_64F);
}

double PanoramaEngine::computeLaplacianVariance(const cv::Mat& gray) {
    cv::Mat laplacian;
    cv::Laplacian(gray, laplacian, CV_64F);
    cv::Scalar mean, stddev;
    cv::meanStdDev(laplacian, mean, stddev);
    return stddev[0] * stddev[0];
}

bool PanoramaEngine::processFrame(uint8_t* bufferAddr, int width, int height, int stride, int format, int64_t timestamp) {
    std::lock_guard<std::mutex> lock(mPipelineMutex);

    cv::Mat raw(height, width, CV_8UC4, bufferAddr, stride);
    cv::Mat fullResRgba = raw.clone();

    cv::Mat gray;
    cv::cvtColor(fullResRgba, gray, cv::COLOR_RGBA2GRAY);

    cv::Mat graySmall;
    cv::resize(gray, graySmall, cv::Size(width / 2, height / 2));

    double blurVar = computeLaplacianVariance(graySmall);
    if (blurVar < mBlurThreshold) {
        LOGE("Frame rejected: Motion Blur detected (Variance: %.2f)", blurVar);
        return false;
    }

    FrameData frameData;
    frameData.frameId = timestamp;
    frameData.fullResMat = fullResRgba;
    frameData.grayScaleMat = graySmall;

    mFeatureDetector->detectAndCompute(graySmall, cv::noArray(), frameData.keypoints, frameData.descriptors);

    if (mFrames.empty()) {
        frameData.H_global = cv::Mat::eye(3, 3, CV_64F);
        mFrames.push_back(frameData);
        LOGI("First frame registered.");
        return true;
    }

    cv::Mat H_relative;
    bool success = estimateHomographyBetween(mFrames.back(), frameData, H_relative);
    if (!success) {
        LOGE("Homography estimation failed.");
        return false;
    }

    cv::Mat S = (cv::Mat_<double>(3, 3) << 2.0, 0.0, 0.0,  0.0, 2.0, 0.0,  0.0, 0.0, 1.0);
    cv::Mat S_inv = (cv::Mat_<double>(3, 3) << 0.5, 0.0, 0.0,  0.0, 0.5, 0.0,  0.0, 0.0, 1.0);
    cv::Mat H_full = S * H_relative * S_inv;

    mAccumulatedH = mAccumulatedH * H_full;
    frameData.H_global = mAccumulatedH.clone();

    mFrames.push_back(frameData);
    LOGI("Frame added. Count: %zu", mFrames.size());
    return true;
}

bool PanoramaEngine::estimateHomographyBetween(const FrameData& prevFrame, FrameData& currFrame, cv::Mat& H_out) {
    if (prevFrame.descriptors.empty() || currFrame.descriptors.empty()) return false;

    std::vector<std::vector<cv::DMatch>> knnMatches;
    mMatcher->knnMatch(prevFrame.descriptors, currFrame.descriptors, knnMatches, 2);

    std::vector<cv::Point2f> srcPts, dstPts;
    for (const auto& match : knnMatches) {
        if (match.size() >= 2 && match[0].distance < mMatchRatio * match[1].distance) {
            srcPts.push_back(prevFrame.keypoints[match[0].queryIdx].pt);
            dstPts.push_back(currFrame.keypoints[match[0].trainIdx].pt);
        }
    }

    if (srcPts.size() < 10) return false;

    H_out = cv::findHomography(dstPts, srcPts, cv::RANSAC, 3.0);
    return !H_out.empty();
}

void PanoramaEngine::calculateCanvasBounds(cv::Rect& canvasBounds, cv::Mat& translationMatrix) {
    double minX = 0, minY = 0, maxX = 0, maxY = 0;

    for (const auto& frame : mFrames) {
        std::vector<cv::Point2f> corners = {
            {0, 0},
            {static_cast<float>(frame.fullResMat.cols), 0},
            {static_cast<float>(frame.fullResMat.cols), static_cast<float>(frame.fullResMat.rows)},
            {0, static_cast<float>(frame.fullResMat.rows)}
        };
        std::vector<cv::Point2f> transformedCorners;
        cv::perspectiveTransform(corners, transformedCorners, frame.H_global);

        for (const auto& pt : transformedCorners) {
            if (pt.x < minX) minX = pt.x;
            if (pt.y < minY) minY = pt.y;
            if (pt.x > maxX) maxX = pt.x;
            if (pt.y > maxY) maxY = pt.y;
        }
    }

    int canvasW = static_cast<int>(std::ceil(maxX - minX));
    int canvasH = static_cast<int>(std::ceil(maxY - minY));
    canvasBounds = cv::Rect(0, 0, canvasW, canvasH);

    translationMatrix = (cv::Mat_<double>(3, 3) << 1.0, 0.0, -minX, 0.0, 1.0, -minY, 0.0, 0.0, 1.0);
}

bool PanoramaEngine::finalizeStitch(cv::Mat& outputPanorama) {
    std::lock_guard<std::mutex> lock(mPipelineMutex);
    if (mFrames.size() < 2) return false;

    LOGI("Finalizing stitch with %zu frames.", mFrames.size());

    cv::Rect canvasBounds;
    cv::Mat T;
    calculateCanvasBounds(canvasBounds, T);

    LOGI("Canvas size: %dx%d", canvasBounds.width, canvasBounds.height);

    if (canvasBounds.width > 20000 || canvasBounds.height > 10000 || canvasBounds.area() <= 0) {
        LOGE("Canvas size too large or invalid!");
        return false;
    }

    std::vector<cv::Mat> warpedFrames;
    std::vector<cv::Point> corners;
    std::vector<cv::Mat> masks;

    for (auto& frame : mFrames) {
        cv::Mat H_adjusted = T * frame.H_global;

        std::vector<cv::Point2f> corners_in = {
            {0, 0},
            {static_cast<float>(frame.fullResMat.cols), 0},
            {static_cast<float>(frame.fullResMat.cols), static_cast<float>(frame.fullResMat.rows)},
            {0, static_cast<float>(frame.fullResMat.rows)}
        };
        std::vector<cv::Point2f> corners_out;
        cv::perspectiveTransform(corners_in, corners_out, H_adjusted);
        cv::Rect bbox = cv::boundingRect(corners_out);

        bbox = bbox & cv::Rect(0, 0, canvasBounds.width, canvasBounds.height);
        if (bbox.area() <= 0) continue;

        cv::Mat T_shift = (cv::Mat_<double>(3, 3) << 1.0, 0.0, -bbox.x, 0.0, 1.0, -bbox.y, 0.0, 0.0, 1.0);
        cv::Mat H_bbox = T_shift * H_adjusted;

        cv::Mat warpedFrame, warpedMask;
        cv::Mat mask = cv::Mat::ones(frame.fullResMat.size(), CV_8UC1) * 255;

        cv::Mat frameBgr;
        cv::cvtColor(frame.fullResMat, frameBgr, cv::COLOR_RGBA2BGR);
        frame.fullResMat.release(); // Free high-res frame immediately

        cv::warpPerspective(frameBgr, warpedFrame, H_bbox, bbox.size());
        cv::warpPerspective(mask, warpedMask, H_bbox, bbox.size(), cv::INTER_NEAREST);

        warpedFrames.push_back(warpedFrame);
        masks.push_back(warpedMask);
        corners.push_back(bbox.tl());
    }

    LOGI("Warping complete. Finding seams...");

    try {
        cv::Ptr<cv::detail::SeamFinder> seamFinder = cv::makePtr<cv::detail::DpSeamFinder>(cv::detail::DpSeamFinder::COLOR);

        // SeamFinder requires std::vector<cv::UMat>
        std::vector<cv::UMat> warpedUMats(warpedFrames.size()), masksUMats(masks.size());
        for (size_t i = 0; i < warpedFrames.size(); ++i) {
            warpedFrames[i].copyTo(warpedUMats[i]);
            masks[i].copyTo(masksUMats[i]);
        }

        seamFinder->find(warpedUMats, corners, masksUMats);

        for (size_t i = 0; i < masks.size(); ++i) {
            masksUMats[i].copyTo(masks[i]);
        }
    } catch (const std::exception& e) {
        LOGE("Seam finding failed: %s", e.what());
    }

    LOGI("Blending frames...");
    blendFramesMultiBand(warpedFrames, corners, masks, canvasBounds, outputPanorama);

    bool success = !outputPanorama.empty();
    mFrames.clear();
    return success;
}

void PanoramaEngine::blendFramesMultiBand(const std::vector<cv::Mat>& warpedFrames,
                                        const std::vector<cv::Point>& corners,
                                        const std::vector<cv::Mat>& masks,
                                        const cv::Rect& canvasBounds,
                                        cv::Mat& result) {
    try {
        cv::detail::MultiBandBlender blender(false, 2);
        blender.prepare(canvasBounds);

        for (size_t i = 0; i < warpedFrames.size(); ++i) {
            blender.feed(warpedFrames[i], masks[i], corners[i]);
        }

        cv::Mat resultS16, resultMask;
        blender.blend(resultS16, resultMask);

        cv::Mat resultBgr;
        resultS16.convertTo(resultBgr, CV_8UC3);
        cv::cvtColor(resultBgr, result, cv::COLOR_BGR2RGBA);
    } catch (const cv::Exception& e) {
        LOGE("Blending failed: %s", e.what());
    }
}
