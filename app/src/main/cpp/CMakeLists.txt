# Native build for the on-device llama.cpp runtime used by SAM's offline LLM.
#
# This is REAL (not a stub): it pulls a pinned llama.cpp release at configure time via CMake
# FetchContent and links it into a single shared library `libsamllama.so` loaded by LlamaNative.kt.
#
# The whole native build is OPTIONAL and gated by the Gradle property `samLlama` (see
# app/build.gradle.kts). When it is not enabled the app builds exactly as before and the Kotlin
# layer degrades gracefully (LlamaNative.available == false). Target ABI: arm64-v8a (S24 Ultra).

cmake_minimum_required(VERSION 3.22.1)
project(samllama CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

# Pin a known-good llama.cpp tag. Bump deliberately after testing on CI.
FetchContent_Declare(
        llama_cpp
        GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
        GIT_TAG b4409
)

# Build llama.cpp as a static lib without its tools/tests/examples to keep the .so small.
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(llama_cpp)

add_library(samllama SHARED llama_jni.cpp)

find_library(log-lib log)

target_link_libraries(samllama
        llama
        ${log-lib})
