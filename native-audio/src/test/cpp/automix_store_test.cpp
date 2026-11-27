// 自然过渡原生存储 PcmStore：分块重采样与整段一次重采样逐样本一致、与送入块大小无关，尾段/开头的保留长度与帧号正确。
#include "../../main/cpp/automix/am_mix.h"
#include "../../main/cpp/automix/am_store.h"

#include <algorithm>
#include <cassert>
#include <cmath>
#include <cstdio>
#include <numeric>
#include <vector>

using namespace automix;

namespace {

/** 生成 seconds 秒立体声交错信号：两个正弦加确定性噪声，左右不同，便于发现声道错位。 */
std::vector<float> signal(int rate, double seconds) {
    long n = (long)(rate * seconds);
    std::vector<float> x((size_t)n * 2);
    unsigned seed = 12345;
    for (long i = 0; i < n; ++i) {
        seed = seed * 1103515245u + 12345u;
        float noise = ((seed >> 16) & 0x7fff) / 32768.f - 0.5f;
        double t = (double)i / rate;
        x[2 * i] = (float)(0.4 * std::sin(2 * 3.14159265358979323846 * 440 * t) + 0.1 * noise);
        x[2 * i + 1] = (float)(0.3 * std::sin(2 * 3.14159265358979323846 * 1234 * t) - 0.1 * noise);
    }
    return x;
}

/** 按给定块长（循环使用）依次送入，结束后取出。 */
Stereo run(PcmStore &store, const std::vector<float> &x, const std::vector<long> &chunks, bool stopWhenHead = false) {
    long frames = (long)x.size() / 2, at = 0;
    size_t k = 0;
    while (at < frames) {
        long n = std::min(chunks[k++ % chunks.size()], frames - at);
        store.push(x.data() + at * 2, n);
        at += n;
        if (stopWhenHead && store.headComplete()) break;
    }
    store.finish();
    return store.slice(0, store.frames());
}

bool same(const Stereo &a, const Stereo &b) {
    if (a.n() != b.n()) return false;
    for (size_t i = 0; i < a.n(); ++i) if (a.L[i] != b.L[i] || a.R[i] != b.R[i]) return false;
    return true;
}

double maxDiff(const Stereo &a, const Stereo &whole, long offset) {
    double d = 0;
    for (size_t i = 0; i < a.n(); ++i) {
        d = std::max(d, (double)std::fabs(a.L[i] - whole.L[offset + i]));
        d = std::max(d, (double)std::fabs(a.R[i] - whole.R[offset + i]));
    }
    return d;
}

Stereo toStereo(const std::vector<float> &x) {
    Stereo s;
    s.resize(x.size() / 2);
    for (size_t i = 0; i < s.n(); ++i) { s.L[i] = x[2 * i]; s.R[i] = x[2 * i + 1]; }
    return s;
}

void tailMatchesWholeResampleAndIsChunkIndependent() {
    for (int rate : {48000, 96000, 32000}) {
        auto x = signal(rate, 30.0);
        long nativeFrames = (long)x.size() / 2;
        PcmStore one(rate, true, 10.0), small(rate, true, 10.0), odd(rate, true, 10.0);
        Stereo a = run(one, x, {nativeFrames});
        Stereo b = run(small, x, {1152});
        Stereo c = run(odd, x, {1, 4096, 777, 100000, 3});
        assert(same(a, b) && same(a, c));
        // 只保留最后 seconds + 2 秒，且尾端正好是整首末尾
        assert(one.frames() == (long)(10.0 * WORK_SR) + 2L * WORK_SR);
        assert(one.offset() + one.frames() == one.totalOut(nativeFrames));
        // 与整段一次重采样逐样本一致（分块只是实现细节）
        long g = std::gcd((long)rate, (long)WORK_SR);
        Stereo whole = resamplePoly(toStereo(x), (int)(WORK_SR / g), (int)(rate / g));
        assert((long)whole.n() == one.totalOut(nativeFrames));
        double d = maxDiff(a, whole, one.offset());
        if (d > 1e-6) { std::printf("tail rate=%d maxDiff=%g\n", rate, d); assert(false); }
    }
}

void headStopsOnceEnoughIsDecoded() {
    auto x = signal(48000, 30.0);
    PcmStore store(48000, false, 10.0);
    long expect = (long)(10.0 * WORK_SR) + 2L * WORK_SR;
    long frames = (long)x.size() / 2, at = 0;
    while (at < frames && !store.headComplete()) {
        long n = std::min(4096L, frames - at);
        store.push(x.data() + at * 2, n);
        at += n;
    }
    assert(store.headComplete());
    assert(at < frames);  // 不必解完整首
    store.finish();
    assert(store.offset() == 0);
    assert(store.frames() >= expect);
    Stereo whole = resamplePoly(toStereo(x), 147, 160);
    Stereo head = store.slice(0, expect);
    double d = maxDiff(head, whole, 0);
    if (d > 1e-6) { std::printf("head maxDiff=%g\n", d); assert(false); }

    PcmStore again(48000, false, 10.0);
    Stereo other = run(again, x, {333, 9000}, true);
    assert(same(other.slice(0, expect), head));
}

void workRateIsStoredUnchanged() {
    auto x = signal(WORK_SR, 20.0);
    PcmStore store(WORK_SR, true, 5.0);
    Stereo s = run(store, x, {1000, 5000});
    long n = (long)x.size() / 2;
    assert(store.offset() + store.frames() == n);
    for (long i = 0; i < store.frames(); ++i) {
        long k = store.offset() + i;
        assert(s.L[i] == x[2 * k] && s.R[i] == x[2 * k + 1]);
    }
}

void shortTrackKeepsEverything() {
    auto x = signal(48000, 3.0);
    PcmStore store(48000, true, 10.0);
    run(store, x, {4096});
    assert(store.offset() == 0);
    assert(store.frames() == store.totalOut((long)x.size() / 2));
}

}  // namespace

int main() {
    tailMatchesWholeResampleAndIsChunkIndependent();
    headStopsOnceEnoughIsDecoded();
    workRateIsStoredUnchanged();
    shortTrackKeepsEverything();
    std::puts("automix_store_test: OK");
    return 0;
}
