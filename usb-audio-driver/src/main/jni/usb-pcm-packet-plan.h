#pragma once

// 只规划完整 URB；数据不足时不推进采样相位，留给下一批 PCM 继续拼接。
inline int planUsbPcmPackets(int availableBytes, int bytesPerFrame, double framesPerPacket,
                            int packetCount, double phase, int *packetSizes,
                            double &nextPhase) {
    int totalBytes = 0;
    double plannedPhase = phase;
    for (int i = 0; i < packetCount; ++i) {
        plannedPhase += framesPerPacket;
        const int frames = static_cast<int>(plannedPhase);
        plannedPhase -= frames;
        const int bytes = frames * bytesPerFrame;
        if (bytes > availableBytes - totalBytes) return 0;
        packetSizes[i] = bytes;
        totalBytes += bytes;
    }
    nextPhase = plannedPhase;
    return totalBytes;
}

// 曲末允许最后一个微帧短包，按音频帧对齐提交全部尾音，不插入静音。
inline int planUsbPcmTail(int availableBytes, int bytesPerFrame, double framesPerPacket,
                         int capacity, double &phase, int *sizes) {
    if (availableBytes <= 0 || bytesPerFrame <= 0 || availableBytes % bytesPerFrame != 0) return 0;
    int remaining = availableBytes;
    int count = 0;
    double next = phase;
    while (remaining > 0 && count < capacity) {
        next += framesPerPacket;
        const int frames = static_cast<int>(next);
        next -= frames;
        const int normal = frames * bytesPerFrame;
        if (normal <= 0) return 0;
        sizes[count] = remaining < normal ? remaining : normal;
        remaining -= sizes[count++];
    }
    if (remaining != 0) return 0;
    phase = next;
    return count;
}
