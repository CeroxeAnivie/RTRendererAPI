cmake_minimum_required(VERSION 3.25)
project(rtrenderer_luxcore LANGUAGES CXX)
file(TO_CMAKE_PATH "${JAVA_HOME}" JAVA_HOME)
if(NOT EXISTS "${JAVA_HOME}/include/jni.h")
    message(FATAL_ERROR "JAVA_HOME must point to a JDK with JNI headers.")
endif()
set(JNI_INCLUDE_DIRS "${JAVA_HOME}/include" "${JAVA_HOME}/include/win32")
if(NOT WIN32)
    message(FATAL_ERROR "This SDK integration currently packages the official Windows x64 runtime.")
endif()
if(NOT EXISTS "${LUXCORE_SDK}/lib/luxcore.lib" OR NOT EXISTS "${LUXCORE_SOURCE}/include/luxcore/luxcore.h")
    message(FATAL_ERROR "Use the pinned LuxCore 2.11.2 SDK and matching source headers.")
endif()
set(LUXCORE_VERSION "2.11.2")
set(LUXRAYS_ENABLE_OPENCL ON)
set(LUXRAYS_ENABLE_CUDA ON)
set(LUXRAYS_ENABLE_OPTIX ON)
configure_file("${LUXCORE_SOURCE}/include/luxcore/cfg.h.in" "generated/luxcore/cfg.h" @ONLY)
configure_file("${LUXCORE_SOURCE}/include/luxrays/cfg.h.in" "generated/luxrays/cfg.h" @ONLY)
# The official DLL omits the out-of-line public Properties parser exports. Compile
# this exact upstream API utility, without copying or changing any renderer code.
add_library(rtrenderer_luxcore SHARED luxcore_jni.cpp
    "${LUXCORE_SOURCE}/src/luxrays/utils/properties.cpp"
    "${LUXCORE_SOURCE}/src/luxrays/core/geometry/matrix4x4.cpp")
target_compile_features(rtrenderer_luxcore PRIVATE cxx_std_20)
target_compile_definitions(rtrenderer_luxcore PRIVATE NOMINMAX WIN32_LEAN_AND_MEAN BOOST_ALL_NO_LIB)
target_include_directories(rtrenderer_luxcore PRIVATE ${JNI_INCLUDE_DIRS}
    "${LUXCORE_SOURCE}/include" "${CMAKE_CURRENT_BINARY_DIR}/generated" "${BOOST_INCLUDE}")
target_compile_options(rtrenderer_luxcore PRIVATE /O2 /EHsc /utf-8 /W4)
target_link_libraries(rtrenderer_luxcore PRIVATE "${LUXCORE_SDK}/lib/luxcore.lib" delayimp)
target_link_options(rtrenderer_luxcore PRIVATE /DELAYLOAD:luxcore.dll)
set_target_properties(rtrenderer_luxcore PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY_RELEASE "${CMAKE_CURRENT_BINARY_DIR}/../native")

add_executable(luxcore_native_benchmark EXCLUDE_FROM_ALL "${CMAKE_CURRENT_LIST_DIR}/../../../../tools/luxcore_native_benchmark.cpp"
    "${LUXCORE_SOURCE}/src/luxrays/utils/properties.cpp"
    "${LUXCORE_SOURCE}/src/luxrays/core/geometry/matrix4x4.cpp")
target_compile_features(luxcore_native_benchmark PRIVATE cxx_std_20)
target_compile_definitions(luxcore_native_benchmark PRIVATE NOMINMAX WIN32_LEAN_AND_MEAN BOOST_ALL_NO_LIB)
target_include_directories(luxcore_native_benchmark PRIVATE "${LUXCORE_SOURCE}/include" "${CMAKE_CURRENT_BINARY_DIR}/generated" "${BOOST_INCLUDE}")
target_compile_options(luxcore_native_benchmark PRIVATE /O2 /EHsc /utf-8)
target_link_libraries(luxcore_native_benchmark PRIVATE "${LUXCORE_SDK}/lib/luxcore.lib" delayimp)
target_link_options(luxcore_native_benchmark PRIVATE /DELAYLOAD:luxcore.dll)
set_target_properties(luxcore_native_benchmark PROPERTIES RUNTIME_OUTPUT_DIRECTORY_RELEASE "${CMAKE_CURRENT_BINARY_DIR}/../native")
