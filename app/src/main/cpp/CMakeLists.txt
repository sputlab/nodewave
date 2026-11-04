cmake_minimum_required(VERSION 3.22.1)
project(nodewave_codec2 C)

# Codec2 (David Rowe, LGPL-2.1, see codec2/COPYING): only the speech modes, sources unchanged except for the
# generated codebook tables and config.h that its own CMake build produces. See codec2/README-NODEWAVE.md.
file(GLOB CODEC2_SRCS ${CMAKE_CURRENT_SOURCE_DIR}/codec2/*.c)
add_library(nodewave_codec2 SHARED codec2_jni.c ${CODEC2_SRCS})
target_include_directories(nodewave_codec2 PRIVATE codec2)
target_compile_definitions(nodewave_codec2 PRIVATE HAVE_CONFIG_H)
target_compile_options(nodewave_codec2 PRIVATE -O2 -w)
target_link_libraries(nodewave_codec2 m log)
