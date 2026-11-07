# 棋盘识别（librecognition_jni.so）。ncnn 是预编译静态库，放在 ncnn/<ABI> 下，只有 arm64-v8a。
set(ncnn_DIR ${CMAKE_CURRENT_SOURCE_DIR}/ncnn/${ANDROID_ABI}/lib/cmake/ncnn)
find_package(ncnn REQUIRED)

add_library(recognition_jni SHARED recognition_core.cpp recognition_jni.cpp)
# Debug 构建默认 -O0，手工写的拉正和滤波循环会慢十几倍
target_compile_options(recognition_jni PRIVATE -O2 -fopenmp)
target_link_libraries(recognition_jni PRIVATE ncnn android jnigraphics log -fopenmp -static-openmp)
