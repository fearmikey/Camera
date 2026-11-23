// Portrait background blur. Host-verifiable: only needs OpenCV core + imgproc.
// See PortraitEngine.hpp for the pipeline overview.

#include "PortraitEngine.hpp"

#include <opencv2/imgproc.hpp>

#include <algorithm>
#include <cmath>
#include <cstdint>

namespace portrait {
namespace {

constexpr int kSegSide = 640;      // GrabCut + matte coefficients (long side)
constexpr int kMatteSide = 1280;   // matte detail resolution (long side)
constexpr int kBlurSide = 1024;    // lens blur resolution (long side)
constexpr double kRadiusFrac = 0.018;  // disc radius / blur long side at strength 1
constexpr float kGamma = 2.2f;
constexpr float kHighlightThreshold = 0.70f;  // linear luma where bloom starts
constexpr float kHighlightGain = 8.0f;        // extra energy for clipped highlights
constexpr double kMatteEps = 2e-3;            // guided filter regularizer
constexpr double kMatteBandFrac = 0.014;      // soft-edge band half-width / matte long side
constexpr double kMatteLow = 0.2;             // guided-filter output mapped to alpha 0..1
constexpr double kMatteHigh = 0.8;
constexpr double kFitFloor = 1e-3;            // R^2 of the color model -> 1 in flat windows
constexpr double kFitLow = 0.35;              // R^2 below: keep the mask; above kFitHigh: trust color
constexpr double kFitHigh = 0.75;
constexpr int kNoiseTile = 512;

cv::Size fitLongSide(cv::Size s, int side) {
    const int longSide = std::max(s.width, s.height);
    if (longSide <= side) return s;
    const double k = static_cast<double>(side) / longSide;
    return {std::max(1, cvRound(s.width * k)), std::max(1, cvRound(s.height * k))};
}

cv::Rect toPixels(const cv::Rect2f& r, cv::Size s) {
    cv::Rect px(cvRound(r.x * s.width), cvRound(r.y * s.height),
                cvRound(r.width * s.width), cvRound(r.height * s.height));
    return px & cv::Rect(0, 0, s.width, s.height);
}

cv::Mat resizedRgb(const cv::Mat& rgba, cv::Size size) {
    cv::Mat small, rgb;
    if (size == rgba.size()) {
        small = rgba;
    } else {
        cv::resize(rgba, small, size, 0, 0, cv::INTER_AREA);
    }
    cv::cvtColor(small, rgb, cv::COLOR_RGBA2RGB);
    return rgb;
}

// ---------------------------------------------------------------------------
// Segmentation
// ---------------------------------------------------------------------------

// Person-shaped priors in head-box units (FaceLocator's box: eyes at 0.41 of its height,
// top near the hairline, bottom near the chin): a head ellipse plus a body outline (right
// half from the neck down, mirrored) closed along the frame bottom.
struct Silhouette {
    double headY, headRx, headRy;   // ellipse center below the box top, radii
    std::vector<cv::Point2d> side;  // right-hand body outline (dx, dy), top to bottom
    double baseHalf;                // half width at the frame bottom
};

// Core: deliberately tight, the probable subject; GrabCut grows it into matching colors
// (hair, wide shoulders). GrabCut mostly keeps regions in the class they start in, so
// background inside a generous core seeds the subject color model and stays sharp.
const Silhouette kCore{0.40, 0.50, 0.62, {{0.28, 0.85}, {0.28, 1.30}, {0.95, 1.75}}, 1.15};
// Reach: the furthest a head-and-shoulders subject plausibly extends: hair up to 0.4 box
// above the hairline, ears, and shoulders no higher than a line sloping down from the jaw.
// Outside every reach is definite background, which keeps chair backs and shelves poking
// up behind the shoulders out of the subject.
const Silhouette kReach{0.38, 0.80, 0.78, {{0.55, 0.92}, {1.60, 1.22}, {2.50, 1.45}}, 2.80};
// Head core plus a margin for temple hair and ears (refineHeads; only the head is used).
const Silhouette kHeadTrust{0.40, 0.62, 0.68, {}, 0.0};

void drawHead(cv::Mat& dst, const cv::Rect& f, const Silhouette& s) {
    cv::ellipse(dst, cv::Point(cvRound(f.x + f.width / 2.0), cvRound(f.y + s.headY * f.height)),
                cv::Size(cvRound(s.headRx * f.width), cvRound(s.headRy * f.height)), 0, 0, 360,
                cv::Scalar(255), cv::FILLED);
}

void drawSilhouette(cv::Mat& dst, const cv::Rect& f, const Silhouette& s) {
    drawHead(dst, f, s);
    const double cx = f.x + f.width / 2.0;
    auto pt = [&](double dx, double dy) {
        return cv::Point(cvRound(cx + dx * f.width), std::min(dst.rows, cvRound(f.y + dy * f.height)));
    };
    std::vector<cv::Point> body;
    for (const auto& p : s.side) body.push_back(pt(p.x, p.y));
    body.emplace_back(cvRound(cx + s.baseHalf * f.width), dst.rows);
    body.emplace_back(cvRound(cx - s.baseHalf * f.width), dst.rows);
    for (auto it = s.side.rbegin(); it != s.side.rend(); ++it) body.push_back(pt(-it->x, it->y));
    cv::fillPoly(dst, std::vector<std::vector<cv::Point>>{body}, cv::Scalar(255));
}

// Eyes, nose and mouth: always subject.
cv::Rect innerFace(const cv::Rect& f) {
    return {f.x + f.width / 5, f.y + f.height / 6, f.width * 3 / 5, f.height * 2 / 3};
}

// Re-segments each head with color models of its own: skin + hair against the background
// right around the head. The global model also learns the clothes as subject, so a dark
// chair or shelf beside dark hair matches a black shirt there and would stay sharp.
void refineHeads(const cv::Mat& rgb, const std::vector<cv::Rect>& faces, const cv::Mat& core,
                 const cv::Mat& reach, cv::Mat& fg) {
    const cv::Rect frame(0, 0, rgb.cols, rgb.rows);
    for (const auto& f : faces) {
        const int chin = cvRound(f.y + 0.98 * f.height);
        const cv::Rect crop = cv::Rect(cvRound(f.x - 0.45 * f.width), cvRound(f.y - 0.55 * f.height),
                                       cvRound(1.9 * f.width), cvRound(1.75 * f.height)) & frame;
        if (crop.width < 12 || crop.height < 12 || chin <= crop.y) continue;

        cv::Mat gc(crop.size(), CV_8UC1, cv::Scalar(cv::GC_BGD));
        gc.setTo(cv::Scalar(cv::GC_PR_BGD), reach(crop));
        gc.setTo(cv::Scalar(cv::GC_PR_FGD), core(crop));
        const cv::Rect inner = ((innerFace(f) & frame) - crop.tl()) & cv::Rect(cv::Point(), crop.size());
        if (inner.area() > 0) gc(inner).setTo(cv::Scalar(cv::GC_FGD));
        const int bgCount = cv::countNonZero((gc == cv::GC_BGD) | (gc == cv::GC_PR_BGD));
        if (bgCount < 64 || static_cast<int>(gc.total()) - bgCount < 64) continue;

        cv::Mat bgdModel, fgdModel;
        cv::grabCut(rgb(crop), gc, cv::Rect(), bgdModel, fgdModel, 5, cv::GC_INIT_WITH_MASK);

        // Above the chin, the head result replaces the global one in the ring out to the
        // head reach, and only adds to it close to the head (kHeadTrust: short dark temple
        // hair can match a dark background right beside it, and the global pass keeps it).
        // The neck and body keep the global result.
        cv::Mat ring(rgb.size(), CV_8UC1, cv::Scalar(0));
        cv::Mat inside(rgb.size(), CV_8UC1, cv::Scalar(0));
        drawHead(ring, f, kReach);
        drawHead(inside, f, kHeadTrust);
        if (chin < ring.rows) ring.rowRange(std::max(0, chin), ring.rows).setTo(0);
        ring.setTo(0, inside);
        const cv::Mat local = (gc == cv::GC_FGD) | (gc == cv::GC_PR_FGD);
        cv::Mat dst = fg(crop);
        local.copyTo(dst, ring(crop));
        cv::bitwise_or(dst, local, dst, inside(crop));
    }
}

// GrabCut seeded with person-shaped priors per face (plus a per-head pass), or a centered
// rectangle when no face was found. Returns a binary (0/255) subject mask.
cv::Mat segmentSubject(const cv::Mat& rgb, const std::vector<cv::Rect>& faces) {
    const int w = rgb.cols;
    const int h = rgb.rows;
    const cv::Rect frame(0, 0, w, h);
    cv::Mat gc(h, w, CV_8UC1, cv::Scalar(cv::GC_PR_BGD));
    cv::Mat bgdModel, fgdModel;

    if (faces.empty()) {
        const cv::Rect rect(w / 6, h / 10, w * 2 / 3, h * 9 / 10 - 1);
        cv::grabCut(rgb, gc, rect, bgdModel, fgdModel, 5, cv::GC_INIT_WITH_RECT);
    } else {
        // Person-shaped priors per face: a tight core as probable subject, a wide reach as
        // probable background, definite background outside every reach (see kCore/kReach).
        cv::Mat core(h, w, CV_8UC1, cv::Scalar(0));
        cv::Mat reach(h, w, CV_8UC1, cv::Scalar(0));
        for (const auto& f : faces) {
            drawSilhouette(core, f, kCore);
            drawSilhouette(reach, f, kReach);
        }
        gc.setTo(cv::Scalar(cv::GC_BGD));
        gc.setTo(cv::Scalar(cv::GC_PR_BGD), reach);
        gc.setTo(cv::Scalar(cv::GC_PR_FGD), core);

        // Definite foreground: inner face and a neck/chest strip.
        for (const auto& f : faces) {
            const cv::Rect inner = innerFace(f) & frame;
            const cv::Rect chest = cv::Rect(f.x + f.width / 4, f.y + f.height, f.width / 2,
                                            static_cast<int>(0.8 * f.height)) & frame;
            if (inner.area() > 0) gc(inner).setTo(cv::Scalar(cv::GC_FGD));
            if (chest.area() > 0) gc(chest).setTo(cv::Scalar(cv::GC_FGD));
        }

        // GrabCut needs background samples; a face filling the frame leaves none.
        if (cv::countNonZero((gc == cv::GC_BGD) | (gc == cv::GC_PR_BGD)) == 0) {
            gc(cv::Rect(0, 0, w, std::max(2, h / 40))).setTo(cv::Scalar(cv::GC_PR_BGD));
        }
        cv::grabCut(rgb, gc, cv::Rect(), bgdModel, fgdModel, 5, cv::GC_INIT_WITH_MASK);
        cv::Mat fg = (gc == cv::GC_FGD) | (gc == cv::GC_PR_FGD);
        refineHeads(rgb, faces, core, reach, fg);
        return fg;
    }
    return (gc == cv::GC_FGD) | (gc == cv::GC_PR_FGD);
}

// Keeps the components that overlap a seed rectangle (or the largest one).
void keepSubjectComponents(cv::Mat& mask, const std::vector<cv::Rect>& seeds) {
    cv::Mat labels, stats, centroids;
    const int n = cv::connectedComponentsWithStats(mask, labels, stats, centroids, 8, CV_32S);
    if (n <= 1) return;

    std::vector<uint8_t> keep(static_cast<size_t>(n), 0);
    bool any = false;
    for (int i = 1; i < n; ++i) {
        const cv::Rect r(stats.at<int>(i, cv::CC_STAT_LEFT), stats.at<int>(i, cv::CC_STAT_TOP),
                         stats.at<int>(i, cv::CC_STAT_WIDTH), stats.at<int>(i, cv::CC_STAT_HEIGHT));
        for (const auto& s : seeds) {
            if ((r & s).area() > 0) {
                keep[static_cast<size_t>(i)] = 1;
                any = true;
                break;
            }
        }
    }
    if (!any) {
        int best = 1;
        for (int i = 2; i < n; ++i) {
            if (stats.at<int>(i, cv::CC_STAT_AREA) > stats.at<int>(best, cv::CC_STAT_AREA)) best = i;
        }
        keep[static_cast<size_t>(best)] = 1;
    }
    for (int r = 0; r < mask.rows; ++r) {
        const int* l = labels.ptr<int>(r);
        uint8_t* m = mask.ptr<uint8_t>(r);
        for (int c = 0; c < mask.cols; ++c) m[c] = keep[static_cast<size_t>(l[c])] ? 255 : 0;
    }
}

// Fills enclosed background specks (e.g. GrabCut holes in clothing) smaller than maxArea.
void fillSmallHoles(cv::Mat& mask, double maxArea) {
    cv::Mat holes = mask == 0;
    cv::Mat labels, stats, centroids;
    const int n = cv::connectedComponentsWithStats(holes, labels, stats, centroids, 4, CV_32S);
    std::vector<uint8_t> fill(static_cast<size_t>(n), 0);
    for (int i = 1; i < n; ++i) {
        const int x = stats.at<int>(i, cv::CC_STAT_LEFT);
        const int y = stats.at<int>(i, cv::CC_STAT_TOP);
        const int cw = stats.at<int>(i, cv::CC_STAT_WIDTH);
        const int ch = stats.at<int>(i, cv::CC_STAT_HEIGHT);
        const bool touchesBorder = x == 0 || y == 0 || x + cw == mask.cols || y + ch == mask.rows;
        if (!touchesBorder && stats.at<int>(i, cv::CC_STAT_AREA) < maxArea) fill[static_cast<size_t>(i)] = 1;
    }
    for (int r = 0; r < mask.rows; ++r) {
        const int* l = labels.ptr<int>(r);
        uint8_t* m = mask.ptr<uint8_t>(r);
        for (int c = 0; c < mask.cols; ++c) {
            if (fill[static_cast<size_t>(l[c])]) m[c] = 255;
        }
    }
}

void cleanMask(cv::Mat& fg, const std::vector<cv::Rect>& seeds) {
    const int w = fg.cols;
    const int open = std::max(3, (w / 128) | 1);
    const int close = std::max(5, (w / 72) | 1);
    cv::morphologyEx(fg, fg, cv::MORPH_OPEN, cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(open, open)));
    cv::morphologyEx(fg, fg, cv::MORPH_CLOSE, cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(close, close)));
    keepSubjectComponents(fg, seeds);
    fillSmallHoles(fg, 0.004 * fg.total());
    // Smooth GrabCut's pixel staircase and nicks so the outline (and the blur edge
    // that follows it) is even along the head and shoulders.
    cv::GaussianBlur(fg, fg, cv::Size(0, 0), std::max(1.0, w / 400.0));
    cv::threshold(fg, fg, 127, 255, cv::THRESH_BINARY);
}

