#include "PanoramaEngine.hpp"
#include <opencv2/core/ocl.hpp>
#include <android/log.h>

#define LOG_TAG "PanoramaEngineNDK"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

PanoramaEngine::PanoramaEngine(double blurThreshold, float matchRatio)
    : mBlurThreshold(blurThreshold), mMatchRatio(matchRatio) {
    // Pro Fix: Disable OpenCL to prevent driver hangs on certain mobile GPUs
    cv::ocl::setUseOpenCL(false);

    // Increased feature count and added multi-scale support for better alignment
    mFeatureDetector = cv::ORB::create(5000, 1.2f, 8, 31, 0, 2, cv::ORB::HARRIS_SCORE, 31, 15);
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
    mResult.release();
}

double PanoramaEngine::computeLaplacianVariance(const cv::Mat& gray) {
    cv::Mat laplacian;
    cv::Laplacian(gray, laplacian, CV_64F);
    cv::Scalar mean, stddev;
    cv::meanStdDev(laplacian, mean, stddev);
    return stddev[0] * stddev[0];
}

bool PanoramaEngine::processFrame(uint8_t* bufferAddr, int width, int height, int stride, int format, int rotationDegrees, int64_t timestamp) {
    std::lock_guard<std::mutex> lock(mPipelineMutex);

    cv::Mat raw(height, width, CV_8UC4, bufferAddr, stride);
    cv::Mat fullResRgba = raw.clone();

    // Rotate frame based on device orientation
    if (rotationDegrees == 90) {
        cv::rotate(fullResRgba, fullResRgba, cv::ROTATE_90_CLOCKWISE);
    } else if (rotationDegrees == 180) {
        cv::rotate(fullResRgba, fullResRgba, cv::ROTATE_180);
    } else if (rotationDegrees == 270) {
        cv::rotate(fullResRgba, fullResRgba, cv::ROTATE_90_COUNTERCLOCKWISE);
    }

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

    // Memory Guard: Stop adding frames if we reach a safe limit (e.g. 60 frames)
    if (mFrames.size() > 60) {
        LOGE("Panorama reached max frame limit (60). Dropping new frames.");
        return false;
    }

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
        if (match.size() >= 2 && match[0].distance < (mMatchRatio + 0.05f) * match[1].distance) {
            srcPts.push_back(prevFrame.keypoints[match[0].queryIdx].pt);
            dstPts.push_back(currFrame.keypoints[match[0].trainIdx].pt);
        }
    }

    if (srcPts.size() < 15) return false;

    // Pro Fix: Use estimateAffinePartial2D for rigid horizontal stability
    cv::Mat affine = cv::estimateAffinePartial2D(dstPts, srcPts, cv::noArray(), cv::RANSAC, 2.0);

    if (affine.empty()) return false;

    H_out = cv::Mat::eye(3, 3, CV_64F);
    affine.copyTo(H_out(cv::Rect(0, 0, 3, 2)));

    return true;
}

