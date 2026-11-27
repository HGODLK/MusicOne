#include "am_analysis.h"

#include <algorithm>
#include <cstdio>

namespace automix {

// ———————————————————— 响度 ————————————————————

static void rbjCoefficients(double G, double Q, double fc, double rate, bool highShelf, double b[3], double a[3]) {
    double A = std::pow(10.0, G / 40.0);
    double w0 = 2.0 * M_PI * (fc / rate);
    double alpha = std::sin(w0) / (2.0 * Q);
    double b0, b1, b2, a0, a1, a2;
    if (highShelf) {
        b0 = A * ((A + 1) + (A - 1) * std::cos(w0) + 2 * std::sqrt(A) * alpha);
        b1 = -2 * A * ((A - 1) + (A + 1) * std::cos(w0));
        b2 = A * ((A + 1) + (A - 1) * std::cos(w0) - 2 * std::sqrt(A) * alpha);
        a0 = (A + 1) - (A - 1) * std::cos(w0) + 2 * std::sqrt(A) * alpha;
        a1 = 2 * ((A - 1) - (A + 1) * std::cos(w0));
        a2 = (A + 1) - (A - 1) * std::cos(w0) - 2 * std::sqrt(A) * alpha;
    } else {
        b0 = (1 + std::cos(w0)) / 2;
        b1 = -(1 + std::cos(w0));
        b2 = (1 + std::cos(w0)) / 2;
        a0 = 1 + alpha;
        a1 = -2 * std::cos(w0);
        a2 = 1 - alpha;
    }
    b[0] = b0 / a0; b[1] = b1 / a0; b[2] = b2 / a0;
    a[0] = 1; a[1] = a1 / a0; a[2] = a2 / a0;
}

double integratedLoudness(const Stereo &x, long i0, long i1, double sr) {
    long N = (long)x.n();
    i0 = std::max(0L, std::min(i0, N));
    i1 = std::max(i0, std::min(i1, N));
    long n = i1 - i0;
    double bs[3], as[3], bh[3], ah[3];
    rbjCoefficients(4.0, 1 / std::sqrt(2.0), 1500.0, sr, true, bs, as);
    rbjCoefficients(0.0, 0.5, 38.0, sr, false, bh, ah);
    const double Tg = 0.4, step = 1.0 - 0.75;
    double T = (double)n / sr;
    long numBlocks = (long)nround((T - Tg) / (Tg * step)) + 1;
    if (n < (long)(Tg * sr) || numBlocks <= 0) return NAN;   // pyloudnorm 会报错，这里返回 NaN
    std::vector<vecd> z(2, vecd(numBlocks, 0.0));
    for (int ch = 0; ch < 2; ++ch) {
        const vecf &src = ch == 0 ? x.L : x.R;
        vecd d(src.begin() + i0, src.begin() + i1);
        lfilter2(bs, as, d.data(), d.size());
        for (auto &v : d) v = (double)(float)v;   // pyloudnorm 写回 float32 数组
        lfilter2(bh, ah, d.data(), d.size());
        for (auto &v : d) v = (double)(float)v;
        vecd csum(n + 1, 0.0);
        for (long i = 0; i < n; ++i) csum[i + 1] = csum[i] + (double)((float)d[i] * (float)d[i]);
        for (long j = 0; j < numBlocks; ++j) {
            long l = (long)(Tg * ((double)j * step) * sr);
            long u = (long)(Tg * ((double)j * step + 1) * sr);
            l = std::min(l, n); u = std::min(u, n);
            z[ch][j] = (1.0 / (Tg * sr)) * (u > l ? csum[u] - csum[l] : 0.0);
        }
    }
    vecd lj(numBlocks);
    for (long j = 0; j < numBlocks; ++j) lj[j] = -0.691 + 10.0 * std::log10(z[0][j] + z[1][j]);
    double za[2] = {0, 0};
    long cnt = 0;
    for (long j = 0; j < numBlocks; ++j) if (lj[j] >= -70.0) { za[0] += z[0][j]; za[1] += z[1][j]; ++cnt; }
    double gammaR = cnt ? -0.691 + 10.0 * std::log10(za[0] / cnt + za[1] / cnt) - 10.0 : NAN;
    double zb[2] = {0, 0};
    long cnt2 = 0;
    for (long j = 0; j < numBlocks; ++j) if (lj[j] > gammaR && lj[j] > -70.0) { zb[0] += z[0][j]; zb[1] += z[1][j]; ++cnt2; }
    double s = cnt2 ? zb[0] / cnt2 + zb[1] / cnt2 : 0.0;
    return -0.691 + 10.0 * std::log10(s);
}

double integratedLoudness(const Stereo &x, double sr) { return integratedLoudness(x, 0, (long)x.n(), sr); }

// ———————————————————— 帧特征 ————————————————————

static const int N_FFT = 2048, HOP = 512, CHROMA_FFT = 8192, CHROMA_HOP = 2048;

/** 居中 STFT 功率谱（np.hanning 对称窗），逐帧回调。 */
template <typename F>
static int framesPower(const vecd &x, int nfft, int hop, F &&each) {
    int pad = nfft / 2;
    long len = (long)x.size() + 2 * pad;
    int n = (int)(1 + (len - nfft) / hop);
    vecd win = hanningSym(nfft);
    for (auto &w : win) w = (double)(float)w;
    vecd buf(nfft);
    std::vector<cplx> spec(nfft / 2 + 1);
    vecd p(nfft / 2 + 1);
    for (int t = 0; t < n; ++t) {
        long start = (long)t * hop - pad;
        for (int i = 0; i < nfft; ++i) {
            long j = start + i;
            buf[i] = (j >= 0 && j < (long)x.size()) ? x[j] * win[i] : 0.0;
        }
        rfft(buf.data(), nfft, spec.data());
        for (int f = 0; f <= nfft / 2; ++f) p[f] = std::norm(spec[f]);
        each(t, p);
    }
    return n;
}

static void kWeight(vecd &x, double sr) {
    double f0 = 1681.974450955533, g = 3.999843853973347, q = 0.7071752369554196;
    double k = std::tan(M_PI * f0 / sr);
    double vh = std::pow(10.0, g / 20), vb = std::pow(vh, 0.4996667741545416);
    double a0 = 1 + k / q + k * k;
    double b1[3] = {(vh + vb * k / q + k * k) / a0, 2 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0};
    double a1[3] = {1, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0};
    f0 = 38.13547087602444; q = 0.5003270373238773;
    k = std::tan(M_PI * f0 / sr);
    a0 = 1 + k / q + k * k;
    double b2[3] = {1, -2, 1};
    double a2[3] = {1, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0};
    lfilter2(b1, a1, x.data(), x.size());
    lfilter2(b2, a2, x.data(), x.size());
}

static vecd loudnessCurve(const vecd &csum, double sr, const vecd &frameTimes, double window) {
    long len = (long)csum.size() - 1;
    long half = (long)(window * sr / 2);
    vecd out(frameTimes.size());
    for (size_t i = 0; i < frameTimes.size(); ++i) {
        long c = (long)(frameTimes[i] * sr);
        long lo = std::max(0L, std::min(len, c - half)), hi = std::max(0L, std::min(len, c + half));
        double ms = (csum[hi] - csum[lo]) / (double)std::max(hi - lo, 1L);
        out[i] = -0.691 + 10 * std::log10(ms + 1e-12);
    }
    return out;
}

Features frameFeatures(const Stereo &x, double sr) {
    Features ff;
    ff.sr = sr;
    ff.fr = sr / HOP;
    size_t n = x.n();
    vecd mid(n), side(n);
    for (size_t i = 0; i < n; ++i) {
        mid[i] = (double)((x.L[i] + x.R[i]) * 0.5f);
        side[i] = (double)((x.L[i] - x.R[i]) * 0.5f);
    }
    // 频带：低频按 FFT 分辨率、高频 1/6 倍频程
    double fmin = 30.0, fmax = std::min(16000.0, sr / 2 * 0.95);
    double nOct = std::log2(fmax / fmin);
    int nf = (int)(nOct * 6) + 1;
    std::vector<int> edges;
    for (int i = 0; i < nf; ++i) {
        double f = fmin * std::pow(2.0, (double)i / 6);
        int b = (int)nround(f * N_FFT / sr);
        if (edges.empty()) edges.push_back(std::max(1, b));
        else if (b > edges.back()) edges.push_back(b);
    }
    int nb = (int)edges.size() - 1;
    vecd centers(nb);
    for (int k = 0; k < nb; ++k) centers[k] = (edges[k] + edges[k + 1]) / 2.0 * sr / N_FFT;
    std::vector<vecd> total;
    framesPower(mid, N_FFT, HOP, [&](int, const vecd &p) {
        vecd b(nb, 0.0);
        for (int k = 0; k < nb; ++k) {
            double s = 0;
            for (int f = edges[k]; f < edges[k + 1]; ++f) s += p[f];
            b[k] = s;
        }
        total.push_back(b);
    });
    int t = 0;
    framesPower(side, N_FFT, HOP, [&](int i, const vecd &p) {
        for (int k = 0; k < nb; ++k) {
            double s = 0;
            for (int f = edges[k]; f < edges[k + 1]; ++f) s += p[f];
            total[i][k] += s;
        }
        ++t;
    });
    ff.nframes = (int)total.size();
    ff.times.resize(ff.nframes);
    for (int i = 0; i < ff.nframes; ++i) ff.times[i] = i / ff.fr;
    // onset：对数频带谱的正向差分
    double smax = -1e300;
    std::vector<vecd> s(ff.nframes, vecd(nb));
    for (int i = 0; i < ff.nframes; ++i)
        for (int k = 0; k < nb; ++k) { s[i][k] = 10 * std::log10(total[i][k] + 1e-10); smax = std::max(smax, s[i][k]); }
    for (auto &row : s) for (auto &v : row) v = std::max(v, smax - 80);
    ff.onset.assign(ff.nframes, 0.0);
    ff.onsetLow.assign(ff.nframes, 0.0);
    int nLow = 0;
    for (int k = 0; k < nb; ++k) nLow += centers[k] < 150;
    for (int i = 1; i < ff.nframes; ++i) {
        double a = 0, l = 0;
        for (int k = 0; k < nb; ++k) {
            double d = std::max(0.0, s[i][k] - s[i - 1][k]);
            a += d;
            if (centers[k] < 150) l += d;
        }
        ff.onset[i] = a / nb;
        ff.onsetLow[i] = nLow ? l / nLow : 0.0;
    }
    // 响度曲线
    vecd power(n, 0.0);
    for (int ch = 0; ch < 2; ++ch) {
        const vecf &src = ch == 0 ? x.L : x.R;
        vecd d(src.begin(), src.end());
        kWeight(d, sr);
        for (size_t i = 0; i < n; ++i) power[i] += d[i] * d[i];
    }
    vecd csum(n + 1, 0.0);
    for (size_t i = 0; i < n; ++i) csum[i + 1] = csum[i] + power[i];
    ff.momentary = loudnessCurve(csum, sr, ff.times, 0.4);
    ff.shortTerm = loudnessCurve(csum, sr, ff.times, 3.0);
    // chroma
    int nfreq = CHROMA_FFT / 2 + 1;
    std::vector<int> pc(nfreq, -1);
    vecd weight(nfreq, 0.0);
    for (int f = 0; f < nfreq; ++f) {
        double hz = (double)f * sr / CHROMA_FFT;
        if (hz >= 60 && hz <= 5000) {
            double midi = 69 + 12 * std::log2(hz / 440.0);
            double r = nround(midi);
            pc[f] = (int)(((long)r % 12 + 12) % 12);
            weight[f] = std::exp(-0.5 * std::pow((midi - r) / 0.25, 2));
        }
    }
    framesPower(mid, CHROMA_FFT, CHROMA_HOP, [&](int, const vecd &p) {
        std::array<double, 12> c{};
        for (int f = 0; f < nfreq; ++f) if (pc[f] >= 0) c[pc[f]] += std::sqrt(p[f]) * weight[f];
        ff.chroma.push_back(c);
    });
    ff.chromaTimes.resize(ff.chroma.size());
    for (size_t i = 0; i < ff.chroma.size(); ++i) ff.chromaTimes[i] = (double)i * CHROMA_HOP / sr;
    return ff;
}

// ———————————————————— 节拍 ————————————————————

static double parabolic(const vecd &y, int i) {
    if (i > 0 && i < (int)y.size() - 1) {
        double a = y[i - 1], b = y[i], c = y[i + 1];
        double d = a - 2 * b + c;
        if (d < 0) return i + 0.5 * (a - c) / d;
    }
    return (double)i;
}

TempoEstimate estimateTempo(const vecd &o, double fr) {
    const double bpmMin = 60, bpmMax = 200, priorBpm = 110, priorOct = 0.6;
    TempoEstimate r;
    int maxLag = (int)std::ceil(4 * 60 * fr / bpmMin) + 4;
    int win = (int)(12.0 * fr), hop = (int)(1.0 * fr);
    vecd w = hanningSym(win);
    std::vector<vecd> rows;
    int nfft = nextPow2(2 * win);
    vecd buf(nfft);
    std::vector<cplx> spec(nfft / 2 + 1);
    vecd ac(nfft);
    long limit = std::max(1L, (long)o.size() - win + 1);
    for (long s = 0; s < limit; s += hop) {
        if (s + win > (long)o.size()) break;
        double m = 0;
        for (int i = 0; i < win; ++i) m += o[s + i];
        m /= win;
        std::fill(buf.begin(), buf.end(), 0.0);
        for (int i = 0; i < win; ++i) buf[i] = (o[s + i] - m) * w[i];
        rfft(buf.data(), nfft, spec.data());
        for (auto &c : spec) c = std::norm(c);
        irfft(spec.data(), nfft, ac.data());
        if (ac[0] <= 0) continue;
        vecd row(maxLag + 1);
        for (int k = 0; k <= maxLag; ++k) row[k] = ac[k] / ac[0];
        rows.push_back(row);
    }
    if (rows.empty()) return r;
    vecd meanAc(maxLag + 1, 0.0);
    for (auto &row : rows) for (int k = 0; k <= maxLag; ++k) meanAc[k] += row[k];
    for (auto &v : meanAc) v /= (double)rows.size();
    vecd idx(meanAc.size());
    for (size_t i = 0; i < idx.size(); ++i) idx[i] = (double)i;
    auto acAt = [&](double x) { return interp1(x, idx, meanAc, meanAc.front(), 0.0); };
    int lagLo = (int)(60 * fr / bpmMax), lagHi = (int)std::ceil(60 * fr / bpmMin);
    double best = -1e300, bestScore = -1e300;
    int bestLag = -1;
    vecd hsAll;
    for (int lag = lagLo; lag <= lagHi; ++lag) {
        if (lag <= 0) continue;
        double hs = acAt(lag) + 0.7 * acAt(2.0 * lag) + 0.5 * acAt(4.0 * lag) + 0.3 * acAt(lag / 2.0);
        hsAll.push_back(hs);
        double prior = std::exp(-0.5 * std::pow(std::log2(60 * fr / lag / priorBpm) / priorOct, 2));
        double score = std::max(hs, 0.0) * prior;
        if (score > bestScore) { bestScore = score; bestLag = lag; }
        best = std::max(best, hs);
    }
    double lag = parabolic(meanAc, bestLag);
    r.bpm = 60 * fr / lag;
    r.conf = std::max(0.0, std::min(1.0, (best - median(hsAll)) / 0.8));
    int lo = (int)(lag / 1.12), hi = (int)std::ceil(lag * 1.12) + 1;
    vecd local;
    for (auto &row : rows) {
        int j = lo;
        for (int k = lo; k < std::min(hi, (int)row.size()); ++k) if (row[k] > row[j]) j = k;
        local.push_back(60 * fr / parabolic(row, j));
    }
    double med = median(local);
    vecd dev;
    for (double v : local) dev.push_back(std::fabs(v - med));
    r.stability = local.empty() ? 1.0 : median(dev) / med;
    return r;
}

vecd trackBeats(const vecd &o, double fr, double bpm, double tightness) {
    double period = 60 * fr / bpm;
    double sd = stdev(o) + 1e-9;
    long n = (long)o.size();
    vecd on(n);
    for (long i = 0; i < n; ++i) on[i] = o[i] / sd;
    int P = (int)period;
    vecd kern(2 * P + 1);
    for (int k = -P; k <= P; ++k) kern[k + P] = std::exp(-0.5 * std::pow(k * 32.0 / period, 2));
    vecd local(n, 0.0);
    for (long i = 0; i < n; ++i) {
        double s = 0;
        for (int k = -P; k <= P; ++k) {
            long j = i - k;
            if (j >= 0 && j < n) s += on[j] * kern[k + P];
        }
        local[i] = s;
    }
    long offLo = -(long)nround(2 * period), offHi = -(long)nround(period / 2);
    std::vector<long> offs;
    for (long v = offLo; v <= offHi; ++v) offs.push_back(v);
    vecd txcost(offs.size());
    for (size_t i = 0; i < offs.size(); ++i) txcost[i] = -tightness * std::pow(std::log(-offs[i] / period), 2);
    vecd cum(n, 0.0);
    std::vector<long> back(n, -1);
    bool first = true;
    double thresh = 0.01 * *std::max_element(local.begin(), local.end());
    for (long i = 0; i < n; ++i) {
        double bestV = -1e300;
        long bestZ = -1;
        for (size_t k = 0; k < offs.size(); ++k) {
            long z = i + offs[k];
            if (z < 0) continue;
            double c = cum[z] + txcost[k];
            if (c > bestV) { bestV = c; bestZ = z; }
        }
        if (bestZ < 0) { cum[i] = local[i]; continue; }
        if (first && local[i] < thresh) {
            cum[i] = local[i];
        } else {
            first = false;
            cum[i] = local[i] + bestV;
            back[i] = bestZ;
        }
    }
    std::vector<char> peaks(n, 0);
    for (long i = 1; i < n - 1; ++i) peaks[i] = cum[i] >= cum[i - 1] && cum[i] >= cum[i + 1];
    vecd pv;
    for (long i = 0; i < n; ++i) if (peaks[i]) pv.push_back(cum[i]);
    if (pv.empty()) return {};
    double thr = 0.5 * median(pv);
    long last = -1;
    for (long i = 0; i < n; ++i) if (peaks[i] && cum[i] > thr) last = i;
    if (last < 0) return {};
    std::vector<long> beats{last};
    while (back[beats.back()] >= 0) beats.push_back(back[beats.back()]);
    std::reverse(beats.begin(), beats.end());
    double ms = 0;
    for (long b : beats) ms += local[b] * local[b];
    double t = 0.5 * std::sqrt(ms / beats.size());
    long g0 = -1, g1 = -1;
    for (size_t i = 0; i < beats.size(); ++i) if (local[beats[i]] >= t) { if (g0 < 0) g0 = (long)i; g1 = (long)i; }
    if (g0 >= 0) beats = std::vector<long>(beats.begin() + g0, beats.begin() + g1 + 1);
    vecd refined;
    for (long b : beats) {
        long lo = std::max(0L, b - 2), hi = std::min(n, b + 3);
        long j = lo;
        for (long k = lo; k < hi; ++k) if (on[k] > on[j]) j = k;
        refined.push_back(std::labs(j - b) <= 2 ? parabolic(on, (int)j) : (double)b);
    }
    return refined;
}

Grid refineGrid(const vecd &o, double fr, double p0, const vecd &beatFrames, double spanPct) {
    double sd = stdev(o) + 1e-9;
    vecd norm(o.size());
    for (size_t i = 0; i < o.size(); ++i) norm[i] = o[i] / sd;
    vecd on = gaussianFilter1d(norm, 1.0);
    long len = (long)on.size();
    vecd x(len);
    for (long i = 0; i < len; ++i) x[i] = (double)i;
    double t0 = beatFrames[0];
    long kLo = -(long)(t0 / p0) - 1, kHi = (long)((len - t0) / p0) + 1;
    // 网格点取值与求均值都按 numpy 的舍入方式（linspace、成对求和），拍点时间才能逐位一致
    vecd buf((size_t)std::max(0L, kHi - kLo));
    auto score = [&](double base, double p) {
        for (long k = kLo; k < kHi; ++k) buf[k - kLo] = interp1(base + p * (double)k, x, on, 0.0, 0.0);
        return buf.empty() ? 0.0 : mean(buf);
    };
    double bestS = -1e9, bestT = t0, bestP = p0;
    for (double sp : linspace(-spanPct, spanPct, 121)) {
        double p = p0 * (1 + sp);
        for (double ph : linspace(-p / 2, p / 2, 48)) {
            double s = score(t0 + ph, p);
            if (s > bestS) { bestS = s; bestT = t0 + ph; bestP = p; }
        }
    }
    t0 = bestT;
    double p = bestP;
    for (double sp : linspace(-spanPct / 10, spanPct / 10, 21)) {
        double p2 = p * (1 + sp);
        for (double ph : linspace(-1.5, 1.5, 31)) {
            double s = score(t0 + ph, p2);
            if (s > bestS) { bestS = s; bestT = t0 + ph; bestP = p2; }
        }
    }
    vecd res;
    for (double bf : beatFrames) {
        double kk = nround((bf - bestT) / bestP);
        res.push_back(std::fabs(bf - (bestT + bestP * kk)));
    }
    return {bestT, bestP, median(res) / fr * 1000};
}

// ———————————————————— 单曲分析 ————————————————————

static const double KK_MAJOR[12] = {6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88};
static const double KK_MINOR[12] = {6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17};
static const char *NOTE_NAMES[12] = {"C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B"};
static const int CAMELOT_MAJOR[12] = {8, 3, 10, 5, 12, 7, 2, 9, 4, 11, 6, 1};   // 主音音级 → 编号
static const int CAMELOT_MINOR[12] = {5, 12, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10};

static vecd chromaChangeAtBeats(const Features &ff, const vecd &beatRel) {
    std::vector<std::array<double, 12>> c = ff.chroma;
    for (auto &row : c) {
        double nrm = 0;
        for (double v : row) nrm += v * v;
        nrm = std::sqrt(nrm) + 1e-9;
        for (auto &v : row) v /= nrm;
    }
    vecd out;
    size_t nb = beatRel.size();
    for (size_t i = 0; i < nb; ++i) {
        double t = beatRel[i];
        double prevT = i >= 2 ? beatRel[i - 2] : t - 1.0;
        double nextT = i + 2 < nb ? beatRel[i + 2] : t + 1.0;
        std::array<double, 12> va{}, vb{};
        int na = 0, nbn = 0;
        for (size_t k = 0; k < c.size(); ++k) {
            double ct = ff.chromaTimes[k];
            if (ct >= prevT && ct < t) { for (int j = 0; j < 12; ++j) va[j] += c[k][j]; ++na; }
            if (ct >= t && ct < nextT) { for (int j = 0; j < 12; ++j) vb[j] += c[k][j]; ++nbn; }
        }
        if (!na || !nbn) { out.push_back(0.0); continue; }
        double dot = 0, n1 = 0, n2 = 0;
        for (int j = 0; j < 12; ++j) {
            va[j] /= na; vb[j] /= nbn;
            dot += va[j] * vb[j]; n1 += va[j] * va[j]; n2 += vb[j] * vb[j];
        }
        out.push_back(1 - dot / (std::sqrt(n1) * std::sqrt(n2) + 1e-9));
    }
    return out;
}

Analysis analyze(const Stereo &seg, double sr, double offset, double duration) {
    Analysis a;
    a.offset = offset;
    a.duration = duration;
    a.ff = frameFeatures(seg, sr);
    const Features &ff = a.ff;
    a.bodyLufs = percentile(ff.shortTerm, 90);
    double thr = std::max(-60.0, a.bodyLufs - 40);
    long first = -1, last = -1;
    for (int i = 0; i < ff.nframes; ++i) if (ff.momentary[i] > thr) { if (first < 0) first = i; last = i; }
    a.effStart = offset + (first >= 0 ? ff.times[first] : 0.0);
    a.effEnd = offset + (last >= 0 ? ff.times[last] : ff.times.back());

    TempoEstimate te = estimateTempo(ff.onset, ff.fr);
    a.bpm = te.bpm;
    a.tempoStability = te.stability;
    vecd frames = a.bpm > 0 ? trackBeats(ff.onset, ff.fr, a.bpm) : vecd();
    const bool hadBeats = frames.size() >= 8;   // 与实验代码一致：只按 DP 拍数判断，网格替换后不再复核
    if (hadBeats) {
        Grid g = refineGrid(ff.onset, ff.fr, 60 * ff.fr / a.bpm, frames);
        a.gridResidMs = g.residMs;
        if (g.residMs < 35 || a.tempoStability <= 0.008) {
            double sF = (a.effStart - offset) * ff.fr - g.period * 0.25;
            double eF = (a.effEnd - offset) * ff.fr;
            long kmin = (long)std::ceil((sF - g.t0) / g.period);
            long kmax = (long)std::floor((eF - g.t0) / g.period);
            vecd grid;
            for (long k = kmin; k <= kmax; ++k) {
                double f = g.t0 + g.period * (double)k;
                if (f >= 0 && f < (double)ff.onset.size()) grid.push_back(f);
            }
            frames = grid;
            char note[96];
            std::snprintf(note, sizeof note, "使用等间隔网格（DP 残差 %.0fms）", g.residMs);
            a.notes.push_back(note);
        } else {
            char note[96];
            std::snprintf(note, sizeof note, "速度不恒定（DP 残差 %.0fms），保留 DP 拍点", g.residMs);
            a.notes.push_back(note);
        }
    }
    if (hadBeats && !frames.empty()) {
        vecd rel(frames.size());
        for (size_t i = 0; i < frames.size(); ++i) { rel[i] = frames[i] / ff.fr; a.beats.push_back(rel[i] + offset); }
        vecd lowSmooth = uniformFilter1d(ff.onsetLow, 3);
        vecd idx(lowSmooth.size());
        for (size_t i = 0; i < idx.size(); ++i) idx[i] = (double)i;
        vecd low(frames.size());
        for (size_t i = 0; i < frames.size(); ++i) low[i] = interp1(frames[i], idx, lowSmooth);
        vecd cc = chromaChangeAtBeats(ff, rel);
        double lowMean = mean(low), ccMean = mean(cc);
        double bestScore = -1e300;
        for (int p = 0; p < 4; ++p) {
            double sl = 0, sc = 0;
            int cnt = 0;
            for (size_t i = p; i < rel.size(); i += 4) { sl += low[i]; sc += cc[i]; ++cnt; }
            double sv = cnt ? 0.5 * (sl / cnt) / (lowMean + 1e-9) + 1.0 * (sc / cnt) / (ccMean + 1e-9) : -1e9;
            if (sv > bestScore) { bestScore = sv; a.downbeatPhase = p; }
        }
        vecd k(a.beats.size());
        for (size_t i = 0; i < k.size(); ++i) k[i] = (double)i;
        if (a.beats.size() >= 3) a.bpm = 60.0 / polyfit1(k, a.beats).first;
        for (int i = a.downbeatPhase; i < (int)a.beats.size(); i += 4) a.bars.push_back(i);
    } else if (!hadBeats) {
        a.notes.push_back("节拍不足，视为无节拍");
    }
    // 调性
    std::array<double, 12> prof{};
    for (const auto &row : ff.chroma) {
        double w = 0;
        for (double v : row) w += v;
        for (int j = 0; j < 12; ++j) prof[j] += row[j] * w;
    }
    vecd pv(prof.begin(), prof.end());
    double bestR = -2;
    int tonic = 0;
    bool minor = false;
    for (int t = 0; t < 12; ++t) {
        for (int m = 0; m < 2; ++m) {
            const double *tpl = m ? KK_MINOR : KK_MAJOR;
            vecd rolled(12);
            for (int i = 0; i < 12; ++i) rolled[i] = tpl[((i - t) % 12 + 12) % 12];
            double r = corrcoef(pv, rolled);
            if (r > bestR) { bestR = r; tonic = t; minor = m == 1; }
        }
    }
    a.key = std::string(NOTE_NAMES[tonic]) + (minor ? "m" : "");
    a.camelot = std::to_string(minor ? CAMELOT_MINOR[tonic] : CAMELOT_MAJOR[tonic]) + (minor ? "A" : "B");
    // 尾部最后强击
    double tEnd = a.effEnd - offset;
    double osd = stdev(ff.onset) + 1e-9;
    for (int i = 0; i < ff.nframes; ++i) {
        if (ff.times[i] > tEnd - 10 && ff.times[i] <= tEnd && ff.onset[i] / osd > 3) {
            a.hasLastHit = true;
            a.lastHit = ff.times[i] + offset;
        }
    }
    return a;
}

}  // namespace automix
