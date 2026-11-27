// 自然过渡混音引擎冒烟测试：合成鼓点信号跑完整决策与渲染，检查时间表顺序、片段长度、映射表与样本有限；静音输入不崩溃、不产生过渡。
// 用法：automix_mix_smoke_test <MusicOneVoxGap_head.bin 路径>
#include "../../main/cpp/automix/am_mix.h"

#include <cassert>
#include <cmath>
#include <cstdio>
#include <vector>

using namespace automix;

namespace {

constexpr double PI = 3.14159265358979323846;
constexpr long KEEP = (long)(80.0 * WORK_SR) + 2L * WORK_SR;

/** 合成一首 seconds 秒的歌：四拍底鼓、二四拍军鼓噪声、每小节换根音的贝斯与一个中频长音。 */
Stereo song(double seconds, double bpm, double rootHz, unsigned seed) {
    long n = (long)(seconds * WORK_SR);
    Stereo x;
    x.resize((size_t)n);
    double beat = 60.0 / bpm;
    for (long i = 0; i < n; ++i) {
        seed = seed * 1103515245u + 12345u;
        double noise = ((seed >> 16) & 0x7fff) / 16384.0 - 1.0;
        double t = (double)i / WORK_SR;
        long k = (long)(t / beat);
        double tb = t - k * beat;
        double kick = 0.7 * std::sin(2 * PI * 55 * tb) * std::exp(-tb / 0.12);
        double snare = (k % 2 == 1) ? 0.25 * noise * std::exp(-tb / 0.06) : 0.0;
        double root = rootHz * ((k / 4) % 2 == 0 ? 1.0 : 1.335);
        double bass = 0.2 * std::sin(2 * PI * root * t);
        double pad = 0.08 * std::sin(2 * PI * root * 4 * t);
        x.L[i] = (float)(kick + snare + bass + pad);
        x.R[i] = (float)(kick + 0.8 * snare + bass - pad);
    }
    return x;
}

int yamnetFrames(long workFrames) {
    long len16 = (long)((double)workFrames * 16000 / WORK_SR);
    return len16 < YAMNET_WIN ? 0 : (int)((len16 - YAMNET_WIN) / YAMNET_HOP + 1);
}

MixResult mix(const Stereo &a, const Stereo &b, const VoxHead &head) {
    MixInput in;
    long aTotal = (long)a.n();
    in.a.offset = std::max(0L, aTotal - KEEP);
    in.a.x = a.slice(in.a.offset, aTotal);
    in.a.total = aTotal;
    in.b.x = b.slice(0, std::min((long)b.n(), KEEP));
    in.b.offset = 0;
    in.b.total = (long)b.n();
    in.a.sr = in.b.sr = WORK_SR;
    long window = (long)(WINDOW_S * WORK_SR);
    in.framesA = yamnetFrames(std::min(window, aTotal));
    in.framesB = yamnetFrames(std::min(window, (long)in.b.x.n()));
    in.embA.assign((size_t)in.framesA * head.dim, 0.f);
    in.embB.assign((size_t)in.framesB * head.dim, 0.f);
    in.head = &head;
    MixOptions opt;
    opt.render = true;
    opt.faithful = false;
    return automix::automix(in, opt);
}

void checkPlan(const MixResult &r, double aSeconds) {
    const double eps = 1e-6;
    assert(r.clipStartA >= 0 && r.clipStartA < aSeconds);
    assert(r.aDryUntil >= 0 && r.aDryUntil <= r.total + eps);
    assert(0 <= r.enter + eps && r.enter <= r.seam + eps && r.seam <= r.fromEnd + eps && r.fromEnd <= r.total + eps);
    double clipS = (double)r.clip.n() / WORK_SR;
    assert(std::fabs(clipS - r.total) < 0.02);
    size_t points = (size_t)std::llround(r.total / 0.01);
    for (const vecd *m : {&r.mapA, &r.mapB, &r.visual}) assert(m->size() + 2 >= points && m->size() <= points + 2);
    double peak = 0;
    for (size_t i = 0; i < r.clip.n(); ++i) {
        assert(std::isfinite(r.clip.L[i]) && std::isfinite(r.clip.R[i]));
        peak = std::max(peak, (double)std::max(std::fabs(r.clip.L[i]), std::fabs(r.clip.R[i])));
    }
    assert(peak > 0.01 && peak < 4.0);
    for (double v : r.visual) assert(std::isfinite(v) && v >= -eps && v <= 1 + eps);
    for (size_t i = 1; i < r.mapB.size(); ++i) assert(r.mapB[i] + eps >= r.mapB[i - 1]);
}

}  // namespace

int main(int argc, char **argv) {
    if (argc < 2) { std::puts("用法：automix_mix_smoke_test <head.bin>"); return 2; }
    VoxHead head;
    if (!head.load(argv[1])) { std::printf("读取判别头失败：%s\n", argv[1]); return 2; }

    int produced = 0;
    struct Case { double bpmA, bpmB, rootA, rootB; };
    for (Case c : {Case{120, 120, 55, 55}, Case{120, 123, 55, 61.7}, Case{96, 128, 49, 65.4}}) {
        Stereo a = song(180, c.bpmA, c.rootA, 1);
        Stereo b = song(150, c.bpmB, c.rootB, 7);
        MixResult r = mix(a, b, head);
        std::printf("bpm %.0f→%.0f：kind=%d branch=%s total=%.2fs\n", c.bpmA, c.bpmB, (int)r.kind, r.branch.c_str(), r.total);
        if (r.kind == MixKind::None) continue;
        checkPlan(r, 180.0);
        ++produced;
    }
    assert(produced > 0);  // 节拍清晰的合成信号至少有一组能产生过渡

    Stereo silent;
    silent.resize((size_t)(120 * WORK_SR));
    MixResult quiet = mix(silent, silent, head);
    std::printf("静音：kind=%d branch=%s\n", (int)quiet.kind, quiet.branch.c_str());
    assert(quiet.kind == MixKind::None);

    std::puts("automix_mix_smoke_test: OK");
    return 0;
}