bool PanoramaEngine::finalizeStitch() {
    std::lock_guard<std::mutex> lock(mPipelineMutex);
    if (mFrames.size() < 2) return false;

    LOGI("Finalizing stitch with %zu frames.", mFrames.size());

    // Performance Optimization: Balancing resolution and speed
    float stitchScale = 0.4f;

    double minX = 0, minY = 0, maxX = 0, maxY = 0;
    for (const auto& frame : mFrames) {
        float sw = static_cast<float>(frame.fullResMat.cols) * stitchScale;
        float sh = static_cast<float>(frame.fullResMat.rows) * stitchScale;
        std::vector<cv::Point2f> corners = {{0,0}, {sw,0}, {sw,sh}, {0,sh}};
        std::vector<cv::Point2f> transformed;
        cv::perspectiveTransform(corners, transformed, frame.H_global);
        for (const auto& pt : transformed) {
            if (pt.x < minX) minX = pt.x; if (pt.y < minY) minY = pt.y;
            if (pt.x > maxX) maxX = pt.x; if (pt.y > maxY) maxY = pt.y;
        }
    }

    int canvasW = static_cast<int>(std::ceil(maxX - minX));
    int canvasH = static_cast<int>(std::ceil(maxY - minY));

    LOGI("Scaled Canvas size: %dx%d", canvasW, canvasH);

    if (canvasW > 20000 || canvasH > 10000 || canvasW * canvasH <= 0) {
        LOGE("Canvas size invalid!");
        return false;
    }

    cv::Mat T = (cv::Mat_<double>(3, 3) << 1.0, 0.0, -minX, 0.0, 1.0, -minY, 0.0, 0.0, 1.0);

    std::vector<cv::Mat> warpedFrames;
    std::vector<cv::Point> corners;
    std::vector<cv::Mat> masks;

    for (auto& frame : mFrames) {
        cv::Mat S = (cv::Mat_<double>(3, 3) << stitchScale, 0.0, 0.0,  0.0, stitchScale, 0.0,  0.0, 0.0, 1.0);
        cv::Mat S_inv = (cv::Mat_<double>(3, 3) << 1.0/stitchScale, 0.0, 0.0,  0.0, 1.0/stitchScale, 0.0,  0.0, 0.0, 1.0);
        cv::Mat H_scaled = S * frame.H_global * S_inv;
        cv::Mat H_adjusted = T * H_scaled;

        std::vector<cv::Point2f> c_in = {{0,0}, {static_cast<float>(frame.fullResMat.cols)*stitchScale, 0},
                                        {static_cast<float>(frame.fullResMat.cols)*stitchScale, static_cast<float>(frame.fullResMat.rows)*stitchScale},
                                        {0, static_cast<float>(frame.fullResMat.rows)*stitchScale}};
        std::vector<cv::Point2f> c_out;
        cv::perspectiveTransform(c_in, c_out, H_adjusted);
        cv::Rect bbox = cv::boundingRect(c_out);
        bbox = bbox & cv::Rect(0, 0, canvasW, canvasH);
        if (bbox.area() <= 0) continue;

        cv::Mat T_shift = (cv::Mat_<double>(3, 3) << 1.0, 0.0, -bbox.x, 0.0, 1.0, -bbox.y, 0.0, 0.0, 1.0);
        cv::Mat H_bbox = T_shift * H_adjusted;

        cv::Mat frameSmall;
        cv::resize(frame.fullResMat, frameSmall, cv::Size(), stitchScale, stitchScale, cv::INTER_LINEAR);
        cv::Mat frameBgr;
        cv::cvtColor(frameSmall, frameBgr, cv::COLOR_RGBA2BGR);
        frameSmall.release();
        frame.fullResMat.release();

        cv::Mat mask = cv::Mat::ones(frameBgr.size(), CV_8UC1) * 255;
        cv::Mat warpedFrame, warpedMask;
        cv::warpPerspective(frameBgr, warpedFrame, H_bbox, bbox.size());
        cv::warpPerspective(mask, warpedMask, H_bbox, bbox.size(), cv::INTER_NEAREST);

        warpedFrames.push_back(warpedFrame);
        masks.push_back(warpedMask);
        corners.push_back(bbox.tl());
    }

    LOGI("Warping complete. Optimization phase starting...");

    try {
        // Convert to UMat locally for both Exposure and Seams
        std::vector<cv::UMat> uImages(warpedFrames.size()), uMasks(masks.size());
        for (size_t i = 0; i < warpedFrames.size(); ++i) {
            warpedFrames[i].copyTo(uImages[i]);
            masks[i].copyTo(uMasks[i]);
        }

        // Step 3A: Exposure Compensation (Simple Gain)
        LOGI("Step 3A: Exposure Compensation (Gain)...");
        cv::Ptr<cv::detail::ExposureCompensator> compensator = cv::detail::ExposureCompensator::createDefault(cv::detail::ExposureCompensator::GAIN);
        compensator->feed(corners, uImages, uMasks);
        for (size_t i = 0; i < uImages.size(); ++i) {
            compensator->apply(static_cast<int>(i), corners[i], uImages[i], uMasks[i]);
        }

        // Step 3B: Seam Finding
        LOGI("Step 3B: Seam Finding (DpSeamFinder)...");
        cv::Ptr<cv::detail::SeamFinder> seamFinder = cv::makePtr<cv::detail::DpSeamFinder>(cv::detail::DpSeamFinder::COLOR);
        seamFinder->find(uImages, corners, uMasks);

        // Copy results back to Mat
        for (size_t i = 0; i < masks.size(); ++i) {
            uImages[i].copyTo(warpedFrames[i]);
            uMasks[i].copyTo(masks[i]);
            uImages[i].release();
            uMasks[i].release();
        }
        LOGI("Optimization phase complete.");
    } catch (const std::exception& e) {
        LOGE("Optimization phase failed: %s. Falling back.", e.what());
    }

    LOGI("Blending...");
    blendFramesMultiBand(warpedFrames, corners, masks, cv::Rect(0,0,canvasW,canvasH), mResult);

    bool success = !mResult.empty();
    mFrames.clear();
    return success;
}

