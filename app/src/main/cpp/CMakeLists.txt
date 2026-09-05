cmake_minimum_required(VERSION 3.22.1)
project(agentjin_llama)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Match llama.cpp Android NDK docs. Must be set BEFORE add_subdirectory.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(LLAMA_FATAL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_RPC OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)

include(FetchContent)
set(LLAMA_CPP_TAG "master" CACHE STRING "llama.cpp git tag/commit")
FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        ${LLAMA_CPP_TAG}
    GIT_SHALLOW    TRUE
)
FetchContent_GetProperties(llama)
if(NOT llama_POPULATED)
    FetchContent_Populate(llama)
    add_subdirectory(${llama_SOURCE_DIR} ${llama_BINARY_DIR} EXCLUDE_FROM_ALL)
endif()

if(NOT TARGET llama)
    message(FATAL_ERROR "llama.cpp did not define target 'llama' (src=${llama_SOURCE_DIR})")
endif()

add_library(agentjin_llama SHARED llama_jni.cpp)
target_include_directories(agentjin_llama PRIVATE
    ${llama_SOURCE_DIR}/include
    ${llama_SOURCE_DIR}/ggml/include
)
target_link_libraries(agentjin_llama PRIVATE llama android log)
set_target_properties(agentjin_llama PROPERTIES
    C_VISIBILITY_PRESET default
    CXX_VISIBILITY_PRESET default
    VISIBILITY_INLINES_HIDDEN OFF
)
target_compile_options(agentjin_llama PRIVATE -fvisibility=default -Wno-unused-parameter)
