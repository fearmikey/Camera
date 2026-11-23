// Portrait background blur ("bokeh") built only on OpenCV core/imgproc, so it can be
// compiled and tested on a desktop host as well as in the app (see vision_jni.cpp).
//
// Pipeline: face-seeded GrabCut subject mask (tight core / wide reach prior, a shoulder
// prior where clothes and background share colors, and a per-head pass with its own
// color models) -> hole filling + outline smoothing ->
// guided-filter matte refinement in a narrow edge band where color explains the edge
// -> lens-style disc blur in linear light with highlight bloom -> grain
// matching -> full-resolution blend.
#pragma once

#include <opencv2/core.hpp>

#include <vector>

namespace portrait {

struct Stats {
    int faces = 0;            // faces used to seed the segmentation
    double noiseSigma = 0.0;  // estimated sensor noise re-applied to the blurred background
    double subjectArea = 0.0; // fraction of the frame kept sharp
};

// Blurs the background of an RGBA (CV_8UC4) image in place.
//  faces    normalized (0..1) head boxes; empty -> a centered subject is assumed.
//  strength blur amount: 1.0 ~ f/2.8, 2.0 ~ f/1.4 (PortraitAperture.blurStrength in
//           Kotlin); clamped to [0.1, 2.5].
//  alphaOut optional: receives the subject matte (CV_8UC1, matte resolution).
// Throws cv::Exception on OpenCV failure.
void renderBokeh(cv::Mat& rgba, const std::vector<cv::Rect2f>& faces, float strength,
                 Stats* stats = nullptr, cv::Mat* alphaOut = nullptr);

}  // namespace portrait
