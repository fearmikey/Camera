#pragma once

#include <opencv2/opencv.hpp>
#include <opencv2/stitching/detail/blenders.hpp>
#include <opencv2/stitching/detail/seam_finders.hpp>
#include <vector>
#include <memory>
#include <mutex>

struct FrameData {
    int64_t frameId;
    cv::Mat fullResMat;
    cv::Mat grayScaleMat;
    std::vector<cv::KeyPoint> keypoints;
    cv::Mat descriptors;
    cv::Mat H_global;
};

class PanoramaEngine {
public:
    PanoramaEngine(double blurThreshold = 100.0, float matchRatio = 0.7f);
    ~PanoramaEngine();

    bool processFrame(uint8_t* bufferAddr, int width, int height, int stride, int format, int64_t timestamp);
    bool finalizeStitch(cv::Mat& outputPanorama);
    void reset();

private:
    double mBlurThreshold;
    float mMatchRatio;

    cv::Ptr<cv::Feature2D> mFeatureDetector;
    cv::Ptr<cv::DescriptorMatcher> mMatcher;

    std::vector<FrameData> mFrames;
    cv::Mat mAccumulatedH;

    std::mutex mPipelineMutex;

    double computeLaplacianVariance(const cv::Mat& gray);
    bool estimateHomographyBetween(const FrameData& prevFrame, FrameData& currFrame, cv::Mat& H_out);
    void calculateCanvasBounds(cv::Rect& canvasBounds, cv::Mat& translationMatrix);
    void blendFramesMultiBand(const std::vector<cv::Mat>& warpedFrames,
                             const std::vector<cv::Point>& corners,
                             const std::vector<cv::Mat>& masks,
                             cv::Mat& result);
};
