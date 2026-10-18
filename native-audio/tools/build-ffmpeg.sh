#!/usr/bin/env bash
set -euo pipefail
export PATH="/usr/bin:$PATH"

# 复用已下载的 FFmpeg 6.1.1 源码；产物与中间文件留在项目构建目录。
SOURCE_DIR="$(cd "${1:?请传入 FFmpeg 源码目录}" && pwd)"
NDK_DIR="$(cd "${2:?请传入 Android NDK 目录}" && pwd)"
JOBS="${3:-2}"
MODULE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/windows-x86_64"
export PATH="/usr/bin:$TOOLCHAIN/bin:$PATH"

build_one() {
    local arch="$1" cpu="$2" target="$3" abi="$4"
    local output="$MODULE_DIR/build/ffmpeg/$abi/install"
    local work="$MODULE_DIR/build/ffmpeg/$abi/work"
    mkdir -p "$work" "$output"
    (
        cd "$work"
        "$SOURCE_DIR/configure" \
            --prefix="$output" --target-os=android --arch="$arch" --cpu="$cpu" \
            --enable-cross-compile \
            --cc="$TOOLCHAIN/bin/${target}23-clang" \
            --cxx="$TOOLCHAIN/bin/${target}23-clang++" \
            --ar="$TOOLCHAIN/bin/llvm-ar" --nm="$TOOLCHAIN/bin/llvm-nm" \
            --ranlib="$TOOLCHAIN/bin/llvm-ranlib" --strip="$TOOLCHAIN/bin/llvm-strip" \
            --disable-x86asm --disable-everything --disable-programs --disable-doc \
            --disable-avdevice --disable-postproc --disable-avfilter --disable-network \
            --enable-avformat --enable-avcodec --enable-swresample --enable-avutil \
            --enable-static --disable-shared --enable-pic \
            --enable-demuxer=ape,mov,wv,dsf,iff,aiff \
            --enable-decoder=ape,alac,wavpack,dsd_lsbf,dsd_msbf,dsd_lsbf_planar,dsd_msbf_planar \
            --enable-decoder=pcm_s8,pcm_s16le,pcm_s24le,pcm_s32le,pcm_f32le,pcm_s16be,pcm_s24be,pcm_s32be \
            --enable-protocol=file,pipe,data \
            --extra-cflags="-O3 -fPIC -DANDROID" \
            --extra-ldflags="-Wl,-z,max-page-size=16384"
        make -j"$JOBS"
        make install
    )
    # 仅在该架构构建成功后替换四个受版本控制的静态库。
    for library in avformat avcodec swresample avutil; do
        cp "$output/lib/lib$library.a" "$MODULE_DIR/src/main/cpp/prebuilt/$abi/lib$library.a"
    done
}

build_one x86_64 x86-64 x86_64-linux-android x86_64
build_one aarch64 armv8-a aarch64-linux-android arm64-v8a
