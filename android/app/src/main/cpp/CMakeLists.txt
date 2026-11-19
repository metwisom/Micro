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
    # Pin the tarball so a compromised mirror / MITM of the download can't slip tampered libopus
    # into the APK. Update this hash whenever the Opus version above changes.
    URL_HASH SHA256=65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1
)
FetchContent_MakeAvailable(opus)

add_library(opusjni SHARED opus_jni.c)
target_link_libraries(opusjni opus log)
