#pragma once

#include <array>
#include <cstdint>

// 按实际回收的槽位记账，取消和失败的请求不计入播放进度。
template <int Capacity>
struct UsbTransferState {
    std::array<int, Capacity> bytes{};
    int pending = 0;
    int64_t submittedFrames = 0;
    int64_t completedFrames = 0;

    int freeSlot() const {
        for (int i = 0; i < Capacity; ++i) if (bytes[i] == 0) return i;
        return -1;
    }
    void submitted(int slot, int length, int bytesPerFrame) {
        bytes[slot] = length;
        ++pending;
        submittedFrames += length / bytesPerFrame;
    }
    bool complete(int slot, int successfulBytes, int bytesPerFrame, bool cancelled) {
        if (slot < 0 || slot >= Capacity || bytes[slot] == 0) return false;
        if (!cancelled) completedFrames += successfulBytes / bytesPerFrame;
        bytes[slot] = 0;
        --pending;
        return true;
    }
    bool reset() {
        if (pending != 0) return false;
        submittedFrames = completedFrames = 0;
        return true;
    }
};
