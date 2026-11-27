// 自然过渡引擎：基础信号处理。语义逐项对齐实验代码所用的 numpy / scipy 函数（见各函数注释）。
#pragma once

#include <array>
#include <cmath>
#include <complex>
#include <cstddef>
#include <cstdint>
#include <vector>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

namespace automix {

using vecd = std::vector<double>;
using vecf = std::vector<float>;
using cplx = std::complex<double>;

/** 立体声（左右分开存放）。 */
struct Stereo {
    vecf L, R;
    size_t n() const { return L.size(); }
    void resize(size_t n) { L.assign(n, 0.f); R.assign(n, 0.f); }
    /** [i0, i1) 片段，越界部分补零（对应 numpy 切片会截断，调用方保证只在需要时越界）。 */
    Stereo slice(long i0, long i1) const;
    vecd mono() const;   // (L + R) / 2，对应 x.mean(axis=1)
};

// —— 数学小工具 ——
double median(vecd v);
double percentile(vecd v, double q);            // np.percentile 线性插值
double mean(const vecd &v);                      // np.mean（成对求和）
double sumNp(const double *a, size_t n);         // np.sum（成对求和）
vecd linspace(double start, double stop, int num);   // np.linspace
double stdev(const vecd &v);                     // np.std（总体）
double corrcoef(const vecd &a, const vecd &b);   // np.corrcoef(a, b)[0, 1]
double interp1(double x, const vecd &xp, const vecd &fp);   // np.interp
double interp1(double x, const vecd &xp, const vecd &fp, double left, double right);
double nround(double x);                         // np.round（四舍六入五成双）
/** np.polyfit(x, y, 1)：返回 (斜率, 截距)。 */
std::pair<double, double> polyfit1(const vecd &x, const vecd &y);
/** 从 float 舍入到 IEEE 半精度再转回（训练特征按 float16 保存）。 */
float roundHalf(float v);

// —— FFT（基 2）——
/** n 为 2 的幂；out 长 n/2+1。 */
void rfft(const double *x, int n, cplx *out);
/** 逆实数 FFT（numpy irfft 语义，含 1/n）。 */
void irfft(const cplx *in, int n, double *out);
int nextPow2(int n);

// —— 窗函数 ——
vecd hanningSym(int n);      // np.hanning
vecd hannPeriodic(int n);    // scipy get_window('hann', n)
vecd kaiserSym(int n, double beta);

// —— IIR ——
/** 二阶节：b0 b1 b2 a0 a1 a2（a0 = 1）。 */
using Section = std::array<double, 6>;
using Sos = std::vector<Section>;
enum class BandType { Low, High, Band };
/** scipy.signal.butter(N, Wn, btype, fs=fs, output="sos")。 */
Sos butter(int order, double f1, double f2, BandType type, double fs);
/** scipy.signal.sosfilt（零初值），原地。 */
void sosfilt(const Sos &sos, double *x, size_t n);
/** scipy.signal.sosfiltfilt（padtype odd，默认 padlen），返回新数组。 */
vecd sosfiltfilt(const Sos &sos, const vecd &x);
/** scipy.signal.lfilter，二阶 b/a（a[0] 已归一）。 */
void lfilter2(const double b[3], const double a[3], double *x, size_t n);

// —— ndimage（mode='reflect'，origin=0）——
void medianFilter1d(const double *x, double *y, long n, long stride, int size);
vecd medianFilter(const vecd &x, int size);
vecd uniformFilter1d(const vecd &x, int size);
vecd minimumFilter1d(const vecd &x, int size);
vecd gaussianFilter1d(const vecd &x, double sigma, double truncate = 4.0);

// —— 重采样：scipy.signal.resample_poly(x, up, down)（kaiser β=5）——
vecd resamplePoly(const vecd &x, int up, int down);
/** 立体声按声道重采样。 */
Stereo resamplePoly(const Stereo &x, int up, int down);

// —— STFT：scipy.signal.stft / istft（hann 周期窗、boundary zeros、padded、scaling spectrum）——
struct Spec {
    int nperseg = 0, hop = 0, nfreq = 0, nframes = 0;
    double fs = 0;
    std::vector<cplx> z;   // z[t * nfreq + f]
    cplx &at(int f, int t) { return z[(size_t)t * nfreq + f]; }
    const cplx &at(int f, int t) const { return z[(size_t)t * nfreq + f]; }
    double freq(int f) const { return f * fs / nperseg; }
    double time(int t) const { return (double)t * hop / fs; }
};
Spec stft(const double *x, size_t n, double fs, int nperseg, int noverlap);
vecd istft(const Spec &s);

/** 谐波 / 打击中值滤波分离用：|Z|^2 沿频率（size 17）与沿时间（size 17）的中值。 */
void hpssMedians(const vecd &S, int nfreq, int nframes, int fsize, int tsize, vecd &P, vecd &H);

/** scipy.signal.fftconvolve 的完整线性卷积。 */
vecd fftconvolve(const vecd &a, const vecd &b);

}  // namespace automix
