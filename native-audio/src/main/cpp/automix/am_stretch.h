// 自然过渡引擎：按时间映射流式变速（对应 experiment/apple_mix.py stretch_by_map 与 stretch_map.cpp）。
#pragma once

#include <functional>

#include "am_parts.h"

namespace automix {

/**
 * 把 X 按 srcOfO（输出秒 → 原曲秒）变速，返回 o ∈ [oStart, oStop) 的音频（长度 round((oStop-oStart)·sr)）。
 * 与实验代码一致：每 50ms 一个映射点、输入位置保留三位小数、Signalsmith Stretch 默认预设、种子 42。
 */
Stereo stretchByMap(const Track &X, double oStart, double oStop, const std::function<double(double)> &srcOfO);

}  // namespace automix
