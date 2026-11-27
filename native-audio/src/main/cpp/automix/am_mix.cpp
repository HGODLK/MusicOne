// 决策流程逐行对应 experiment/vox_mix.py main()；参数与规则不改动。
// 播放接入模式（MixOptions::faithful = false）只在渲染上做三处必要调整，决策完全相同：
//   1. 增益：A 保持原样（0 dB），B 用两首归一化之差（b_norm - a_norm），片段末尾 B 逐渐回到 0 dB，
//      这样片段两端都能与实时播放的 A、B 无缝衔接；
//   2. 片段开头 A 仍是原速时直接用原样 A（与实时 A 逐样本一致），在变速开始前 50ms 等功率交叉到变速 A；
//      B 在片段末尾同理已是原样 B；
//   3. 片段至少延长到底鼓增强退完、A 的实时输入结束之后，限幅只作用于混合后比任一单轨更响的部分。
#include "am_mix.h"

#include <algorithm>
#include <cstdarg>
#include <cstdio>
#include <functional>
#include <optional>
#include <random>

#include "am_stretch.h"

namespace automix {

namespace {

// —— vox_mix.py 常量 ——
const double GENTLE_BPM_S = 0.3, MAX_VOCAL_SPEEDUP = 0.06, FAST_BPM_S = 2.5, B_VOCAL_TOL = 0.02;
const double SETTLE_BEATS = 2;
const double B_UNDER_LU[2] = {8.0, 5.0};
const double A_DUCK_DB = -2.5, PRESENCE_DIP_DB = -5.0, PRE = 6.0, POST = 15.0, MIN_GAP_BEATS = 2.0, DT = 0.001;
const double CUT_XF = 0.02, CUT_RING_BEATS = 1.0, CUT_RING_FADE = 0.15, CUT_LOUD_JUMP = 4.0, KEY_SPAN = 12.0;
const double NO_PREP_S = 5.0, PUNCH_MIN = 2.0;
const int PUNCH_BARS = 8;
const double MAX_TOTAL_RATIO = 0.25, CLEAR_MIN = 0.35, CLEAR_MEAN = 0.60;
const int CLEAR_BEATS = 32;
const double TARGET_LUFS = -14.0, SPARSE_HI_REL_DB = -30.0, SWAP_LEAD_BEATS = 8.0;
const double RECEDE_DB = -7.0, RECEDE_HI_DB = -4.0, RECEDE_WET_DB = -7.0, RECEDE_RT60 = 2.0;
const double DEGRADE_QUIET_LU = 9.0, WEAK_PICKUP_DB = 12.0, SWEEP_TO_HZ = 1500.0;
const double ECHO_FEEDBACK_DB = -6.0, ECHO_WET_DB = -4.0, ECHO_LOWCUT_HZ = 300.0, ECHO_HOLD_BEATS = 2.0;
const double ECHO_END_BEATS = 4.0, ECHO_HICUT_HZ = 6000.0;
// 淡出淡入（B 前奏很轻或稀疏、不适合回声时）：A 在实际结束点前等功率淡出，B 跳过前导静音后短淡入，与 A 淡出尾段重叠
const double FADE_OUT_S = 4.0, FADE_MIN_OUT_S = 2.0, FADE_OVERLAP_S = 1.5, FADE_IN_S = 0.5, FADE_ONSET_DB = -50.0;
// bridge.py
const double CUT_MAX_RATIO = 0.10, CUT_MIN_A_TAIL_S = 5.0, CUT_MIN_VOCAL_S = 3.0;
const int CUT_PREP_BARS = 4, CUT_B_LEAD_BARS = 1;
const double CUT_JUMP_MAX_S = 30.0, CUT_JUMP_VOX_P = 0.9;
const int CUT_JUMP_PHRASE = 4;
const int MAX_EXTRA_BARS = 4, MAX_REPEATS = 1;
// 播放接入
const double APP_B_RAMP_S = 8.0;      // 片段末尾 B 从相对增益回到 0 dB 的时长
const double APP_A_INPUT_TAIL_S = 2.0;  // 实时 A 输入结束后片段至少再留多久（用于对齐实时 B）
const double APP_XFADE_S = 0.05;

using optd = std::optional<double>;

std::string fmt(const char *f, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, f);
    std::vsnprintf(buf, sizeof buf, f, ap);
    va_end(ap);
    return buf;
}

std::string fmtS(optd v) { return v ? fmt("%.1fs", *v) : std::string("未检测到"); }

struct Info {
    double s = 0, k = 1, ta = 0, tb = 0, last_db = 0, d = 0, b_db = 0, a_end = 0, b_gain_db = 0;
    double a_norm_db = 0, b_norm_db = 0;
    bool b_sparse = false;
    optd b_from;
    std::optional<int> cut;
    bool cut_ring = false, a_dry = false;
    optd punch_g;
};

struct Cand {
    int n = 0;
    double d = 0, phi_v = 0, phi_bv = 0, phi_f0 = 0, f0 = 0, phi_t = 0, pre = 0, extra = 0, fast = 0, tail = 0;
    double gentle = 0, win_s = 0;
    bool ok = false;
};
using Knots = std::vector<std::pair<double, double>>;
struct Sched { Knots knots; Cand c; };

// —— 增益曲线（vox_mix._gain）——
enum class Curve { Out, In, Lin, End };
struct GPoint { double t, db; Curve c; };

class Gain {
public:
    explicit Gain(std::vector<GPoint> pts) : p(std::move(pts)) {
        pu.resize(p.size());
        double m = -1e300;
        for (size_t i = 0; i < p.size(); ++i) { m = std::max(m, p[i].t); pu[i] = m; }
    }
    static double amp(double db) { return db <= -59.9 ? 0.0 : std::pow(10.0, db / 20); }
    double operator()(double u) const {
        if (u >= pu.back()) return amp(p.back().db);
        // 后面的段覆盖前面的段（与 numpy 逐段赋值一致）
        for (size_t k = p.size() - 1; k-- > 0;) {
            double u0 = p[k].t, u1 = pu[k + 1];
            if (u >= u0 && u < u1) {
                double x = (u - u0) / std::max(u1 - u0, 1e-9);
                double a0 = amp(p[k].db), a1 = amp(p[k + 1].db);
                if (p[k].c == Curve::Out) return a1 + (a0 - a1) * std::cos(M_PI * x / 2);
                if (p[k].c == Curve::In) return a0 + (a1 - a0) * std::sin(M_PI * x / 2);
                return a0 + (a1 - a0) * x;
            }
        }
        return amp(p[0].db);
    }
private:
    std::vector<GPoint> p;
    vecd pu;
};

// —— PCHIP（scipy.interpolate.PchipInterpolator）——
struct Pchip {
    vecd x, y, d;
    Pchip(const vecd &xs, const vecd &ys) : x(xs), y(ys), d(xs.size(), 0.0) {
        size_t n = x.size();
        if (n < 2) return;
        vecd h(n - 1), m(n - 1);
        for (size_t i = 0; i + 1 < n; ++i) { h[i] = x[i + 1] - x[i]; m[i] = (y[i + 1] - y[i]) / h[i]; }
        if (n == 2) { d[0] = d[1] = m[0]; return; }
        auto sgn = [](double v) { return (double)((v > 0) - (v < 0)); };
        for (size_t k = 1; k + 1 < n; ++k) {
            bool cond = sgn(m[k]) != sgn(m[k - 1]) || m[k] == 0 || m[k - 1] == 0;
            if (cond) { d[k] = 0; continue; }
            double w1 = 2 * h[k] + h[k - 1], w2 = h[k] + 2 * h[k - 1];
            double whmean = (w1 / m[k - 1] + w2 / m[k]) / (w1 + w2);
            d[k] = 1.0 / whmean;
        }
        auto edge = [&](double h0, double h1, double m0, double m1) {
            double dd = ((2 * h0 + h1) * m0 - h0 * m1) / (h0 + h1);
            // scipy 先按符号置零、再按 mask2 覆盖为 3·m0（mask2 用的是原始 d）
            if (sgn(m0) != sgn(m1) && std::fabs(dd) > 3 * std::fabs(m0)) return 3 * m0;
            if (sgn(dd) != sgn(m0)) return 0.0;
            return dd;
        };
        d[0] = edge(h[0], h[1], m[0], m[1]);
        d[n - 1] = edge(h[n - 2], h[n - 3], m[n - 2], m[n - 3]);
    }
    double operator()(double v) const {
        size_t n = x.size();
        size_t k = (size_t)(std::upper_bound(x.begin(), x.end(), v) - x.begin());
        k = k == 0 ? 0 : std::min(k - 1, n - 2);
        double h = x[k + 1] - x[k], t = v - x[k], m = (y[k + 1] - y[k]) / h;
        double c3 = (d[k] + d[k + 1] - 2 * m) / (h * h), c2 = (3 * m - 2 * d[k] - d[k + 1]) / h;
        return y[k] + t * (d[k] + t * (c2 + t * c3));
    }
};

// —— 共用拍钟（VoxClock）——
struct VoxClock {
    double pa = 0, pb = 0, phiStart = 0;
    vecd o, phi, T, aSrc, bSrc;
    double t0 = 0, tLast = 0, tRamp0 = 0, tRamp1 = 0, tAvend = 0, tBvstart = 0;
    double tOfPhi(double x) const { return interp1(x, phi, o); }
    double at(const vecd &arr, double t) const { return interp1(t, o, arr); }

