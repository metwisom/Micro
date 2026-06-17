cmake_minimum_required(VERSION 3.22)
project(opusjni C)

include(FetchContent)

# Build a static libopus (no shared lib / tests / programs) for the NDK target.
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    opus
    URL https://github.com/xiph/opus/releases/download/v1.5.2/opus-1.5.2.tar.gz
)
FetchContent_MakeAvailable(opus)

add_library(opusjni SHARED opus_jni.c)
target_link_libraries(opusjni opus log)
