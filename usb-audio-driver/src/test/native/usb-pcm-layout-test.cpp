#include "../../main/jni/usb-pcm-layout.h"
#include <cassert>
#include <cstdio>
#include <vector>

static std::vector<uint8_t> pack(const std::vector<int64_t> &values, int bits, int containerBytes) {
    std::vector<uint8_t> bytes;
    for (int64_t value : values) {
        const int64_t aligned = value * (1LL << (containerBytes * 8 - bits));
        for (int b = 0; b < containerBytes; ++b) bytes.push_back((aligned >> (b * 8)) & 0xFF);
    }
    return bytes;
}

int main() {
    int cases = 0;
    for (int validBits : {16, 24, 32}) {
        const int64_t half = 1LL << (validBits - 1);
        std::vector<int64_t> samples = {0, 1, -1, half - 1, -half, half / 2, -half / 2,
            half / 3, -half / 3};
        uint32_t random = 12345;
        for (int i = 0; i < 1000; ++i) {
            random = random * 1664525u + 1013904223u;
            samples.push_back(static_cast<int64_t>(random % (half * 2)) - half);
        }
        for (int inputBits : {16, 24, 32}) {
            if (inputBits < validBits) continue;
            const auto input = pack(samples, validBits, inputBits / 8);
            for (int targetBits : {16, 24, 32}) {
                if (targetBits < validBits) continue;
                for (int subslot : {2, 3, 4}) {
                    if (subslot * 8 < targetBits) continue;
                    std::vector<uint8_t> actual(samples.size() * subslot);
                    assert(convertUsbIntegerPcm(input.data(), actual.data(), samples.size(),
                        inputBits, validBits, targetBits, subslot));
                    assert(actual == pack(samples, validBits, subslot));
                    ++cases;
                }
            }
        }
    }
    // DSD 的 S32 可能含低八位滤波结果；输出 24 位时保留高位，不能截断高八位。
    const uint8_t dsd[] = {0xEF, 0x56, 0x34, 0x12, 0xAB, 0xA9, 0xCB, 0xED};
    uint8_t packed[6] = {};
    assert(convertUsbIntegerPcm(dsd, packed, 2, 32, 24, 24, 3));
    const std::vector<uint8_t> expected = {0x56, 0x34, 0x12, 0xA9, 0xCB, 0xED};
    assert(std::vector<uint8_t>(packed, packed + 6) == expected);
    uint8_t padded[8] = {};
    assert(convertUsbIntegerPcm(dsd, padded, 2, 32, 24, 24, 4));
    const std::vector<uint8_t> expectedPadded = {0, 0x56, 0x34, 0x12, 0, 0xA9, 0xCB, 0xED};
    assert(std::vector<uint8_t>(padded, padded + 8) == expectedPadded);
    assert(!convertUsbIntegerPcm(dsd, packed, 2, 32, 32, 24, 3));
    assert(!convertUsbIntegerPcm(dsd, packed, 2, 16, 24, 24, 3));
    assert(!convertUsbIntegerPcm(dsd, packed, 2, 32, 24, 24, 2));
    std::printf("USB PCM: %d layouts, boundary/random samples and DSD packing passed\n", cases);
}