    VoxClock(const Info &info, const Knots &knots, const Cand &c, double totalHint = 180.0) {
        double ta = info.ta, tb = info.tb;
        pa = 60 / ta;
        pb = 60 / tb;
        vecd kp, kt;
        for (auto &k : knots) { kp.push_back(k.first); kt.push_back(k.second); }
        Pchip curve(kp, kt);
        auto Tof = [&](double ph) {
            if (ph <= kp.front()) return kt.front();
            if (ph >= kp.back()) return kt.back();
            return curve(ph);
        };
        phiStart = std::min(kp.front(), 0.0) - PRE * ta / 60;
        long n = (long)(totalHint / DT);
        phi.resize(n); T.resize(n); o.resize(n); aSrc.resize(n); bSrc.resize(n);
        double p = phiStart;
        for (long i = 0; i < n; ++i) {
            phi[i] = p;
            T[i] = Tof(p);
            p += T[i] / 60 * DT;
        }
        for (long i = 0; i < n; ++i) {
            o[i] = (double)i * DT;
            aSrc[i] = c.d + pa * phi[i];
            bSrc[i] = info.b_db + pb * phi[i];
        }
        t0 = tOfPhi(0.0);
        tLast = tOfPhi(c.n * 4.0);
        tRamp0 = tOfPhi(kp.front());
        tRamp1 = tOfPhi(kp.back());
        tAvend = tOfPhi(c.phi_v);
        tBvstart = tOfPhi(c.phi_bv);
    }
};

// —— 三频段（apple_mix.split3）：低 = LR4 低通 250Hz，高 = LR4 高通 2.5kHz，中 = 原信号 − 低 − 高 ——
struct Split3 {
    Sos lp, hp;
    explicit Split3(double sr) : lp(butter(2, 250.0, 0, BandType::Low, sr)), hp(butter(2, 2500.0, 0, BandType::High, sr)) {}
    void run(const double *x, size_t n, vecd &low, vecd &high) const {
        low.assign(x, x + n);
        sosfilt(lp, low.data(), n);
        sosfilt(lp, low.data(), n);
        high.assign(x, x + n);
        sosfilt(hp, high.data(), n);
        sosfilt(hp, high.data(), n);
    }
};

double meanSquare(const Stereo &x) {
    double s = 0;
    for (size_t i = 0; i < x.n(); ++i) s += (double)x.L[i] * x.L[i] + (double)x.R[i] * x.R[i];
    return x.n() ? s / (2.0 * x.n()) : NAN;
}

// —— plan / 下拍 ——
Info plan(const Analysis &A, const Analysis &B) {
    Info info;
    double pa = 60 / A.bpm, pb = 60 / B.bpm;
    const double opts[3] = {0.5, 1.0, 2.0};
    double k = 0.5, bestv = 1e300;
    for (double m : opts) {
        double v = std::fabs(std::log(pa / (pb * m)));
        if (v < bestv) { bestv = v; k = m; }
    }
    info.s = pa / (pb * k);
    info.k = k;
    double ref = (A.hasLastHit && A.lastHit != 0) ? A.lastHit : A.effEnd;
    double lastDb = A.barTime(0), bd = 1e300;
    for (size_t i = 0; i < A.bars.size(); ++i) {
        double t = A.barTime((int)i);
        if (std::fabs(t - ref) < bd) { bd = std::fabs(t - ref); lastDb = t; }
    }
    info.last_db = lastDb;
    info.d = lastDb - 4 * 4 * pa;
    double grid0 = B.beats[0] - pb * std::floor((B.beats[0] - (B.effStart - pb / 4)) / pb);
    info.b_db = std::max(0.0, grid0);
    info.ta = A.bpm;
    info.tb = B.bpm / k;
    info.a_end = A.effEnd;
    info.b_gain_db = std::max(-6.0, std::min(0.0, std::min(0.0, A.bodyLufs - B.bodyLufs)));
    return info;
}

double nanMedian(const vecd &v) {
    for (double x : v) if (std::isnan(x)) return NAN;
    return median(v);
}

std::pair<double, std::string> pickBDownbeat(const Track &xb, const Analysis &B, const Info &info) {
    double sr = xb.sr;
    double first = info.b_db;
    Sos lp = butter(4, 250, 0, BandType::Low, sr);
    vecd head = xb.monoSlice(0, (long)(20 * sr));
    sosfilt(lp, head.data(), head.size());
    auto lowDb = [&](double t) {
        long i = (long)(std::max(t, 0.0) * sr), w = (long)(0.12 * sr);
        long e = std::min((long)head.size(), i + w);
        if (e <= i) return (double)NAN;
        double s = 0;
        for (long j = i; j < e; ++j) s += head[j] * head[j];
        return 10 * std::log10(s / (e - i) + 1e-12);
    };
    vecd bars;
    for (int bi : B.bars) {
        double t = B.beats[bi];
        if (first - 0.05 <= t && t <= first + 12) bars.push_back(t);
    }
    if (bars.empty()) return {first, "无下拍信息，用第一拍"};
    vecd lv;
    for (size_t i = 0; i < bars.size() && i < 4; ++i) lv.push_back(lowDb(bars[i]));
    double ref = nanMedian(lv);
    double e0 = lowDb(first);
    if (e0 >= ref - WEAK_PICKUP_DB || std::fabs(bars[0] - first) < 0.05)
        return {first, fmt("第一拍低频 %.0f dB，下拍参考 %.0f dB → 从第一拍起", e0, ref)};
    return {bars[0], fmt("第一拍低频 %.0f dB，比下拍 %.0f dB 弱 → 弱起，首个下拍 %.3fs", e0, ref, bars[0])};
}

// —— schedule ——
std::optional<Sched> schedule(const Info &info, optd aVend, optd bVstart, const std::vector<int> &barsOptions,
                              bool nativeAtSeam) {
    double ta = info.ta, tb = info.tb;
    double pa = 60 / ta, pb = 60 / tb;
    double dT = tb - ta;
    double sg = dT >= 0 ? 1.0 : -1.0;
    double tol = std::min(B_VOCAL_TOL * tb, std::fabs(dT));
    double need = dT - sg * tol;
    double cap = MAX_VOCAL_SPEEDUP * ta;
    if (std::max(tb / ta, ta / tb) - 1 > MAX_TOTAL_RATIO) return std::nullopt;
    double pre = sg * std::min(std::min(std::fabs(std::sqrt(ta * tb) - ta), cap), std::fabs(need));
    std::optional<Cand> best;
    for (int n : barsOptions) {
        Cand c;
        double d = info.last_db - n * 4 * pa;
        double phi_v = aVend ? (*aVend - d) / pa : -32.0;
        double phi_bv = bVstart ? (*bVstart - info.b_db) / pb : n * 4 + 16.0;
        double phi_f0 = std::max(phi_v, -32.0);
        if (phi_bv - phi_f0 < MIN_GAP_BEATS) continue;
        double phi_aend = (info.a_end - d) / pa;
        double phi_t = std::min(phi_bv, phi_aend);
        if (nativeAtSeam) phi_t = std::min(phi_t, n * 4.0);
        double f0 = phi_f0;
        double win_beats = std::max(0.0, phi_t - f0);
        double win_s = win_beats * 60 / ((ta + tb) / 2);
        double pre_n = phi_f0 >= 0 ? pre : 0.0;
        double rem = need - pre_n;
        double fast = sg * std::min(std::fabs(rem), FAST_BPM_S * win_s);
        double tail = 0.0;
        double extra = rem - fast - tail;
        bool ok = std::fabs(pre_n + extra) <= cap + 1e-9;
        c = Cand{n, d, phi_v, phi_bv, phi_f0, f0, phi_t, pre_n, extra, fast, tail, pre_n + extra, win_s, ok};
        if (ok) { best = c; break; }
    }
    if (!best) return std::nullopt;
    Cand c = *best;
    std::vector<std::pair<double, double>> pts;
    if (std::fabs(c.pre) > 1e-6) {
        pts = {{-std::fabs(c.pre) / GENTLE_BPM_S * ta / 60, ta}, {0.0, ta + c.pre}, {c.f0, ta + c.pre + c.extra}};
    } else {
        pts = {{c.f0 - std::fabs(c.extra) / GENTLE_BPM_S * ta / 60, ta}, {c.f0, ta + c.extra}};
    }
    if (std::fabs(c.tail) > 1e-6) {
        pts.push_back({c.phi_t, ta + c.pre + c.extra + c.fast});
        pts.push_back({c.phi_bv, tb - sg * tol});
        pts.push_back({c.phi_bv + SETTLE_BEATS, tb});
    } else {
        pts.push_back({c.phi_t, tb - sg * tol});
        pts.push_back({c.phi_t + SETTLE_BEATS, tb});
    }
    Knots knots;
    for (auto &pt : pts) {
        if (!knots.empty() && pt.first <= knots.back().first + 1e-6) knots.back() = {knots.back().first, pt.second};
        else knots.push_back(pt);
    }
    return Sched{knots, c};
}

// —— drum_cut_plan / find_drum_entry ——
struct CutPlan { double x; optd cue; double b_vstart; int n; bool prep, ring; };

std::optional<CutPlan> drumCutPlan(const Analysis &A, const Analysis &B, const Info &info, optd aVend,
                                   const std::vector<std::pair<double, double>> &bRuns, optd xSeam) {
    if (!aVend || bRuns.empty()) return std::nullopt;
    if (std::max(info.tb / info.ta, info.ta / info.tb) - 1 > CUT_MAX_RATIO) return std::nullopt;
    if (!camelotClose(A.camelot, B.camelot)) return std::nullopt;
    double barA = 4 * 60 / A.bpm;
    if (info.last_db - *aVend < barA) return std::nullopt;
    optd onset;
    for (auto &r : bRuns) if (r.second - r.first >= CUT_MIN_VOCAL_S) { onset = r.first; break; }
    if (!onset) return std::nullopt;
    double barB = 4 * 60 / B.bpm;
    double vbar = info.b_db + std::floor((*onset - info.b_db) / barB) * barB;
    double x = xSeam ? *xSeam : info.last_db;
    bool ring = x < info.last_db - 1e-3;
    bool prep = x - *aVend >= CUT_MIN_A_TAIL_S;
    int lead = prep ? CUT_B_LEAD_BARS : CUT_PREP_BARS;
    double cue = vbar - lead * barB;
    if (cue < info.b_db - 1e-3) {
        if (!prep) return std::nullopt;
        return CutPlan{x, std::nullopt, bRuns[0].first, 0, prep, ring};
    }
    return CutPlan{x, cue, *onset, 1, prep, ring};
}

std::optional<std::pair<double, optd>> findDrumEntry(const Track &xb, const Analysis &B, double bDb, const VoxResult &v,
                                                     int minLeadBars) {
    double bar = 4 * 60 / B.bpm;
    vecd grid;
    {
        double n = std::ceil((CUT_JUMP_MAX_S - (bDb + bar)) / bar);
        for (long i = 0; i < (long)n; ++i) grid.push_back(bDb + bar + bar * i);
    }
    vecd phrase;
    for (double t : grid) {
        long r = (long)nround((t - bDb) / bar);
        if (((r % CUT_JUMP_PHRASE) + CUT_JUMP_PHRASE) % CUT_JUMP_PHRASE == 0) phrase.push_back(t);
    }
    for (const vecd *cands : {&phrase, &grid}) {
        for (double t : *cands) {
            bool near = false;
            double mx = -1;
            for (size_t i = 0; i < v.t.size(); ++i)
                if (v.t[i] >= t - 0.35 && v.t[i] <= t + 0.05) { near = true; mx = std::max(mx, v.p[i]); }
            if (near && mx >= CUT_JUMP_VOX_P) continue;
            if (drumsAbsent(xb, t, bar, bDb, bDb + 60.0).absent) continue;
            optd nxt;
            for (size_t i = 0; i < v.t.size(); ++i) if (v.t[i] > t && v.p[i] >= 0.5) { nxt = v.t[i]; break; }
            if (minLeadBars && nxt && *nxt - t < minLeadBars * bar) continue;
            return std::make_pair(t, nxt);
        }
    }
    return std::nullopt;
}

double bHiRel(const Track &xb, const Info &info, optd bVstart) {
    double sr = xb.sr;
    long i0 = (long)(info.b_db * sr);
    double lim = (bVstart && *bVstart != 0) ? *bVstart : 8.0;
    long i1 = (long)(std::min(info.b_db + 8.0, lim) * sr);
    Stereo seg = xb.slice(i0, std::max(i1, i0 + (long)sr));
    Split3 sp(sr);
    double eh = 0, es = 0;
    vecd lo, hi;
    for (int ch = 0; ch < 2; ++ch) {
        const vecf &src = ch ? seg.R : seg.L;
        vecd d(src.begin(), src.end());
        sp.run(d.data(), d.size(), lo, hi);
        for (size_t i = 0; i < d.size(); ++i) { eh += hi[i] * hi[i]; es += d[i] * d[i]; }
    }
    double n = 2.0 * seg.n();
    return 10 * std::log10((eh / n + 1e-12) / (es / n + 1e-12));
}

// —— 渲染辅助 ——
Stereo take(const Track &x, const std::function<double(double)> &srcOf, size_t n, double sr) {
    Stereo out;
    out.resize(n);
    for (size_t i = 0; i < n; ++i) {
        long idx = (long)nround(srcOf((double)i / sr) * sr);
        if (idx >= 0 && idx < x.total) {
            long j = idx - x.offset;
            if (j >= 0 && j < (long)x.x.n()) { out.L[i] = x.x.L[j]; out.R[i] = x.x.R[j]; }
        }
    }
    return out;
}

/** 前视峰值限制（vox_mix 末尾那段）。appMode 时只处理混合后比单轨更响的部分。 */
void limit(Stereo &out, double sr, const Stereo *partA, const Stereo *partB) {
    size_t n = out.n();
    vecd need(n);
    for (size_t i = 0; i < n; ++i) {
        double m = std::max(std::fabs(out.L[i]), std::fabs(out.R[i]));
        double v = std::min(1.0, 0.98 / std::max(m, 1e-9));
        if (partA && partB) {
            double single = std::max({std::fabs(partA->L[i]), std::fabs(partA->R[i]), std::fabs(partB->L[i]), std::fabs(partB->R[i])});
            if (m <= single + 1e-9) v = 1.0;
        }
        need[i] = v;
    }
    int win = (int)(0.005 * sr);
    vecd g = uniformFilter1d(minimumFilter1d(need, 2 * win + 1), win + 1);
    for (size_t i = 0; i < n; ++i) {
        float k = (float)std::min(g[i], need[i]);
        out.L[i] *= k;
        out.R[i] *= k;
    }
}

/** scipy fftconvolve(send[:,None], ir, axes=0)：分块重叠相加，返回长 len(send)+len(ir)-1。 */
vecd convolveBlocks(const vecd &x, const vecd &h) {
    if (x.empty() || h.empty()) return {};
    size_t outLen = x.size() + h.size() - 1;
    vecd out(outLen, 0.0);
    int block = 1 << 17;
    int nfft = nextPow2(block + (int)h.size() - 1);
    vecd hp(nfft, 0.0);
    std::copy(h.begin(), h.end(), hp.begin());
    std::vector<cplx> H(nfft / 2 + 1), X(nfft / 2 + 1);
    rfft(hp.data(), nfft, H.data());
    vecd buf(nfft), y(nfft);
    for (size_t s = 0; s < x.size(); s += block) {
        size_t len = std::min((size_t)block, x.size() - s);
        std::fill(buf.begin(), buf.end(), 0.0);
        std::copy(x.begin() + s, x.begin() + s + len, buf.begin());
        rfft(buf.data(), nfft, X.data());
        for (size_t i = 0; i < X.size(); ++i) X[i] *= H[i];
        irfft(X.data(), nfft, y.data());
        size_t m = std::min((size_t)nfft, outLen - s);
        for (size_t i = 0; i < m; ++i) out[s + i] += y[i];
    }
    return out;
}

/** 合成房间混响冲激响应（_room_ir）。随机序列与 numpy 不同（统计特性相同）。 */
std::vector<vecd> roomIr(double sr) {
    double predelay = 0.025, rt60 = RECEDE_RT60;
    long n = (long)((rt60 * 1.2 + predelay) * sr);
    std::mt19937_64 rng(7);
    std::normal_distribution<double> nd(0.0, 1.0);
    std::vector<vecd> ir(2, vecd(n));
    for (long i = 0; i < n; ++i) {
        double t = i / sr;
        double env = t < predelay ? 0.0 : std::exp(-6.91 * (t - predelay) / rt60);
        env *= std::max(0.0, std::min(1.0, (t - predelay) / 0.04));
        ir[0][i] = nd(rng) * env;
        ir[1][i] = nd(rng) * env;
    }
    Sos bp = butter(2, 250, 3500, BandType::Band, sr);
    for (auto &c : ir) {
        sosfilt(bp, c.data(), c.size());
        double e = 0;
        for (double v : c) e += v * v;
        e = std::sqrt(e + 1e-12);
        for (auto &v : c) v /= e;
    }
    return ir;
}

/** recede_vocal：逐帧 STFT → 改写 → 重叠相加（与 scipy 整段 stft/istft 数学等价，内存占用小）。 */
void recedeVocal(Stereo &a, double sr, double o0, double o1, double oEnd) {
    long i0 = std::max(0L, (long)((o0 - 0.5) * sr));
    long i1 = std::min((long)a.n(), (long)(oEnd * sr));
    if (i1 - i0 < (long)sr) return;
    long n = i1 - i0;
    const int nper = 2048, hop = 512;
    long pad = nper / 2;
    long L = n + 2 * pad;
    long rem = L - nper;
    long nadd = ((-rem) % hop + hop) % hop;
    nadd %= nper;
    L += nadd;
    int frames = (int)((L - nper) / hop + 1);
    vecd win = hannPeriodic(nper);
    double wsum = 0;
    for (double w : win) wsum += w;
    double scale = 1.0 / wsum;
    int nf = nper / 2 + 1;
    long outLen = nper + (long)(frames - 1) * hop;
    vecd yl(outLen, 0.0), yr(outLen, 0.0), send(outLen, 0.0), norm(outLen, 0.0);
    vecd bl(nper), br(nper), tl(nper), tr(nper), ts(nper);
    std::vector<cplx> ZL(nf), ZR(nf), C(nf), OL(nf), OR(nf), OS(nf);
    vecd band(nf), G(nf);
    for (int f = 0; f < nf; ++f) {
        double hz = (double)f * sr / nper;
        band[f] = std::max(0.0, std::min(1.0, (hz - 150) / 100)) * std::max(0.0, std::min(1.0, (7000 - hz) / 1500));
        double hi = std::max(0.0, std::min(1.0, (hz - 2500) / 1000));
        G[f] = std::pow(10.0, (RECEDE_DB + RECEDE_HI_DB * hi) / 20);
    }
    for (int t = 0; t < frames; ++t) {
        long start = (long)t * hop - pad;
        for (int i = 0; i < nper; ++i) {
            long j = start + i;
            bool in = j >= 0 && j < n;
            bl[i] = in ? (double)a.L[i0 + j] * win[i] : 0.0;
            br[i] = in ? (double)a.R[i0 + j] * win[i] : 0.0;
        }
        rfft(bl.data(), nper, ZL.data());
        rfft(br.data(), nper, ZR.data());
        double tt = (double)t * hop / sr;
        double x = std::max(0.0, std::min(1.0, (tt + i0 / sr - o0) / std::max(o1 - o0, 1e-3)));
        double d = x * x * (3 - 2 * x);
        for (int f = 0; f < nf; ++f) {
            cplx zl = ZL[f] * scale, zr = ZR[f] * scale;
            double psi = 2 * std::abs(zl * std::conj(zr)) / (std::norm(zl) + std::norm(zr) + 1e-12);
            double mask = std::pow(std::max(0.0, std::min(1.0, (psi - 0.6) / 0.35)), 2);
            cplx c = mask * band[f] * (zl + zr) / 2.0;
            double red = (1 - G[f]) * d;
            OL[f] = zl - red * c;
            OR[f] = zr - red * c;
            OS[f] = c * d;
        }
        irfft(OL.data(), nper, tl.data());
        irfft(OR.data(), nper, tr.data());
        irfft(OS.data(), nper, ts.data());
        long o = (long)t * hop;
        for (int i = 0; i < nper; ++i) {
            yl[o + i] += tl[i] * wsum * win[i];
            yr[o + i] += tr[i] * wsum * win[i];
            send[o + i] += ts[i] * wsum * win[i];
            norm[o + i] += win[i] * win[i];
        }
    }
    vecd sendOut(n, 0.0);
    for (long i = 0; i < n; ++i) {
        long k = i + pad;
        if (k >= outLen - pad) break;   // istft 去掉两端 nperseg/2 后不足 n 的部分补零
        double nv = norm[k] > 1e-10 ? norm[k] : 1.0;
        a.L[i0 + i] = (float)(yl[k] / nv);
        a.R[i0 + i] = (float)(yr[k] / nv);
        sendOut[i] = send[k] / nv;
    }
    auto ir = roomIr(sr);
    double wet = std::pow(10.0, RECEDE_WET_DB / 20);
    for (int ch = 0; ch < 2; ++ch) {
        vecd w = convolveBlocks(sendOut, ir[ch]);
        vecf &dst = ch ? a.R : a.L;
        long j1 = std::min((long)dst.size(), i0 + (long)w.size());
        for (long i = i0; i < j1; ++i) dst[i] += (float)(w[i - i0] * wet);
    }
}

/** 时变 4 阶低通（lowpass_sweep）。 */
void lowpassSweep(Stereo &x, double sr, const std::function<double(long)> &fc) {
    long n = (long)x.n(), i0 = -1;
    for (long i = 0; i < n; ++i) if (fc(i) < 19000) { i0 = i; break; }
    if (i0 < 0) return;
    for (int ch = 0; ch < 2; ++ch) {
        vecf &v = ch ? x.R : x.L;
        vecd seg(v.begin() + i0, v.end());
        for (double q : {0.5412, 1.3066}) {
            double k = 1 / q, ic1 = 0, ic2 = 0;
            for (long i = 0; i < (long)seg.size(); ++i) {
                double g = std::tan(M_PI * std::min(fc(i0 + i), 0.45 * sr) / sr);
                double a1 = 1 / (1 + g * (g + k)), a2 = g * a1, a3 = g * g * a1;
                double v3 = seg[i] - ic2;
                double v1 = a1 * ic1 + a2 * v3;
                double v2 = ic2 + a2 * ic1 + a3 * v3;
                ic1 = 2 * v1 - ic1;
                ic2 = 2 * v2 - ic2;
                seg[i] = v2;
            }
        }
        for (long i = 0; i < (long)seg.size(); ++i) v[i0 + i] = (float)seg[i];
    }
}

/** 拍子同步反馈回声（echo_tail），只返回湿声。 */
Stereo echoTail(const Stereo &send, double sr, double delayS) {
    Sos sos = butter(2, ECHO_LOWCUT_HZ, 0, BandType::High, sr);
    Sos lp = butter(1, ECHO_HICUT_HZ, 0, BandType::Low, sr);
    sos.insert(sos.end(), lp.begin(), lp.end());
    long D = (long)nround(delayS * sr);
    long n = (long)send.n();
    Stereo out;
    out.resize(n);
    vecd e[2] = {vecd(send.L.begin(), send.L.end()), vecd(send.R.begin(), send.R.end())};
    double g = std::pow(10.0, ECHO_WET_DB / 20);
    for (int k = 1; k <= 12; ++k) {
        sosfilt(sos, e[0].data(), n);
        sosfilt(sos, e[1].data(), n);
        if (k * D >= n || g < 1e-3) break;
        for (long i = k * D; i < n; ++i) {
            out.L[i] += (float)(g * e[0][i - k * D]);
            out.R[i] += (float)(g * e[1][i - k * D]);
        }
        g *= std::pow(10.0, ECHO_FEEDBACK_DB / 20);
    }
    return out;
}

using Labels = std::vector<std::pair<double, std::string>>;

void sortLabels(Labels &l) { std::stable_sort(l.begin(), l.end()); }

void printLabels(MixResult &r, const Labels &labels) {
    for (auto &l : labels) r.log.push_back(fmt("  %6.2fs  %s", l.first, l.second.c_str()));
}

/** B 搭桥后的时间轴 → 原时间轴。 */
struct SpliceMap {
    double S = 0, E = 0;
    int extra = 0;
    double operator()(double s) const {
        if (extra <= 0 || s < E) return s;
        double len = E - S, ins = extra * len;
        if (s >= E + ins) return s - ins;
        return S + std::fmod(s - E, len);
    }
};

/** 视觉进度表：B 在中高频上的占比，单调不减，进场前为 0、A 收干净后为 1。 */
/** B 开头第一个可闻处（10ms 窗 RMS 达到 FADE_ONSET_DB），只在 [0, maxS) 内找，找不到用 fallback。 */
double firstAudible(const Track &x, double sr, double maxS, double fallback) {
    long win = (long)(0.01 * sr);
    long end = std::min(x.total, (long)(maxS * sr));
    double th = std::pow(10.0, FADE_ONSET_DB / 20);
    for (long i = 0; i + win <= end; i += win) {
        Stereo seg = x.slice(i, i + win);
        double e = 0;
        for (size_t k = 0; k < seg.n(); ++k) e += (double)seg.L[k] * seg.L[k] + (double)seg.R[k] * seg.R[k];
        if (std::sqrt(e / (2.0 * win)) >= th) return i / sr;
    }
    return fallback;
}

vecd visualTable(double total, double enter, double fromEnd, const std::function<double(double)> &share) {
    vecd v;
    double m = 0;
    for (double t = 0; t <= total + 1e-9; t += 0.01) {
        double s = t < enter ? 0.0 : (t >= fromEnd ? 1.0 : share(t));
        m = std::max(m, std::max(0.0, std::min(1.0, s)));
        v.push_back(m);
    }
    return v;
}

}  // namespace