// ---------------------------------------------------------------------------
// Matte refinement: fast color guided filter (He et al.). Coefficients are solved
// at the segmentation resolution and applied with the higher-resolution guide, so
// the matte follows real image edges (hair, shoulders) instead of GrabCut's blocks.
// ---------------------------------------------------------------------------

cv::Mat refineMatte(const cv::Mat& rgbLow, const cv::Mat& maskLow, const cv::Mat& rgbHigh) {
    const int r = std::max(2, rgbLow.cols / 160);
    const cv::Size k(2 * r + 1, 2 * r + 1);
    auto box = [&k](const cv::Mat& m) {
        cv::Mat o;
        cv::boxFilter(m, o, CV_32F, k);
        return o;
    };

    cv::Mat iLow;
    rgbLow.convertTo(iLow, CV_32FC3, 1.0 / 255.0);
    std::vector<cv::Mat> I;
    cv::split(iLow, I);
    cv::Mat p;
    maskLow.convertTo(p, CV_32F, 1.0 / 255.0);

    const cv::Mat mR = box(I[0]), mG = box(I[1]), mB = box(I[2]), mP = box(p);
    const cv::Mat cR = box(I[0].mul(p)) - mR.mul(mP);
    const cv::Mat cG = box(I[1].mul(p)) - mG.mul(mP);
    const cv::Mat cB = box(I[2].mul(p)) - mB.mul(mP);

    const cv::Mat vRR = box(I[0].mul(I[0])) - mR.mul(mR) + kMatteEps;
    const cv::Mat vRG = box(I[0].mul(I[1])) - mR.mul(mG);
    const cv::Mat vRB = box(I[0].mul(I[2])) - mR.mul(mB);
    const cv::Mat vGG = box(I[1].mul(I[1])) - mG.mul(mG) + kMatteEps;
    const cv::Mat vGB = box(I[1].mul(I[2])) - mG.mul(mB);
    const cv::Mat vBB = box(I[2].mul(I[2])) - mB.mul(mB) + kMatteEps;

    // Inverse of the symmetric 3x3 covariance (adjugate / determinant), per pixel.
    const cv::Mat iRR = vGG.mul(vBB) - vGB.mul(vGB);
    const cv::Mat iRG = vGB.mul(vRB) - vRG.mul(vBB);
    const cv::Mat iRB = vRG.mul(vGB) - vGG.mul(vRB);
    const cv::Mat iGG = vRR.mul(vBB) - vRB.mul(vRB);
    const cv::Mat iGB = vRB.mul(vRG) - vRR.mul(vGB);
    const cv::Mat iBB = vRR.mul(vGG) - vRG.mul(vRG);
    const cv::Mat det = iRR.mul(vRR) + iRG.mul(vRG) + iRB.mul(vRB);

    cv::Mat aR = (iRR.mul(cR) + iRG.mul(cG) + iRB.mul(cB)) / det;
    cv::Mat aG = (iRG.mul(cR) + iGG.mul(cG) + iGB.mul(cB)) / det;
    cv::Mat aB = (iRB.mul(cR) + iGB.mul(cG) + iBB.mul(cB)) / det;
    cv::Mat b = mP - aR.mul(mR) - aG.mul(mG) - aB.mul(mB);

    // How much of the mask's local variance the color model explains (R^2; p is binary so
    // var(p) = mP - mP^2). Low where subject and background share colors (dark hair on a
    // dark shelf): there the filter's output is a guess, so the mask is used instead.
    cv::Mat fit = (aR.mul(cR) + aG.mul(cG) + aB.mul(cB) + kFitFloor) / (mP - mP.mul(mP) + kFitFloor);
    fit = box(fit);

    aR = box(aR);
    aG = box(aG);
    aB = box(aB);
    b = box(b);

    const cv::Size hi = rgbHigh.size();
    cv::Mat iHigh;
    rgbHigh.convertTo(iHigh, CV_32FC3, 1.0 / 255.0);
    std::vector<cv::Mat> H;
    cv::split(iHigh, H);
    cv::resize(aR, aR, hi, 0, 0, cv::INTER_LINEAR);
    cv::resize(aG, aG, hi, 0, 0, cv::INTER_LINEAR);
    cv::resize(aB, aB, hi, 0, 0, cv::INTER_LINEAR);
    cv::resize(b, b, hi, 0, 0, cv::INTER_LINEAR);
    cv::Mat q = aR.mul(H[0]) + aG.mul(H[1]) + aB.mul(H[2]) + b;

    // The guided filter copies local texture into the matte for up to ~2 filter radii
    // either side of the outline, which leaves patches of half-sharp background (and
    // half-blurred clothing) of uneven width. Trust it only in a narrow band around the
    // outline, tapering to the clean binary mask, and push its mid-grays to a decision.
    cv::Mat up, bin;
    cv::resize(maskLow, up, hi, 0, 0, cv::INTER_LINEAR);
    cv::threshold(up, bin, 127, 255, cv::THRESH_BINARY);
    cv::Mat dIn, dOut;
    cv::distanceTransform(bin, dIn, cv::DIST_L2, 3);       // subject pixels: distance to background
    cv::distanceTransform(~bin, dOut, cv::DIST_L2, 3);     // background pixels: distance to subject
    const double band = std::max(4.0, std::max(hi.width, hi.height) * kMatteBandFrac);
    cv::Mat t = cv::max(1.0 - (dIn + dOut) * (1.0 / band), 0.0);
    cv::Mat weight = t.mul(t).mul(3.0 - 2.0 * t);  // smoothstep taper
    cv::resize(fit, fit, hi, 0, 0, cv::INTER_LINEAR);
    t = cv::min(cv::max((fit - kFitLow) * (1.0 / (kFitHigh - kFitLow)), 0.0), 1.0);
    weight = weight.mul(t.mul(t).mul(3.0 - 2.0 * t));

    t = cv::min(cv::max((q - kMatteLow) * (1.0 / (kMatteHigh - kMatteLow)), 0.0), 1.0);
    const cv::Mat decided = t.mul(t).mul(3.0 - 2.0 * t);

    cv::Mat soft;  // binary mask with a ~1 px anti-aliased edge
    bin.convertTo(soft, CV_32F, 1.0 / 255.0);
    cv::GaussianBlur(soft, soft, cv::Size(0, 0), 0.8);

    const cv::Mat alpha = soft + weight.mul(decided - soft);
    cv::Mat alpha8;
    alpha.convertTo(alpha8, CV_8U, 255.0);  // saturates to [0, 255]
    return alpha8;
}

