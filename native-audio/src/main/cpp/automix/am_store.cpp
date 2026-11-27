#include "am_store.h"

#include <algorithm>
#include <cstring>
#include <numeric>

#include "am_mix.h"

namespace automix {

namespace {
constexpr long CONTEXT_FRAMES = 4096;
}

PcmStore::PcmStore(int inRate, bool keepTail, double seconds) : inRate_(inRate), keepTail_(keepTail) {
    long g = std::gcd((long)inRate, (long)WORK_SR);
    p_ = inRate / g;
    q_ = WORK_SR / g;
    context_ = inRate == WORK_SR ? 0 : p_ * ((CONTEXT_FRAMES + p_ - 1) / p_);
    chunk_ = p_ * std::max(1L, ((long)inRate * 5) / p_);
    keepOut_ = (long)(seconds * WORK_SR) + (long)WORK_SR * 2L;
}

void PcmStore::push(const float *interleaved, long frames) {
    if (frames <= 0) return;
    size_t need = (size_t)(pendingFrames_ + frames) * 2;
    if (pending_.size() < need) pending_.resize(std::max(need, pending_.size() * 2));
    std::memcpy(pending_.data() + pendingFrames_ * 2, interleaved, (size_t)frames * 2 * sizeof(float));
    pendingFrames_ += frames;
    while (pendingStart_ + pendingFrames_ >= nextIn_ + chunk_ + context_) emit(nextIn_ + chunk_, false);
}

void PcmStore::emit(long endIn, bool last) {
    if (headComplete()) { pendingFrames_ = 0; return; }
    long leftCtx = nextIn_ - pendingStart_;
    long rightIn = last ? pendingStart_ + pendingFrames_ : endIn + context_;
    long n = rightIn - pendingStart_;
    Stereo seg;
    seg.resize((size_t)n);
    for (long i = 0; i < n; ++i) { seg.L[i] = pending_[2 * i]; seg.R[i] = pending_[2 * i + 1]; }
    Stereo res = inRate_ == WORK_SR ? std::move(seg) : resamplePoly(seg, (int)q_, (int)p_);
    long skip = leftCtx * q_ / p_;
    long keep = last ? ((long)res.n() - skip) : ((endIn - nextIn_) * q_ / p_);
    keep = std::min(keep, (long)res.n() - skip);
    if (keep > 0) {
        L_.insert(L_.end(), res.L.begin() + skip, res.L.begin() + skip + keep);
        R_.insert(R_.end(), res.R.begin() + skip, res.R.begin() + skip + keep);
    }
    if (keepTail_) dropBefore(outEnd() - keepOut_);
    // 留下新的左侧上下文
    long newStart = std::max(0L, endIn - context_);
    long drop = std::max(0L, std::min(newStart - pendingStart_, pendingFrames_));
    if (drop > 0) {
        std::memmove(pending_.data(), pending_.data() + drop * 2, (size_t)(pendingFrames_ - drop) * 2 * sizeof(float));
        pendingFrames_ -= drop;
        pendingStart_ += drop;
    }
    nextIn_ = endIn;
}

void PcmStore::dropBefore(long frame) {
    long n = std::max(0L, std::min(frame - outStart_, frames()));
    if (n == 0) return;
    head_ += (size_t)n;
    outStart_ += n;
    if (head_ > L_.size() / 2) {
        L_.erase(L_.begin(), L_.begin() + (long)head_);
        R_.erase(R_.begin(), R_.begin() + (long)head_);
        head_ = 0;
    }
}

void PcmStore::finish() {
    if (pendingStart_ + pendingFrames_ > nextIn_) emit(pendingStart_ + pendingFrames_, true);
    std::vector<float>().swap(pending_);
    pendingFrames_ = 0;
}

long PcmStore::totalOut(long nativeFrames) const {
    return (long)std::ceil((double)nativeFrames * (double)q_ / (double)p_);
}

Stereo PcmStore::slice(long start, long n) const {
    Stereo s;
    start = std::max(0L, std::min(start, frames()));
    n = std::max(0L, std::min(n, frames() - start));
    size_t b = head_ + (size_t)start;
    s.L.assign(L_.begin() + (long)b, L_.begin() + (long)(b + n));
    s.R.assign(R_.begin() + (long)b, R_.begin() + (long)(b + n));
    return s;
}

Stereo PcmStore::take() {
    if (head_ > 0) {
        L_.erase(L_.begin(), L_.begin() + (long)head_);
        R_.erase(R_.begin(), R_.begin() + (long)head_);
        head_ = 0;
    }
    Stereo s;
    s.L = std::move(L_);
    s.R = std::move(R_);
    L_.clear();
    R_.clear();
    return s;
}

}  // namespace automix
