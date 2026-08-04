cmake_minimum_required(VERSION 3.30)

# This target deliberately owns the SDK build at the JNI boundary.  Java only loads one DLL, while
# NRD/NRI remain external source inputs and are statically linked into that DLL.
project(rtrenderer_nvidia_bridge LANGUAGES CXX)

if(NOT DEFINED NRD_ROOT OR NOT IS_DIRECTORY "${NRD_ROOT}")
    message(FATAL_ERROR "NRD_ROOT must identify an existing NRD SDK directory")
endif()
if(NOT DEFINED NRI_ROOT OR NOT IS_DIRECTORY "${NRI_ROOT}")
    message(FATAL_ERROR "NRI_ROOT must identify an existing NRI SDK directory")
endif()
if(NOT DEFINED STREAMLINE_ROOT OR NOT IS_DIRECTORY "${STREAMLINE_ROOT}")
    message(FATAL_ERROR "STREAMLINE_ROOT must identify an existing Streamline SDK directory")
endif()
if(NOT DEFINED RTXMU_ROOT OR NOT IS_DIRECTORY "${RTXMU_ROOT}")
    message(FATAL_ERROR "RTXMU_ROOT must identify the pinned NVIDIA RTXMU v1.4 source directory")
endif()
if(NOT DEFINED RTXMU_COMMIT OR NOT RTXMU_COMMIT STREQUAL "0c9ce1177000d5923e2cc6a35ae9cb7ff03748d2")
    message(FATAL_ERROR "RTXMU_COMMIT must identify the pinned NVIDIA RTXMU v1.4 commit")
endif()
if(NOT EXISTS "${RTXMU_ROOT}/include/rtxmu/VkAccelStructManager.h"
        OR NOT EXISTS "${RTXMU_ROOT}/src/VkAccelStructManager.cpp")
    message(FATAL_ERROR "RTXMU_ROOT is missing the Vulkan acceleration-structure manager")
endif()
if(NOT EXISTS "${STREAMLINE_ROOT}/include/sl_core_api.h" OR NOT EXISTS "${STREAMLINE_ROOT}/bin/x64/sl.interposer.dll")
    message(FATAL_ERROR "STREAMLINE_ROOT must include headers and bin/x64/sl.interposer.dll")
endif()
if(NOT DEFINED JNI_INCLUDE_DIR OR NOT IS_DIRECTORY "${JNI_INCLUDE_DIR}")
    message(FATAL_ERROR "JNI_INCLUDE_DIR must identify the JDK include directory")
endif()
if(NOT DEFINED JNI_PLATFORM_INCLUDE_DIR OR NOT IS_DIRECTORY "${JNI_PLATFORM_INCLUDE_DIR}")
    message(FATAL_ERROR "JNI_PLATFORM_INCLUDE_DIR must identify the JDK platform include directory")
endif()
if(NOT DEFINED RTRENDERER_VERSION OR RTRENDERER_VERSION STREQUAL "")
    message(FATAL_ERROR "RTRENDERER_VERSION must identify the public renderer version")
endif()
find_path(VULKAN_HEADERS_DIR vulkan/vulkan.h HINTS ENV VULKAN_SDK PATH_SUFFIXES Include)
if(NOT VULKAN_HEADERS_DIR)
    message(FATAL_ERROR "Vulkan headers are required for Streamline manual Vulkan integration")
endif()

