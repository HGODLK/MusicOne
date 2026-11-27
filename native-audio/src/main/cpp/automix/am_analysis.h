// 自然过渡引擎：单曲分析（对应 experiment/features.py、beats.py、analysis.py 中决策用到的部分）。
#pragma once

#include <array>
#include <string>
#include <vector>

#include "am_dsp.h"

namespace automix {

/** pyloudnorm.Meter(sr).integrated_loudness（BS.1770-4 门限）。 */
double integratedLoudness(const Stereo &x, double sr);
double integratedLoudness(const Stereo &x, long i0, long i1, double sr);

/** 帧级特征（FrameFeatures，只保留决策需要的量）。时间为片段内相对秒。 */
struct Features {
    double sr = 0, fr = 0;
    int nframes = 0;
    vecd times, onset, onsetLow, momentary, shortTerm;
    std::vector<std::array<double, 12>> chroma;
    vecd chromaTimes;
};
Features frameFeatures(const Stereo &x, double sr);

struct Analysis {
    double offset = 0, duration = 0;
    Features ff;
    double bpm = 0, tempoStability = 1, gridResidMs = 0;
    vecd beats;               // 原曲绝对秒
    std::vector<int> bars;    // 小节首拍在 beats 中的序号
    int downbeatPhase = 0;
    double effStart = 0, effEnd = 0, bodyLufs = -20;
    bool hasLastHit = false;
    double lastHit = 0;
    std::string key, camelot;
    std::vector<std::string> notes;
    double barTime(int i) const { return beats[bars[i]]; }
};
Analysis analyze(const Stereo &seg, double sr, double offset, double duration);

// beats.py
struct TempoEstimate { double bpm = 0, conf = 0, stability = 1; };
TempoEstimate estimateTempo(const vecd &o, double fr);
vecd trackBeats(const vecd &o, double fr, double bpm, double tightness = 100.0);
struct Grid { double t0, period, residMs; };
Grid refineGrid(const vecd &o, double fr, double p0, const vecd &beatFrames, double spanPct = 0.015);

}  // namespace automix