// ---------------------------------------------------------------------------
// Lens blur
// ---------------------------------------------------------------------------

// Anti-aliased flat disc: the shape a real aperture gives out-of-focus points.
cv::Mat discKernel(float radius) {
    const int r = std::max(1, static_cast<int>(std::ceil(radius)));
    cv::Mat k(2 * r + 1, 2 * r + 1, CV_32F);
    for (int y = -r; y <= r; ++y) {
        auto* row = k.ptr<float>(y + r);
        for (int x = -r; x <= r; ++x) {
            const float d = std::sqrt(static_cast<float>(x * x + y * y));
            row[x + r] = std::clamp(radius + 0.5f - d, 0.f, 1.f);
        }
    }
    k /= cv::sum(k)[0];
    return k;
}

// Normalized-convolution disc blur of the background in linear light. Highlights get
// extra energy so they bloom into bright discs; subject pixels carry no weight, so
// their colors never bleed into the bokeh.
cv::Mat lensBlur(const cv::Mat& rgba, cv::Size size, const cv::Mat& alpha8, float radius) {
    const cv::Mat rgb = resizedRgb(rgba, size);
    cv::Mat toLinear(1, 256, CV_32F);
    for (int i = 0; i < 256; ++i) toLinear.at<float>(i) = std::pow(i / 255.f, kGamma);
    cv::Mat lin;
    cv::LUT(rgb, toLinear, lin);
    std::vector<cv::Mat> c;
    cv::split(lin, c);

    cv::Mat luma = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2];
    cv::Mat over;
    cv::max(luma - kHighlightThreshold, 0.0, over);
    over *= 1.0 / (1.0 - kHighlightThreshold);
    const cv::Mat boost = 1.0 + kHighlightGain * over.mul(over);

    cv::Mat alpha;
    cv::resize(alpha8, alpha, size, 0, 0, cv::INTER_LINEAR);
    alpha.convertTo(alpha, CV_32F, 1.0 / 255.0);
    cv::Mat bgW = 1.0 - alpha;
    bgW = bgW.mul(bgW);  // partially-subject edge pixels contribute little
    const cv::Mat energy = boost.mul(bgW);

    cv::Mat planes[] = {c[0].mul(energy), c[1].mul(energy), c[2].mul(energy), bgW};
    cv::Mat stack, blurred;
    cv::merge(planes, 4, stack);
    cv::filter2D(stack, blurred, -1, discKernel(radius), cv::Point(-1, -1), 0, cv::BORDER_REFLECT);

    std::vector<cv::Mat> b;
    cv::split(blurred, b);
    const cv::Mat den = cv::max(b[3], 1e-6);
    cv::Mat out3;
    cv::Mat outPlanes[] = {b[0] / den, b[1] / den, b[2] / den};
    cv::merge(outPlanes, 3, out3);
    out3 = cv::min(cv::max(out3, 0.0), 1.0);
    cv::pow(out3, 1.0 / kGamma, out3);
    cv::Mat out8;
    out3.convertTo(out8, CV_8UC3, 255.0);
    return out8;
}