bool PanoramaEngine::getResult(cv::Mat& output) {
    std::lock_guard<std::mutex> lock(mPipelineMutex);
    if (mResult.empty()) return false;
    output = mResult;
    return true;
}

void PanoramaEngine::blendFramesMultiBand(const std::vector<cv::Mat>& warpedFrames,
                                        const std::vector<cv::Point>& corners,
                                        const std::vector<cv::Mat>& masks,
                                        const cv::Rect& canvasBounds,
                                        cv::Mat& result) {
    try {
        cv::detail::MultiBandBlender blender(false, 3);
        blender.prepare(canvasBounds);

        for (size_t i = 0; i < warpedFrames.size(); ++i) {
            cv::Mat warped16S;
            warpedFrames[i].convertTo(warped16S, CV_16S);
            blender.feed(warped16S, masks[i], corners[i]);
            warped16S.release();
        }

        cv::Mat resultS16, resultMask;
        blender.blend(resultS16, resultMask);

        cv::Mat resultBgr;
        resultS16.convertTo(resultBgr, CV_8UC3);
        autoCrop(resultBgr, resultMask);
        cv::cvtColor(resultBgr, result, cv::COLOR_BGR2RGBA);

        resultS16.release();
        resultMask.release();
        resultBgr.release();
    } catch (const cv::Exception& e) {
        LOGE("Blending failed: %s", e.what());
    }
}

void PanoramaEngine::autoCrop(cv::Mat& image, const cv::Mat& mask) {
    if (mask.empty() || image.empty()) return;

    std::vector<std::vector<cv::Point>> contours;
    cv::findContours(mask, contours, cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE);
    if (contours.empty()) return;

    size_t largestIdx = 0;
    double maxArea = 0;
    for (size_t i = 0; i < contours.size(); ++i) {
        double area = cv::contourArea(contours[i]);
        if (area > maxArea) {
            maxArea = area;
            largestIdx = i;
        }
    }

    cv::Rect bounds = cv::boundingRect(contours[largestIdx]);
    int top = bounds.y, bottom = bounds.y + bounds.height;
    int left = bounds.x, right = bounds.x + bounds.width;

    const uint8_t thresh = 200;
    while (top < bottom) {
        bool allValid = true;
        const auto* row = mask.ptr<uint8_t>(top);
        for (int x = left; x < right; ++x) {
            if (row[x] < thresh) { allValid = false; break; }
        }
        if (allValid) break;
        top++;
    }
    while (bottom > top) {
        bool allValid = true;
        const auto* row = mask.ptr<uint8_t>(bottom - 1);
        for (int x = left; x < right; ++x) {
            if (row[x] < thresh) { allValid = false; break; }
        }
        if (allValid) break;
        bottom--;
    }

    cv::Rect cropRect(left, top, right - left, bottom - top);
    if (cropRect.area() > 0 && cropRect.width > 100 && cropRect.height > 100) {
        image = image(cropRect).clone();
        LOGI("Auto-cropped to: %dx%d", image.cols, image.rows);
    }
}
