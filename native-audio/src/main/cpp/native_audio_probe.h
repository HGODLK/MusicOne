#ifndef NATIVE_AUDIO_PROBE_H
#define NATIVE_AUDIO_PROBE_H

#include <string>
#include <vector>
#include <cstdint>

struct ProbeResult {
    bool success{false};
    std::string errorMessage;

    std::string formatName;      // "APE", "ALAC", "WavPack", "DSD64", "DSD128", "DSD256" 等
    std::string containerName;   // "ape", "mov,mp4", "wv", "dsf", "dff"
    std::string codecName;

    int64_t durationMs{0};
    int sampleRate{0};
    int channelCount{0};
    int sourceBitDepth{16};      // 有效位深：16, 24, 32；DSD 则为 1
    int dsdRateMultiplier{0};    // DSD64: 64, DSD128: 128, 非 DSD 为 0
    bool isDsd{false};
    bool isWavPackHybrid{false};

    std::string title;
    std::string artist;
    std::string album;

    std::vector<uint8_t> coverData; // 内嵌封面图片字节（JPEG / PNG）
    std::string coverMime;
};

class NativeAudioProbe {
public:
    static ProbeResult probe(class NativeAudioIoBridge *ioBridge);
};

#endif // NATIVE_AUDIO_PROBE_H