MixResult automix(MixInput &in, const MixOptions &opt) {
    MixResult r;
    Track &xa = in.a;
    Track &xb = in.b;
    const double sr = WORK_SR;
    long na = xa.total;
    long s0 = std::max(0L, (long)(na - WINDOW_S * sr));
    Analysis A = analyze(xa.slice(s0, na), sr, s0 / sr, na / sr);
    Analysis B = analyze(xb.slice(0, (long)(WINDOW_S * sr)), sr, 0.0, xb.total / sr);
    if (A.bars.empty() || B.beats.empty() || A.bpm <= 0 || B.bpm <= 0) {
        r.branch = "无节拍";
        r.log.push_back("节拍不足，不做过渡");
        return r;
    }
    Info info = plan(A, B);
    std::string dbNote;
    std::tie(info.b_db, dbNote) = pickBDownbeat(xb, B, info);
    VoxResult va = voxDetect(*in.head, in.embA, in.framesA, s0 / sr);
    VoxResult vb = voxDetect(*in.head, in.embB, in.framesB, 0.0);
    optd aVend = va.runs.empty() ? optd() : optd(va.runs.back().second);
    optd bVstart = vb.runs.empty() ? optd() : optd(vb.runs.front().first);
    info.a_norm_db = TARGET_LUFS - A.bodyLufs;
    info.b_norm_db = TARGET_LUFS - B.bodyLufs;
    const double origBDb = info.b_db, origLastDb = info.last_db, origAEnd = info.a_end;
    const optd origBVstart = bVstart;
    auto undoCut = [&]() {
        info.b_db = origBDb; info.last_db = origLastDb; info.a_end = origAEnd;
        info.b_from.reset(); info.cut.reset(); info.cut_ring = false; info.punch_g.reset(); info.a_dry = false;
        bVstart = origBVstart;
    };
    auto LOG = [&](const std::string &s) { r.log.push_back(s); };
    const long aTotalOrig = xa.total;

    double xDrum = lastDrumDownbeat(xa, A, info.last_db);
    std::optional<CutPlan> cut = drumCutPlan(A, B, info, aVend, vb.runs, xDrum);
    if (cut) {
        double bIn = cut->cue ? *cut->cue + 4 * 60 / B.bpm : info.b_db;
        auto ka = localKeys(xa, cut->x - KEY_SPAN, cut->x);
        auto kb = localKeys(xb, bIn, bIn + KEY_SPAN);
        bool close = false;
        for (auto &p : ka) for (auto &q : kb) close = close || camelotClose(p.first, q.first);
        if (!close) {
            std::string sa, sb;
            for (size_t i = 0; i < ka.size(); ++i) sa += (i ? "/" : "") + ka[i].first;
            for (size_t i = 0; i < kb.size(); ++i) sb += (i ? "/" : "") + kb[i].first;
            LOG(fmt("鼓点衔接取消：交接处局部调不相近（A %s，B %s）", sa.c_str(), sb.c_str()));
            cut.reset();
        }
    }
    if (cut) {
        double barB = 4 * 60 / B.bpm;
        double bTake = cut->cue ? *cut->cue + barB : info.b_db;
        DrumCheck nd = drumsAbsent(xb, bTake, barB, info.b_db, info.b_db + 60.0);
        bool noDrum = nd.absent;
        if (noDrum) {
            double barA = 4 * 60 / A.bpm;
            DrumCheck ad = drumsAbsent(xa, cut->x - barA, barA, std::max(0.0, cut->x - 60.0), cut->x);
            if (ad.absent) {
                noDrum = false;
                info.a_dry = true;
                LOG(fmt("B 接手的小节没有鼓，但 A 结尾也没鼓（底鼓比 A 鼓最满处低 %.0f dB）→ 轻接轻，B 保留前奏照常切入", -ad.rel));
            }
        }
        std::optional<std::pair<double, optd>> jump;
        if (noDrum) jump = findDrumEntry(xb, B, info.b_db, vb, cut->prep ? 0 : CUT_PREP_BARS);
        int jumpN = 0;
        if (jump) {
            double la = integratedLoudness(xa.slice((long)((cut->x - 4) * sr), (long)(cut->x * sr)), sr) + info.a_norm_db;
            double lb = integratedLoudness(xb.slice((long)(jump->first * sr), (long)((jump->first + 4) * sr)), sr) + info.b_norm_db;
            double entry = jump->first;
            if (lb - la > CUT_LOUD_JUMP && jump->first - barB >= origBDb - 1e-3) { jumpN = 1; entry = jump->first - barB; }
            info.last_db = cut->x; info.a_end = cut->x; info.cut = jumpN; info.b_db = entry; info.b_from = entry;
            bVstart = jump->second;
            LOG(fmt("B 开头没鼓（底鼓比最满处低 %.0f dB）→ 跳到 B %.2fs 的有鼓下拍，鼓点直接衔接（之后第一句 %s）",
                    -nd.rel, jump->first, fmtS(jump->second).c_str()));
            if (jumpN) LOG(fmt("交接后 B 比 A 响 %.1f LU → 不硬切，B 从 %.2fs 进场、在 A 下面交叠 1 小节先升起来", lb - la, entry));
        } else {
            if (noDrum) {
                info.cut = cut->n;
                info.cut_ring = true;
                LOG(fmt("B 接手的小节没有鼓（底鼓比最满处低 %.0f dB），后面也找不到合适的有鼓下拍 → 鼓点收尾", -nd.rel));
            } else {
                info.last_db = cut->x; info.a_end = cut->x; info.cut = cut->n;
            }
            if (cut->cue) { info.b_db = *cut->cue; info.b_from = *cut->cue; }
            bVstart = cut->b_vstart;
        }
        if (cut->ring) {
            info.a_end = origAEnd;
            info.cut_ring = true;
            LOG(fmt("A 的鼓在 %.2fs 就停了（之后是余音，原最后下拍 %.2fs）→ 在这里交接，余音留 1 拍再收", cut->x, origLastDb));
        }
        std::string how;
        if (jump) how = fmt("B 从 %.2fs 的有鼓下拍切入，交叠 %d 小节", jump->first, jumpN);
        else if (cut->cue) how = fmt("B 从 %.2fs 切入，交叠 1 小节", *cut->cue);
        else how = "B 一开口就唱：从头播放，首个下拍对准交接下拍";
        std::string prep = cut->prep ? "A 尾奏给铺垫" : fmt("A 尾奏太短，B 保留 %d 小节前奏", CUT_PREP_BARS);
        LOG(fmt("鼓点衔接：调性 %s/%s，A 在 %.2fs 交接（唱完后 %.1fs，%s），%s", A.camelot.c_str(), B.camelot.c_str(),
                cut->x, cut->x - *aVend, prep.c_str(), how.c_str()));
    }
    if (info.cut && !info.cut_ring && !info.a_dry) {
        double barA = 4 * 60 / A.bpm, barB = 4 * 60 / B.bpm;
        Stereo sa = xa.slice((long)((info.last_db - 2 * barA) * sr), (long)(info.last_db * sr));
        Stereo sb = xb.slice((long)(info.b_db * sr), (long)((info.b_db + 4 * barB) * sr));
        Split3 sp(sr);
        auto lowOf = [&](const Stereo &s) {
            Stereo o;
            o.resize(s.n());
            vecd lo, hi;
            for (int ch = 0; ch < 2; ++ch) {
                const vecf &src = ch ? s.R : s.L;
                vecd d(src.begin(), src.end());
                sp.run(d.data(), d.size(), lo, hi);
                vecf &dst = ch ? o.R : o.L;
                for (size_t i = 0; i < d.size(); ++i) dst[i] = (float)lo[i];
            }
            return o;
        };
        double pa = punchDb(lowOf(sa), kickPart(sa, sr)), pb = punchDb(lowOf(sb), kickPart(sb, sr));
        if (pa - pb > PUNCH_MIN) {
            double g = PUNCH_MAX_GAIN;
            info.punch_g = g;
            LOG(fmt("底鼓冲击度 A %.1f dB，B %.1f dB → B 底鼓成分放大 %.1f dB，%d 小节内退回原样", pa, pb, 20 * std::log10(g), PUNCH_BARS));
        }
    }
    double hiRel = bHiRel(xb, info, bVstart);
    info.b_sparse = hiRel < SPARSE_HI_REL_DB;
    const std::vector<int> defaultBars{4, 3, 2, 1};
    std::optional<Sched> sch = schedule(info, aVend, bVstart, info.cut ? std::vector<int>{*info.cut} : defaultBars, (bool)info.cut);
    if (!sch && info.cut) {
        LOG("鼓点衔接排不出来，改回常规进场");
        undoCut();
        hiRel = bHiRel(xb, info, bVstart);
        info.b_sparse = hiRel < SPARSE_HI_REL_DB;
        sch = schedule(info, aVend, bVstart, defaultBars, false);
    }
    double pa = 60 / A.bpm, pb = 60 / B.bpm;
    double ca = rhythmRepeat(xa, A.beats, info.last_db - CLEAR_BEATS * pa, info.last_db + pa, false, false);
    double cb = rhythmRepeat(xb, B.beats, info.b_db, info.b_db + CLEAR_BEATS * pb, true, true);
    r.clarityA = ca;
    r.clarityB = cb;
    bool clear = std::min(ca, cb) >= CLEAR_MIN && (ca + cb) / 2 >= CLEAR_MEAN;
    std::string why = "人声空窗不足或速度差过大";
    if (sch && !clear) { sch.reset(); why = "鼓点不够明确"; }
    LOG(fmt("鼓点清晰度 A 结尾 %.2f，B 开头 %.2f → %s", ca, cb, clear ? "可以对拍" : "不对拍"));
    std::vector<std::pair<char, double>> seams;
    SpliceMap spliceB, spliceA;
    bool bridged = false;
    if (!sch && clear) {
        // 循环搭桥
        std::optional<Loop> candA, candB;
        if (bVstart) {
            double pbn = 60 / B.bpm;
            vecd bnd;
            for (int i = -1; i < 48; ++i) bnd.push_back(info.b_db + 4 * pbn * i);
            candB = findLoop(xb, bnd, info.b_db, *bVstart - 0.5);
        }
        if (aVend) {
            double pan = 60 / A.bpm;
            vecd bnd;
            for (int i = -48; i < 2; ++i) bnd.push_back(info.last_db + 4 * pan * i);
            candA = findLoop(xa, bnd, *aVend + 0.5, info.last_db);
        }
        for (char k : {'A', 'B'}) {
            const std::optional<Loop> &c = k == 'A' ? candA : candB;
            LOG(fmt("循环候选 %c：", k) + (c ? fmt("%.2fs~%.2fs，%d 小节，接缝相似度 %.2f", c->S, c->E, c->bars, c->score)
                                         : std::string("没有合格的纯伴奏循环段")));
        }
        if (candA || candB) {
            struct O { int bars; bool aUsed; int ea, eb; };
            std::vector<O> opts;
            int maxA = candA ? std::min(MAX_REPEATS, MAX_EXTRA_BARS / candA->bars) : 0;
            int maxB = candB ? std::min(MAX_REPEATS, MAX_EXTRA_BARS / candB->bars) : 0;
            for (int ea = 0; ea <= maxA; ++ea)
                for (int eb = 0; eb <= maxB; ++eb)
                    if (ea || eb) opts.push_back({ea * (candA ? candA->bars : 0) + eb * (candB ? candB->bars : 0), ea > 0, ea, eb});
            std::sort(opts.begin(), opts.end(), [](const O &x, const O &y) {
                if (x.bars != y.bars) return x.bars < y.bars;
                if (x.aUsed != y.aUsed) return !x.aUsed;
                if (x.ea != y.ea) return x.ea < y.ea;
                return x.eb < y.eb;
            });
            bool found = false;
            for (auto &o : opts) {
                double insA = o.ea ? o.ea * (candA->E - candA->S) : 0.0;
                double insB = o.eb ? o.eb * (candB->E - candB->S) : 0.0;
                Info info2 = info;
                info2.last_db = info.last_db + insA;
                info2.a_end = info.a_end + insA;
                optd bv2 = bVstart ? optd(*bVstart + insB) : optd();
                auto s2 = schedule(info2, aVend, bv2, defaultBars, false);
                if (!s2) continue;
                if (o.ea) {
                    for (double t : splice(xa, candA->S, candA->E, o.ea)) seams.push_back({'A', t});
                    spliceA = SpliceMap{candA->S, candA->E, o.ea};
                }
                if (o.eb) {
                    for (double t : splice(xb, candB->S, candB->E, o.eb)) seams.push_back({'B', t});
                    spliceB = SpliceMap{candB->S, candB->E, o.eb};
                }
                std::string desc;
                if (o.ea) desc += fmt("A 尾奏 %d 小节循环加 %d 遍", candA->bars, o.ea);
                if (o.eb) desc += (desc.empty() ? "" : "、") + fmt("B 前奏 %d 小节循环加 %d 遍", candB->bars, o.eb);
                info = info2;
                bVstart = bv2;
                sch = s2;
                bridged = true;
                found = true;
                LOG(fmt("循环搭桥：%s（共加 %d 小节）", desc.c_str(), o.bars));
                break;
            }
            if (!found) LOG("循环搭桥：加到上限仍排不出对拍");
        }
    }
    if (sch && !info.cut) {
        double aPrep = aVend ? xDrum - *aVend : 1e9;
        double bHeard = sch->c.phi_bv * 60 / info.tb;
        if (aPrep < NO_PREP_S && bHeard < NO_PREP_S) {
            sch.reset();
            why = fmt("两边都没有铺垫（A 唱完到打完 %.1fs，B 进场到开唱 %.1fs）", aPrep, bHeard);
        }
    }
    if (!sch && info.cut) undoCut();
    r.ta = info.ta;
    r.tb = info.tb;
    r.aVend = aVend ? *aVend : NAN;
    r.bVstart = bVstart ? *bVstart : NAN;

    // ———————————— 对不上拍：回声退场或淡出淡入 ————————————
    if (!sch) {
        double aTailLu = integratedLoudness(xa.slice((long)(std::max(0.0, info.a_end - 8) * sr), (long)(info.a_end * sr)), sr) + info.a_norm_db;
        double b1 = std::min(info.b_db + 8.0, (bVstart && *bVstart != 0 && *bVstart > info.b_db + 1) ? *bVstart : info.b_db + 8.0);
        double bIntLu = integratedLoudness(xb.slice((long)(info.b_db * sr), (long)(std::max(b1, info.b_db + 1) * sr)), sr) + info.b_norm_db;
        double quiet = aTailLu - bIntLu;
        std::string mode;
        Labels labels;
        double clip0;
        if (info.b_sparse || quiet >= DEGRADE_QUIET_LU) {
            // 原“降级为普通切歌”：A 尾部静音、切歌装载、B 前导静音叠在一起会有长空档，改为短重叠的淡出淡入
            r.kind = MixKind::Fade;
            r.branch = "淡出淡入";
            double av = aVend && *aVend != 0 ? *aVend : 0.0;
            double aEnd = info.a_end;
            // A 最后一个字尽量唱完再淡，但至少留 FADE_MIN_OUT_S 的淡出
            double fadeAt = std::max(0.0, std::max(aEnd - FADE_OUT_S, std::min(av, aEnd - FADE_MIN_OUT_S)));
            double bOn = firstAudible(xb, sr, info.b_db + 0.5, info.b_db);   // B 原曲秒，保留弱起
            double tbIn = std::max(fadeAt, aEnd - FADE_OVERLAP_S);            // B 可闻起点落在 A 原曲的这个时刻
            clip0 = std::max(0.0, fadeAt - 12.0);
            double tFade = fadeAt - clip0, tIn = tbIn - clip0, tEnd = aEnd - clip0;
            double bOff = tIn - bOn;                                          // B 原曲 0 秒在片段中的位置
            mode = fmt("淡出淡入（B 前奏比 A 结尾轻 %.1f LU%s；A 淡出 %.1fs，重叠 %.1fs）", quiet,
                       info.b_sparse ? "，稀疏前奏" : "", aEnd - fadeAt, aEnd - tbIn);
            labels = {{tFade, "A 开始等功率淡出"}, {tIn, fmt("B 从 %.2fs 处淡入（跳过前导静音）", bOn)}, {tEnd, "A 淡出结束"}};
            if (bVstart) labels.push_back({bOff + *bVstart, "B 开唱"});
            sortLabels(labels);
            r.seam = (tIn + tEnd) / 2;
            r.seamA = clip0 + r.seam;
            r.bCue = bOn;
            r.clipStartA = clip0;
            if (opt.render) {
                double total = tEnd + POST;
                double gainA = opt.faithful ? std::pow(10.0, info.a_norm_db / 20) : 1.0;
                double gainB = opt.faithful ? std::pow(10.0, info.b_norm_db / 20) : std::pow(10.0, (info.b_norm_db - info.a_norm_db) / 20);
                double tX = aTotalOrig / sr - clip0;
                double rampStart = tEnd;
                if (!opt.faithful) total = std::max({total, rampStart + APP_B_RAMP_S, tX + APP_A_INPUT_TAIL_S});
                long n = (long)(total * sr);
                Stereo a = take(xa, [&](double o) { return o + clip0; }, n, sr);
                Stereo b = take(xb, [&](double o) { return o - bOff; }, n, sr);
                Gain ga({{0, 0, Curve::Lin}, {tFade, 0, Curve::Out}, {tEnd, -60, Curve::End}});
                Gain gb({{0, -60, Curve::Lin}, {tIn, -60, Curve::In}, {tIn + FADE_IN_S, 0, Curve::End}});
                Stereo out, partA, partB;
                out.resize(n);
                partA.resize(n);
                partB.resize(n);
                for (long i = 0; i < n; ++i) {
                    double o = i / sr;
                    double rb = 1.0;
                    if (!opt.faithful && o > rampStart) rb = std::pow(10.0, -(info.b_norm_db - info.a_norm_db) / 20 * std::min(1.0, (o - rampStart) / (total - rampStart)));
                    double kA = ga(o) * gainA, kB = gb(o) * gainB * rb;
                    partA.L[i] = (float)(kA * a.L[i]);
                    partA.R[i] = (float)(kA * a.R[i]);
                    partB.L[i] = (float)(kB * b.L[i]);
                    partB.R[i] = (float)(kB * b.R[i]);
                    out.L[i] = partA.L[i] + partB.L[i];
                    out.R[i] = partA.R[i] + partB.R[i];
                }
                limit(out, sr, opt.faithful ? nullptr : &partA, opt.faithful ? nullptr : &partB);
                r.clip = std::move(out);
                r.total = total;
                r.enter = tIn;
                r.fromEnd = tEnd;
                r.aDryUntil = tFade;   // 淡出开始前片段里的 A 就是原样 A
                r.bEndSrc = total - bOff;
                for (double t = 0; t <= total + 1e-9; t += 0.01) {
                    r.mapA.push_back(clip0 + t);
                    r.mapB.push_back(std::max(0.0, t - bOff));
                }
                r.visual = visualTable(total, r.enter, r.fromEnd, [&](double t) {
                    double x = ga(t), y = gb(t);
                    return y * y / (x * x + y * y + 1e-12);
                });
            }
        } else {
            r.kind = MixKind::EchoOut;
            r.branch = "回声退场";
            mode = fmt("回声退场（不变速，B 前奏比 A 结尾轻 %.1f LU）", quiet);
            double paA = 60 / info.ta;
            double av = aVend && *aVend != 0 ? *aVend : 0.0;
            double tbIn = std::max(info.last_db, av + paA);
            double aEnd = std::max(info.a_end, tbIn + 0.5);
            clip0 = std::max(0.0, tbIn - 12.0);
            double bOff = tbIn - info.b_db - clip0;
            double tIn = tbIn - clip0;
            double pbB = 60 / info.tb;
            double tBv = bVstart ? bOff + *bVstart : tIn + 8 * pbB;
            double g0 = std::min(tIn + ECHO_HOLD_BEATS * pbB, std::max(tIn, tBv - 2 * pbB));
            double g1 = std::min(tIn + ECHO_END_BEATS * pbB, std::max(tIn + pbB, tBv - 0.25 * pbB));
            double gEnd = std::max(g1, g0 + 0.25 * pbB);
            labels = {{tIn, fmt("B 首个下拍（原速进场）；A 回声退场，回声间隔 = B 的 1 拍 %.3fs", pbB)},
                      {aEnd - clip0, "A 原声结束"}, {g0, "回声开始收尾"}, {gEnd, "回声收完"}};
            if (bVstart) labels.push_back({tBv, "B 开唱（回声已收干净）"});
            sortLabels(labels);
            r.seamA = tbIn;
            r.bCue = info.b_db;
            r.clipStartA = clip0;
            r.seam = tIn;
            if (opt.render) {
                double total = aEnd - clip0 + POST;
                double gainA = opt.faithful ? std::pow(10.0, info.a_norm_db / 20) : 1.0;
                double gainB = opt.faithful ? std::pow(10.0, info.b_norm_db / 20) : std::pow(10.0, (info.b_norm_db - info.a_norm_db) / 20);
                // 播放接入：A 实时输入结束后再留一段，便于交回实时 B
                double tX = aTotalOrig / sr - clip0;
                double rampStart = std::max(aEnd - clip0, gEnd);
                if (!opt.faithful) total = std::max({total, rampStart + APP_B_RAMP_S, tX + APP_A_INPUT_TAIL_S});
                long n = (long)(total * sr);
                Stereo a = take(xa, [&](double o) { return o + clip0; }, n, sr);
                Stereo b = take(xb, [&](double o) { return o - bOff; }, n, sr);
                Gain ga({{0, 0, Curve::Lin}, {tIn, 0, Curve::Out}, {aEnd - clip0, -60, Curve::End}});
                Gain gb({{0, -60, Curve::Lin}, {std::max(bOff, 0.0), -60, Curve::In}, {std::max(bOff, 0.0) + paA, 0, Curve::End}});
                Gain sendEnv({{0, -60, Curve::Lin}, {tIn - paA, -60, Curve::In}, {tIn - 0.5 * paA, 0, Curve::Lin}, {aEnd - clip0, 0, Curve::End}});
                Gain gate({{0, 0, Curve::Lin}, {g0, 0, Curve::Out}, {gEnd, -60, Curve::End}});
                Stereo send;
                send.resize(n);
                double an = std::pow(10.0, info.a_norm_db / 20);
                for (long i = 0; i < n; ++i) {
                    double o = i / sr;
                    if (o < bOff) { b.L[i] = 0; b.R[i] = 0; }
                    double s = sendEnv(o) * (opt.faithful ? an : 1.0);
                    send.L[i] = (float)(s * a.L[i]);
                    send.R[i] = (float)(s * a.R[i]);
                }
                Stereo echo = echoTail(send, sr, pbB);
                Stereo out, partA, partB;
                out.resize(n);
                partA.resize(n);
                partB.resize(n);
                for (long i = 0; i < n; ++i) {
                    double o = i / sr;
                    double rb = 1.0;
                    if (!opt.faithful && o > rampStart) rb = std::pow(10.0, -(info.b_norm_db - info.a_norm_db) / 20 * std::min(1.0, (o - rampStart) / (total - rampStart)));
                    double kA = ga(o) * gainA, kB = gb(o) * gainB * rb, kE = gate(o);
                    partA.L[i] = (float)(kA * a.L[i] + kE * echo.L[i]);
                    partA.R[i] = (float)(kA * a.R[i] + kE * echo.R[i]);
                    partB.L[i] = (float)(kB * b.L[i]);
                    partB.R[i] = (float)(kB * b.R[i]);
                    out.L[i] = partA.L[i] + partB.L[i];
                    out.R[i] = partA.R[i] + partB.R[i];
                }
                limit(out, sr, opt.faithful ? nullptr : &partA, opt.faithful ? nullptr : &partB);
                r.clip = std::move(out);
                r.total = total;
                r.enter = std::max(bOff, 0.0);
                r.fromEnd = std::max(aEnd - clip0, gEnd);
                r.aDryUntil = std::max(0.0, tIn - paA);   // 回声送入开始前片段里的 A 就是原样 A
                r.bEndSrc = total - bOff;
                for (double t = 0; t <= total + 1e-9; t += 0.01) {
                    r.mapA.push_back(clip0 + t);
                    r.mapB.push_back(std::max(0.0, t - bOff));
                }
                r.visual = visualTable(total, r.enter, r.fromEnd, [&](double t) {
                    double x = ga(t), y = gb(t);
                    return y * y / (x * x + y * y + 1e-12);
                });
            }
        }
        LOG(fmt("A %.2f → B %.2f BPM；A 人声结束 %s，B 开唱 %s", info.ta, info.tb, fmtS(aVend).c_str(), fmtS(bVstart).c_str()));
        LOG(fmt("%s → %s。片段从 A %.2fs 开始", why.c_str(), mode.c_str(), clip0));
        printLabels(r, labels);
        return r;
    }

    // ———————————— 变速对拍 / 鼓点衔接 ————————————
    const Cand &c = sch->c;
    VoxClock clk(info, sch->knots, c);
    r.kind = info.cut ? MixKind::DrumCut : MixKind::BeatMatch;
    r.branch = info.cut ? "鼓点衔接" : (bridged ? "循环搭桥" : "常规对拍");
    r.overlapBars = c.n;
    r.bCue = info.b_db;
    double ta = info.ta, tb = info.tb;
    auto Tat = [&](double t) { return clk.at(clk.T, t); };
    r.rateA = Tat(clk.t0) / ta;
    r.rateB = Tat(clk.t0) / tb;

    // render()
    double aTail = (info.a_end - info.last_db) / (tb / ta);
    double total = std::max({clk.tLast + aTail, clk.tRamp1, clk.tBvstart}) + POST;
    double t0 = clk.t0, L = clk.tLast - clk.t0;
    double beat = 60 / tb;
    double bFrom = info.b_from ? *info.b_from : 0.0;
    double ob0 = interp1(bFrom, clk.bSrc, clk.o);
    double hand = clk.tRamp1;
    double aTail0 = aTail;
    // 增益与时间点（只依赖决策，不依赖音频）
    double vaT = clk.tAvend - t0 + 0.2;
    vaT = std::min(vaT, clk.tLast - t0 + aTail0);
    double vbT = clk.tBvstart - t0 - 0.1;
    double fs = vaT >= 0 ? vaT : std::max(vaT, std::min(0.0, vbT - SWAP_LEAD_BEATS * beat));
    double aVendSrc = c.d + c.phi_v * 60 / ta;
    Stereo aSeg = xa.slice((long)(c.d * sr), (long)(std::max(c.d + 3, aVendSrc) * sr));
    Stereo bSeg = xb.slice((long)(info.b_db * sr), (long)((info.b_db + 8) * sr));
    Stereo bmh;
    {
        Split3 sp(sr);
        bmh.resize(bSeg.n());
        vecd lo, hi;
        for (int ch = 0; ch < 2; ++ch) {
            const vecf &src = ch ? bSeg.R : bSeg.L;
            vecd d(src.begin(), src.end());
            sp.run(d.data(), d.size(), lo, hi);
            vecf &dst = ch ? bmh.R : bmh.L;
            for (size_t i = 0; i < d.size(); ++i) dst[i] = (float)(d[i] - lo[i]);   // 中 + 高 = 原信号 − 低
        }
    }
    double aLu = integratedLoudness(aSeg, sr) + info.a_norm_db;
    double bLu = integratedLoudness(bmh, sr) + info.b_norm_db;
    double gIn = std::max(-30.0, std::min(0.0, aLu - B_UNDER_LU[0] - bLu));
    double gVa = std::max(-30.0, std::min(0.0, aLu - B_UNDER_LU[1] - bLu));
    double vx = std::max(fs + 0.5 * (vbT - fs), vbT - 0.5 * beat);
    double bPre = std::min(0.0, ob0 - t0);
    if (vaT <= bPre) {
        fs = std::max(fs, bPre);
        vx = std::max(vx, fs + 4 * beat);
    }
    if (info.cut) vx = std::max(L, fs + 0.25 * beat);
    vx = std::min(vx, std::max(L + aTail, fs + beat));
    double eIn = std::min({2 * beat, std::max(fs, beat), vx});
    bool under = fs > eIn + 1e-3;
    const double T0 = -1e3;
    std::vector<GPoint> aHi, bHi, aMid, aLo, bLo;
    if (under) {
        aHi = {{T0, 0, Curve::Lin}, {0, 0, Curve::Lin}, {eIn, A_DUCK_DB, Curve::Lin}, {fs, A_DUCK_DB, Curve::Out}, {vx, -60, Curve::End}};
        bHi = {{T0, -60, Curve::Lin}, {bPre, -60, Curve::In}, {eIn, gIn, Curve::Lin}, {fs, gVa, Curve::In}, {vx, 0, Curve::End}};
    } else {
        double bFull = std::max(std::min(vx, vbT), bPre + 0.1);
        aHi = {{T0, 0, Curve::Lin}, {fs, 0, Curve::Out}, {vx, -60, Curve::End}};
        bHi = {{T0, -60, Curve::Lin}, {bPre, -60, Curve::In}, {bFull, 0, Curve::End}};
        eIn = bFull;
    }
    std::vector<GPoint> bMid = bHi;
    aMid = {{T0, 0, Curve::Lin}, {fs, 0, Curve::Out}, {vx, -60, Curve::End}};
    if (!info.cut && vx < L - 4 * beat) {
        L = std::max(4 * beat, std::ceil((vx - 1e-6) / (4 * beat)) * 4 * beat);
        if (L > vbT && L - 4 * beat >= std::max(fs, 2 * beat)) L -= 4 * beat;
        aTail = 0.0;
    }
    if (L >= 2 * beat)
        aLo = {{T0, 0, Curve::Lin}, {0, 0, Curve::Lin}, {std::min(eIn, L - 2 * beat), A_DUCK_DB, Curve::Lin}, {L - 2 * beat, A_DUCK_DB, Curve::Out}, {L, -60, Curve::End}};
    else
        aLo = {{T0, 0, Curve::Lin}, {L - 2 * beat, 0, Curve::Out}, {L, -60, Curve::End}};
    bLo = {{T0, -60, Curve::Lin}, {L - 2 * beat, -60, Curve::In}, {L, 0, Curve::End}};
    bool recede = under && fs > 1.0;
    bool sparse = info.b_sparse && !info.cut;
    if (sparse) {
        aLo = {{T0, 0, Curve::Lin}, {fs, 0, Curve::Out}, {vx, -60, Curve::End}};
        bLo = {{T0, -60, Curve::Lin}, {fs, -60, Curve::In}, {vx, 0, Curve::End}};
    }
    double aStop = 0;
    if (info.cut) {
        std::vector<GPoint> hard;
        if (info.cut_ring) {
            aStop = L + std::min(aTail, CUT_RING_BEATS * beat);
            hard = {{T0, 0, Curve::Lin}, {aStop, 0, Curve::Out}, {aStop + CUT_RING_FADE, -60, Curve::End}};
        } else {
            hard = {{T0, 0, Curve::Lin}, {L - CUT_XF, 0, Curve::Out}, {L, -60, Curve::End}};
        }
        aLo = hard; aMid = hard; aHi = hard;
        bLo = {{T0, -60, Curve::Lin}, {L - CUT_XF, -60, Curve::In}, {L, 0, Curve::End}};
    }
    Gain GaLo(aLo), GaMid(aMid), GaHi(aHi), GbLo(bLo), GbMid(bMid), GbHi(bHi);

    // 标签
    Labels labels = {
        {clk.tRamp0, fmt("开始变速（%.1f BPM）", Tat(clk.tRamp0))},
        {t0, fmt("B 从头进入（%.1f BPM，A ×%.3f，B ×%.3f）", Tat(t0), Tat(t0) / ta, Tat(t0) / tb)},
        {clk.tAvend, fmt("A 人声结束（%.1f BPM，A ×%.3f）", Tat(clk.tAvend), Tat(clk.tAvend) / ta)},
        {t0 + eIn, under ? fmt("B 中高频到位（比 A 低 %.1f LU）", aLu - bLu - gIn) : std::string("B 中高频升满")},
        {t0 + fs, sparse ? "A 开始低通扫频（A 已唱完）" : "中高频交换开始（A 已唱完）"},
        {t0 + vx, "A 中高频退出完毕"},
        {hand, fmt("到达 B 原速 %.1f BPM", tb)},
        {clk.tBvstart, "B 开唱"},
        {t0 + L, "低频交换 / A 最后一个下拍"},
        {t0 + L + aTail, "A 结束"},
    };
    if (recede) { labels.push_back({t0, "A 人声开始变远"}); labels.push_back({t0 + fs, "A 人声退到最远（A 唱完）"}); }
    for (auto &s : seams) {
        const vecd &src = s.first == 'A' ? clk.aSrc : clk.bSrc;
        labels.push_back({interp1(s.second, src, clk.o), s.first == 'A' ? "A 尾奏循环接缝" : "B 前奏循环接缝"});
    }
    sortLabels(labels);

    r.seam = t0 + L;
    r.seamA = info.last_db;
    r.clipStartA = clk.aSrc[0];
    double punchEnd = info.punch_g ? t0 + L + PUNCH_BARS * 4 * beat : 0.0;
    double aGone = info.cut ? (info.cut_ring ? t0 + aStop + CUT_RING_FADE : t0 + L)
                            : std::max({t0 + vx, t0 + L, (double)0});
    // A 的“余音”在 L 之后仍由各频段增益决定：取三条增益都归零的时刻
    {
        double last = 0;
        for (double t = 0; t < total + 30; t += 0.01) {
            double u = t - t0;
            if (GaLo(u) > 0 || GaMid(u) > 0 || GaHi(u) > 0) last = t;
        }
        aGone = std::min(std::max(aGone, last), clk.tLast + aTail0 + 0.5);
    }
    double rampStart = std::max({aGone, hand + 0.3, punchEnd, clk.tBvstart});
    double tX = aTotalOrig / sr - r.clipStartA;   // 实时 A 输入在片段时间里的结束时刻（输入 1:1 消耗）
    if (!opt.faithful) total = std::max({total, rampStart + APP_B_RAMP_S, tX + APP_A_INPUT_TAIL_S, punchEnd + 0.5});
    r.total = total;
    r.fromEnd = aGone;
    r.enter = std::max(ob0, t0 + bPre);
    {
        // 第一处 B 增益大于 0 的时刻
        for (double t = ob0; t < total; t += 0.005) {
            double u = t - t0;
            if (GbLo(u) > 0 || GbMid(u) > 0 || GbHi(u) > 0) { r.enter = t; break; }
        }
    }
    r.aDryUntil = std::max(0.0, std::min({clk.tRamp0, t0 + bPre, r.enter}) - 0.2);
    auto mapAt = [&](const vecd &arr, double t) { return clk.at(arr, t); };
    for (double t = 0; t <= total + 1e-9; t += 0.01) {
        r.mapA.push_back(spliceA(mapAt(clk.aSrc, t)));
        r.mapB.push_back(spliceB(std::max(bFrom, mapAt(clk.bSrc, t))));
    }
    r.bEndSrc = spliceB(std::max(bFrom, mapAt(clk.bSrc, total)));
    r.visual = visualTable(total, r.enter, r.fromEnd, [&](double t) {
        double u = t - t0;
        double am = GaMid(u), ah = GaHi(u), bm = GbMid(u), bh = GbHi(u);
        double eb = bm * bm + bh * bh, ea = am * am + ah * ah;
        return eb / (ea + eb + 1e-12);
    });

    LOG(fmt("A %.2f → B %.2f BPM；A 人声结束 %s，B 开唱 %s", ta, tb, fmtS(aVend).c_str(), fmtS(bVstart).c_str()));
    LOG(fmt("B 首个下拍 %.3fs（%s）", info.b_db, dbNote.c_str()));
    LOG(fmt("响度归一化 A %+.1f dB，B %+.1f dB；B 前奏高频 %.1f dB → %s", info.a_norm_db, info.b_norm_db, hiRel,
            info.b_sparse ? "稀疏前奏，A 低通扫频退场" : "有律动，频段交换"));
    LOG(fmt("重叠 %d 小节，B 在 A %.2fs 进场；唱歌期间变速 %+.2f BPM（B 进场前 %+.2f），空窗内变速 %+.2f BPM，A 结束后 B 缓变 %+.2f BPM（空窗 %.1fs）",
            c.n, c.d, c.gentle, c.pre, c.fast, c.tail, c.win_s));
    LOG(fmt("片段从 A %.2fs 开始", r.clipStartA));
    printLabels(r, labels);
    if (!opt.render) return r;

    // ———— 音频 ————
    long n = (long)(total * sr);
    Stereo a = stretchByMap(xa, 0.0, total, [&](double u) { return clk.at(clk.aSrc, u); });
    a.L.resize(n, 0.f);
    a.R.resize(n, 0.f);
    double aGain = opt.faithful ? std::pow(10.0, info.a_norm_db / 20) : 1.0;
    double aCut = clk.tLast + aTail0 + 0.5;
    if (!opt.faithful) {
        // 片段开头 A 尚未变速：用原样 A，变速开始前交叉到变速 A
        Stereo dry = take(xa, [&](double o) { return clk.at(clk.aSrc, o); }, (size_t)std::min<long>(n, (long)((r.aDryUntil + APP_XFADE_S) * sr) + 1), sr);
        for (size_t i = 0; i < dry.n(); ++i) {
            double o = i / sr;
            double x = std::max(0.0, std::min(1.0, (o - r.aDryUntil) / APP_XFADE_S));
            double cw = std::cos(M_PI * x / 2), sw = std::sin(M_PI * x / 2);
            a.L[i] = (float)(dry.L[i] * cw + a.L[i] * sw);
            a.R[i] = (float)(dry.R[i] * cw + a.R[i] * sw);
        }
    }
    for (long i = 0; i < n; ++i) {
        double o = i / sr;
        if (o > aCut) { a.L[i] = 0; a.R[i] = 0; }
        else { a.L[i] = (float)(a.L[i] * aGain); a.R[i] = (float)(a.R[i] * aGain); }
    }
    Stereo b;
    b.resize(n);
    {
        long k0 = (long)nround(ob0 * sr), k1 = (long)nround((hand + 0.3) * sr);
        Stereo seg = stretchByMap(xb, ob0, hand + 0.3, [&](double u) { return std::max(bFrom, clk.at(clk.bSrc, u)); });
        long m = std::min((long)seg.n(), k1 - k0);
        for (long i = 0; i < m && k0 + i < n; ++i) { b.L[k0 + i] = seg.L[i]; b.R[k0 + i] = seg.R[i]; }
        Stereo dry = take(xb, [&](double o) { return clk.at(clk.bSrc, o); }, n, sr);
        double bGain = opt.faithful ? std::pow(10.0, info.b_norm_db / 20) : std::pow(10.0, (info.b_norm_db - info.a_norm_db) / 20);
        for (long i = 0; i < n; ++i) {
            double o = i / sr;
            double x = std::max(0.0, std::min(1.0, (o - hand) / 0.05));
            double cw = std::cos(M_PI * x / 2), sw = std::sin(M_PI * x / 2);
            double l = b.L[i] * cw + dry.L[i] * sw, rr = b.R[i] * cw + dry.R[i] * sw;
            if (o < ob0) { l = 0; rr = 0; }
            double g = bGain;
            if (!opt.faithful && o > rampStart)
                g *= std::pow(10.0, -(info.b_norm_db - info.a_norm_db) / 20 * std::min(1.0, (o - rampStart) / (total - rampStart)));
            b.L[i] = (float)(l * g);
            b.R[i] = (float)(rr * g);
        }
    }
    if (recede) recedeVocal(a, sr, t0, t0 + fs, t0 + L + aTail + 1.0);
    if (sparse) {
        double fsv = fs, vxv = vx;
        lowpassSweep(a, sr, [&](long i) {
            double u = i / sr - t0;
            if (u < fsv) return 20000.0;
            double x = std::max(0.0, std::min(1.0, (u - fsv) / std::max(vxv - fsv, 1e-3)));
            return 18000.0 * std::pow(SWEEP_TO_HZ / 18000.0, x);
        });
    }
    // 三频段混合：逐声道处理，控制内存
    Stereo out, partA, partB;
    out.resize(n);
    if (!opt.faithful) { partA.resize(n); partB.resize(n); }
    Split3 sp(sr);
    Sos bpSos = butter(2, 1000, 4000, BandType::Band, sr);
    // 底鼓增强区间
    long pI0 = 0, pI1 = 0;
    Stereo kick;
    auto punchExtra = [&](double u) {
        if (!info.punch_g) return 0.0;
        double e = (*info.punch_g - 1) * std::max(0.0, std::min(1.0, 1 - (u - L) / (PUNCH_BARS * 4 * beat)));
        return u >= L - CUT_XF ? e : 0.0;
    };
    if (info.punch_g) {
        long first = -1, last = -1;
        for (long i = 0; i < n; ++i) if (punchExtra(i / sr - t0) > 0) { if (first < 0) first = i; last = i; }
        if (first >= 0) {
            pI0 = std::max(0L, first - (long)sr);
            pI1 = std::min(n, last + 1 + (long)sr);
            kick = kickPart(b.slice(pI0, pI1), sr);
        }
    }
    double dipMax = 1 - std::pow(10.0, PRESENCE_DIP_DB / 20);
    vecd lo, hi;
    for (int ch = 0; ch < 2; ++ch) {
        const vecf &ac = ch ? a.R : a.L;
        const vecf &bc = ch ? b.R : b.L;
        vecd x(ac.begin(), ac.end());
        sp.run(x.data(), x.size(), lo, hi);
        vecd oa(n);
        for (long i = 0; i < n; ++i) {
            double u = i / sr - t0;
            double mid = x[i] - lo[i] - hi[i];
            oa[i] = GaLo(u) * lo[i] + GaMid(u) * mid + GaHi(u) * hi[i];
        }
        vecd xb2(bc.begin(), bc.end());
        sp.run(xb2.data(), xb2.size(), lo, hi);
        vecd mid(n);
        for (long i = 0; i < n; ++i) mid[i] = xb2[i] - lo[i] - hi[i];
        if (info.punch_g && pI1 > pI0) {
            const vecf &kc = ch ? kick.R : kick.L;
            for (long i = pI0; i < pI1; ++i) lo[i] += punchExtra(i / sr - t0) * kc[i - pI0];
        }
        vecd bp = sosfiltfilt(bpSos, mid);
        vecf &dst = ch ? out.R : out.L;
        for (long i = 0; i < n; ++i) {
            double u = i / sr - t0;
            double dip = dipMax * std::max(0.0, std::min(1.0, 1 - (u - fs) / std::max((vbT - fs) / 2, 1e-3)));
            double m2 = mid[i] - dip * bp[i];
            double ob = GbLo(u) * lo[i] + GbMid(u) * m2 + GbHi(u) * hi[i];
            dst[i] = (float)(oa[i] + ob);
            if (!opt.faithful) {
                (ch ? partA.R : partA.L)[i] = (float)oa[i];
                (ch ? partB.R : partB.L)[i] = (float)ob;
            }
        }
    }
    limit(out, sr, opt.faithful ? nullptr : &partA, opt.faithful ? nullptr : &partB);
    r.clip = std::move(out);
    return r;
}

}  // namespace automix