// ---------------------------------------------------------------------------
// Grain
// ---------------------------------------------------------------------------

// Robust sensor-noise estimate (Immerkaer's operator with a median instead of a mean,
// so edges barely matter) on a full-resolution center crop.
double estimateNoise(const cv::Mat& rgba) {
    const int cw = std::min(rgba.cols, 1024);
    const int ch = std::min(rgba.rows, 1024);
    if (cw < 16 || ch < 16) return 0.0;
    const cv::Rect crop((rgba.cols - cw) / 2, (rgba.rows - ch) / 2, cw, ch);
    cv::Mat gray;
    cv::cvtColor(rgba(crop), gray, cv::COLOR_RGBA2GRAY);
    const cv::Mat k = (cv::Mat_<float>(3, 3) << 1, -2, 1, -2, 4, -2, 1, -2, 1);
    cv::Mat resp;
    cv::filter2D(gray, resp, CV_32F, k);
    std::vector<float> v;
    v.reserve(static_cast<size_t>((cw - 2) * (ch - 2)));
    for (int r = 1; r < ch - 1; ++r) {
        const float* p = resp.ptr<float>(r);
        for (int c = 1; c < cw - 1; ++c) v.push_back(std::fabs(p[c]));
    }
    auto mid = v.begin() + static_cast<long>(v.size() / 2);
    std::nth_element(v.begin(), mid, v.end());
    // |N(0, 6 sigma)| has median 0.6745 * 6 sigma (the kernel's L2 norm is 6).
    return *mid / (0.6745 * 6.0);
}

}  // namespace

