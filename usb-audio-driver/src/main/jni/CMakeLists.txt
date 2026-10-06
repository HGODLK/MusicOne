cmake_minimum_required(VERSION 3.22.1)
project("decent_usb_audio" C CXX)

set(CMAKE_CXX_STANDARD 11)

# MusicOne 只复用 USB 传输核心，网络音源继续由 Media3 解码。
add_library(decent_usb_audio SHARED
    usb-audio-output.cpp
    usb-audio-transfers.cpp
)

# NDK r27 默认仍使用 4 KB 页面对齐，显式兼容 Android 的 16 KB 页面设备。
target_link_options(decent_usb_audio PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)

target_include_directories(decent_usb_audio PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}"
)

find_library(log-lib log)
target_link_libraries(decent_usb_audio
    ${log-lib}
)
