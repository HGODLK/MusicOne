# 原生音频

基于 FFmpeg 解码本地 APE、ALAC、WavPack、DSF／未压缩 DFF 及 AIFF/AIFC 整数 PCM。DSD 转 PCM，不支持 Native DSD/DoP、DST 或 WavPack Hybrid。

重建预编译库（FFmpeg 6.1.1、NDK 27.1.12297006）：

```bash
bash native-audio/tools/build-ffmpeg.sh /path/to/ffmpeg-6.1.1 /path/to/android-ndk 2
```

同时更新 arm64-v8a、x86_64 静态库，保留 16KiB 页对齐。
