cmake_minimum_required(VERSION 3.22.1)

project("native-audio")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

add_library(avformat STATIC IMPORTED)
set_target_properties(avformat PROPERTIES IMPORTED_LOCATION
    ${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/${ANDROID_ABI}/libavformat.a)

add_library(avcodec STATIC IMPORTED)
set_target_properties(avcodec PROPERTIES IMPORTED_LOCATION
    ${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/${ANDROID_ABI}/libavcodec.a)

add_library(swresample STATIC IMPORTED)
set_target_properties(swresample PROPERTIES IMPORTED_LOCATION
    ${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/${ANDROID_ABI}/libswresample.a)

add_library(avutil STATIC IMPORTED)
set_target_properties(avutil PROPERTIES IMPORTED_LOCATION
    ${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/${ANDROID_ABI}/libavutil.a)

add_library(native-audio SHARED
    native_audio_jni.cpp
    native_audio_io.cpp
    native_audio_probe.cpp
    native_audio_session.cpp
    native_pcm_resampler.cpp
    lg_pcm_output.cpp
)

target_link_options(native-audio PRIVATE "-Wl,-z,max-page-size=16384")

target_link_libraries(native-audio
    avformat
    avcodec
    swresample
    avutil
    log
    OpenSLES
    z
    m
)

# 自然过渡（AutoMix）引擎：独立的动态库，不依赖 FFmpeg。
add_library(musicone-automix SHARED
    automix/am_dsp.cpp
    automix/am_analysis.cpp
    automix/am_parts.cpp
    automix/am_stretch.cpp
    automix/am_mix.cpp
    automix/am_store.cpp
    automix/am_jni.cpp
)
target_include_directories(musicone-automix PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/automix
    ${CMAKE_CURRENT_SOURCE_DIR}/automix/third_party
)
target_compile_options(musicone-automix PRIVATE -O2 -fno-fast-math -ffp-contract=off)
target_link_options(musicone-automix PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(musicone-automix log m)
