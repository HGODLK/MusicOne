#pragma once

extern "C" {
#include <libswresample/swresample.h>
#include <libavutil/opt.h>
}

// DSD 和 USB 兼容转换共用高精度滤波；不额外放大电平或主动减少有效位。
inline bool configureNativeAudioResampling(SwrContext *context) {
    return av_opt_set_sample_fmt(context, "internal_sample_fmt", AV_SAMPLE_FMT_DBLP, 0) >= 0 &&
        av_opt_set_int(context, "filter_type", SWR_FILTER_TYPE_KAISER, 0) >= 0 &&
        av_opt_set_int(context, "filter_size", 128, 0) >= 0 &&
        av_opt_set_double(context, "kaiser_beta", 12.0, 0) >= 0 &&
        av_opt_set_int(context, "exact_rational", 1, 0) >= 0 &&
        av_opt_set_int(context, "phase_shift", 10, 0) >= 0;
}
