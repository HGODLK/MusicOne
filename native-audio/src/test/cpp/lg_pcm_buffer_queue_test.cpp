#include "../../main/cpp/lg_pcm_buffer_queue.h"
#include <cassert>
#include <iostream>

using musicone::LgPcmBufferQueue;

static void capacityIsTwoHundredMs() {
    for (int rate : {44100, 48000, 96000, 192000}) {
        for (int frameBytes : {3, 6, 8}) {
            LgPcmBufferQueue queue(rate, frameBytes);
            std::vector<uint8_t> audio(rate * frameBytes);
            int calls = 0;
            auto submit = [&](const uint8_t *, size_t bytes) { ++calls; assert(bytes == queue.blockBytes()); return true; };
            const int accepted = queue.write(audio.data(), static_cast<int>(audio.size()), submit);
            assert(accepted == rate / 50 * frameBytes * 10);
            assert(calls == 10);
            assert(queue.write(audio.data(), frameBytes, submit) == 0);
            assert(queue.completedFrames() == 0);
            for (int i = 0; i < 10; ++i) assert(queue.consumed());
            assert(queue.completedFrames() == rate / 50 * 10);
            assert(!queue.hasPendingData());
            assert(!queue.consumed());
        }
    }
}

static void smallWritesMergeAndTailIsExact() {
    LgPcmBufferQueue queue(48000, 6);
    std::vector<uint8_t> audio(960 * 6 + 6);
    for (size_t i = 0; i < audio.size(); ++i) audio[i] = static_cast<uint8_t>(i);
    std::vector<uint8_t> captured;
    auto submit = [&](const uint8_t *data, size_t bytes) {
        captured.insert(captured.end(), data, data + bytes);
        return true;
    };
    for (size_t i = 0; i < audio.size(); i += 6) assert(queue.write(audio.data() + i, 6, submit) == 6);
    assert(captured.size() == 960 * 6);
    assert(queue.consumed());
    assert(queue.completedFrames() == 960);
    assert(queue.hardwareEmpty());
    assert(queue.hasPendingData());
    assert(queue.flushPartial(submit));
    assert(queue.flushPartial(submit));
    assert(captured == audio);
    assert(queue.consumed());
    assert(queue.completedFrames() == 961);
    assert(!queue.hasPendingData());
}

static void queuedPointersSurviveBackpressureAndRingWrap() {
    LgPcmBufferQueue queue(44100, 6);
    std::array<const uint8_t *, 10> pointers {};
    std::array<uint8_t, 10> values {};
    size_t head = 0, tail = 0;
    std::vector<uint8_t> block(queue.blockBytes());
    auto submit = [&](const uint8_t *data, size_t bytes) {
        assert(bytes == block.size());
        pointers[tail] = data;
        values[tail] = data[0];
        tail = (tail + 1) % 10;
        return true;
    };
    auto consume = [&] {
        for (size_t j = 0; j < block.size(); ++j) assert(pointers[head][j] == values[head]);
        assert(queue.consumed());
        head = (head + 1) % 10;
    };
    for (int i = 0; i < 1000; ++i) {
        std::fill(block.begin(), block.end(), static_cast<uint8_t>(i));
        if (i >= 10) consume();
        assert(queue.write(block.data(), static_cast<int>(block.size()), submit) == static_cast<int>(block.size()));
        if (i >= 9) assert(queue.write(block.data(), 6, submit) == 0);
    }
    for (int i = 0; i < 10; ++i) consume();
    assert(queue.completedFrames() == 882000);
    assert(!queue.hasPendingData());
}

static void fullQueueAbsorbsOneHundredTwentyMsOfProducerDelay() {
    LgPcmBufferQueue queue(48000, 6);
    std::vector<uint8_t> audio(9600 * 6);
    auto submit = [](const uint8_t *, size_t) { return true; };
    assert(queue.write(audio.data(), static_cast<int>(audio.size()), submit) == static_cast<int>(audio.size()));
    for (int i = 0; i < 6; ++i) assert(queue.consumed());
    assert(queue.hasPendingData());
    assert(queue.write(audio.data(), 5760 * 6, submit) == 5760 * 6);
    for (int i = 0; i < 10; ++i) assert(queue.consumed());
    assert(queue.completedFrames() == 15360);
}

static void failedSubmissionDoesNotAdvancePlayback() {
    LgPcmBufferQueue queue(48000, 6);
    std::vector<uint8_t> block(queue.blockBytes());
    assert(queue.write(block.data(), static_cast<int>(block.size()), [](const uint8_t *, size_t) { return false; }) == -1);
    assert(queue.completedFrames() == 0);
    assert(queue.hardwareEmpty());
}

int main() {
    capacityIsTwoHundredMs();
    smallWritesMergeAndTailIsExact();
    queuedPointersSurviveBackpressureAndRingWrap();
    fullQueueAbsorbsOneHundredTwentyMsOfProducerDelay();
    failedSubmissionDoesNotAdvancePlayback();
    std::cout << "LG PCM native queue: 5 scenarios passed\n";
}
