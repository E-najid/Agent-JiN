cmake_minimum_required(VERSION 3.22.1)
project(agentjin_llama)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# llama.cpp / ggml: CPU, mmap-friendly, no optional desktop deps.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_RPC OFF CACHE BOOL "" FORCE)
set(GGML_STATIC ON CACHE BOOL "" FORCE)
set(MTMD_VIDEO OFF CACHE BOOL "" FORCE)

include(FetchContent)
set(LLAMA_CPP_TAG "master" CACHE STRING "llama.cpp git tag/commit")
FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        ${LLAMA_CPP_TAG}
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(llama)

add_library(agentjin_llama SHARED llama_jni.cpp)

target_include_directories(agentjin_llama PRIVATE
    ${llama_SOURCE_DIR}/include
    ${llama_SOURCE_DIR}/ggml/include
)

target_link_libraries(agentjin_llama
    llama
    ggml
    android
    log
)

# Vision path: libmtmd (mmproj + screenshot RGB). Built only if present in this llama.cpp.
set(MTMD_DIR "${llama_SOURCE_DIR}/tools/mtmd")
if(EXISTS "${MTMD_DIR}/CMakeLists.txt")
    if(NOT TARGET mtmd)
        add_subdirectory(${MTMD_DIR} ${CMAKE_BINARY_DIR}/mtmd)
    endif()
    if(TARGET mtmd)
        target_link_libraries(agentjin_llama mtmd)
        target_include_directories(agentjin_llama PRIVATE ${MTMD_DIR})
        target_compile_definitions(agentjin_llama PRIVATE AGENTJIN_HAS_MTMD=1)
    endif()
endif()

target_compile_options(agentjin_llama PRIVATE
    -fvisibility=hidden
    -Wno-unused-parameter
)
