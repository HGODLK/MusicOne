// 自然过渡引擎：决策用到的局部测量（对应 rhythm.py、punch.py、keycheck.py、bridge.py、voxgap.py）。
#pragma once

#include <optional>
#include <string>
#include <utility>
#include <vector>

#include "am_analysis.h"
#include "am_dsp.h"

namespace automix {

/**
 * 一首歌的音频：只保存决策与渲染需要的一段（A 的尾段、B 的头段），按原曲绝对样本号访问。
 * 对应实验代码里整首的 xa / xb；切片语义与 numpy 相同（越过整首两端截断）。
 */
struct Track {
    Stereo x;
    long offset = 0;   // x[0] 在原曲中的样本号
    long total = 0;    // 原曲总样本数
    double sr = 44100;
    Stereo slice(long i0, long i1) const;
    vecd monoSlice(long i0, long i1) const;
    long clampIndex(long i) const { return std::max(0L, std::min(i, total)); }
};

// rhythm.py
/** includeStart / includeEnd：恰好落在窗口端点（1 微秒内）的拍点是否计入。 */
double rhythmRepeat(const Track &x, const vecd &beats, double t0, double t1, bool includeStart, bool includeEnd);
struct DrumCheck { bool absent; double rel; };
DrumCheck drumsAbsent(const Track &x, double tEntry, double bar, double refT0, double refT1, double dropDb = 8.0);

// punch.py
Stereo kickPart(const Stereo &x, double sr, double cutoff = 250.0);
double punchDb(const Stereo &low, const Stereo &kick);
constexpr double PUNCH_MAX_GAIN = 2.0;

// keycheck.py
std::vector<std::pair<std::string, double>> localKeys(const Track &x, double t0, double t1);
bool camelotClose(const std::string &a, const std::string &b);

// voxgap.py：嵌入由 YAMNet 给出（每 0.5 秒一帧，1024 维），这里只做分类头与区间合并
struct VoxHead {
    int dim = 1024, context = 1;
    std::vector<float> mu, sd, W;
    float b = 0;
    bool load(const std::string &path);
    bool loadMemory(const void *data, size_t size);
};
struct VoxResult {
    vecd t, p;
    std::vector<std::pair<double, double>> runs;
};
/** embeddings：frames × dim（已按 float16 舍入）；frameStart：每帧 16kHz 样本起点。 */
VoxResult voxDetect(const VoxHead &head, const std::vector<float> &embeddings, int frames, double offset);
/** 人声检测前的 16kHz 单声道（resample_poly 到 16000，再转 float32）。 */
std::vector<float> voxInput16k(const Stereo &seg, int sr);
constexpr int YAMNET_WIN = 15600;
constexpr int YAMNET_HOP = 8000;

// bridge.py
struct Loop { double S, E; int bars; double score; };
std::optional<Loop> findLoop(const Track &x, const vecd &bnd, double tLo, double tHi);
/** 把 [S,E) 额外重复 extra 遍插在 E 处；返回接缝时刻（新时间轴秒）。 */
std::vector<double> splice(Track &x, double S, double E, int extra);
double lastDrumDownbeat(const Track &xa, const Analysis &A, double lastDb, int maxBackBars = 8);

}  // namespace automix
