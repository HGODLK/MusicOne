// 离线解码的流式重采样与存储：PCM 留在原生内存，不占 Java 堆，避免分析时触发阻塞式回收拖慢界面。
#pragma once

#include "am_dsp.h"

namespace automix {

/**
 * 分块重采样到 WORK_SR：块边界按输入周期对齐，两侧各带足够上下文后丢掉边缘，
 * 与整首一次 resample_poly 的结果在内部逐样本一致（滤波器有限长）。
 * 逐行沿用原 Kotlin ChunkResampler 的分块、上下文与取整，输出与之完全相同。
 */
class PcmStore {
public:
    PcmStore(int inRate, bool keepTail, double seconds);

    /** 追加原始采样率下的立体声交错浮点。 */
    void push(const float *interleaved, long frames);
    /** 只解开头时：已攒够所需长度。 */
    bool headComplete() const { return !keepTail_ && outEnd() >= keepOut_; }
    /** 输入结束：把剩余输入出块。 */
    void finish();

    /** 存储首帧在整首 WORK_SR 序列里的帧号。 */
    long offset() const { return outStart_; }
    long frames() const { return (long)(L_.size() - head_); }
    /** 整首在 WORK_SR 下的帧数（按原始帧数换算）。 */
    long totalOut(long nativeFrames) const;
    /** 取存储内 [start, start + n) 一段（相对存储首帧）。 */
    Stereo slice(long start, long n) const;
    /** 交出全部数据（之后存储为空）。 */
    Stereo take();

private:
    long outEnd() const { return outStart_ + frames(); }
    void emit(long endIn, bool last);
    void dropBefore(long frame);

    int inRate_;
    bool keepTail_;
    long p_, q_, context_, chunk_, keepOut_;
    /** 尚未出块的输入（含左侧上下文），pending_[0] 对应输入帧 pendingStart_。 */
    std::vector<float> pending_;
    long pendingFrames_ = 0, pendingStart_ = 0;
    /** 下一块的首个输入帧。 */
    long nextIn_ = 0;
    /** 输出：L_/R_ 自 head_ 起有效，head_ 对应帧号 outStart_；丢弃前段只移动 head_，过半再整体搬移。 */
    vecf L_, R_;
    size_t head_ = 0;
    long outStart_ = 0;
};

}  // namespace automix
