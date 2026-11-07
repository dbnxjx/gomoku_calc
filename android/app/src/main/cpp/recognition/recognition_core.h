// 棋盘识别的完整流程，算法和 RecognitionModel/pipeline/recognize.py 保持一致：
//   角点模型 -> 路数判断 -> 网格校正 -> 棋子分类
// 手动裁切时跳过第一步。这里不依赖 JNI，手机上的命令行校验程序也用同一份代码。
#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "net.h"

namespace recog {

// 紧密排列的 RGB 图
struct Image
{
    int w = 0;
    int h = 0;
    std::vector<uint8_t> rgb;

    Image() = default;
    Image(int width, int height) : w(width), h(height), rgb(static_cast<size_t>(width) * height * 3) {}
};

struct Quad
{
    float p[4][2];
};

struct Detection
{
    Quad quad;
    float score;
    bool plausible;
};

struct Recognition
{
    int n = 0;
    std::vector<int> state;  // n*n，0 空 1 黑 2 白；第 0 行第 0 列对应 quad 的第 0 个点
    Quad quad;
    bool manual = false;
    float cornerScore = 0;
    float sizeRatio = 0;
    bool gridUsed = false;  // 校正后的角点是否被采用；quad 是实际用来分类的角点
    int inliers = 0;
    int measured = 0;
    float minConfidence = 0;
    std::vector<std::string> warnings;

    std::string toJson() const;
};

class Recognizer
{
public:
    Recognizer();

    ncnn::Net& cornerNet() { return corner_; }
    ncnn::Net& stoneNet() { return stone_; }

    // 实时取景：单遍定位。角点已按顺时针排序，plausible 表示能构成棋盘。
    Detection detectLive(const Image& img);
    // 拍照后：两遍定位，结果可疑时补黑边重试。
    Detection detectStill(const Image& img);
    Recognition recognize(const Image& img, const Quad* manualQuad);

private:
    struct Classification
    {
        std::vector<int> state;
        float minConfidence = 0;
        double meanLogConfidence = 0;  // 每个交叉点最大类别概率的对数平均，越接近 0 越有把握
    };

    Classification classify(const Image& img, const Quad& quad, int n);
    Detection detectOnce(const Image& img);
    Detection detectTwoPass(const Image& img);
    Detection decodeCorners(const ncnn::Mat& input, float scale, int px, int py);

    ncnn::Net corner_;
    ncnn::Net stone_;
};

// 把棋盘拉正画到 dst：四周各留半格，方便用户确认裁切结果。
Image renderCrop(const Image& img, const Quad& quad, int n, int side);

}  // namespace recog
