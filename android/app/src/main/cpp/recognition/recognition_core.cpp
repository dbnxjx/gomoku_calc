#include "recognition_core.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <numeric>
#include <random>

#include "cpu.h"
#include "mat.h"

namespace recog {

namespace {

constexpr int kCornerInput = 384;
constexpr int kStride = 4;
constexpr int kPatch = 32;
constexpr int kPitch = 32;
constexpr int kThreads = 2;
// 和 pipeline/recognize.py 相同
constexpr float kCornerScoreMin = 0.3f;
constexpr float kSizeRatioMin = 1.3f;
constexpr float kStoneThreshold = 0.6f;
const int kBoardSizes[4] = {9, 13, 15, 19};

struct Mat3
{
    double m[9];
};

Mat3 multiply(const Mat3& a, const Mat3& b)
{
    Mat3 r;
    for (int i = 0; i < 3; i++)
        for (int j = 0; j < 3; j++)
            r.m[i * 3 + j] = a.m[i * 3] * b.m[j] + a.m[i * 3 + 1] * b.m[3 + j] + a.m[i * 3 + 2] * b.m[6 + j];
    return r;
}

bool invert(const Mat3& a, Mat3& out)
{
    const double* m = a.m;
    const double c00 = m[4] * m[8] - m[5] * m[7];
    const double c01 = m[5] * m[6] - m[3] * m[8];
    const double c02 = m[3] * m[7] - m[4] * m[6];
    const double det = m[0] * c00 + m[1] * c01 + m[2] * c02;
    if (std::fabs(det) < 1e-18) return false;
    const double d = 1.0 / det;
    out.m[0] = c00 * d;
    out.m[1] = (m[2] * m[7] - m[1] * m[8]) * d;
    out.m[2] = (m[1] * m[5] - m[2] * m[4]) * d;
    out.m[3] = c01 * d;
    out.m[4] = (m[0] * m[8] - m[2] * m[6]) * d;
    out.m[5] = (m[2] * m[3] - m[0] * m[5]) * d;
    out.m[6] = c02 * d;
    out.m[7] = (m[1] * m[6] - m[0] * m[7]) * d;
    out.m[8] = (m[0] * m[4] - m[1] * m[3]) * d;
    return true;
}

void apply(const Mat3& h, double x, double y, double& u, double& v)
{
    const double w = h.m[6] * x + h.m[7] * y + h.m[8];
    u = (h.m[0] * x + h.m[1] * y + h.m[2]) / w;
    v = (h.m[3] * x + h.m[4] * y + h.m[5]) / w;
}

// 8 元线性方程组的高斯消元，A 是 8 行 9 列的增广矩阵
bool solve8(double A[8][9], double x[8])
{
    for (int c = 0; c < 8; c++)
    {
        int p = c;
        for (int r = c + 1; r < 8; r++)
            if (std::fabs(A[r][c]) > std::fabs(A[p][c])) p = r;
        if (std::fabs(A[p][c]) < 1e-12) return false;
        if (p != c)
            for (int k = 0; k < 9; k++) std::swap(A[c][k], A[p][k]);
        for (int r = 0; r < 8; r++)
        {
            if (r == c) continue;
            const double f = A[r][c] / A[c][c];
            for (int k = c; k < 9; k++) A[r][k] -= f * A[c][k];
        }
    }
    for (int i = 0; i < 8; i++) x[i] = A[i][8] / A[i][i];
    return true;
}

bool perspectiveFromFour(const double src[4][2], const double dst[4][2], Mat3& out)
{
    double A[8][9];
    for (int i = 0; i < 4; i++)
    {
        const double x = src[i][0], y = src[i][1], u = dst[i][0], v = dst[i][1];
        const double r0[9] = {x, y, 1, 0, 0, 0, -u * x, -u * y, u};
        const double r1[9] = {0, 0, 0, x, y, 1, -v * x, -v * y, v};
        std::copy(r0, r0 + 9, A[2 * i]);
        std::copy(r1, r1 + 9, A[2 * i + 1]);
    }
    double h[8];
    if (!solve8(A, h)) return false;
    std::copy(h, h + 8, out.m);
    out.m[8] = 1;
    return true;
}

Mat3 quadToSquare(const Quad& q, float a, float b)
{
    const double src[4][2] = {{q.p[0][0], q.p[0][1]}, {q.p[1][0], q.p[1][1]}, {q.p[2][0], q.p[2][1]}, {q.p[3][0], q.p[3][1]}};
    const double dst[4][2] = {{a, a}, {b, a}, {b, b}, {a, b}};
    Mat3 h = {{1, 0, 0, 0, 1, 0, 0, 0, 1}};
    perspectiveFromFour(src, dst, h);
    return h;
}

float meanSide(const Quad& q)
{
    float total = 0;
    for (int i = 0; i < 4; i++)
    {
        const int k = (i + 1) % 4;
        total += std::hypot(q.p[i][0] - q.p[k][0], q.p[i][1] - q.p[k][1]);
    }
    return total / 4;
}

// 和 cv2.resize 的 INTER_AREA 一样：目标像素等于它覆盖的源区域的面积加权平均
Image resizeArea(const Image& src, int dw, int dh)
{
    Image dst(dw, dh);
    const double sx = static_cast<double>(src.w) / dw, sy = static_cast<double>(src.h) / dh;
    struct Span
    {
        int begin, end;
        std::vector<float> weight;
    };
    const auto spans = [](int count, double scale, int limit) {
        std::vector<Span> out(count);
        for (int i = 0; i < count; i++)
        {
            const double lo = i * scale, hi = std::min((i + 1) * scale, static_cast<double>(limit));
            Span& s = out[i];
            s.begin = static_cast<int>(std::floor(lo));
            s.end = std::min(static_cast<int>(std::ceil(hi)), limit);
            for (int k = s.begin; k < s.end; k++)
            {
                const double overlap = std::min(hi, k + 1.0) - std::max(lo, static_cast<double>(k));
                s.weight.push_back(static_cast<float>(overlap / (hi - lo)));
            }
        }
        return out;
    };
    const std::vector<Span> xs = spans(dw, sx, src.w), ys = spans(dh, sy, src.h);
    #pragma omp parallel for num_threads(kThreads)
    for (int y = 0; y < dh; y++)
    {
        std::vector<float> line(static_cast<size_t>(dw) * 3, 0.f);
        const Span& sv = ys[y];
        for (int r = sv.begin; r < sv.end; r++)
        {
            const float wy = sv.weight[r - sv.begin];
            const uint8_t* row = src.rgb.data() + static_cast<size_t>(r) * src.w * 3;
            for (int x = 0; x < dw; x++)
            {
                const Span& sh = xs[x];
                float c[3] = {0, 0, 0};
                for (int k = sh.begin; k < sh.end; k++)
                {
                    const float wx = sh.weight[k - sh.begin];
                    c[0] += row[k * 3] * wx;
                    c[1] += row[k * 3 + 1] * wx;
                    c[2] += row[k * 3 + 2] * wx;
                }
                for (int ch = 0; ch < 3; ch++) line[x * 3 + ch] += c[ch] * wy;
            }
        }
        uint8_t* out = dst.rgb.data() + static_cast<size_t>(y) * dw * 3;
        for (int i = 0; i < dw * 3; i++) out[i] = static_cast<uint8_t>(std::min(255.f, line[i] + 0.5f));
    }
    return dst;
}

Image resizeBilinear(const Image& src, int dw, int dh)
{
    Image dst(dw, dh);
    const double sx = static_cast<double>(src.w) / dw, sy = static_cast<double>(src.h) / dh;
    for (int y = 0; y < dh; y++)
    {
        const double fy = std::max(0.0, (y + 0.5) * sy - 0.5);
        const int iy = std::min(static_cast<int>(fy), src.h - 1), iy2 = std::min(iy + 1, src.h - 1);
        const float ay = static_cast<float>(fy - iy);
        for (int x = 0; x < dw; x++)
        {
            const double fx = std::max(0.0, (x + 0.5) * sx - 0.5);
            const int ix = std::min(static_cast<int>(fx), src.w - 1), ix2 = std::min(ix + 1, src.w - 1);
            const float ax = static_cast<float>(fx - ix);
            for (int c = 0; c < 3; c++)
            {
                const float top = src.rgb[(static_cast<size_t>(iy) * src.w + ix) * 3 + c] * (1 - ax) + src.rgb[(static_cast<size_t>(iy) * src.w + ix2) * 3 + c] * ax;
                const float low = src.rgb[(static_cast<size_t>(iy2) * src.w + ix) * 3 + c] * (1 - ax) + src.rgb[(static_cast<size_t>(iy2) * src.w + ix2) * 3 + c] * ax;
                dst.rgb[(static_cast<size_t>(y) * dw + x) * 3 + c] = static_cast<uint8_t>(top * (1 - ay) + low * ay + 0.5f);
            }
        }
    }
    return dst;
}

Image resizeTo(const Image& src, int dw, int dh)
{
    if (dw <= src.w && dh <= src.h) return resizeArea(src, dw, dh);
    return resizeBilinear(src, dw, dh);
}

// inverseMap 把目标坐标映射回源坐标。borderBlack 为真时图外取黑色，否则复制边缘。
Image warp(const Image& src, const Mat3& inverseMap, int dw, int dh, bool borderBlack)
{
    Image dst(dw, dh);
    const double* m = inverseMap.m;
    const int sw = src.w, sh = src.h;
    const uint8_t* s = src.rgb.data();
    #pragma omp parallel for num_threads(kThreads)
    for (int y = 0; y < dh; y++)
    {
        uint8_t* out = dst.rgb.data() + static_cast<size_t>(y) * dw * 3;
        for (int x = 0; x < dw; x++)
        {
            const double w = m[6] * x + m[7] * y + m[8];
            const double fx = (m[0] * x + m[1] * y + m[2]) / w;
            const double fy = (m[3] * x + m[4] * y + m[5]) / w;
            const int ix = static_cast<int>(std::floor(fx)), iy = static_cast<int>(std::floor(fy));
            const float ax = static_cast<float>(fx - ix), ay = static_cast<float>(fy - iy);
            for (int c = 0; c < 3; c++)
            {
                const auto at = [&](int px, int py) -> float {
                    if (borderBlack && (px < 0 || py < 0 || px >= sw || py >= sh)) return 0.f;
                    px = std::min(std::max(px, 0), sw - 1);
                    py = std::min(std::max(py, 0), sh - 1);
                    return s[(static_cast<size_t>(py) * sw + px) * 3 + c];
                };
                const float top = at(ix, iy) * (1 - ax) + at(ix + 1, iy) * ax;
                const float low = at(ix, iy + 1) * (1 - ax) + at(ix + 1, iy + 1) * ax;
                out[x * 3 + c] = static_cast<uint8_t>(top * (1 - ay) + low * ay + 0.5f);
            }
        }
    }
    return dst;
}

// 拉正成每格 pitch 像素的正方形，四周留半个图块。缩小太多时先面积缩放，避免走样。
Image rectify(const Image& img, const Quad& quadIn, int n, int pitch, int patch)
{
    Quad quad = quadIn;
    const Image* source = &img;
    Image shrunk;
    const float side = meanSide(quad);
    const int grid = pitch * (n - 1);
    if (side > grid * 1.2f)
    {
        const float s = grid / side;
        shrunk = resizeArea(img, std::max(1, static_cast<int>(std::lround(img.w * s))), std::max(1, static_cast<int>(std::lround(img.h * s))));
        for (auto& point : quad.p)
        {
            point[0] *= s;
            point[1] *= s;
        }
        source = &shrunk;
    }
    const int pad = patch / 2;
    const float a = pad - 0.5f, b = a + grid;
    Mat3 inverse = {{1, 0, 0, 0, 1, 0, 0, 0, 1}};
    invert(quadToSquare(quad, a, b), inverse);
    return warp(*source, inverse, grid + patch, grid + patch, false);
}

std::vector<float> toGray(const Image& img)
{
    std::vector<float> g(static_cast<size_t>(img.w) * img.h);
    for (size_t i = 0; i < g.size(); i++)
    {
        const uint8_t* p = img.rgb.data() + i * 3;
        g[i] = static_cast<float>((p[0] * 4899 + p[1] * 9617 + p[2] * 1868 + 8192) >> 14);
    }
    return g;
}

inline int reflect101(int i, int n)
{
    if (n == 1) return 0;
    while (i < 0 || i >= n)
    {
        if (i < 0) i = -i;
        if (i >= n) i = 2 * n - 2 - i;
    }
    return i;
}

// 一维滑动平均。stride 为相邻元素的间距，count 个元素。
void boxBlurLine(const float* in, int count, int stride, int k, float* out)
{
    const int r = k / 2;
    double sum = 0;
    for (int i = -r; i <= r; i++) sum += in[reflect101(i, count) * stride];
    for (int i = 0; i < count; i++)
    {
        out[i] = static_cast<float>(sum / k);
        sum += in[reflect101(i + r + 1, count) * stride] - in[reflect101(i - r, count) * stride];
    }
}

float medianOf(std::vector<float>& v)
{
    const size_t mid = v.size() / 2;
    std::nth_element(v.begin(), v.begin() + mid, v.end());
    const float hi = v[mid];
    if (v.size() % 2) return hi;
    return (hi + *std::max_element(v.begin(), v.begin() + mid)) / 2;
}

// 网格线是周期信号：路数为 n 时，投影的频谱在 n-1 处有峰。只算需要的几个频率，不用完整 FFT。
void detectBoardSize(const Image& img, const Quad& quad, int& bestN, float& ratio)
{
    const int side = 1024;
    const double src[4][2] = {{quad.p[0][0], quad.p[0][1]}, {quad.p[1][0], quad.p[1][1]}, {quad.p[2][0], quad.p[2][1]}, {quad.p[3][0], quad.p[3][1]}};
    const double dst[4][2] = {{0, 0}, {static_cast<double>(side), 0}, {static_cast<double>(side), static_cast<double>(side)}, {0, static_cast<double>(side)}};
    Mat3 forward = {{1, 0, 0, 0, 1, 0, 0, 0, 1}}, inverse = forward;
    perspectiveFromFour(src, dst, forward);
    invert(forward, inverse);
    const std::vector<float> g = toGray(warp(img, inverse, side, side, true));

    std::vector<double> window(side);
    for (int i = 0; i < side; i++) window[i] = 0.5 - 0.5 * std::cos(2 * M_PI * i / (side - 1));

    double scores[4] = {0, 0, 0, 0};
    std::vector<float> thin(side), wide(side), resp(static_cast<size_t>(side) * side);
    for (int axis = 0; axis < 2; axis++)
    {
        // axis=0：沿水平方向求差，响应竖线；再按列投影
        for (int line = 0; line < side; line++)
        {
            const float* start = axis == 0 ? g.data() + static_cast<size_t>(line) * side : g.data() + line;
            const int stride = axis == 0 ? 1 : side;
            boxBlurLine(start, side, stride, 21, wide.data());
            boxBlurLine(start, side, stride, 3, thin.data());
            for (int i = 0; i < side; i++)
            {
                const float value = std::fabs(wide[i] - thin[i]);
                if (axis == 0) resp[static_cast<size_t>(line) * side + i] = value;
                else resp[static_cast<size_t>(i) * side + line] = value;
            }
        }
        std::vector<double> profile(side);
        std::vector<float> values(side);
        double total = 0;
        for (int i = 0; i < side; i++)
        {
            double sum = 0;
            for (int k = 0; k < side; k++)
            {
                values[k] = axis == 0 ? resp[static_cast<size_t>(k) * side + i] : resp[static_cast<size_t>(i) * side + k];
                sum += values[k];
            }
            profile[i] = medianOf(values) + 0.25 * sum / side;
            total += profile[i];
        }
        total /= side;
        const auto magnitude = [&](int k) {
            double re = 0, im = 0;
            for (int i = 0; i < side; i++)
            {
                const double v = (profile[i] - total) * window[i];
                const double angle = 2 * M_PI * k * i / side;
                re += v * std::cos(angle);
                im -= v * std::sin(angle);
            }
            return std::hypot(re, im);
        };
        for (int s = 0; s < 4; s++) scores[s] += magnitude(kBoardSizes[s] - 1) + 0.5 * magnitude(2 * (kBoardSizes[s] - 1));
    }
    int order[4] = {0, 1, 2, 3};
    std::sort(order, order + 4, [&](int a, int b) { return scores[a] > scores[b]; });
    bestN = kBoardSizes[order[0]];
    ratio = static_cast<float>(scores[order[0]] / std::max(scores[order[1]], 1e-6));
}

// 二阶差分：kernelMain=[1,-2,1] 沿求导方向，[1,2,1] 沿另一方向平滑
void sobel2(const std::vector<float>& g, int w, int h, bool dy, std::vector<float>& out)
{
    out.resize(g.size());
    for (int y = 0; y < h; y++)
    {
        const float* r0 = g.data() + static_cast<size_t>(reflect101(y - 1, h)) * w;
        const float* r1 = g.data() + static_cast<size_t>(y) * w;
        const float* r2 = g.data() + static_cast<size_t>(reflect101(y + 1, h)) * w;
        float* o = out.data() + static_cast<size_t>(y) * w;
        for (int x = 0; x < w; x++)
        {
            const int l = reflect101(x - 1, w), r = reflect101(x + 1, w);
            float value;
            if (dy) value = (r0[l] + 2 * r0[x] + r0[r]) - 2 * (r1[l] + 2 * r1[x] + r1[r]) + (r2[l] + 2 * r2[x] + r2[r]);
            else value = (r0[l] - 2 * r0[x] + r0[r]) + 2 * (r1[l] - 2 * r1[x] + r1[r]) + (r2[l] - 2 * r2[x] + r2[r]);
            o[x] = std::fabs(value);
        }
    }
}

// 返回亚像素峰位置，strength 为峰值和中位数之比
float peakOffset(std::vector<float>& profile, float& strength)
{
    const int count = static_cast<int>(profile.size());
    const int k = static_cast<int>(std::max_element(profile.begin(), profile.end()) - profile.begin());
    const float a = k > 0 ? profile[k - 1] : 0, b = profile[k], c = k + 1 < count ? profile[k + 1] : 0;
    std::vector<float> copy = profile;
    strength = b / std::max(medianOf(copy), 1e-3f);
    if (k > 0 && k < count - 1)
    {
        const float d = a - 2 * b + c;
        if (std::fabs(d) > 1e-6f) return k + std::min(0.5f, std::max(-0.5f, 0.5f * (a - c) / d));
    }
    return static_cast<float>(k);
}

struct Pt
{
    double x, y;
};

void measureIntersections(const Image& rect, int n, int pitch, int pad, float search, float minStrength, std::vector<Pt>& measured, std::vector<Pt>& ideal)
{
    const std::vector<float> g = toGray(rect);
    const int w = rect.w, h = rect.h;
    std::vector<float> horizontal, vertical;
    sobel2(g, w, h, true, horizontal);
    sobel2(g, w, h, false, vertical);

    // 棋子边缘响应很强，截断后才不会盖过细线
    std::vector<float> all;
    all.reserve(horizontal.size() * 2);
    all.insert(all.end(), horizontal.begin(), horizontal.end());
    all.insert(all.end(), vertical.begin(), vertical.end());
    const double position = 0.98 * (all.size() - 1);
    const size_t lower = static_cast<size_t>(position);
    std::nth_element(all.begin(), all.begin() + lower, all.end());
    const float a = all[lower];
    const float b = lower + 1 < all.size() ? *std::min_element(all.begin() + lower + 1, all.end()) : a;
    const float cap = static_cast<float>(a + (b - a) * (position - lower));
    for (float& v : horizontal) v = std::min(v, cap);
    for (float& v : vertical) v = std::min(v, cap);

    const int radius = static_cast<int>(std::lround(search * pitch));
    const int half = pitch / 2;
    const int lo = pad, hi = pad + pitch * (n - 1);
    std::vector<float> profile;
    for (int i = 0; i < n; i++)
    {
        for (int j = 0; j < n; j++)
        {
            const int cy = pad + i * pitch, cx = pad + j * pitch;
            const int x0 = std::max(lo, cx - half), x1 = std::min(hi, cx + half) + 1;
            const int y0 = std::max(lo, cy - half), y1 = std::min(hi, cy + half) + 1;
            const int ya = std::max(0, cy - radius), yb = std::min(h, cy + radius + 1);
            const int xa = std::max(0, cx - radius), xb = std::min(w, cx + radius + 1);

            profile.assign(yb - ya, 0.f);
            for (int y = ya; y < yb; y++)
            {
                float sum = 0;
                for (int x = x0; x < x1; x++) sum += horizontal[static_cast<size_t>(y) * w + x];
                profile[y - ya] = sum / (x1 - x0);
            }
            float sy = 0;
            const float py = peakOffset(profile, sy);

            profile.assign(xb - xa, 0.f);
            for (int x = xa; x < xb; x++)
            {
                float sum = 0;
                for (int y = y0; y < y1; y++) sum += vertical[static_cast<size_t>(y) * w + x];
                profile[x - xa] = sum / (y1 - y0);
            }
            float sx = 0;
            const float px = peakOffset(profile, sx);

            if (sy >= minStrength && sx >= minStrength)
            {
                measured.push_back({xa + static_cast<double>(px), ya + static_cast<double>(py)});
                ideal.push_back({cx - 0.5, cy - 0.5});
            }
        }
    }
}

// 用全部内点的重投影误差做几步高斯牛顿，和 cv2.findHomography 最后的 LM 优化作用相同
void refineOnInliers(const std::vector<Pt>& from, const std::vector<Pt>& to, const std::vector<int>& inliers, Mat3& h, double scale)
{
    // 坐标缩到 1 附近，矩阵条件数才不会太差
    Mat3 s = {{scale, 0, 0, 0, scale, 0, 0, 0, 1}}, sInv = {{1 / scale, 0, 0, 0, 1 / scale, 0, 0, 0, 1}};
    Mat3 n = multiply(multiply(s, h), sInv);
    for (int v = 0; v < 9; v++) n.m[v] /= n.m[8];
    for (int iter = 0; iter < 6; iter++)
    {
        double JtJ[8][8] = {};
        double Jtr[8] = {};
        for (int id : inliers)
        {
            const double x = from[id].x * scale, y = from[id].y * scale;
            const double du = to[id].x * scale, dv = to[id].y * scale;
            const double w = n.m[6] * x + n.m[7] * y + 1;
            const double u = (n.m[0] * x + n.m[1] * y + n.m[2]) / w;
            const double v = (n.m[3] * x + n.m[4] * y + n.m[5]) / w;
            const double ju[8] = {x / w, y / w, 1 / w, 0, 0, 0, -u * x / w, -u * y / w};
            const double jv[8] = {0, 0, 0, x / w, y / w, 1 / w, -v * x / w, -v * y / w};
            for (int a = 0; a < 8; a++)
            {
                Jtr[a] += ju[a] * (du - u) + jv[a] * (dv - v);
                for (int b = 0; b < 8; b++) JtJ[a][b] += ju[a] * ju[b] + jv[a] * jv[b];
            }
        }
        double A[8][9];
        for (int a = 0; a < 8; a++)
        {
            for (int b = 0; b < 8; b++) A[a][b] = JtJ[a][b] + (a == b ? 1e-9 : 0);
            A[a][8] = Jtr[a];
        }
        double delta[8];
        if (!solve8(A, delta)) break;
        for (int v = 0; v < 8; v++) n.m[v] += delta[v];
    }
    h = multiply(multiply(sInv, n), s);
    for (int v = 0; v < 9; v++) h.m[v] /= h.m[8];
}

// RANSAC 求单应矩阵。返回内点下标，和 cv2.findHomography(RANSAC) 的做法一致；种子固定，结果可复现。
bool ransacHomography(const std::vector<Pt>& from, const std::vector<Pt>& to, double threshold, Mat3& best, std::vector<int>& bestInliers)
{
    const int count = static_cast<int>(from.size());
    if (count < 4) return false;
    std::mt19937 rng(12345);
    std::uniform_int_distribution<int> pick(0, count - 1);
    const double t2 = threshold * threshold;
    int maxIters = 2000;
    bool found = false;
    std::vector<int> inliers;
    for (int iter = 0; iter < maxIters; iter++)
    {
        int ids[4];
        for (int i = 0; i < 4; i++)
        {
            bool repeated = true;
            while (repeated)
            {
                ids[i] = pick(rng);
                repeated = false;
                for (int k = 0; k < i; k++) repeated = repeated || ids[k] == ids[i];
            }
        }
        const double s[4][2] = {{from[ids[0]].x, from[ids[0]].y}, {from[ids[1]].x, from[ids[1]].y}, {from[ids[2]].x, from[ids[2]].y}, {from[ids[3]].x, from[ids[3]].y}};
        const double d[4][2] = {{to[ids[0]].x, to[ids[0]].y}, {to[ids[1]].x, to[ids[1]].y}, {to[ids[2]].x, to[ids[2]].y}, {to[ids[3]].x, to[ids[3]].y}};
        // 共线的采样解不出有意义的矩阵
        bool collinear = false;
        for (int a = 0; a < 4 && !collinear; a++)
            for (int b = a + 1; b < 4 && !collinear; b++)
                for (int c = b + 1; c < 4 && !collinear; c++)
                {
                    const double cross = (s[b][0] - s[a][0]) * (s[c][1] - s[a][1]) - (s[b][1] - s[a][1]) * (s[c][0] - s[a][0]);
                    const double crossD = (d[b][0] - d[a][0]) * (d[c][1] - d[a][1]) - (d[b][1] - d[a][1]) * (d[c][0] - d[a][0]);
                    collinear = std::fabs(cross) < 1 || std::fabs(crossD) < 1;
                }
        if (collinear) continue;
        Mat3 h;
        if (!perspectiveFromFour(s, d, h)) continue;
        inliers.clear();
        for (int i = 0; i < count; i++)
        {
            double u, v;
            apply(h, from[i].x, from[i].y, u, v);
            const double dx = u - to[i].x, dy = v - to[i].y;
            if (dx * dx + dy * dy < t2) inliers.push_back(i);
        }
        if (inliers.size() > bestInliers.size())
        {
            bestInliers = inliers;
            best = h;
            found = true;
            const double ratio = 1.0 - static_cast<double>(bestInliers.size()) / count;
            const double denom = 1.0 - std::pow(1.0 - ratio, 4);
            if (denom < 1e-12) maxIters = std::min(maxIters, iter + 1);
            else if (std::log(denom) < 0) maxIters = std::min(maxIters, static_cast<int>(std::ceil(std::log(1 - 0.999) / std::log(denom))));
        }
    }
    if (found && bestInliers.size() >= 4) refineOnInliers(from, to, bestInliers, best, 1.0 / 512);
    return found;
}

struct GridFit
{
    Quad quad;
    bool used = false;
    int inliers = 0;
    int measured = 0;
};

GridFit refineGrid(const Image& img, const Quad& quad0, int n)
{
    GridFit fit;
    fit.quad = quad0;
    const int pitch = kPitch, pad = pitch;
    const float cell = meanSide(quad0) / (n - 1);
    for (int iteration = 0; iteration < 2; iteration++)
    {
        const Image rect = rectify(img, fit.quad, n, pitch, 2 * pad);
        std::vector<Pt> measured, ideal;
        measureIntersections(rect, n, pitch, pad, 0.42f, 1.6f, measured, ideal);
        fit.measured = static_cast<int>(measured.size());
        if (fit.measured < std::max(16, n * n / 6)) break;

        Mat3 h;
        std::vector<int> inliers;
        if (!ransacHomography(measured, ideal, pitch * 0.1, h, inliers)) break;
        fit.inliers = static_cast<int>(inliers.size());

        // 内点要分布到棋盘四周，否则外推到角点不可靠
        double loX = 1e9, hiX = -1e9, loY = 1e9, hiY = -1e9;
        for (int id : inliers)
        {
            loX = std::min(loX, ideal[id].x);
            hiX = std::max(hiX, ideal[id].x);
            loY = std::min(loY, ideal[id].y);
            hiY = std::max(hiY, ideal[id].y);
        }
        const double minSpread = pitch * (n - 1) * 0.6;
        if (fit.inliers < std::max(12, fit.measured / 3) || hiX - loX < minSpread || hiY - loY < minSpread) break;

        const float a = pad - 0.5f, b = a + pitch * (n - 1);
        const Mat3 toWarp = quadToSquare(fit.quad, a, b);
        Mat3 hInv, warpInv;
        if (!invert(h, hInv) || !invert(toWarp, warpInv)) break;
        const double corners[4][2] = {{a, a}, {b, a}, {b, b}, {a, b}};
        Quad candidate;
        float moved = 0;
        for (int k = 0; k < 4; k++)
        {
            double ux, uy, ox, oy;
            apply(hInv, corners[k][0], corners[k][1], ux, uy);
            apply(warpInv, ux, uy, ox, oy);
            candidate.p[k][0] = static_cast<float>(ox);
            candidate.p[k][1] = static_cast<float>(oy);
            moved = std::max(moved, std::hypot(candidate.p[k][0] - quad0.p[k][0], candidate.p[k][1] - quad0.p[k][1]));
        }
        // 移动过大多半是锁到了相邻的线上
        if (moved > cell * 0.9f) break;
        fit.quad = candidate;
        fit.used = true;
    }
    return fit;
}

Quad orderClockwise(const float pts[4][2])
{
    float cx = 0, cy = 0;
    for (int i = 0; i < 4; i++)
    {
        cx += pts[i][0] / 4;
        cy += pts[i][1] / 4;
    }
    int order[4] = {0, 1, 2, 3};
    float angle[4];
    for (int i = 0; i < 4; i++) angle[i] = std::atan2(pts[i][1] - cy, pts[i][0] - cx);
    std::sort(order, order + 4, [&](int a, int b) { return angle[a] < angle[b]; });
    int start = 0;
    float least = 1e30f;
    for (int i = 0; i < 4; i++)
    {
        const float sum = pts[order[i]][0] + pts[order[i]][1];
        if (sum < least)
        {
            least = sum;
            start = i;
        }
    }
    Quad q;
    for (int i = 0; i < 4; i++)
    {
        q.p[i][0] = pts[order[(start + i) % 4]][0];
        q.p[i][1] = pts[order[(start + i) % 4]][1];
    }
    return q;
}

bool quadIsPlausible(const float pts[4][2])
{
    const Quad q = orderClockwise(pts);
    float ex[4], ey[4], shortest = 1e30f, longest = 0;
    for (int i = 0; i < 4; i++)
    {
        const int k = (i + 1) % 4;
        ex[i] = q.p[k][0] - q.p[i][0];
        ey[i] = q.p[k][1] - q.p[i][1];
        const float length = std::hypot(ex[i], ey[i]);
        shortest = std::min(shortest, length);
        longest = std::max(longest, length);
    }
    if (shortest < 1e-3f || shortest / longest < 0.35f) return false;
    bool positive = true, negative = true;
    for (int i = 0; i < 4; i++)
    {
        const int k = (i + 1) % 4;
        const float cross = ex[i] * ey[k] - ey[i] * ex[k];
        positive = positive && cross > 0;
        negative = negative && cross < 0;
    }
    return positive || negative;
}

// 都用性能核：相机服务也在占用能效核，实测取景只有约 22 帧。
// 亲和性设置要遍历工作线程，每帧都调用会明显拖慢取景，所以只设一次。
void useFastCores()
{
    static bool done = false;
    if (done) return;
    ncnn::set_cpu_powersave(2);
    ncnn::set_omp_dynamic(0);
    ncnn::set_omp_num_threads(kThreads);
    done = true;
}

ncnn::Mat toInput(const Image& img)
{
    static const float mean[3] = {127.5f, 127.5f, 127.5f};
    static const float norm[3] = {1 / 127.5f, 1 / 127.5f, 1 / 127.5f};
    ncnn::Mat in = ncnn::Mat::from_pixels(img.rgb.data(), ncnn::Mat::PIXEL_RGB, img.w, img.h);
    in.substract_mean_normalize(mean, norm);
    return in;
}

}  // namespace

Recognizer::Recognizer()
{
    corner_.opt.lightmode = true;
    corner_.opt.use_vulkan_compute = false;
    corner_.opt.num_threads = kThreads;
    stone_.opt.lightmode = true;
    stone_.opt.use_vulkan_compute = false;
    stone_.opt.num_threads = 1;
}

Detection Recognizer::detectOnce(const Image& img)
{
    const float scale = static_cast<float>(kCornerInput) / std::max(img.w, img.h);
    const int nw = std::max(1, static_cast<int>(std::lround(img.w * scale)));
    const int nh = std::max(1, static_cast<int>(std::lround(img.h * scale)));
    const Image small = resizeTo(img, nw, nh);
    Image canvas(kCornerInput, kCornerInput);
    std::fill(canvas.rgb.begin(), canvas.rgb.end(), 0);
    const int px = (kCornerInput - nw) / 2, py = (kCornerInput - nh) / 2;
    for (int y = 0; y < nh; y++)
        std::copy_n(small.rgb.data() + static_cast<size_t>(y) * nw * 3, static_cast<size_t>(nw) * 3,
                    canvas.rgb.data() + (static_cast<size_t>(y + py) * kCornerInput + px) * 3);

    return decodeCorners(toInput(canvas), scale, px, py);
}

Detection Recognizer::decodeCorners(const ncnn::Mat& input, float scale, int px, int py)
{
    ncnn::Extractor ex = corner_.create_extractor();
    ex.input("in0", input);
    ncnn::Mat out;
    Detection result;
    result.score = 0;
    result.plausible = false;
    std::fill(&result.quad.p[0][0], &result.quad.p[0][0] + 8, 0.f);
    if (ex.extract("out0", out) != 0) return result;

    // 5x5 非极大值抑制后取最高的 4 个峰，分数取其中最低的
    const ncnn::Mat heat = out.channel(0), offX = out.channel(1), offY = out.channel(2);
    const int w = out.w, h = out.h;
    std::vector<std::pair<float, int>> peaks;
    for (int y = 0; y < h; y++)
    {
        const float* row = heat.row(y);
        for (int x = 0; x < w; x++)
        {
            const float v = row[x];
            bool isPeak = true;
            for (int dy = -2; dy <= 2 && isPeak; dy++)
            {
                if (y + dy < 0 || y + dy >= h) continue;
                const float* other = heat.row(y + dy);
                for (int dx = -2; dx <= 2; dx++)
                {
                    if (x + dx < 0 || x + dx >= w) continue;
                    if (other[x + dx] > v)
                    {
                        isPeak = false;
                        break;
                    }
                }
            }
            if (isPeak) peaks.emplace_back(v, y * w + x);
        }
    }
    const size_t k = std::min<size_t>(4, peaks.size());
    if (k < 4) return result;
    std::partial_sort(peaks.begin(), peaks.begin() + k, peaks.end(), [](const auto& a, const auto& b) { return a.first > b.first; });
    float pts[4][2];
    for (size_t i = 0; i < 4; i++)
    {
        const int x = peaks[i].second % w, y = peaks[i].second / w;
        pts[i][0] = ((x + offX.row(y)[x]) * kStride - px) / scale;
        pts[i][1] = ((y + offY.row(y)[x]) * kStride - py) / scale;
    }
    std::copy(&pts[0][0], &pts[0][0] + 8, &result.quad.p[0][0]);
    result.score = 1.f / (1.f + std::exp(-peaks[3].first));
    result.plausible = quadIsPlausible(pts);
    return result;
}

Detection Recognizer::detectTwoPass(const Image& img)
{
    const Detection first = detectOnce(img);
    float loX = 1e30f, hiX = -1e30f, loY = 1e30f, hiY = -1e30f;
    for (const auto& point : first.quad.p)
    {
        loX = std::min(loX, point[0]);
        hiX = std::max(hiX, point[0]);
        loY = std::min(loY, point[1]);
        hiY = std::max(hiY, point[1]);
    }
    const float side = std::max(hiX - loX, hiY - loY);
    if (first.score < 0.05f || side < 8) return first;
    const float half = side * 0.62f, cx = (loX + hiX) / 2, cy = (loY + hiY) / 2;
    const int x0 = static_cast<int>(std::max(0.f, cx - half)), y0 = static_cast<int>(std::max(0.f, cy - half));
    const int x1 = static_cast<int>(std::min(static_cast<float>(img.w), cx + half)), y1 = static_cast<int>(std::min(static_cast<float>(img.h), cy + half));
    if (x1 - x0 < 8 || y1 - y0 < 8) return first;
    Image crop(x1 - x0, y1 - y0);
    for (int y = y0; y < y1; y++)
        std::copy_n(img.rgb.data() + (static_cast<size_t>(y) * img.w + x0) * 3, static_cast<size_t>(crop.w) * 3,
                    crop.rgb.data() + static_cast<size_t>(y - y0) * crop.w * 3);
    Detection second = detectOnce(crop);
    for (auto& point : second.quad.p)
    {
        point[0] += x0;
        point[1] += y0;
    }
    return second;
}

Detection Recognizer::detectStill(const Image& img)
{
    useFastCores();

    Detection best = detectTwoPass(img);
    if (best.plausible && best.score >= 0.5f) return best;

    // 网格角点紧贴图片边缘时（棋盘截图、裁好的图）网络容易失败，补边后角点离开边缘就能认出
    const int p = static_cast<int>(std::lround(std::max(img.w, img.h) * 0.08f));
    Image padded(img.w + 2 * p, img.h + 2 * p);
    std::fill(padded.rgb.begin(), padded.rgb.end(), 0);
    for (int y = 0; y < img.h; y++)
        std::copy_n(img.rgb.data() + static_cast<size_t>(y) * img.w * 3, static_cast<size_t>(img.w) * 3,
                    padded.rgb.data() + (static_cast<size_t>(y + p) * padded.w + p) * 3);
    Detection retry = detectTwoPass(padded);
    for (auto& point : retry.quad.p)
    {
        point[0] -= p;
        point[1] -= p;
    }
    if (retry.plausible > best.plausible || (retry.plausible == best.plausible && retry.score > best.score)) return retry;
    return best;
}

Detection Recognizer::detectLive(const Image& img)
{
    useFastCores();
    // 相机帧本来就小，缩放比例接近 1，用 ncnn 的 NEON 双线性比面积缩放快得多，结果也几乎相同
    const float scale = static_cast<float>(kCornerInput) / std::max(img.w, img.h);
    const int nw = std::max(1, static_cast<int>(std::lround(img.w * scale)));
    const int nh = std::max(1, static_cast<int>(std::lround(img.h * scale)));
    const int px = (kCornerInput - nw) / 2, py = (kCornerInput - nh) / 2;
    const ncnn::Mat resized = ncnn::Mat::from_pixels_resize(img.rgb.data(), ncnn::Mat::PIXEL_RGB, img.w, img.h, nw, nh);
    ncnn::Mat input;
    ncnn::copy_make_border(resized, input, py, kCornerInput - nh - py, px, kCornerInput - nw - px, ncnn::BORDER_CONSTANT, 0.f);
    static const float mean[3] = {127.5f, 127.5f, 127.5f};
    static const float norm[3] = {1 / 127.5f, 1 / 127.5f, 1 / 127.5f};
    input.substract_mean_normalize(mean, norm);
    Detection d = decodeCorners(input, scale, px, py);
    d.quad = orderClockwise(d.quad.p);
    return d;
}

Recognition Recognizer::recognize(const Image& img, const Quad* manualQuad)
{
    Recognition r;
    Quad quad;
    if (manualQuad)
    {
        quad = *manualQuad;
        r.manual = true;
        useFastCores();
    }
    else
    {
        const Detection d = detectStill(img);
        quad = orderClockwise(d.quad.p);
        r.cornerScore = d.score;
        if (d.score < kCornerScoreMin || !d.plausible) r.warnings.push_back("角点不可信，请手动裁切");
    }

    detectBoardSize(img, quad, r.n, r.sizeRatio);
    if (r.sizeRatio < kSizeRatioMin) r.warnings.push_back("路数不确定，请确认");

    const GridFit fit = refineGrid(img, quad, r.n);
    if (!fit.used) r.warnings.push_back("网格校正失败，请检查角点");
    r.inliers = fit.inliers;
    r.measured = fit.measured;

    // 棋盘下满时最外圈的线被棋子盖住，校正可能锁到相邻的线，整个网格错位不到一格。
    // 错位后图块落在棋子之间，分类器明显没把握，所以用平均把握在校正前后二选一。
    Classification chosen = classify(img, quad, r.n);
    r.quad = quad;
    r.gridUsed = false;
    if (fit.used)
    {
        Classification refined = classify(img, fit.quad, r.n);
        if (refined.meanLogConfidence >= chosen.meanLogConfidence)
        {
            chosen = std::move(refined);
            r.quad = fit.quad;
            r.gridUsed = true;
        }
    }
    r.state = std::move(chosen.state);
    r.minConfidence = chosen.minConfidence;
    return r;
}

Recognizer::Classification Recognizer::classify(const Image& img, const Quad& quad, int n)
{
    const Image rect = rectify(img, quad, n, kPitch, kPatch);
    const int cells = n * n;
    Classification result;
    result.state.assign(static_cast<size_t>(cells), 0);
    std::vector<float> confidence(static_cast<size_t>(cells), 1.f);
    std::vector<double> logTop(static_cast<size_t>(cells), 0.0);
    #pragma omp parallel for schedule(dynamic, 8) num_threads(kThreads)
    for (int k = 0; k < cells; k++)
    {
        const int i = k / n, j = k % n;
        const uint8_t* p = rect.rgb.data() + (static_cast<size_t>(i) * kPitch * rect.w + j * kPitch) * 3;
        static const float mean[3] = {127.5f, 127.5f, 127.5f};
        static const float norm[3] = {1 / 127.5f, 1 / 127.5f, 1 / 127.5f};
        ncnn::Mat in = ncnn::Mat::from_pixels(p, ncnn::Mat::PIXEL_RGB, kPatch, kPatch, rect.w * 3);
        in.substract_mean_normalize(mean, norm);
        ncnn::Extractor ex = stone_.create_extractor();
        ex.input("in0", in);
        ncnn::Mat out;
        if (ex.extract("out0", out) != 0) continue;
        double logits[3] = {out[0], out[1], out[2]};
        const double top = std::max(logits[0], std::max(logits[1], logits[2]));
        double sum = 0;
        double prob[3];
        for (int c = 0; c < 3; c++)
        {
            prob[c] = std::exp(logits[c] - top);
            sum += prob[c];
        }
        for (double& value : prob) value /= sum;
        // 黑加白的概率达到门槛才算有子
        int label = 0;
        if (prob[1] + prob[2] >= kStoneThreshold) label = prob[2] > prob[1] ? 2 : 1;
        result.state[k] = label;
        confidence[k] = static_cast<float>(prob[label]);
        logTop[k] = std::log(std::max(prob[0], std::max(prob[1], prob[2])) + 1e-6);
    }
    result.minConfidence = *std::min_element(confidence.begin(), confidence.end());
    result.meanLogConfidence = std::accumulate(logTop.begin(), logTop.end(), 0.0) / cells;
    return result;
}

std::string Recognition::toJson() const
{
    char buffer[256];
    std::string json = "{\"n\":" + std::to_string(n) + ",\"board\":[";
    for (int i = 0; i < n; i++)
    {
        json += i ? ",\"" : "\"";
        for (int j = 0; j < n; j++) json += ".BW"[state[static_cast<size_t>(i) * n + j]];
        json += "\"";
    }
    json += "],\"quad\":[";
    for (int k = 0; k < 4; k++)
    {
        snprintf(buffer, sizeof(buffer), "%s[%.2f,%.2f]", k ? "," : "", quad.p[k][0], quad.p[k][1]);
        json += buffer;
    }
    snprintf(buffer, sizeof(buffer), "],\"manual\":%s,\"cornerScore\":%.4f,\"sizeRatio\":%.3f,\"gridUsed\":%s,\"inliers\":%d,\"measured\":%d,\"minConfidence\":%.4f,\"warnings\":[",
             manual ? "true" : "false", cornerScore, sizeRatio, gridUsed ? "true" : "false", inliers, measured, minConfidence);
    json += buffer;
    for (size_t i = 0; i < warnings.size(); i++)
    {
        json += i ? ",\"" : "\"";
        json += warnings[i];
        json += "\"";
    }
    json += "]}";
    return json;
}

Image renderCrop(const Image& img, const Quad& quad, int n, int side)
{
    // 留半格边距：输出边长 side 对应 n 格（n-1 个格距加两个半格）
    const float cell = static_cast<float>(side) / n;
    const float a = cell / 2 - 0.5f, b = side - cell / 2 - 0.5f;
    const float src_side = meanSide(quad);
    Quad scaled = quad;
    const Image* source = &img;
    Image shrunk;
    if (src_side > side * 1.2f)
    {
        const float s = side / src_side;
        shrunk = resizeArea(img, std::max(1, static_cast<int>(std::lround(img.w * s))), std::max(1, static_cast<int>(std::lround(img.h * s))));
        for (auto& point : scaled.p)
        {
            point[0] *= s;
            point[1] *= s;
        }
        source = &shrunk;
    }
    Mat3 inverse = {{1, 0, 0, 0, 1, 0, 0, 0, 1}};
    invert(quadToSquare(scaled, a, b), inverse);
    return warp(*source, inverse, side, side, false);
}

}  // namespace recog
