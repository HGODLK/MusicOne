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
)

target_link_options(native-audio PRIVATE "-Wl,-z,max-page-size=16384")

target_link_libraries(native-audio
    avformat
    avcodec
    swresample
    avutil
    log
    z
    m
)
