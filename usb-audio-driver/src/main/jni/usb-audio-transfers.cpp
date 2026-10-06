#include "usb-audio-output.h"
#include "usb-pcm-packet-plan.h"

#include <android/log.h>
#include <cerrno>
#include <cstring>
#include <cstdlib>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

static int64_t monotonicMs() {
    timespec now{};
    clock_gettime(CLOCK_MONOTONIC, &now);
    return now.tv_sec * 1000LL + now.tv_nsec / 1000000;
}

bool submitFeedbackUrb(UsbAudioContext *ctx) {
    if (ctx->endpointFeedback <= 0 || ctx->feedbackInFlight) return true;
    auto *u = ctx->feedbackUrb;
    memset(u, 0, sizeof(usbdevfs_urb) + sizeof(usbdevfs_iso_packet_desc));
    u->type = USBDEVFS_URB_TYPE_ISO;
    u->flags = USBDEVFS_URB_ISO_ASAP;
    u->endpoint = static_cast<unsigned char>(ctx->endpointFeedback);
    u->buffer = ctx->feedbackBuffer;
    u->buffer_length = 4;
    u->number_of_packets = 1;
    u->iso_frame_desc[0].length = 4;
    ctx->feedbackInFlight = ioctl(ctx->fd, USBDEVFS_SUBMITURB, u) == 0;
    return ctx->feedbackInFlight;
}

// 所有端点共用完成队列，必须辨认返回的请求，不能按预期槽位推断。
static int reapOne(UsbAudioContext *ctx, bool cancelling) {
    usbdevfs_urb *urb = nullptr;
    if (ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &urb) < 0) {
        if (errno == EAGAIN || errno == EINTR) return 0;
        ctx->failed = true;
        return -1;
    }
    if (urb == ctx->feedbackUrb) {
        ctx->feedbackInFlight = false;
        if (!cancelling && urb->status == 0 && urb->iso_frame_desc[0].status == 0 &&
            urb->iso_frame_desc[0].actual_length >= 4) {
            auto *fb = ctx->feedbackBuffer;
            const uint32_t raw = uint32_t(fb[0]) | (uint32_t(fb[1]) << 8) |
                (uint32_t(fb[2]) << 16) | (uint32_t(fb[3]) << 24);
            const double rate = raw / 65536.0;
            const double nominal = ctx->sampleRate / 8000.0;
            if (rate > nominal * 0.99 && rate < nominal * 1.01) ctx->calibratedFpmf = rate;
        }
        if (!cancelling && ctx->running.load()) submitFeedbackUrb(ctx);
        return 1;
    }
    for (int i = 0; i < USB_AUDIO_NUM_URBS; ++i) {
        if (urb != ctx->ring[i].urb) continue;
        int bytes = 0;
        bool success = urb->status == 0;
        for (int p = 0; p < urb->number_of_packets; ++p) {
            const auto &packet = urb->iso_frame_desc[p];
            if (packet.status != 0 || packet.actual_length != packet.length) success = false;
            if (packet.status == 0) bytes += packet.actual_length;
        }
        if (!ctx->transfers.complete(i, success ? bytes : 0, ctx->bytesPerFrame, cancelling)) {
            ctx->failed = true;
            return -1;
        }
        ctx->completedFrames.store(ctx->transfers.completedFrames);
        if (!cancelling && !success) ctx->failed = true;
        return 1;
    }
    ctx->failed = true;
    return -1;
}

bool pollUsbTransfers(UsbAudioContext *ctx) {
    // 单次有界，持续反馈不能饿死控制命令。
    for (int i = 0; i < USB_AUDIO_NUM_URBS + 2; ++i) {
        if (reapOne(ctx, false) <= 0) break;
    }
    return !ctx->failed;
}

static bool waitForSlot(UsbAudioContext *ctx) {
    const int64_t deadline = monotonicMs() + 500;
    while (ctx->running.load() && !ctx->failed && ctx->transfers.freeSlot() < 0) {
        if (reapOne(ctx, false) < 0) return false;
        if (monotonicMs() >= deadline) { ctx->failed = true; return false; }
        usleep(125);
    }
    return ctx->running.load() && !ctx->failed;
}

