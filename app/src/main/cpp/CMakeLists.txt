cmake_minimum_required(VERSION 3.22.1)
project(lumenraw LANGUAGES C CXX)

file(GLOB_RECURSE LIBRAW_SOURCES CONFIGURE_DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/libraw/src/*.cpp)
list(FILTER LIBRAW_SOURCES EXCLUDE REGEX "_ph\\.cpp$")

add_library(lumenraw SHARED
    lumen_raw_jni.cpp
    ${LIBRAW_SOURCES}
)

target_include_directories(lumenraw PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/libraw
    ${CMAKE_CURRENT_SOURCE_DIR}/libraw/libraw
    ${CMAKE_CURRENT_SOURCE_DIR}/libraw/internal
    ${CMAKE_CURRENT_SOURCE_DIR}/libraw/src
)

target_compile_definitions(lumenraw PRIVATE LIBRAW_NODLL)
target_compile_options(lumenraw PRIVATE -O2 -fexceptions -frtti)
target_link_libraries(lumenraw android log)
