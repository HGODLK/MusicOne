#include "am_dsp.h"

#include <algorithm>
#include <cstring>
#include <map>
#include <memory>
#include <mutex>
#include <numeric>

namespace automix {

Stereo Stereo::slice(long i0, long i1) const {
    Stereo out;
    long n = std::max(0L, i1 - i0);
    out.resize((size_t)n);
    long N = (long)L.size();
    for (long i = 0; i < n; ++i) {
        long j = i0 + i;
        if (j >= 0 && j < N) { out.L[i] = L[j]; out.R[i] = R[j]; }
    }
    return out;
}

vecd Stereo::mono() const {
    vecd m(L.size());
    // float32 求均值后再转 double，与 numpy float32 mean 的结果一致
    for (size_t i = 0; i < L.size(); ++i) m[i] = (double)((L[i] + R[i]) * 0.5f);
    return m;
}

// ———————————————————— 数学小工具 ————————————————————

double median(vecd v) {
    if (v.empty()) return NAN;
    size_t n = v.size(), h = n / 2;
    std::nth_element(v.begin(), v.begin() + h, v.end());
    double hi = v[h];
    if (n % 2) return hi;
    double lo = *std::max_element(v.begin(), v.begin() + h);
    return (lo + hi) / 2;
}

double percentile(vecd v, double q) {
    if (v.empty()) return NAN;
    std::sort(v.begin(), v.end());
    double pos = q / 100.0 * (double)(v.size() - 1);
    size_t lo = (size_t)std::floor(pos);
    size_t hi = std::min(lo + 1, v.size() - 1);
    double frac = pos - (double)lo;
    return v[lo] + (v[hi] - v[lo]) * frac;
}

/** numpy 的成对求和（pairwise_sum），保证求和的舍入与 numpy 一致。 */
static double pairwise(const double *a, size_t n) {
    if (n < 8) {
        double r = 0.;
        for (size_t i = 0; i < n; ++i) r += a[i];
        return r;
    }
    if (n <= 128) {
        double r[8];
        for (int j = 0; j < 8; ++j) r[j] = a[j];
        size_t i = 8;
        for (; i < n - (n % 8); i += 8)
            for (int j = 0; j < 8; ++j) r[j] += a[i + j];
        double res = ((r[0] + r[1]) + (r[2] + r[3])) + ((r[4] + r[5]) + (r[6] + r[7]));
        for (; i < n; ++i) res += a[i];
        return res;
    }
    size_t n2 = n / 2;
    n2 -= n2 % 8;
    return pairwise(a, n2) + pairwise(a + n2, n - n2);
}

double sumNp(const double *a, size_t n) {
    // np.add.reduce：初值 0，全部元素成对求和（已用 numpy 2.5 实测核对）
    return 0.0 + pairwise(a, n);
}

double mean(const vecd &v) {
    if (v.empty()) return NAN;
    return sumNp(v.data(), v.size()) / (double)v.size();
}

double stdev(const vecd &v) {
    if (v.empty()) return NAN;
    double m = mean(v);
    vecd d(v.size());
    for (size_t i = 0; i < v.size(); ++i) d[i] = (v[i] - m) * (v[i] - m);
    return std::sqrt(sumNp(d.data(), d.size()) / (double)v.size());
}

vecd linspace(double start, double stop, int num) {
    // np.linspace：step = (stop-start)/(num-1)，y = i·step + start，最后一个点精确等于 stop
    vecd y(std::max(0, num));
    if (num <= 0) return y;
    if (num == 1) { y[0] = start; return y; }
    double step = (stop - start) / (num - 1);
    for (int i = 0; i < num; ++i) y[i] = (double)i * step + start;
    y[num - 1] = stop;
    return y;
}

double corrcoef(const vecd &a, const vecd &b) {
    size_t n = std::min(a.size(), b.size());
    if (n == 0) return NAN;
    double ma = 0, mb = 0;
    for (size_t i = 0; i < n; ++i) { ma += a[i]; mb += b[i]; }
    ma /= (double)n; mb /= (double)n;
    double sab = 0, saa = 0, sbb = 0;
    for (size_t i = 0; i < n; ++i) {
        double da = a[i] - ma, db = b[i] - mb;
        sab += da * db; saa += da * da; sbb += db * db;
    }
    if (saa <= 0 || sbb <= 0) return NAN;
    double r = sab / std::sqrt(saa * sbb);
    return std::max(-1.0, std::min(1.0, r));
}

double interp1(double x, const vecd &xp, const vecd &fp) {
    if (xp.empty()) return NAN;
    return interp1(x, xp, fp, fp.front(), fp.back());
}

double interp1(double x, const vecd &xp, const vecd &fp, double left, double right) {
    size_t n = xp.size();
    if (n == 0) return NAN;
    if (std::isnan(x)) return NAN;
    if (x < xp[0]) return left;
    if (x > xp[n - 1]) return right;
    if (x == xp[n - 1]) return fp[n - 1];
    size_t j = (size_t)(std::upper_bound(xp.begin(), xp.end(), x) - xp.begin());
    if (j == 0) return fp[0];
    size_t i = j - 1;
    double dx = xp[j] - xp[i];
    if (dx == 0) return fp[j];
    double s = (fp[j] - fp[i]) / dx;
    return s * (x - xp[i]) + fp[i];
}

double nround(double x) { return std::nearbyint(x); }

std::pair<double, double> polyfit1(const vecd &x, const vecd &y) {
    size_t n = std::min(x.size(), y.size());
    double mx = 0, my = 0;
    for (size_t i = 0; i < n; ++i) { mx += x[i]; my += y[i]; }
    mx /= (double)n; my /= (double)n;
    double sxy = 0, sxx = 0;
    for (size_t i = 0; i < n; ++i) { sxy += (x[i] - mx) * (y[i] - my); sxx += (x[i] - mx) * (x[i] - mx); }
    double a = sxx > 0 ? sxy / sxx : 0;
    return {a, my - a * mx};
}

float roundHalf(float v) {
    // IEEE 754 binary16 舍入（就近偶数），再还原为 float
    uint32_t bits;
    std::memcpy(&bits, &v, 4);
    uint32_t sign = bits & 0x80000000u;
    int32_t exp = (int32_t)((bits >> 23) & 0xff) - 127;
    uint32_t mant = bits & 0x7fffffu;
    if (((bits >> 23) & 0xff) == 0xff) return v;   // inf / nan
    float out;
    if (exp > 15) {
        uint32_t o = sign | 0x7f800000u;
        std::memcpy(&out, &o, 4);
        return out;
    }
    if (exp >= -14) {
        // 规格化：保留 10 位尾数
        uint32_t m = mant;
        uint32_t lsb = (m >> 13) & 1u, rem = m & 0x1fffu;
        m >>= 13;
        if (rem > 0x1000u || (rem == 0x1000u && lsb)) {
            m += 1;
            if (m == 0x400u) { m = 0; exp += 1; if (exp > 15) { uint32_t o = sign | 0x7f800000u; std::memcpy(&out, &o, 4); return out; } }
        }
        uint32_t o = sign | ((uint32_t)(exp + 127) << 23) | (m << 13);
        std::memcpy(&out, &o, 4);
        return out;
    }
    // 次正规：以 2^-24 为单位就近偶数舍入
    double a = std::fabs((double)v) / std::ldexp(1.0, -24);
    double r = std::nearbyint(a);
    double res = r * std::ldexp(1.0, -24);
    return sign ? -(float)res : (float)res;
}

// ———————————————————— FFT ————————————————————

int nextPow2(int n) {
    int p = 1;
    while (p < n) p <<= 1;
    return p;
}

namespace {
struct Plan {
    int n;
    std::vector<int> rev;
    std::vector<cplx> tw;
    explicit Plan(int n_) : n(n_), rev(n_), tw(n_ / 2) {
        int lg = 0;
        while ((1 << lg) < n) ++lg;
        for (int i = 0; i < n; ++i) {
            int r = 0;
            for (int b = 0; b < lg; ++b) if (i & (1 << b)) r |= 1 << (lg - 1 - b);
            rev[i] = r;
        }
        for (int i = 0; i < n / 2; ++i) tw[i] = std::polar(1.0, -2.0 * M_PI * i / n);
    }
    void run(cplx *a, bool inverse) const {
        for (int i = 0; i < n; ++i) if (i < rev[i]) std::swap(a[i], a[rev[i]]);
        for (int len = 2; len <= n; len <<= 1) {
            int step = n / len, half = len / 2;
            for (int i = 0; i < n; i += len) {
                for (int j = 0; j < half; ++j) {
                    cplx w = tw[j * step];
                    if (inverse) w = std::conj(w);
                    cplx u = a[i + j], v = a[i + j + half] * w;
                    a[i + j] = u + v;
                    a[i + j + half] = u - v;
                }
            }
        }
    }
};

const Plan &planFor(int n) {
    static std::mutex lock;
    static std::map<int, std::unique_ptr<Plan>> plans;
    std::lock_guard<std::mutex> g(lock);
    auto &p = plans[n];
    if (!p) p.reset(new Plan(n));
    return *p;
}
}  // namespace

void rfft(const double *x, int n, cplx *out) {
    if (n == 1) { out[0] = x[0]; return; }
    int h = n / 2;
    std::vector<cplx> a(h);
    for (int i = 0; i < h; ++i) a[i] = cplx(x[2 * i], x[2 * i + 1]);
    planFor(h).run(a.data(), false);
    // 由半长复数 FFT 还原实数 FFT
    for (int k = 0; k <= h; ++k) {
        cplx ak = a[k % h], bk = std::conj(a[(h - k) % h]);
        cplx even = (ak + bk) * 0.5, odd = (ak - bk) * cplx(0, -0.5);
        out[k] = even + std::polar(1.0, -2.0 * M_PI * k / n) * odd;
    }
}

void irfft(const cplx *in, int n, double *out) {
    int h = n / 2;
    std::vector<cplx> a(h);
    for (int k = 0; k < h; ++k) {
        // 只取实部语义：DC 与 Nyquist 的虚部忽略
        cplx Xk = in[k], Xn = std::conj(in[h - k]);
        if (k == 0) { Xk = cplx(in[0].real(), 0); Xn = cplx(in[h].real(), 0); }
        cplx even = (Xk + Xn) * 0.5;
        cplx odd = (Xk - Xn) * 0.5 * std::polar(1.0, 2.0 * M_PI * k / n);
        a[k] = even + cplx(0, 1) * odd;
    }
    planFor(h).run(a.data(), true);
    for (int i = 0; i < h; ++i) {
        out[2 * i] = a[i].real() / h;
        out[2 * i + 1] = a[i].imag() / h;
    }
}

// ———————————————————— 窗 ————————————————————

vecd hanningSym(int n) {
    vecd w(n);
    if (n == 1) { w[0] = 1; return w; }
    for (int i = 0; i < n; ++i) w[i] = 0.5 - 0.5 * std::cos(2 * M_PI * i / (n - 1));
    return w;
}

vecd hannPeriodic(int n) {
    vecd w(n);
    for (int i = 0; i < n; ++i) w[i] = 0.5 - 0.5 * std::cos(2 * M_PI * i / n);
    return w;
}

static double besselI0(double x) {
    double sum = 1, term = 1, q = x * x / 4;
    for (int k = 1; k < 500; ++k) {
        term *= q / ((double)k * k);
        sum += term;
        if (term < sum * 1e-17) break;
    }
    return sum;
}

vecd kaiserSym(int n, double beta) {
    vecd w(n);
    if (n == 1) { w[0] = 1; return w; }
    double alpha = (n - 1) / 2.0, d = besselI0(beta);
    for (int i = 0; i < n; ++i) {
        double r = (i - alpha) / alpha;
        w[i] = besselI0(beta * std::sqrt(std::max(0.0, 1 - r * r))) / d;
    }
    return w;
}

// ———————————————————— IIR ————————————————————

Sos butter(int order, double f1, double f2, BandType type, double fs) {
    // 模拟原型极点
    std::vector<cplx> p, z;
    for (int m = -order + 1; m < order; m += 2) p.push_back(-std::exp(cplx(0, M_PI * m / (2.0 * order))));
    double k = 1;
    const double fsd = 2.0;   // scipy 先把频率归一到奈奎斯特，再按 fs=2 双线性变换
    auto warp = [&](double f) { double wn = f / (fs / 2); return 2 * fsd * std::tan(M_PI * wn / fsd); };
    int degree = (int)p.size();
    if (type == BandType::Low) {
        double wo = warp(f1);
        for (auto &x : p) x *= wo;
        k *= std::pow(wo, degree);
    } else if (type == BandType::High) {
        double wo = warp(f1);
        cplx prodp = 1;
        for (auto &x : p) prodp *= -x;
        for (auto &x : p) x = wo / x;
        k *= (1.0 / prodp).real();
        z.assign(degree, cplx(0, 0));
    } else {
        double w1 = warp(f1), w2 = warp(f2);
        double bw = w2 - w1, wo = std::sqrt(w1 * w2);
        std::vector<cplx> pb;
        for (auto x : p) {
            cplx pl = x * (bw / 2);
            cplx s = std::sqrt(pl * pl - wo * wo);
            pb.push_back(pl + s);
        }
        for (auto x : p) {
            cplx pl = x * (bw / 2);
            cplx s = std::sqrt(pl * pl - wo * wo);
            pb.push_back(pl - s);
        }
        p = pb;
        z.assign(degree, cplx(0, 0));
        k *= std::pow(bw, degree);
    }
    // 双线性变换
    const double fs2 = 2 * fsd;
    cplx num = 1, den = 1;
    for (auto &x : z) { num *= (fs2 - x); x = (fs2 + x) / (fs2 - x); }
    for (auto &x : p) { den *= (fs2 - x); x = (fs2 + x) / (fs2 - x); }
    while (z.size() < p.size()) z.push_back(cplx(-1, 0));
    k *= (num / den).real();
    // 分节：共轭极点成对，零点按 +1 / -1 搭配（Butterworth 的零点只有 ±1）
    std::vector<cplx> poles = p, zeros = z;
    std::vector<std::pair<cplx, cplx>> pp;
    std::vector<cplx> reals;
    std::vector<bool> used(poles.size(), false);
    for (size_t i = 0; i < poles.size(); ++i) {
        if (used[i]) continue;
        if (std::fabs(poles[i].imag()) < 1e-12) { reals.push_back(poles[i]); used[i] = true; continue; }
        used[i] = true;
        size_t best = i;
        double bd = 1e9;
        for (size_t j = i + 1; j < poles.size(); ++j) {
            if (used[j]) continue;
            double d = std::abs(poles[j] - std::conj(poles[i]));
            if (d < bd) { bd = d; best = j; }
        }
        used[best] = true;
        pp.push_back({poles[i], poles[best]});
    }
    for (size_t i = 0; i + 1 < reals.size(); i += 2) pp.push_back({reals[i], reals[i + 1]});
    bool oddReal = reals.size() % 2 == 1;
    std::vector<double> zr;
    for (auto x : zeros) zr.push_back(x.real());
    std::sort(zr.begin(), zr.end());   // -1 在前、+1 在后
    std::vector<double> zPlus, zMinus;
    for (double v : zr) (v > 0 ? zPlus : zMinus).push_back(v);
    auto takeZero = [&]() -> double {
        // 交替取 -1 与 +1，保证每节零点都在实轴
        if (!zMinus.empty() && (zPlus.empty() || zMinus.size() >= zPlus.size())) { double v = zMinus.back(); zMinus.pop_back(); return v; }
        double v = zPlus.back(); zPlus.pop_back(); return v;
    };
    Sos sos;
    for (auto &pr : pp) {
        double z1 = takeZero(), z2 = takeZero();
        Section s{1, -(z1 + z2), z1 * z2, 1, -(pr.first + pr.second).real(), (pr.first * pr.second).real()};
        sos.push_back(s);
    }
    if (oddReal) {
        double z1 = takeZero();
        Section s{1, -z1, 0, 1, -reals.back().real(), 0};
        sos.push_back(s);
    }
    for (int i = 0; i < 3; ++i) sos[0][i] *= k;
    return sos;
}

void sosfilt(const Sos &sos, double *x, size_t n) {
    for (const auto &s : sos) {
        double s0 = 0, s1 = 0;
        for (size_t i = 0; i < n; ++i) {
            double xi = x[i];
            double y = s[0] * xi + s0;
            s0 = s[1] * xi - s[4] * y + s1;
            s1 = s[2] * xi - s[5] * y;
            x[i] = y;
        }
    }
}

static void sosfiltZi(const Sos &sos, double *x, size_t n, const std::vector<std::array<double, 2>> &zi) {
    for (size_t k = 0; k < sos.size(); ++k) {
        const auto &s = sos[k];
        double s0 = zi[k][0], s1 = zi[k][1];
        for (size_t i = 0; i < n; ++i) {
            double xi = x[i];
            double y = s[0] * xi + s0;
            s0 = s[1] * xi - s[4] * y + s1;
            s1 = s[2] * xi - s[5] * y;
            x[i] = y;
        }
    }
}

vecd sosfiltfilt(const Sos &sos, const vecd &x) {
    int ntaps = 2 * (int)sos.size() + 1;
    int zb = 0, za = 0;
    for (auto &s : sos) { zb += s[2] == 0; za += s[5] == 0; }
    ntaps -= std::min(zb, za);
    long edge = 3L * ntaps;
    long n = (long)x.size();
    if (n <= edge) edge = std::max(0L, n - 1);
    // 奇对称延拓
    vecd ext(n + 2 * edge);
    for (long i = 0; i < edge; ++i) ext[i] = 2 * x[0] - x[edge - i];
    for (long i = 0; i < n; ++i) ext[edge + i] = x[i];
    for (long i = 0; i < edge; ++i) ext[edge + n + i] = 2 * x[n - 1] - x[n - 2 - i];
    // sosfilt_zi
    std::vector<std::array<double, 2>> zi(sos.size());
    double scale = 1;
    for (size_t k = 0; k < sos.size(); ++k) {
        const auto &s = sos[k];
        double B0 = s[1] - s[4] * s[0], B1 = s[2] - s[5] * s[0];
        double z0 = (B0 + B1) / (1 + s[4] + s[5]);
        double z1 = B1 - s[5] * z0;
        zi[k] = {scale * z0, scale * z1};
        scale *= (s[0] + s[1] + s[2]) / (1 + s[4] + s[5]);
    }
    std::vector<std::array<double, 2>> z = zi;
    double x0 = ext[0];
    for (auto &v : z) { v[0] *= x0; v[1] *= x0; }
    sosfiltZi(sos, ext.data(), ext.size(), z);
    std::reverse(ext.begin(), ext.end());
    z = zi;
    double y0 = ext[0];
    for (auto &v : z) { v[0] *= y0; v[1] *= y0; }
    sosfiltZi(sos, ext.data(), ext.size(), z);
    std::reverse(ext.begin(), ext.end());
    return vecd(ext.begin() + edge, ext.begin() + edge + n);
}

void lfilter2(const double b[3], const double a[3], double *x, size_t n) {
    double s0 = 0, s1 = 0;
    for (size_t i = 0; i < n; ++i) {
        double xi = x[i];
        double y = b[0] * xi + s0;
        s0 = b[1] * xi - a[1] * y + s1;
        s1 = b[2] * xi - a[2] * y;
        x[i] = y;
    }
}

// ———————————————————— ndimage ————————————————————

static inline long reflectIndex(long i, long n) {
    if (n == 1) return 0;
    long period = 2 * n;
    i %= period;
    if (i < 0) i += period;
    return i >= n ? period - 1 - i : i;
}

void medianFilter1d(const double *x, double *y, long n, long stride, int size) {
    long lo = size / 2;
    std::vector<double> win(size);
    for (long i = 0; i < n; ++i) {
        for (int k = 0; k < size; ++k) win[k] = x[reflectIndex(i - lo + k, n) * stride];
        std::nth_element(win.begin(), win.begin() + size / 2, win.end());
        y[i * stride] = win[size / 2];
    }
}

vecd medianFilter(const vecd &x, int size) {
    vecd y(x.size());
    if (!x.empty()) medianFilter1d(x.data(), y.data(), (long)x.size(), 1, size);
    return y;
}

vecd uniformFilter1d(const vecd &x, int size) {
    long n = (long)x.size(), lo = size / 2;
    vecd y(n);
    for (long i = 0; i < n; ++i) {
        double s = 0;
        for (int k = 0; k < size; ++k) s += x[reflectIndex(i - lo + k, n)];
        y[i] = s / size;
    }
    return y;
}

vecd minimumFilter1d(const vecd &x, int size) {
    long n = (long)x.size(), lo = size / 2;
    vecd y(n);
    // 单调队列求滑动最小
    std::vector<long> dq;
    dq.reserve(n + 2 * size);
    long head = 0;
    auto val = [&](long j) { return x[reflectIndex(j, n)]; };
    long next = -lo;
    for (long i = 0; i < n; ++i) {
        long end = i - lo + size - 1;
        while (next <= end) {
            double v = val(next);
            while ((long)dq.size() > head && val(dq.back()) >= v) dq.pop_back();
            dq.push_back(next);
            ++next;
        }
        while (dq[head] < i - lo) ++head;
        y[i] = val(dq[head]);
    }
    return y;
}

vecd gaussianFilter1d(const vecd &x, double sigma, double truncate) {
    int r = (int)(truncate * sigma + 0.5);
    vecd w(2 * r + 1);
    double s = 0;
    for (int i = -r; i <= r; ++i) { w[i + r] = std::exp(-0.5 * i * i / (sigma * sigma)); s += w[i + r]; }
    for (auto &v : w) v /= s;
    long n = (long)x.size();
    vecd y(n);
    for (long i = 0; i < n; ++i) {
        double acc = 0;
        for (int k = -r; k <= r; ++k) acc += w[k + r] * x[reflectIndex(i + k, n)];
        y[i] = acc;
    }
    return y;
}

// ———————————————————— resample_poly ————————————————————

static long gcdl(long a, long b) { while (b) { long t = a % b; a = b; b = t; } return a; }

vecd resamplePoly(const vecd &x, int up, int down) {
    long g = gcdl(up, down);
    up /= (int)g; down /= (int)g;
    if (up == 1 && down == 1) return x;
    long nIn = (long)x.size();
    long nOut = nIn * up;
    nOut = nOut / down + (nOut % down ? 1 : 0);
    int maxRate = std::max(up, down);
    double fc = 1.0 / maxRate;
    int halfLen = 10 * maxRate;
    int taps = 2 * halfLen + 1;
    vecd h(taps);
    vecd win = kaiserSym(taps, 5.0);
    double alpha = 0.5 * (taps - 1), sum = 0;
    for (int i = 0; i < taps; ++i) {
        double m = i - alpha, v = fc * m;
        double sinc = v == 0 ? 1.0 : std::sin(M_PI * v) / (M_PI * v);
        h[i] = fc * sinc * win[i];
        sum += h[i];
    }
    for (auto &v : h) v = v / sum * up;
    long nPrePad = down - halfLen % down;
    long nPostPad = 0;
    long nPreRemove = (halfLen + nPrePad) / down;
    auto outputLen = [&](long lenH) { return (((nIn - 1) * up + lenH) - 1) / down + 1; };
    while (outputLen((long)h.size() + nPrePad + nPostPad) < nOut + nPreRemove) ++nPostPad;
    vecd hp(nPrePad, 0.0);
    hp.insert(hp.end(), h.begin(), h.end());
    hp.insert(hp.end(), nPostPad, 0.0);
    long lenH = (long)hp.size();
    vecd y(nOut);
    for (long kk = 0; kk < nOut; ++kk) {
        long k = kk + nPreRemove;
        long m = k * down;
        long r = m % up, q = m / up;
        double acc = 0;
        // j = r + up*t，输入下标 q - t
        long tMin = std::max(0L, q - (nIn - 1));
        long tMax = std::min((lenH - 1 - r) / up, q);
        for (long t = tMin; t <= tMax; ++t) acc += hp[r + up * t] * x[q - t];
        y[kk] = acc;
    }
    return y;
}

Stereo resamplePoly(const Stereo &x, int up, int down) {
    vecd l(x.L.begin(), x.L.end()), r(x.R.begin(), x.R.end());
    vecd lo = resamplePoly(l, up, down), ro = resamplePoly(r, up, down);
    Stereo out;
    out.L.assign(lo.begin(), lo.end());
    out.R.assign(ro.begin(), ro.end());
    return out;
}

// ———————————————————— STFT ————————————————————

Spec stft(const double *x, size_t n, double fs, int nperseg, int noverlap) {
    Spec s;
    s.nperseg = nperseg;
    s.hop = nperseg - noverlap;
    s.nfreq = nperseg / 2 + 1;
    s.fs = fs;
    long pad = nperseg / 2;
    long L = (long)n + 2 * pad;
    long rem = L - nperseg;
    long nadd = ((-rem) % s.hop + s.hop) % s.hop;
    nadd %= nperseg;
    L += nadd;
    s.nframes = L < nperseg ? 0 : (int)((L - nperseg) / s.hop + 1);
    vecd win = hannPeriodic(nperseg);
    double wsum = 0;
    for (double w : win) wsum += w;
    double scale = 1.0 / wsum;
    s.z.resize((size_t)s.nframes * s.nfreq);
    vecd buf(nperseg);
    for (int t = 0; t < s.nframes; ++t) {
        long start = (long)t * s.hop - pad;
        for (int i = 0; i < nperseg; ++i) {
            long j = start + i;
            buf[i] = (j >= 0 && j < (long)n) ? x[j] * win[i] : 0.0;
        }
        cplx *out = &s.z[(size_t)t * s.nfreq];
        rfft(buf.data(), nperseg, out);
        for (int f = 0; f < s.nfreq; ++f) out[f] *= scale;
    }
    return s;
}

vecd istft(const Spec &s) {
    int nper = s.nperseg, hop = s.hop;
    vecd win = hannPeriodic(nper);
    double wsum = 0;
    for (double w : win) wsum += w;
    long outLen = nper + (long)(s.nframes - 1) * hop;
    vecd x(outLen, 0.0), norm(outLen, 0.0), buf(nper);
    for (int t = 0; t < s.nframes; ++t) {
        irfft(&s.z[(size_t)t * s.nfreq], nper, buf.data());
        long o = (long)t * hop;
        for (int i = 0; i < nper; ++i) {
            x[o + i] += buf[i] * wsum * win[i];
            norm[o + i] += win[i] * win[i];
        }
    }
    long cut = nper / 2;
    long n = std::max(0L, outLen - 2 * cut);
    vecd y(n);
    for (long i = 0; i < n; ++i) {
        double nv = norm[i + cut];
        y[i] = x[i + cut] / (nv > 1e-10 ? nv : 1.0);
    }
    return y;
}

void hpssMedians(const vecd &S, int nfreq, int nframes, int fsize, int tsize, vecd &P, vecd &H) {
    P.assign(S.size(), 0.0);
    H.assign(S.size(), 0.0);
    for (int t = 0; t < nframes; ++t)
        medianFilter1d(&S[(size_t)t * nfreq], &P[(size_t)t * nfreq], nfreq, 1, fsize);
    for (int f = 0; f < nfreq; ++f)
        medianFilter1d(&S[f], &H[f], nframes, nfreq, tsize);
}

vecd fftconvolve(const vecd &a, const vecd &b) {
    if (a.empty() || b.empty()) return {};
    size_t outLen = a.size() + b.size() - 1;
    int n = nextPow2((int)outLen);
    vecd pa(n, 0.0), pb(n, 0.0);
    std::copy(a.begin(), a.end(), pa.begin());
    std::copy(b.begin(), b.end(), pb.begin());
    std::vector<cplx> fa(n / 2 + 1), fb(n / 2 + 1);
    rfft(pa.data(), n, fa.data());
    rfft(pb.data(), n, fb.data());
    for (size_t i = 0; i < fa.size(); ++i) fa[i] *= fb[i];
    vecd out(n);
    irfft(fa.data(), n, out.data());
    out.resize(outLen);
    return out;
}

}  // namespace automix