static bool submitAudio(UsbAudioContext *ctx, const uint8_t *data, const int *sizes, int packets, int length) {
    if (!waitForSlot(ctx)) return false;
    const int index = ctx->transfers.freeSlot();
    auto &slot = ctx->ring[index];
    auto *urb = slot.urb;
    memcpy(slot.buffer, data, length);
    memset(urb, 0, sizeof(usbdevfs_urb) + packets * sizeof(usbdevfs_iso_packet_desc));
    urb->type = USBDEVFS_URB_TYPE_ISO;
    urb->flags = USBDEVFS_URB_ISO_ASAP;
    urb->endpoint = static_cast<unsigned char>(ctx->endpointOut);
    urb->buffer = slot.buffer;
    urb->buffer_length = length;
    urb->number_of_packets = packets;
    for (int i = 0; i < packets; ++i) urb->iso_frame_desc[i].length = sizes[i];
    if (ioctl(ctx->fd, USBDEVFS_SUBMITURB, urb) < 0) { ctx->failed = true; return false; }
    ctx->transfers.submitted(index, length, ctx->bytesPerFrame);
    return true;
}

void submitPcmToUrbs(UsbAudioContext *ctx, const uint8_t *pcmData, int totalBytes) {
    const uint8_t *data = pcmData;
    int dataLen = totalBytes;
    if (ctx->residualBytes > 0) {
        dataLen += ctx->residualBytes;
        if (ctx->mergedBufferCapacity < dataLen) {
            auto *resized = static_cast<uint8_t *>(realloc(ctx->mergedBuffer, dataLen));
            if (!resized) { ctx->failed = true; return; }
            ctx->mergedBuffer = resized;
            ctx->mergedBufferCapacity = dataLen;
        }
        memcpy(ctx->mergedBuffer, ctx->residualBuffer, ctx->residualBytes);
        memcpy(ctx->mergedBuffer + ctx->residualBytes, pcmData, totalBytes);
        data = ctx->mergedBuffer;
        ctx->residualBytes = 0;
    }
    int offset = 0;
    while (offset < dataLen && ctx->running.load()) {
        int sizes[USB_AUDIO_PACKETS_PER_URB];
        double phase = ctx->frameAccumulator;
        int length = planUsbPcmPackets(dataLen - offset, ctx->bytesPerFrame, ctx->calibratedFpmf,
            USB_AUDIO_PACKETS_PER_URB, ctx->frameAccumulator, sizes, phase);
        if (length <= 0) break;
        if (!submitAudio(ctx, data + offset, sizes, USB_AUDIO_PACKETS_PER_URB, length)) return;
        ctx->frameAccumulator = phase;
        offset += length;
    }
    // 中断的批次直接丢弃；只有正常写入才保留不足一批的 PCM。
    const int remaining = dataLen - offset;
    if (ctx->running.load() && remaining > 0 && remaining < USB_AUDIO_URB_BUFFER_SIZE) {
        memcpy(ctx->residualBuffer, data + offset, remaining);
        ctx->residualBytes = remaining;
    }
}

bool finishUsbTransfers(UsbAudioContext *ctx) {
    if (ctx->residualBytes > 0) {
        int sizes[USB_AUDIO_PACKETS_PER_URB];
        double phase = ctx->frameAccumulator;
        const int packets = planUsbPcmTail(ctx->residualBytes, ctx->bytesPerFrame,
            ctx->calibratedFpmf, USB_AUDIO_PACKETS_PER_URB, phase, sizes);
        if (packets <= 0) { ctx->failed = true; return false; }
        if (!submitAudio(ctx, ctx->residualBuffer, sizes, packets, ctx->residualBytes)) return false;
        ctx->residualBytes = 0;
        ctx->frameAccumulator = phase;
    }
    const int64_t deadline = monotonicMs() + 1000;
    while (ctx->running.load() && !ctx->failed && ctx->transfers.pending > 0) {
        if (reapOne(ctx, false) < 0) break;
        if (monotonicMs() >= deadline) { ctx->failed = true; break; }
        usleep(125);
    }
    return ctx->running.load() && !ctx->failed && ctx->transfers.pending == 0;
}

bool cancelUsbTransfers(UsbAudioContext *ctx) {
    ctx->running.store(false);
    if (ctx->feedbackInFlight) ioctl(ctx->fd, USBDEVFS_DISCARDURB, ctx->feedbackUrb);
    for (int i = 0; i < USB_AUDIO_NUM_URBS; ++i) {
        if (ctx->transfers.bytes[i] > 0) ioctl(ctx->fd, USBDEVFS_DISCARDURB, ctx->ring[i].urb);
    }
    const int64_t deadline = monotonicMs() + 1000;
    while (ctx->feedbackInFlight || ctx->transfers.pending > 0) {
        if (reapOne(ctx, true) < 0 || monotonicMs() >= deadline) return false;
        usleep(125);
    }
    return true;
}