# NRDIntegration only needs Vulkan NRI.  Keeping every other backend off avoids SDK features that
# this Vulkan-only renderer neither initializes nor ships.
set(NRI_STATIC_LIBRARY ON CACHE BOOL "" FORCE)
set(NRI_ENABLE_NONE_SUPPORT OFF CACHE BOOL "" FORCE)
set(NRI_ENABLE_VK_SUPPORT ON CACHE BOOL "" FORCE)
set(NRI_ENABLE_VALIDATION_SUPPORT OFF CACHE BOOL "" FORCE)
set(NRI_ENABLE_D3D11_SUPPORT OFF CACHE BOOL "" FORCE)
set(NRI_ENABLE_D3D12_SUPPORT OFF CACHE BOOL "" FORCE)
set(NRI_ENABLE_WGPU_SUPPORT OFF CACHE BOOL "" FORCE)
set(NRI_ENABLE_NVAPI OFF CACHE BOOL "" FORCE)
set(NRI_ENABLE_AMDAGS OFF CACHE BOOL "" FORCE)
set(NRI_ENABLE_AGILITY_SDK_SUPPORT OFF CACHE BOOL "" FORCE)
set(NRI_ENABLE_NGX_SDK OFF CACHE BOOL "" FORCE)
set(NRI_ENABLE_FFX_SDK OFF CACHE BOOL "" FORCE)
set(NRI_ENABLE_XESS_SDK OFF CACHE BOOL "" FORCE)
set(NRI_ENABLE_NIS_SDK OFF CACHE BOOL "" FORCE)
set(NRI_ENABLE_IMGUI_EXTENSION OFF CACHE BOOL "" FORCE)
set(NRI_ENABLE_NVTX_SUPPORT OFF CACHE BOOL "" FORCE)
add_subdirectory("${NRI_ROOT}" "${CMAKE_BINARY_DIR}/nri")

# Embed only SPIR-V in the static NRD target.  The bridge never exposes an SDK DLL dependency.
set(NRD_STATIC_LIBRARY ON CACHE BOOL "" FORCE)
set(NRD_NRI OFF CACHE BOOL "" FORCE)
set(NRD_EMBEDS_SPIRV_SHADERS ON CACHE BOOL "" FORCE)
set(NRD_EMBEDS_DXIL_SHADERS OFF CACHE BOOL "" FORCE)
set(NRD_EMBEDS_DXBC_SHADERS OFF CACHE BOOL "" FORCE)
# NRD otherwise writes generated shader headers back into its source checkout.  Keeping generated
# state in this build tree makes the external, commit-pinned SDK a read-only input.
set(NRD_SHADERS_PATH "${CMAKE_BINARY_DIR}/nrd-shaders" CACHE STRING "" FORCE)
# ShaderMake defaults to one DXC process per logical CPU and documents transient child-process
# failures under that load. NRD expands SHADERMAKE_PATH as a CMake command list, so append the
# official `--serial` argument there. This keeps MSBuild on the direct executable path; a batch
# launcher would add a nested `call` boundary that can hide ShaderMake failures behind VCEnd.
set(SHADERMAKE_EXECUTABLE "${CMAKE_BINARY_DIR}/_deps/shadermake-build/build/Release/ShaderMake.exe")
# NRI has already created ShaderMake and records its executable in an INTERNAL cache entry.
# Override that entry after NRI is configured so NRD invokes the direct serial command rather than
# spawning one DXC process per logical processor for every NRD shader batch.
set(SHADERMAKE_PATH "${SHADERMAKE_EXECUTABLE};--serial" CACHE INTERNAL "" FORCE)
set(SHADERMAKE_PATH "${SHADERMAKE_EXECUTABLE};--serial")
add_subdirectory("${NRD_ROOT}" "${CMAKE_BINARY_DIR}/nrd")
if(MSVC)
    # NRD enables /Zi even for its Release static library, which makes every compiler process
    # mutate one shared NRD.pdb. An interrupted or overlapping outer Gradle invocation can leave
    # that compiler database unusable (C1090) even though MSBuild itself is restricted to one
    # worker. /Z7 keeps equivalent debug records inside each object and removes that cross-process
    # mutable resource; the final JNI DLL still receives its normal linker PDB.
    set_property(TARGET NRD PROPERTY MSVC_DEBUG_INFORMATION_FORMAT Embedded)
endif()

