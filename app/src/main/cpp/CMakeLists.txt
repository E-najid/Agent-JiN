cmake_minimum_required(VERSION 3.22.1)
project(agentjin_llama)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Same flags as the first CI run that reached ninja (do not add extra CACHE
# options — they made configure fail in ~2 minutes).
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
set(LLAMA_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_FATAL_WARNINGS OFF CACHE BOOL "" FORCE)

set(LLAMA_SRC "" CACHE PATH "Pre-cloned llama.cpp source (skips FetchContent)")
if(LLAMA_SRC AND EXISTS "${LLAMA_SRC}/CMakeLists.txt")
    add_subdirectory(${LLAMA_SRC} ${CMAKE_BINARY_DIR}/llama.cpp EXCLUDE_FROM_ALL)
    set(llama_SOURCE_DIR ${LLAMA_SRC})
else()
    include(FetchContent)
    set(LLAMA_CPP_TAG "master" CACHE STRING "llama.cpp git tag/commit")
    FetchContent_Declare(
        llama
        GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
        GIT_TAG        ${LLAMA_CPP_TAG}
        GIT_SHALLOW    TRUE
    )
    FetchContent_MakeAvailable(llama)
endif()

add_library(agentjin_llama SHARED llama_jni.cpp)

target_include_directories(agentjin_llama PRIVATE
    ${llama_SOURCE_DIR}/include
    ${llama_SOURCE_DIR}/ggml/include
    ${llama_SOURCE_DIR}/ggml/src
)

# Do not add tools/mtmd — that extra target is what ninja died on.
target_link_libraries(agentjin_llama
    llama
    ggml
    android
    log
)

set_target_properties(agentjin_llama PROPERTIES
    C_VISIBILITY_PRESET default
    CXX_VISIBILITY_PRESET default
)
target_compile_options(agentjin_llama PRIVATE -Wno-unused-parameter -Wno-error)
