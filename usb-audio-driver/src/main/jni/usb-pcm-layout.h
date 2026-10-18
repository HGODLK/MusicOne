#pragma once

#include <cstdint>

// Media3 整数 PCM 与 FFmpeg S32 都按容器最高有效位对齐，低位是填充位。
// 先移除输入填充，再按 USB 子槽补零；不能把完整 S32 当作右对齐的 24 位数再次放大。
inline bool convertUsbIntegerPcm(const uint8_t *input, uint8_t *output, int samples,
                                int inputBits, int validBits, int targetBits, int subslotBytes) {
    if ((inputBits != 16 && inputBits != 24 && inputBits != 32) ||
        (validBits != 16 && validBits != 24 && validBits != 32) ||
        validBits > inputBits || validBits > targetBits || targetBits > subslotBytes * 8 ||
        subslotBytes < 2 || subslotBytes > 4 || samples < 0) return false;
    const int inputBytes = inputBits / 8;
    const int inputPadding = inputBits - validBits;
    const int outputPadding = subslotBytes * 8 - validBits;
    for (int i = 0; i < samples; ++i) {
        int64_t sample = 0;
        for (int byte = 0; byte < inputBytes; ++byte) {
            sample |= static_cast<int64_t>(input[i * inputBytes + byte]) << (byte * 8);
        }
        if (sample & (1LL << (inputBits - 1))) sample -= 1LL << inputBits;
        const int64_t aligned = (sample >> inputPadding) * (1LL << outputPadding);
        for (int byte = 0; byte < subslotBytes; ++byte) {
            output[i * subslotBytes + byte] = static_cast<uint8_t>((aligned >> (byte * 8)) & 0xFF);
        }
    }
    return true;
}
