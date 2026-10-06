#include "../../main/jni/usb-pcm-packet-plan.h"
#include "../../main/jni/usb-transfer-state.h"
#include <algorithm>
#include <cassert>
#include <cmath>
#include <iostream>
#include <vector>

struct PacketResult {
    std::vector<int> packets;
    int residual = 0;
    double phase = 0;
};

// 同一段音频无论按 MP3、FLAC 或零碎网络解码块输入，传输包序列都必须一致。
PacketResult packetize(int totalFrames, int bytesPerFrame, double rate,
                       const std::vector<int> &chunks) {
    PacketResult result;
    int consumedFrames = 0;
    unsigned chunk = 0;
    while (consumedFrames < totalFrames) {
        const int remaining = totalFrames - consumedFrames;
        const int frames = std::min(remaining, chunks[chunk++ % chunks.size()]);
        consumedFrames += frames;
        result.residual += frames * bytesPerFrame;
        for (;;) {
            int packets[8];
            double nextPhase = result.phase;
            const int bytes = planUsbPcmPackets(result.residual, bytesPerFrame, rate / 8000.0,
                                               8, result.phase, packets, nextPhase);
            if (bytes == 0) break;
            result.packets.insert(result.packets.end(), packets, packets + 8);
            result.residual -= bytes;
            result.phase = nextPhase;
        }
    }
    int submitted = 0;
    for (int bytes : result.packets) {
        assert(bytes % bytesPerFrame == 0);
        submitted += bytes;
    }
    assert(submitted + result.residual == totalFrames * bytesPerFrame);
    return result;
}

int main() {
    for (double rate : {44100.0, 44101.6, 48000.0, 96000.0, 192000.0, 384000.0}) {
        for (int bytesPerFrame : {2, 4, 6, 8}) {
            const int frames = static_cast<int>(rate) * 3 + 17;
            const auto baseline = packetize(frames, bytesPerFrame, rate, {frames});
            for (const auto &chunks : std::vector<std::vector<int>>{
                    {1152}, {576}, {4096}, {4608}, {1, 5, 44, 1152, 4096, 17}}) {
                const auto split = packetize(frames, bytesPerFrame, rate, chunks);
                assert(split.packets == baseline.packets);
                assert(split.residual == baseline.residual);
                assert(std::abs(split.phase - baseline.phase) < 1e-12);
            }
        }
    }
    int packets[8];
    double phase = .25;
    assert(planUsbPcmPackets(20, 4, 5.5125, 8, phase, packets, phase) == 0);
    assert(phase == .25);
    // 不足一个常规批次的尾音必须全部提交，且不添加静音字节。
    for (double rate : {44100.0, 48000.0, 96000.0, 192000.0, 384000.0}) {
        for (int bytesPerFrame : {2, 4, 6, 8}) {
            for (int frames = 1; frames < static_cast<int>(rate / 1000); ++frames) {
                double tailPhase = .25;
                const int count = planUsbPcmTail(frames * bytesPerFrame, bytesPerFrame,
                    rate / 8000.0, 8, tailPhase, packets);
                assert(count > 0 && count <= 8);
                int bytes = 0;
                for (int i = 0; i < count; ++i) {
                    assert(packets[i] > 0 && packets[i] % bytesPerFrame == 0);
                    bytes += packets[i];
                }
                assert(bytes == frames * bytesPerFrame);
            }
        }
    }
    assert(planUsbPcmTail(3, 4, 6, 8, phase, packets) == 0);
    UsbTransferState<3> transfers;
    transfers.submitted(0, 192, 4);
    transfers.submitted(1, 192, 4);
    assert(!transfers.reset());
    assert(transfers.complete(1, 192, 4, false));
    assert(transfers.pending == 1 && transfers.completedFrames == 48);
    assert(!transfers.complete(1, 192, 4, false));
    assert(transfers.complete(0, 192, 4, true));
    assert(transfers.completedFrames == 48 && transfers.pending == 0);
    assert(transfers.reset() && transfers.completedFrames == 0);
    std::cout << "USB packetization: 120 chunk/rate/layout comparisons, tail and transfer lifecycle checks passed\n";
}
