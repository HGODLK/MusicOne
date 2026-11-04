#pragma once

#include <algorithm>
#include <array>
#include <cstdint>
#include <cstring>
#include <vector>

namespace musicone {
/** 固定十块，每块约 20ms；外部同一把锁保护写入与消费，回调中不分配或释放内存。 */
class LgPcmBufferQueue {
public:
    static constexpr size_t kSlots = 10;

    LgPcmBufferQueue(int rate, int frameBytes)
        : frameBytes_(frameBytes), blockBytes_(std::max(rate / 50, 1) * frameBytes),
          storage_(kSlots * blockBytes_) {}

    template<typename Submit>
    int write(const uint8_t *data, int bytes, Submit submit) {
        if (bytes <= 0 || bytes % frameBytes_ != 0) return -1;
        int accepted = 0;
        while (accepted < bytes && count_ < kSlots) {
            const auto take = std::min(static_cast<size_t>(bytes - accepted), blockBytes_ - staged_);
            std::memcpy(storage_.data() + tail_ * blockBytes_ + staged_, data + accepted, take);
            staged_ += take;
            accepted += static_cast<int>(take);
            if (staged_ == blockBytes_ && !publish(submit)) return -1;
        }
        return accepted;
    }

    template<typename Submit>
    bool flushPartial(Submit submit) { return staged_ == 0 || publish(submit); }

    bool consumed() {
        if (count_ == 0) return false;
        completedFrames_ += lengths_[head_] / frameBytes_;
        head_ = (head_ + 1) % kSlots;
        --count_;
        return true;
    }

    bool hasPendingData() const { return count_ != 0 || staged_ != 0; }
    bool hardwareEmpty() const { return count_ == 0; }
    int64_t completedFrames() const { return completedFrames_; }
    size_t blockBytes() const { return blockBytes_; }

private:
    template<typename Submit>
    bool publish(Submit submit) {
        if (!submit(storage_.data() + tail_ * blockBytes_, staged_)) return false;
        lengths_[tail_] = staged_;
        tail_ = (tail_ + 1) % kSlots;
        ++count_;
        staged_ = 0;
        return true;
    }

    const int frameBytes_;
    const size_t blockBytes_;
    std::vector<uint8_t> storage_;
    std::array<size_t, kSlots> lengths_ {};
    size_t head_ = 0, tail_ = 0, count_ = 0, staged_ = 0;
    int64_t completedFrames_ = 0;
};
}