void renderBokeh(cv::Mat& rgba, const std::vector<cv::Rect2f>& faces, float strength,
                 Stats* stats, cv::Mat* alphaOut) {
    CV_Assert(rgba.type() == CV_8UC4 && !rgba.empty());
    const cv::Size full = rgba.size();
    strength = std::clamp(strength, 0.1f, 2.5f);

    // 1. Subject mask at the segmentation resolution.
    const cv::Size segSize = fitLongSide(full, kSegSide);
    const cv::Mat segRgb = resizedRgb(rgba, segSize);
    std::vector<cv::Rect> faceRects;
    for (const auto& f : faces) {
        const cv::Rect px = toPixels(f, segSize);
        if (px.width >= 4 && px.height >= 4) faceRects.push_back(px);
    }
    cv::Mat fg = segmentSubject(segRgb, faceRects);
    std::vector<cv::Rect> seeds = faceRects;
    if (seeds.empty()) {
        seeds.emplace_back(segSize.width * 2 / 5, segSize.height * 2 / 5, segSize.width / 5, segSize.height / 5);
    }
    cleanMask(fg, seeds);

    // 2. Edge-aware matte.
    const cv::Size matteSize = fitLongSide(full, kMatteSide);
    const cv::Mat alpha8 = refineMatte(segRgb, fg, resizedRgb(rgba, matteSize));

    // 3. Lens blur of the background.
    const cv::Size blurSize = fitLongSide(full, kBlurSide);
    const float radius = static_cast<float>(std::max(blurSize.width, blurSize.height) * kRadiusFrac * strength);
    const cv::Mat blurred = lensBlur(rgba, blurSize, alpha8, radius);

    // 4. Grain so the blurred background matches the sharp subject's texture.
    const double sigma = std::min(estimateNoise(rgba), 6.0);
    cv::Mat noise;
    if (sigma >= 0.6) {
        noise.create(kNoiseTile, kNoiseTile, CV_32F);
        cv::RNG rng(0x5eed);
        rng.fill(noise, cv::RNG::NORMAL, 0.0, sigma);
    }

    // 5. Blend at full resolution (8-bit intermediates keep memory low).
    cv::Mat bgFull, alphaFull;
    cv::resize(blurred, bgFull, full, 0, 0, cv::INTER_LINEAR);
    cv::resize(alpha8, alphaFull, full, 0, 0, cv::INTER_LINEAR);
    for (int r = 0; r < full.height; ++r) {
        uint8_t* dst = rgba.ptr<uint8_t>(r);
        const uint8_t* bg = bgFull.ptr<uint8_t>(r);
        const uint8_t* a = alphaFull.ptr<uint8_t>(r);
        const float* n = noise.empty() ? nullptr : noise.ptr<float>(r % kNoiseTile);
        for (int c = 0; c < full.width; ++c) {
            const int al = a[c];
            if (al == 255) continue;
            const float fa = al * (1.f / 255.f);
            const float fb = 1.f - fa;
            const float grain = n ? n[c % kNoiseTile] : 0.f;
            for (int k = 0; k < 3; ++k) {
                const float v = dst[c * 4 + k] * fa + (bg[c * 3 + k] + grain) * fb;
                dst[c * 4 + k] = cv::saturate_cast<uint8_t>(v);
            }
            dst[c * 4 + 3] = 255;
        }
    }

    if (stats) {
        stats->faces = static_cast<int>(faceRects.size());
        stats->noiseSigma = sigma;
        stats->subjectArea = cv::mean(alpha8)[0] / 255.0;
    }
    if (alphaOut) alpha8.copyTo(*alphaOut);
}

}  // namespace portrait