# RTXMU owns Vulkan BLAS result/scratch/compaction suballocations. Keep its D3D12 backend out of
# this Vulkan-only bridge and use the same Vulkan headers as Streamline and the renderer ABI.
set(RTXMU_WITH_D3D12 OFF CACHE BOOL "" FORCE)
set(RTXMU_WITH_VULKAN ON CACHE BOOL "" FORCE)
set(RTXMU_VULKAN_INCLUDE_DIR "${VULKAN_HEADERS_DIR}" CACHE PATH "" FORCE)
add_subdirectory("${RTXMU_ROOT}" "${CMAKE_BINARY_DIR}/rtxmu")

add_library(rtrenderer_nvidia SHARED
    rtrenderer_nvidia_bridge.cpp
    nvidia_feature_jni.cpp
    nrd_jni.cpp
    nrd_reblur_policy.hpp
    nrd_session.cpp
    rtxmu_jni.cpp
    rtxmu_session.cpp
    streamline_diagnostics.cpp
    streamline_frame_constants_publisher.cpp
    streamline_frame_generation_executor.cpp
    streamline_frame_support.cpp
    streamline_jni.cpp
    streamline_reconstruction_executor.cpp
    streamline_runtime.cpp
    streamline_runtime_jni.cpp
    streamline_sdk.cpp
)
target_compile_features(rtrenderer_nvidia PRIVATE cxx_std_20)
target_compile_definitions(rtrenderer_nvidia PRIVATE
    WIN32_LEAN_AND_MEAN
    NOMINMAX
    RTRENDERER_VERSION="${RTRENDERER_VERSION}"
)
target_include_directories(rtrenderer_nvidia PRIVATE
    "${JNI_INCLUDE_DIR}"
    "${JNI_PLATFORM_INCLUDE_DIR}"
    "${NRI_ROOT}/Include"
    "${NRD_ROOT}/Include"
    "${NRD_ROOT}/Integration"
    "${STREAMLINE_ROOT}/include"
    "${RTXMU_ROOT}/include"
    "${VULKAN_HEADERS_DIR}"
)
target_link_libraries(rtrenderer_nvidia PRIVATE NRD NRI rtxmu)
set_target_properties(rtrenderer_nvidia PROPERTIES
    OUTPUT_NAME "rtrenderer_nvidia"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/output"
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/output"
    ARCHIVE_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/output"
    RUNTIME_OUTPUT_DIRECTORY_DEBUG "${CMAKE_BINARY_DIR}/output"
    RUNTIME_OUTPUT_DIRECTORY_RELEASE "${CMAKE_BINARY_DIR}/output"
    RUNTIME_OUTPUT_DIRECTORY_RELWITHDEBINFO "${CMAKE_BINARY_DIR}/output"
    RUNTIME_OUTPUT_DIRECTORY_MINSIZEREL "${CMAKE_BINARY_DIR}/output"
    LIBRARY_OUTPUT_DIRECTORY_DEBUG "${CMAKE_BINARY_DIR}/output"
    LIBRARY_OUTPUT_DIRECTORY_RELEASE "${CMAKE_BINARY_DIR}/output"
    LIBRARY_OUTPUT_DIRECTORY_RELWITHDEBINFO "${CMAKE_BINARY_DIR}/output"
    LIBRARY_OUTPUT_DIRECTORY_MINSIZEREL "${CMAKE_BINARY_DIR}/output"
    ARCHIVE_OUTPUT_DIRECTORY_DEBUG "${CMAKE_BINARY_DIR}/output"
    ARCHIVE_OUTPUT_DIRECTORY_RELEASE "${CMAKE_BINARY_DIR}/output"
    ARCHIVE_OUTPUT_DIRECTORY_RELWITHDEBINFO "${CMAKE_BINARY_DIR}/output"
    ARCHIVE_OUTPUT_DIRECTORY_MINSIZEREL "${CMAKE_BINARY_DIR}/output"
)
