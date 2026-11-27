#include "am_parts.h"

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iterator>

namespace automix {

// ———————————————————— Track ————————————————————

Stereo Track::slice(long i0, long i1) const {
    i0 = clampIndex(i0);
    i1 = std::max(i0, clampIndex(i1));
    return x.slice(i0 - offset, i1 - offset);
}

vecd Track::monoSlice(long i0, long i1) const { return slice(i0, i1).mono(); }

/** np.arange(start, stop, step) 的浮点语义。 */
static vecd arangeF(double start, double stop, double step) {
    vecd out;
    double n = std::ceil((stop - start) / step);
    for (long i = 0; i < (long)n; ++i) out.push_back(start + step * i);
    return out;
}

// ———————————————————— rhythm.py ————————————————————

static const int RH_HOP = 256;

/** 打击成分功率谱（软掩码），返回 Pm[t * nf + f]。 */
static vecd percussivePower(const Spec &s) {
    vecd S(s.z.size());
    for (size_t i = 0; i < S.size(); ++i) S[i] = std::norm(s.z[i]);
    vecd P, H;
    hpssMedians(S, s.nfreq, s.nframes, 17, 17, P, H);
    vecd Pm(S.size());
    for (size_t i = 0; i < S.size(); ++i) Pm[i] = S[i] * (P[i] * P[i] / (P[i] * P[i] + H[i] * H[i] + 1e-20));
    return Pm;
}

static vecd bandFlux(const vecd &Pm, const Spec &s, double lo, double hi) {
    vecd flux(s.nframes, 0.0);
    for (int f = 0; f < s.nfreq; ++f) {
        double hz = s.freq(f);
        if (hz < lo || hz >= hi) continue;
        double prev = std::log1p(100 * Pm[f]);
        for (int t = 0; t < s.nframes; ++t) {
            double L = std::log1p(100 * Pm[(size_t)t * s.nfreq + f]);
            flux[t] += std::max(0.0, L - prev);
            prev = L;
        }
    }
    return flux;
}

double rhythmRepeat(const Track &x, const vecd &beats, double t0, double t1, bool includeStart, bool includeEnd) {
    double sr = x.sr;
    long i0 = std::max(0L, (long)(t0 * sr)), i1 = std::min(x.total, (long)(t1 * sr));
    vecd m = x.monoSlice(i0, i1);
    if ((double)m.size() < sr * 2) return 0.0;
    Spec s = stft(m.data(), m.size(), sr, 1024, 1024 - RH_HOP);
    vecd Pm = percussivePower(s);
    vecd low = bandFlux(Pm, s, 30, 160), high = bandFlux(Pm, s, 3000, 12000);
    double fr = sr / RH_HOP;
    vecd bt;
    // 窗口两端正好落在拍点上（t0 = 最后下拍 − 32 拍等），实验代码里这一拍算不算进来取决于 1e-14 级的舍入噪声。
    // 端点 1 微秒内的拍点按调用方给的约定固定取舍：A 结尾窗口两端都不算，B 开头窗口两端都算
    // （22 组回归样本里唯一与原版判定全一致的约定）。算进来的终点拍取 t1 的前一个浮点数，与参考端一致。
    const double tol = 1e-6;
    for (double b : beats) {
        if (std::fabs(b - t0) < tol) {
            if (includeStart) bt.push_back(t0 - i0 / sr);
            continue;
        }
        if (std::fabs(b - t1) < tol) {
            if (includeEnd) bt.push_back(std::nextafter(t1, -INFINITY) - i0 / sr);
            continue;
        }
        if (b > t0 && b < t1) bt.push_back(b - i0 / sr);
    }
    if (bt.size() < 4) return 0.0;
    int w = std::max(1, (int)(0.035 * fr));
    double lm = mean(low) + 1e-9, hm = mean(high) + 1e-9;
    vecd comb(low.size());
    for (size_t i = 0; i < low.size(); ++i) comb[i] = low[i] / lm + high[i] / hm;
    auto peakAt = [&](const vecd &env, const vecd &times) {
        vecd out;
        for (double t : times) {
            long idx = (long)(t * fr);
            if (idx < w || idx >= (long)env.size() - w) continue;
            double mx = -1e300;
            for (long k = idx - w; k <= idx + w; ++k) mx = std::max(mx, env[k]);
            out.push_back(mx);
        }
        if (out.empty()) out.push_back(0.0);
        return out;
    };
    std::vector<vecd> bars;
    for (size_t j = 0; j + 4 < bt.size(); j += 4) {
        double b0 = bt[j], b1 = bt[j + 4];
        vecd pos(16);
        for (int k = 0; k < 16; ++k) pos[k] = b0 + (b1 - b0) * k / 16.0;
        vecd pk = peakAt(comb, pos);
        if (pk.size() == 16) bars.push_back(pk);
    }
    if (bars.size() < 3) return 0.0;
    vecd ref(16);
    for (int k = 0; k < 16; ++k) {
        vecd col;
        for (auto &b : bars) col.push_back(b[k]);
        ref[k] = median(col);
    }
    double refSd = stdev(ref);
    vecd cs;
    for (auto &b : bars) if (stdev(b) > 1e-9 && refSd > 1e-9) cs.push_back(corrcoef(b, ref));
    if (cs.empty()) return 0.0;
    double sum = 0;
    int cnt = 0;
    for (double c : cs) if (!std::isnan(c)) { sum += c; ++cnt; }
    return cnt ? sum / cnt : NAN;
}

static double percDb(const vecd &L, const vecd &R, double sr, double t0, double t1) {
    long n = (long)L.size();
    long i0 = std::max(0L, (long)(t0 * sr)), i1 = std::min(n, (long)(t1 * sr));
    if (i1 - i0 < 2048) return -120.0;
    vecd m(i1 - i0);
    for (long i = i0; i < i1; ++i) m[i - i0] = (L[i] + R[i]) / 2;
    Spec s = stft(m.data(), m.size(), sr, 1024, 1024 - RH_HOP);
    vecd Pm = percussivePower(s);
    double acc = 0;
    for (int t = 0; t < s.nframes; ++t) {
        double col = 0;
        for (int f = 0; f < s.nfreq; ++f) col += Pm[(size_t)t * s.nfreq + f];
        acc += col;
    }
    return 10 * std::log10(acc / s.nframes + 1e-20);
}

DrumCheck drumsAbsent(const Track &x, double tEntry, double bar, double refT0, double refT1, double dropDb) {
    double sr = x.sr;
    long i0 = std::max(0L, (long)(std::min(refT0, tEntry) * sr));
    long i1 = std::min(x.total, (long)((std::max(refT1, tEntry + bar) + 0.5) * sr));
    Stereo seg = x.slice(i0, i1);
    Sos sos = butter(4, 150, 0, BandType::Low, sr);
    vecd L = sosfiltfilt(sos, vecd(seg.L.begin(), seg.L.end()));
    vecd R = sosfiltfilt(sos, vecd(seg.R.begin(), seg.R.end()));
    double off = i0 / sr;
    vecd ref;
    for (double t : arangeF(refT0, refT1 - bar, bar)) ref.push_back(percDb(L, R, sr, t - off, t - off + bar));
    if (ref.empty()) return {false, 0.0};
    double e = percDb(L, R, sr, tEntry - off, tEntry - off + bar);
    double rel = e - percentile(ref, 80);
    return {rel < -dropDb, rel};
}

// ———————————————————— punch.py ————————————————————

Stereo kickPart(const Stereo &x, double sr, double cutoff) {
    size_t n = x.n();
    Sos sos = butter(4, cutoff, 0, BandType::Low, sr);
    Stereo out;
    out.resize(n);
    for (int ch = 0; ch < 2; ++ch) {
        const vecf &src = ch == 0 ? x.L : x.R;
        vecd d(src.begin(), src.end());
        Spec s = stft(d.data(), d.size(), sr, 1024, 1024 - 256);
        vecd S(s.z.size());
        for (size_t i = 0; i < S.size(); ++i) S[i] = std::norm(s.z[i]);
        vecd P, H;
        hpssMedians(S, s.nfreq, s.nframes, 17, 17, P, H);
        for (size_t i = 0; i < S.size(); ++i) s.z[i] *= P[i] * P[i] / (P[i] * P[i] + H[i] * H[i] + 1e-20);
        vecd y = istft(s);
        vecd yy(n, 0.0);
        for (size_t i = 0; i < n && i < y.size(); ++i) yy[i] = (double)(float)y[i];   // 实验代码先写入 float32 数组
        vecd f = sosfiltfilt(sos, yy);
        vecf &dst = ch == 0 ? out.L : out.R;
        for (size_t i = 0; i < n; ++i) dst[i] = (float)f[i];
    }
    return out;
}

double punchDb(const Stereo &low, const Stereo &kick) {
    double k = 0, l = 0;
    size_t nk = kick.n() * 2, nl = low.n() * 2;
    for (size_t i = 0; i < kick.n(); ++i) k += (double)kick.L[i] * kick.L[i] + (double)kick.R[i] * kick.R[i];
    for (size_t i = 0; i < low.n(); ++i) l += (double)low.L[i] * low.L[i] + (double)low.R[i] * low.R[i];
    return 10 * std::log10((k / std::max<size_t>(nk, 1) + 1e-20) / (l / std::max<size_t>(nl, 1) + 1e-20));
}

// ———————————————————— keycheck.py ————————————————————

static const double KC_MAJ[12] = {6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88};
static const double KC_MIN[12] = {6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17};

static std::string camelotOf(int k, bool major) {
    int root = major ? k : (k + 3) % 12;
    int num = (8 + 7 * root) % 12;
    if (num == 0) num = 12;
    return std::to_string(num) + (major ? "B" : "A");
}

std::vector<std::pair<std::string, double>> localKeys(const Track &x, double t0, double t1) {
    double sr = x.sr;
    vecd m = x.monoSlice(std::max(0L, (long)(t0 * sr)), (long)(t1 * sr));
    if ((double)m.size() < sr) return {};
    Spec s = stft(m.data(), m.size(), sr, 8192, 6144);
    vecd ch(12, 0.0);
    for (int f = 0; f < s.nfreq; ++f) {
        double hz = s.freq(f);
        if (!(hz > 60 && hz < 4000)) continue;
        double mag = 0;
        for (int t = 0; t < s.nframes; ++t) mag += std::abs(s.at(f, t));
        mag /= s.nframes;
        long pc = (long)nround(12 * std::log2(hz / 261.63));
        pc = ((pc % 12) + 12) % 12;
        ch[pc] += mag * mag;
    }
    double sum = 0;
    for (double v : ch) sum += v;
    for (auto &v : ch) v /= sum + 1e-20;
    std::vector<std::pair<double, std::string>> sc;
    for (int maj = 1; maj >= 0; --maj) {
        const double *p = maj ? KC_MAJ : KC_MIN;
        for (int k = 0; k < 12; ++k) {
            vecd rolled(12);
            for (int i = 0; i < 12; ++i) rolled[i] = p[((i - k) % 12 + 12) % 12];
            sc.push_back({corrcoef(ch, rolled), camelotOf(k, maj == 1)});
        }
    }
    std::sort(sc.begin(), sc.end(), [](const auto &a, const auto &b) {
        if (a.first != b.first) return a.first > b.first;
        return a.second > b.second;
    });
    std::vector<std::pair<std::string, double>> out;
    for (auto &e : sc) if (e.first >= sc[0].first - 0.03) out.push_back({e.second, e.first});
    return out;
}

bool camelotClose(const std::string &a, const std::string &b) {
    if (a.empty() || b.empty()) return false;
    int na = std::stoi(a.substr(0, a.size() - 1)), nb = std::stoi(b.substr(0, b.size() - 1));
    char la = a.back(), lb = b.back();
    if (la == lb) return std::min(((na - nb) % 12 + 12) % 12, ((nb - na) % 12 + 12) % 12) <= 1;
    return na == nb;
}

// ———————————————————— voxgap.py ————————————————————

bool VoxHead::loadMemory(const void *data, size_t size) {
    // 格式：int32 维度、int32 上下文、float32 mu[n]、sd[n]、W[n]、b（n = 维度 × (2·上下文 + 1)）
    if (size < 8) return false;
    const char *p = (const char *)data;
    int32_t d, c;
    std::memcpy(&d, p, 4);
    std::memcpy(&c, p + 4, 4);
    size_t n = (size_t)d * (2 * c + 1);
    if (size < 8 + 4 * (3 * n + 1)) return false;
    dim = d;
    context = c;
    mu.resize(n); sd.resize(n); W.resize(n);
    std::memcpy(mu.data(), p + 8, 4 * n);
    std::memcpy(sd.data(), p + 8 + 4 * n, 4 * n);
    std::memcpy(W.data(), p + 8 + 8 * n, 4 * n);
    std::memcpy(&b, p + 8 + 12 * n, 4);
    return true;
}

bool VoxHead::load(const std::string &path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) return false;
    std::vector<char> buf((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
    return loadMemory(buf.data(), buf.size());
}

static std::vector<std::pair<double, double>> voxRuns(const std::vector<bool> &mask, const vecd &t, double minLen, double gap) {
    std::vector<std::pair<double, double>> out, merged;
    bool open = false;
    double s = 0;
    for (size_t i = 0; i < mask.size(); ++i) {
        if (mask[i] && !open) { s = t[i]; open = true; }
        if (!mask[i] && open) { out.push_back({s, t[i]}); open = false; }
    }
    if (open) out.push_back({s, t.back()});
    for (auto &r : out) {
        if (!merged.empty() && r.first - merged.back().second < gap) merged.back().second = r.second;
        else merged.push_back(r);
    }
    std::vector<std::pair<double, double>> res;
    for (auto &r : merged) if (r.second - r.first >= minLen) res.push_back(r);
    return res;
}

VoxResult voxDetect(const VoxHead &head, const std::vector<float> &emb, int frames, double offset) {
    VoxResult r;
    int d = head.dim, k = head.context, w = 2 * k + 1;
    vecd p(frames);
    for (int i = 0; i < frames; ++i) {
        double z = 0;
        for (int c = 0; c < w; ++c) {
            int src = std::max(0, std::min(frames - 1, i - k + c));
            const float *e = &emb[(size_t)src * d];
            for (int j = 0; j < d; ++j) {
                size_t q = (size_t)c * d + j;
                float xv = (e[j] - head.mu[q]) / head.sd[q];   // 与 numpy 一样按 float32 标准化
                z += (double)xv * head.W[q];
            }
        }
        z += head.b;
        p[i] = 1.0 / (1.0 + std::exp(-z));
    }
    r.p = frames ? medianFilter(p, 3) : p;
    r.t.resize(frames);
    for (int i = 0; i < frames; ++i) {
        float t = (float)((i * (double)YAMNET_HOP + YAMNET_WIN / 2.0) / 16000.0);
        r.t[i] = (double)(t + (float)offset);
    }
    std::vector<bool> mask(frames);
    for (int i = 0; i < frames; ++i) mask[i] = r.p[i] > 0.5;
    r.runs = frames ? voxRuns(mask, r.t, 1.0, 1.5) : std::vector<std::pair<double, double>>{};
    return r;
}

std::vector<float> voxInput16k(const Stereo &seg, int sr) {
    vecd m = seg.mono();
    long g = 1, a = 16000, b = sr;
    while (b) { long t = a % b; a = b; b = t; }
    g = a;
    vecd y = resamplePoly(m, (int)(16000 / g), (int)(sr / g));
    return std::vector<float>(y.begin(), y.end());
}

// ———————————————————— bridge.py ————————————————————

struct BarFeature { vecd chroma, pat; double lvl; };

static BarFeature barFeature(const Track &x, double t0, double t1) {
    double sr = x.sr;
    long i0 = (long)(t0 * sr), i1 = (long)(t1 * sr);
    vecd seg = x.monoSlice(std::max(0L, i0), std::max(i0 + 2048, i1));
    if (seg.size() < 4096) seg.resize(4096, 0.0);
    BarFeature bf;
    Spec s = stft(seg.data(), seg.size(), sr, 4096, 3072);
    bf.chroma.assign(12, 0.0);
    for (int f = 0; f < s.nfreq; ++f) {
        double hz = s.freq(f);
        if (!(hz > 60 && hz < 5000)) continue;
        double mag = 0;
        for (int t = 0; t < s.nframes; ++t) mag += std::abs(s.at(f, t));
        long pc = (long)nround(12 * std::log2(hz / 440.0));
        pc = ((pc % 12) + 12) % 12;
        bf.chroma[pc] += mag / s.nframes;
    }
    double sum = 0;
    for (double v : bf.chroma) sum += v;
    for (auto &v : bf.chroma) v /= sum + 1e-12;
    double cm = mean(bf.chroma);
    for (auto &v : bf.chroma) v -= cm;
    Spec s2 = stft(seg.data(), seg.size(), sr, 1024, 768);
    vecd flux(s2.nframes, 0.0);
    for (int f = 0; f < s2.nfreq; ++f) {
        double prev = std::log1p(100 * std::norm(s2.at(f, 0)));
        for (int t = 0; t < s2.nframes; ++t) {
            double L = std::log1p(100 * std::norm(s2.at(f, t)));
            flux[t] += std::max(0.0, L - prev);
            prev = L;
        }
    }
    double fr = sr / 256;
    int w = std::max(1, (int)(0.03 * fr));
    bf.pat.resize(16);
    for (int k = 0; k < 16; ++k) {
        long p = (long)((k / 16.0) * (t1 - t0) * fr);
        if (p < (long)flux.size()) {
            double mx = -1e300;
            for (long q = std::max(0L, p - w); q < std::min((long)flux.size(), p + w + 1); ++q) mx = std::max(mx, flux[q]);
            bf.pat[k] = mx;
        } else bf.pat[k] = 0.0;
    }
    double ms = 0;
    for (double v : seg) ms += v * v;
    bf.lvl = 10 * std::log10(ms / seg.size() + 1e-12);
    return bf;
}

static double corr0(const vecd &a, const vecd &b) {
    double sa = stdev(a), sb = stdev(b);
    if (sa < 1e-12 || sb < 1e-12) return 0.0;
    double ma = mean(a), mb = mean(b), s = 0;
    for (size_t i = 0; i < a.size(); ++i) s += (a[i] - ma) * (b[i] - mb);
    return s / a.size() / (sa * sb);
}

static double barSim(const BarFeature &a, const BarFeature &b) {
    return 0.5 * corr0(a.chroma, b.chroma) + 0.3 * corr0(a.pat, b.pat) +
           0.2 * (1 - std::min(1.0, std::fabs(a.lvl - b.lvl) / 6.0));
}

std::optional<Loop> findLoop(const Track &x, const vecd &bnd, double tLo, double tHi) {
    const int units[3] = {4, 2, 1};
    const double MIN_SIM = 0.70;
    vecd diffs;
    for (size_t i = 1; i < bnd.size(); ++i) diffs.push_back(bnd[i] - bnd[i - 1]);
    double bar = median(diffs);
    std::vector<std::optional<BarFeature>> cache(bnd.size());
    std::vector<bool> done(bnd.size(), false);
    auto F = [&](long i) -> const BarFeature * {
        if (i < 0 || i + 1 >= (long)bnd.size() || bnd[i] < 0 || bnd[i + 1] * x.sr > (double)x.total) return nullptr;
        if (!done[i]) { cache[i] = barFeature(x, bnd[i], bnd[i + 1]); done[i] = true; }
        return &*cache[i];
    };
    std::optional<Loop> best;
    for (int u : units) {
        for (long i = 0; i + u < (long)bnd.size(); ++i) {
            long j = i + u;
            if (bnd[i] < tLo - 0.02 || bnd[j] > tHi + 0.02) continue;
            vecd sims;
            const BarFeature *fi = F(i), *fj = F(j);
            if (fj && fi) sims.push_back(barSim(*fi, *fj));
            const BarFeature *fi1 = F(i - 1);
            if (fi1 && bnd[i - 1] >= tLo - bar * 0.1) {
                const BarFeature *fj1 = F(j - 1);
                if (fj1) sims.push_back(barSim(*fi1, *fj1));
            }
            if (sims.empty()) continue;
            double score = mean(sims);
            if (score >= MIN_SIM && (!best || score > best->score + 0.05 * (best->bars > u ? 1 : 0)))
                best = Loop{bnd[i], bnd[j], u, score};
        }
        if (best && best->bars == u) break;
    }
    return best;
}

std::vector<double> splice(Track &t, double S, double E, int extra) {
    if (extra <= 0) return {};
    double sr = t.sr;
    long h = (long)(0.012 * sr);
    // 在保存的片段内按原曲样本号操作，片段外视为静音（与补零一致）
    long iS = (long)nround(S * sr), iE = (long)nround(E * sr);
    long loopLen = iE - iS;
    const Stereo &x = t.x;
    long off = t.offset;
    auto at = [&](const vecf &v, long abs) -> float {
        long j = abs - off;
        return (j >= 0 && j < (long)v.size()) ? v[j] : 0.f;
    };
    Stereo y;
    long n = (long)x.n();
    long newN = n + extra * loopLen;
    y.resize(newN);
    long w = 0;
    auto put = [&](float l, float r) { if (w >= 0 && w < newN) { y.L[w] = l; y.R[w] = r; } ++w; };
    for (long a = off; a < iE - h; ++a) put(at(x.L, a), at(x.R, a));
    std::vector<double> seams;
    long pos = iE;
    for (int e = 0; e < extra; ++e) {
        for (long k = 0; k < 2 * h; ++k) {
            double th = 2 * h > 1 ? (M_PI / 2) * k / (2 * h - 1) : 0;
            float fo = (float)std::cos(th), fi = (float)std::sin(th);
            put(at(x.L, iE - h + k) * fo + at(x.L, iS - h + k) * fi, at(x.R, iE - h + k) * fo + at(x.R, iS - h + k) * fi);
        }
        for (long a = iS + h; a < iE - h; ++a) put(at(x.L, a), at(x.R, a));
        seams.push_back(pos / sr);
        pos += loopLen;
    }
    for (long a = iE - h; a < off + n; ++a) put(at(x.L, a), at(x.R, a));
    t.x = y;
    t.total += extra * loopLen;
    return seams;
}

double lastDrumDownbeat(const Track &xa, const Analysis &A, double lastDb, int maxBackBars) {
    const double DECAY_DROP_DB = 6.0;
    double sr = xa.sr;
    double bar = 4 * 60 / A.bpm;
    double ref0 = std::max(0.0, lastDb - 60.0);
    double x = lastDb;
    for (int k = 0; k < maxBackBars; ++k) {
        if (x - bar < ref0) break;
        if (!drumsAbsent(xa, x - bar, bar, ref0, lastDb).absent) {
            if (x >= lastDb - 1e-3) return lastDb;
            double drum = integratedLoudness(xa.slice((long)((x - bar) * sr), (long)(x * sr)), sr);
            double after = integratedLoudness(xa.slice((long)(x * sr), (long)((x + bar) * sr)), sr);
            return drum - after >= DECAY_DROP_DB ? x : lastDb;
        }
        x -= bar;
    }
    return lastDb;
}

}  // namespace automix
