cmake_minimum_required(VERSION 3.22)
project(relad_enhance CXX)

# ncnn prebuilt for this ABI, unpacked by the downloadNcnn Gradle task
set(ncnn_DIR "${NCNN_ROOT}/${ANDROID_ABI}/lib/cmake/ncnn")
find_package(ncnn REQUIRED)

add_library(relad_enhance SHARED upscaler_jni.cpp)
target_link_libraries(relad_enhance ncnn jnigraphics log android)

# 16 KB page size support (required for new Play apps since late 2025)
target_link_options(relad_enhance PRIVATE "-Wl,-z,max-page-size=16384")
