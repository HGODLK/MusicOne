#include "am_stretch.h"

#include <algorithm>
#include <cstdio>
#include <cstdlib>

#include "third_party/signalsmith-stretch/signalsmith-stretch.h"

namespace automix {

namespace {
/** 带偏移的平面缓冲；越界读写为零（同 stretch_map.cpp 的 Planar）。 */
struct Planar {
    std::vector<std::vector<float>> ch;
    long offset = 0;
    struct Chan {
        std::vector<float> *v;
        long off;
        float &operator[](long i) {
            static thread_local float zero;
            long j = i + off;
            if (j < 0 || j >= (long)v->size()) { zero = 0; return zero; }
            return (*v)[j];
        }
    };
    Chan operator[](int c) { return Chan{&ch[c], offset}; }
};
}  // namespace

Stereo stretchByMap(const Track &X, double oStart, double oStop, const std::function<double(double)> &srcOfO) {
    double sr = X.sr;
    double s0 = srcOfO(oStart);
    long i0 = std::max(0L, (long)nround(s0 * sr));
    long i1 = std::min(X.total, (long)std::ceil(srcOfO(oStop) * sr) + 2 * (long)sr);
    // 映射表：每 50ms 一行（np.arange 浮点语义），输入位置按 "%.3f" 舍入
    std::vector<double> mo, mi;
    long count = (long)std::ceil((oStop + 0.05 - oStart) / 0.05);
    for (long k = 0; k < count; ++k) {
        double u = oStart + 0.05 * k;
        double a = (double)(long)nround((u - oStart) * sr);
        char buf[64];
        std::snprintf(buf, sizeof buf, "%.3f", srcOfO(u) * sr - (double)i0);
        mo.push_back(a);
        mi.push_back(std::strtod(buf, nullptr));
    }
    long n = (long)nround((oStop - oStart) * sr);
    Stereo out;
    out.resize((size_t)std::max(0L, n));
    if (mo.size() < 2 || i1 <= i0) return out;
    Stereo input = X.slice(i0, i1);
    Planar in;
    in.ch = {std::vector<float>(input.L.begin(), input.L.end()), std::vector<float>(input.R.begin(), input.R.end())};
    auto map = [&](double o) {
        size_t m = mo.size();
        if (o <= mo[0]) return mi[0] + (o - mo[0]) * (mi[1] - mi[0]) / (mo[1] - mo[0]);
        for (size_t k = 1; k < m; ++k)
            if (o <= mo[k]) return mi[k - 1] + (o - mo[k - 1]) * (mi[k] - mi[k - 1]) / (mo[k] - mo[k - 1]);
        return mi[m - 1] + (o - mo[m - 1]) * (mi[m - 1] - mi[m - 2]) / (mo[m - 1] - mo[m - 2]);
    };
    long outLen = (long)mo.back();
    signalsmith::stretch::SignalsmithStretch<float> stretch(42);
    stretch.presetDefault(2, (float)sr);
    double r0 = (mi[1] - mi[0]) / (mo[1] - mo[0]);
    long supplied = stretch.outputSeekLength((float)r0);
    in.offset = (long)mi[0];
    stretch.outputSeek(in, (int)supplied);
    Planar res;
    res.ch.assign(2, std::vector<float>(std::max(0L, outLen)));
    const long block = 256;
    for (long o = 0; o < outLen; o += block) {
        long nb = std::min(block, outLen - o);
        long target = (long)std::llround(map((double)(o + nb + stretch.outputLatency())) - mi[0]) + stretch.inputLatency();
        long cnt = std::max(0L, target - supplied);
        in.offset = (long)mi[0] + supplied;
        res.offset = o;
        stretch.process(in, (int)cnt, res, (int)nb);
        supplied += cnt;
    }
    long m = std::min(n, outLen);
    for (long i = 0; i < m; ++i) { out.L[i] = res.ch[0][i]; out.R[i] = res.ch[1][i]; }
    return out;
}

}  // namespace automix
