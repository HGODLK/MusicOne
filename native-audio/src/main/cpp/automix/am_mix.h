// 自然过渡引擎：完整决策与渲染（对应 experiment/vox_mix.py main()）。
#pragma once

#include <string>
#include <vector>

#include "am_parts.h"

namespace automix {

constexpr int WORK_SR = 44100;
constexpr double WINDOW_S = 75.0;

enum class MixKind { None = 0, BeatMatch = 1, DrumCut = 2, EchoOut = 3, Fade = 4 };

struct MixInput {
    Track a, b;                      // 已重采样到 WORK_SR
    std::vector<float> embA, embB;   // YAMNet 嵌入（A 最后 75 秒、B 前 75 秒），已按 float16 舍入
    int framesA = 0, framesB = 0;
    const VoxHead *head = nullptr;
};

struct MixOptions {
    bool render = true;
    /** true：完全按实验代码渲染（响度归一化、片段长度），用于对照；false：播放接入用（见 am_mix.cpp 说明）。 */
    bool faithful = false;
};

/** 过渡结果。所有时间单位为秒；“片段时间”从片段第一个样本起算。 */
struct MixResult {
    MixKind kind = MixKind::None;
    std::vector<std::string> log;       // 与实验代码逐行一致的决策日志
    std::string branch;                 // 便于对照的分支名
    // —— 关键决策 ——
    double ta = 0, tb = 0, aVend = NAN, bVstart = NAN;
    double seamA = NAN;                 // 交接下拍（A 原曲秒）
    double bCue = NAN;                  // B 切入 / 首个下拍（B 原曲秒）
    int overlapBars = -1;
    double rateA = NAN, rateB = NAN;    // B 进场时的变速倍率
    double clarityA = NAN, clarityB = NAN;
    // —— 片段 ——
    Stereo clip;                        // WORK_SR 立体声
    double clipStartA = 0;              // 片段 0 处对应 A 原曲秒
    double enter = 0, seam = 0, fromEnd = 0, total = 0;
    /** 片段内保持 A 原样（未变速、未处理）的末尾时刻，在此之前可以从实时 A 无缝切进片段。 */
    double aDryUntil = 0;
    /** 片段末尾 B 已回到原速、原音量，此后可无缝交回实时 B；bEndSrc 为该时刻 B 原曲秒（未搭桥的时间轴）。 */
    double bEndSrc = 0;
    /** 每 10ms 一个点：A / B 原曲位置（秒，B 搭桥部分已折回原时间轴）、视觉进度。 */
    vecd mapA, mapB, visual;
};

MixResult automix(MixInput &in, const MixOptions &opt);

}  // namespace automix
